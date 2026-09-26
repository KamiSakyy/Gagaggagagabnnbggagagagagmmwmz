#!/usr/bin/env python3
"""Installs a Live2D model into the assets of the app.

The models of the project arrive as archives from the games they belong to: the file names carry
spaces, parentheses and non-ASCII characters, the counter block of every motion is written by hand
and the physics file is optional. This tool normalises all of that so the app can load a model with
one line of code:

  * the moc3 becomes "model.moc3", the physics file "model.physics3.json", the pose file
    "model.pose3.json", expressions go to "expressions/" - every reference in the model3.json is
    rewritten to the new name;
  * motion files keep their names (the app addresses them by name);
  * the Meta counters of every motion are recalculated, because a wrong counter makes Cubism abort
    while loading the motion (see tools/fix_motion_meta.py);
  * the model3.json is rewritten in a compact form with the paths above.

Usage:
  tools/install_model.py <zip-or-directory> <asset-name> [--title "Название"] [--check]
"""
import argparse
import json
import os
import shutil
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fix_motion_meta  # noqa: E402  (sibling tool)

ASSETS = os.path.join('app', 'src', 'main', 'assets', 'live2d')


def find_model_json(root):
    for base, _dirs, files in os.walk(root):
        for name in sorted(files):
            if name.endswith('.model3.json'):
                return os.path.join(base, name)
    return None


def collect(root):
    """Every file of the model, keyed by its path relative to the model root."""
    files = {}
    for base, _dirs, names in os.walk(root):
        for name in names:
            full = os.path.join(base, name)
            files[os.path.relpath(full, root).replace(os.sep, '/')] = full
    return files


def pick(files, *suffixes):
    for path in sorted(files):
        for suffix in suffixes:
            if path.lower().endswith(suffix):
                return path
    return None


