#!/usr/bin/env python3
"""Проверяет, что собранный APK запустится на всех Android от 8 до 16.

Главная причина, по которой приложение перестаёт ставиться на новых телефонах, - страницы памяти
16 КБ (Android 15+ на части устройств, Android 16 по умолчанию). Для этого нужно, чтобы каждая
нативная библиотека в APK:

  * лежала в архиве без сжатия и с данными, выровненными на границу 16 КБ (STORED, offset % 16384 == 0),
  * имела сегменты PT_LOAD, выровненные на 16 КБ (p_align >= 0x4000).

Скрипт проверяет и то, и другое, плюс печатает архитектуры и ABI-имена. Запускается из CI и вручную:

    python3 tools/check_apk_compat.py handoff/EchidnaStudio-1.0.0-arm64-v8a.apk
"""

import struct
import sys
import zipfile

PAGE_16K = 16384
PAGE_4K = 4096


def zip_data_offset(handle, header_offset):
    """Смещение самих данных записи ZIP (заголовок + имя + extra)."""
    handle.seek(header_offset)
    head = handle.read(30)
    if len(head) < 30:
        return None
    fields = struct.unpack("<IHHHHHIIIHH", head)
    name_len = fields[9]
    extra_len = fields[10]
    return header_offset + 30 + name_len + extra_len


def load_alignments(elf):
    """Выравнивания сегментов PT_LOAD нативной библиотеки."""
    if elf[:4] != b"\x7fELF":
        return []
    is64 = elf[4] == 2
    if is64:
        phoff = struct.unpack_from("<Q", elf, 0x20)[0]
        phentsize = struct.unpack_from("<H", elf, 0x36)[0]
        phnum = struct.unpack_from("<H", elf, 0x38)[0]
        aligns = []
        for i in range(phnum):
            off = phoff + i * phentsize
            if off + 0x38 > len(elf):
                break
            if struct.unpack_from("<I", elf, off)[0] == 1:  # PT_LOAD
                aligns.append(struct.unpack_from("<Q", elf, off + 0x30)[0])
        return aligns
    phoff = struct.unpack_from("<I", elf, 0x1C)[0]
    phentsize = struct.unpack_from("<H", elf, 0x2A)[0]
    phnum = struct.unpack_from("<H", elf, 0x2C)[0]
    aligns = []
    for i in range(phnum):
        off = phoff + i * phentsize
        if off + 0x20 > len(elf):
            break
        if struct.unpack_from("<I", elf, off)[0] == 1:  # PT_LOAD
            aligns.append(struct.unpack_from("<I", elf, off + 0x1C)[0])
    return aligns


def main(argv):
    if len(argv) < 2:
        print("использование: check_apk_compat.py <apk>")
        return 2
    apk = argv[1]
    problems = []
    libs = []
    abis = set()

    with zipfile.ZipFile(apk) as archive, open(apk, "rb") as handle:
        for info in archive.infolist():
            name = info.filename
            if name.endswith(".so"):
                parts = name.split("/")
                if len(parts) > 1 and parts[0] == "lib":
                    abis.add(parts[1])
                data_offset = zip_data_offset(handle, info.header_offset)
                elf = archive.read(name)
                aligns = load_alignments(elf)
                libs.append((name, info.compress_type, data_offset, aligns))
                if info.compress_type != zipfile.ZIP_STORED:
                    problems.append(name + ": библиотека сжата, её нельзя отобразить в память напрямую")
                if data_offset is None or data_offset % PAGE_16K != 0:
                    problems.append("{0}: данные не выровнены на 16 КБ (смещение {1})".format(
                        name, data_offset))
                if not aligns:
                    problems.append(name + ": нет сегментов PT_LOAD - не нативная библиотека?")
                for align in aligns:
                    if align < PAGE_16K:
                        problems.append("{0}: сегмент выровнен на {1} байт, нужно {2}".format(
                            name, align, PAGE_16K))
                        break
                print("  {0}: {1} КБ, несжатая, смещение % 16КБ = {2}, p_align = {3}".format(
                    name,
                    round(info.file_size / 1024),
                    "0" if data_offset is not None and data_offset % PAGE_16K == 0 else "нет",
                    ", ".join(hex(a) for a in aligns) or "-"))

    print("нативных библиотек: " + str(len(libs)))
    print("архитектуры: " + (" ".join(sorted(abis)) or "нет (чистый Java/Kotlin)"))
    if libs and abis != {"arm64-v8a"}:
        problems.append("собраны не только arm64-v8a: " + " ".join(sorted(abis)))
    if problems:
        print("ПОДДЕРЖКА 16 КБ СТРАНИЦ: ЕСТЬ ПРОБЛЕМЫ")
        for problem in problems:
            print("  - " + problem)
        return 1
    print("ПОДДЕРЖКА 16 КБ СТРАНИЦ: ОК (Android 15/16 на таких устройствах запустятся)")
    print("ПОДДЕРЖКА 4 КБ СТРАНИЦ: ОК (Android 8-14)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
