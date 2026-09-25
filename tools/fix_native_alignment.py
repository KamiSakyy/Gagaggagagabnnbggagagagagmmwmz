#!/usr/bin/env python3
"""Makes the vendored native libraries loadable on devices with 16 KB memory pages.

Android 15 introduced devices whose kernel uses 16 KB pages, and Android 16 ships them by default.
The dynamic linker refuses to load a shared object when a segment's file offset and its virtual
address are not congruent modulo the page size, so a library built for 4 KB pages simply fails with
"UnsatisfiedLinkError" on such a device.

The Live2D Cubism Core inside the vendored AAR is one of those libraries. This tool rewrites the
AAR in place, inserting zero padding so that every PT_LOAD segment satisfies the requirement and
bumping p_align to 16 KB. Only file offsets and section offsets change: the code, the data, the
symbols, the relocations and every virtual address stay exactly as they were, which is what makes
the patch safe.

Usage:
  tools/fix_native_alignment.py <archive.aar|.apk> [more...]   вносит правку
  tools/fix_native_alignment.py --check <archive...>           только проверяет (для сборки)
"""
import io
import os
import shutil
import struct
import sys
import zipfile

PT_LOAD = 1
PAGE = 16384


def read_phdrs(data):
    if data[:4] != b'\x7fELF':
        return None
    is64 = data[4] == 2
    endian = '<' if data[5] == 1 else '>'
    if is64:
        e_phoff, = struct.unpack_from(endian + 'Q', data, 0x20)
        e_phentsize, e_phnum = struct.unpack_from(endian + 'HH', data, 0x36)
        e_shoff, = struct.unpack_from(endian + 'Q', data, 0x28)
        e_shentsize, e_shnum, _ = struct.unpack_from(endian + 'HHH', data, 0x3a)
    else:
        e_phoff, = struct.unpack_from(endian + 'I', data, 0x1c)
        e_phentsize, e_phnum = struct.unpack_from(endian + 'HH', data, 0x2a)
        e_shoff, = struct.unpack_from(endian + 'I', data, 0x20)
        e_shentsize, e_shnum, _ = struct.unpack_from(endian + 'HHH', data, 0x30)
    return {
        'is64': is64, 'endian': endian, 'phoff': e_phoff, 'phentsize': e_phentsize,
        'phnum': e_phnum, 'shoff': e_shoff, 'shentsize': e_shentsize, 'shnum': e_shnum,
    }


def load_segments(data, header):
    endian = header['endian']
    rows = []
    for i in range(header['phnum']):
        off = header['phoff'] + i * header['phentsize']
        if header['is64']:
            fields = struct.unpack_from(endian + 'IIQQQQQQ', data, off)
        else:
            fields = struct.unpack_from(endian + 'IIIIIIII', data, off)
        rows.append((i, off, fields))
    return rows


def segment_alignment_ok(offset, vaddr):
    return (offset - vaddr) % PAGE == 0


def align_shared_object(data):
    """Returns the patched bytes, or None when nothing has to change."""
    header = read_phdrs(data)
    if header is None:
        return None
    segments = load_segments(data, header)

    loads = []
    for index, phoff, fields in segments:
        if fields[0] == PT_LOAD:
            if header['is64']:
                p_offset, p_vaddr, p_filesz, p_memsz, p_align = fields[2], fields[3], fields[5], fields[6], fields[7]
            else:
                p_offset, p_vaddr, p_filesz, p_memsz, p_align = fields[1], fields[2], fields[4], fields[5], fields[7]
            loads.append({'index': index, 'phoff': phoff, 'offset': p_offset,
                          'vaddr': p_vaddr, 'filesz': p_filesz, 'memsz': p_memsz,
                          'align': p_align})

    if not loads:
        return None

    loads.sort(key=lambda item: item['offset'])
    if all(segment_alignment_ok(s['offset'], s['vaddr']) and s['align'] >= PAGE for s in loads):
        return None

    # Where every byte of the old file ends up in the new file.
    shift_of = []           # (old_start, delta) pairs, used for header fields
    pieces = []
    deltas = {}
    previous_end = 0
    for segment in loads:
        target = segment['offset']
        while target < previous_end or (target - segment['vaddr']) % PAGE != 0:
            target += 1
        # Round the target up to the next 16 KB boundary relative to the segment's virtual address.
        if (target - segment['vaddr']) % PAGE != 0:
            target += PAGE - ((target - segment['vaddr']) % PAGE)
        if target < previous_end:
            target = previous_end + ((segment['vaddr'] - previous_end) % PAGE)
        delta = target - segment['offset']
        deltas[segment['offset']] = delta
        segment['new_offset'] = target
        previous_end = target + segment['filesz']
        shift_of.append((segment['offset'], delta))

    if all(delta == 0 for _, delta in shift_of):
        return None

    # Rebuild the file: copy the original bytes, inserting zeros where a segment moved forward.
    pieces = []
    cursor = 0
    for segment in sorted(loads, key=lambda item: item['offset']):
        target = segment['new_offset']
        if target > cursor:
            pieces.append(data[cursor:segment['offset']])
            pieces.append(b'\0' * (target - segment['offset']))
            cursor = segment['offset']
        elif target == cursor and target != segment['offset']:
            pieces.append(b'\0' * (target - segment['offset']))
    pieces.append(data[cursor:])
    patched = bytearray(b''.join(pieces))

    def moved(offset):
        result = offset
        for old_start, delta in sorted(shift_of):
            if offset >= old_start:
                result = offset + delta
        return result

    endian = header['endian']
    # Program headers: move any header whose offset lies in a moved region, and bump p_align.
    for index, phoff, fields in segments:
        fields = list(fields)
        if header['is64']:
            p_type, p_offset, p_vaddr, p_filesz = fields[0], fields[2], fields[3], fields[5]
        else:
            p_type, p_offset, p_vaddr, p_filesz = fields[0], fields[1], fields[2], fields[4]
        new_offset = moved(p_offset) if p_offset else p_offset
        if p_type == PT_LOAD:
            for segment in loads:
                if segment['offset'] == p_offset:
                    new_offset = segment['new_offset']
                    break
        if header['is64']:
            fields[2] = new_offset
            fields[7] = PAGE if p_type == PT_LOAD else fields[7]
        else:
            fields[1] = new_offset
            fields[7] = PAGE if p_type == PT_LOAD else fields[7]
        struct.pack_into(endian + ('IIQQQQQQ' if header['is64'] else 'IIIIIIII'),
                         patched, phoff, *fields)

    # Section headers point at file offsets; shift the ones that moved. The table itself moved as
    # well, so its new position has to be used here.
    new_shoff = moved(header['shoff'])
    for i in range(header['shnum']):
        base = new_shoff + i * header['shentsize']
        if header['is64']:
            sh_offset, = struct.unpack_from(endian + 'Q', patched, base + 0x18)
            new_offset = moved(sh_offset)
            if new_offset != sh_offset:
                struct.pack_into(endian + 'Q', patched, base + 0x18, new_offset)
        else:
            sh_offset, = struct.unpack_from(endian + 'I', patched, base + 0x10)
            new_offset = moved(sh_offset)
            if new_offset != sh_offset:
                struct.pack_into(endian + 'I', patched, base + 0x10, new_offset)

    # The section header table itself moved too.
    if header['is64']:
        struct.pack_into(endian + 'Q', patched, 0x28, new_shoff)
    else:
        struct.pack_into(endian + 'I', patched, 0x20, new_shoff)

    return bytes(patched)