def install(source, asset_name, title):
    destination = os.path.join(ASSETS, asset_name)
    temporary = destination + '.incoming'
    unpacked = destination + '.unpacked'
    for path in (temporary, unpacked):
        if os.path.exists(path):
            shutil.rmtree(path)

    if os.path.isdir(source):
        root = source
    else:
        # The archive is unpacked next to the destination and removed afterwards: only the
        # normalised files belong in the assets.
        os.makedirs(unpacked)
        with zipfile.ZipFile(source) as archive:
            archive.extractall(unpacked)
        root = unpacked

    files = collect(root)
    model_json = find_model_json(root)
    os.makedirs(temporary)

    if model_json is None:
        raise SystemExit('в %s нет файла .model3.json' % source)

    document = json.load(open(model_json, encoding='utf-8-sig'))
    references = document.setdefault('FileReferences', {})
    relative_root = os.path.dirname(os.path.relpath(model_json, root)).replace(os.sep, '/')
    if relative_root == '.':
        relative_root = ''

    def relative(path):
        if relative_root and path.startswith(relative_root + '/'):
            return path[len(relative_root) + 1:]
        return path

    known = {relative(relative_path): full for relative_path, full in files.items()}

    def lookup(path):
        """Finds a file of the model: by its path first, by its name as a fallback.

        The archives of different games lay their files out differently - some keep every texture in
        a folder of its own, some drop them next to the model - and the model3.json of a rig that was
        repacked may still point at the original layout.
        """
        if not path:
            return None
        cleaned = path.replace('\\\\', '/')
        if cleaned in known:
            return known[cleaned]
        if relative(cleaned) in known:
            return known[relative(cleaned)]
        name = os.path.basename(cleaned)
        for key in sorted(known):
            if os.path.basename(key) == name:
                return known[key]
        return None

    # --- the model itself
    moc = references.get('Moc')
    source_moc = lookup(moc)
    if source_moc is None:
        raise SystemExit('не найден moc3 файл, указанный в model3.json: %r' % moc)
    shutil.copy2(source_moc, os.path.join(temporary, 'model.moc3'))
    references['Moc'] = 'model.moc3'

    # --- textures
    textures = []
    for index, texture in enumerate(references.get('Textures') or []):
        source_texture = lookup(texture)
        if source_texture is None:
            continue
        target_dir = os.path.join(temporary, 'textures')
        os.makedirs(target_dir, exist_ok=True)
        name = 'texture_%02d%s' % (index, os.path.splitext(texture)[1].lower())
        shutil.copy2(source_texture, os.path.join(target_dir, name))
        textures.append('textures/' + name)
    if not textures:
        raise SystemExit('у модели нет текстур')
    references['Textures'] = textures

    # --- optional singles
    for key, target in (('Physics', 'model.physics3.json'), ('Pose', 'model.pose3.json')):
        value = references.get(key)
        source_single = lookup(value)
        if source_single is not None:
            shutil.copy2(source_single, os.path.join(temporary, target))
            references[key] = target
        elif key in references:
            references.pop(key)

    # --- motions: keep the file names, the app looks them up by name
    motions = {}
    created = False
    for group, entries in (references.get('Motions') or {}).items():
        installed = []
        for entry in entries:
            source_motion = lookup(entry['File'])
            if source_motion is None:
                continue
            folder = os.path.join(temporary, 'motions')
            os.makedirs(folder, exist_ok=True)
            name = os.path.basename(entry['File'])
            shutil.copy2(source_motion, os.path.join(folder, name))
            installed.append({'File': 'motions/' + name})
            created = True
        if installed:
            motions[group] = installed
    if created:
        references['Motions'] = motions
    else:
        references.pop('Motions', None)

    # --- expressions: the ones the model file declares plus the ones lying next to it, because
    # VTuber rigs ship expressions without listing them.
    expressions = []
    wanted = []
    for entry in references.get('Expressions') or []:
        wanted.append((entry.get('Name'), relative(entry.get('File', ''))))
    for path in sorted(known):
        if path.lower().endswith('.exp3.json') and all(path != item[1] for item in wanted):
            wanted.append((None, path))
    for name, path in wanted:
        source_expression = lookup(path)
        if source_expression is None:
            continue
        folder = os.path.join(temporary, 'expressions')
        os.makedirs(folder, exist_ok=True)
        file_name = os.path.basename(path)
        shutil.copy2(source_expression, os.path.join(folder, file_name))
        expressions.append({'Name': name or os.path.splitext(os.path.splitext(file_name)[0])[0],
                            'File': 'expressions/' + file_name})
    if expressions:
        references['Expressions'] = expressions
    else:
        references.pop('Expressions', None)

    with open(os.path.join(temporary, 'model3.json'), 'w', encoding='utf-8') as handle:
        json.dump(document, handle, ensure_ascii=False, separators=(',', ':'))

    if os.path.exists(destination):
        shutil.rmtree(destination)
    os.rename(temporary, destination)
    if os.path.exists(unpacked):
        shutil.rmtree(unpacked)

    # --- counters of every motion installed
    motions_dir = os.path.join(destination, 'motions')
    fixed = 0
    if os.path.isdir(motions_dir):
        for name in sorted(os.listdir(motions_dir)):
            if not name.endswith('.motion3.json'):
                continue
            changed, problem = fix_motion_meta.process_file(os.path.join(motions_dir, name), False)
            if problem:
                raise SystemExit('движение %s: %s' % (name, problem))
            fixed += 1 if changed else 0

    motion_count = sum(len(v) for v in (references.get('Motions') or {}).values())
    print('%s -> %s: движений %d (исправлено Meta %d), текстур %d, выражений %d, физика %s'
          % (os.path.basename(source), destination, motion_count, fixed, len(textures),
             len(expressions), 'да' if references.get('Physics') else 'нет'))
    return {
        'title': title or asset_name,
        'dir': asset_name,
        'motions': motion_count,
        'expressions': len(expressions),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('source', help='архив или каталог с моделью')
    parser.add_argument('asset_name', help='имя папки внутри assets/live2d')
    parser.add_argument('--title', default=None, help='человеческое название модели')
    arguments = parser.parse_args()
    install(arguments.source, arguments.asset_name, arguments.title)
    return 0


if __name__ == '__main__':
    sys.exit(main())