def verify(data):
    header = read_phdrs(data)
    if header is None:
        return 'не ELF'
    problems = []
    for index, phoff, fields in load_segments(data, header):
        if fields[0] != PT_LOAD:
            continue
        if header['is64']:
            p_offset, p_vaddr, p_align = fields[2], fields[3], fields[7]
        else:
            p_offset, p_vaddr, p_align = fields[1], fields[2], fields[7]
        if (p_offset - p_vaddr) % PAGE != 0:
            problems.append('offset 0x%x и адрес 0x%x не совпадают по модулю 16 КБ' % (p_offset, p_vaddr))
        if p_align < PAGE:
            problems.append('p_align 0x%x меньше 16 КБ' % p_align)
    return '; '.join(problems)


def check_archive(path):
    """Verifies every shared object of an archive without touching it."""
    if not os.path.exists(path):
        print('нет файла: ' + path)
        return 1
    problems = 0
    libraries = 0
    with zipfile.ZipFile(path) as archive:
        for item in archive.infolist():
            if not item.filename.endswith('.so'):
                continue
            libraries += 1
            problem = verify(archive.read(item.filename))
            if problem:
                problems += 1
                print('  %s: %s' % (item.filename, problem))
    if problems:
        print('%s: НЕ ГОТОВО к 16 КБ страницам (проблемных библиотек: %d из %d)' % (path, problems, libraries))
        return 1
    print('%s: все библиотеки (%d) совместимы с 16 КБ страницами' % (path, libraries))
    return 0


def patch_archive(path):
    if not os.path.exists(path):
        print('нет файла: ' + path)
        return 1
    buffer = io.BytesIO()
    changed = 0
    checked = 0
    with zipfile.ZipFile(path) as source:
        with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as target:
            for item in source.infolist():
                payload = source.read(item.filename)
                if item.filename.endswith('.so'):
                    checked += 1
                    problem = verify(payload)
                    if problem:
                        patched = align_shared_object(payload)
                        if patched is None:
                            print('  %s: %s (исправить не удалось)' % (item.filename, problem))
                        else:
                            payload = patched
                            changed += 1
                            print('  %s: выровнено на 16 КБ, %s' % (item.filename, verify(payload) or 'проверка пройдена'))
                    else:
                        print('  %s: уже совместимо с 16 КБ' % item.filename)
                target.writestr(item, payload)
    if changed == 0:
        print('в %s нечего менять (проверено библиотек: %d)' % (path, checked))
        return 0
    backup = path + '.orig'
    if not os.path.exists(backup):
        shutil.copy2(path, backup)
    with open(path, 'wb') as handle:
        handle.write(buffer.getvalue())
    print('%s: исправлено библиотек %d, резервная копия %s' % (path, changed, backup))
    return 0


def main():
    arguments = sys.argv[1:]
    check_only = False
    if arguments and arguments[0] == '--check':
        check_only = True
        arguments = arguments[1:]
    paths = arguments
    if not paths:
        print(__doc__)
        return 2
    result = 0
    for path in paths:
        result |= check_archive(path) if check_only else patch_archive(path)
    return result


if __name__ == '__main__':
    sys.exit(main())
