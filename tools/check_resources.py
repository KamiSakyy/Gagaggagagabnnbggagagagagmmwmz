#!/usr/bin/env python3
"""Lints the Android resources without the SDK.

The build machine here has no aapt2, so the mistakes that aapt2 catches anyway are caught here
first: malformed XML, colour literals in a format aapt2 rejects (#AARRGGBB is fine, #AARRGGBBAA or
odd lengths are not), and references to colours, strings, styles and drawables that do not exist.

Usage: tools/check_resources.py [project-root]
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

COLOUR = re.compile(r'^#([0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$')
REFERENCE = re.compile(r'@(color|string|style|drawable|mipmap|dimen|array)/([A-Za-z0-9_.]+)')
ANDROID_REFERENCE = re.compile(r'@android:(color|string|style|drawable)/')
COLOUR_ATTRS = (
    'fillColor', 'strokeColor', 'color', 'textColor', 'background', 'tint', 'startColor',
    'endColor', 'centerColor', 'windowBackground', 'colorAccent', 'colorPrimary',
    'colorPrimaryDark', 'colorControlHighlight', 'windowLightStatusBar',
)


def xml_files(root):
    for directory, _, names in os.walk(os.path.join(root, 'app/src/main/res')):
        for name in names:
            if name.endswith('.xml'):
                yield os.path.join(directory, name)
    manifest = os.path.join(root, 'app/src/main/AndroidManifest.xml')
    if os.path.exists(manifest):
        yield manifest


def collect_values(root):
    """Names declared in res/values/*.xml, per resource type."""
    declared = {'color': set(), 'string': set(), 'style': set(), 'dimen': set(), 'array': set()}
    values_dir = os.path.join(root, 'app/src/main/res/values')
    for name in sorted(os.listdir(values_dir)) if os.path.isdir(values_dir) else []:
        if not name.endswith('.xml'):
            continue
        try:
            tree = ET.parse(os.path.join(values_dir, name))
        except ET.ParseError as error:
            print('ПРОВАЛ: %s не разбирается: %s' % (name, error))
            continue
        for element in tree.getroot():
            kind = element.tag
            if kind in declared and element.get('name'):
                declared[kind].add(element.get('name'))
    return declared


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else '.'
    problems = []
    checked = 0

    declared = collect_values(root)
    drawables = set()
    for directory in ('drawable', 'mipmap-anydpi-v26', 'layout'):
        path = os.path.join(root, 'app/src/main/res', directory)
        if os.path.isdir(path):
            for name in os.listdir(path):
                drawables.add(os.path.splitext(name)[0])

    for path in sorted(xml_files(root)):
        relative = os.path.relpath(path, root)
        try:
            tree = ET.parse(path)
            checked += 1
        except ET.ParseError as error:
            problems.append('%s: не разбирается XML: %s' % (relative, error))
            continue

        for element in tree.getroot().iter():
            for attribute, value in element.attrib.items():
                short = attribute.split('}')[-1]
                if short in COLOUR_ATTRS or short.endswith('Color'):
                    if value.startswith('#') and not COLOUR.match(value):
                        problems.append('%s: цвет %s="%s" недопустимого формата (нужно #RGB, '
                                        '#ARGB, #RRGGBB или #AARRGGBB)'
                                        % (relative, short, value))
                for kind, name in REFERENCE.findall(value):
                    if kind == 'drawable' and name in drawables:
                        continue
                    if kind == 'mipmap' and name in drawables:
                        continue
                    if kind in declared and name in declared[kind]:
                        continue
                    problems.append('%s: ссылка %s/%s нигде не объявлена'
                                    % (relative, kind, name))

    # Every @style used in the manifest has to exist.
    manifest = os.path.join(root, 'app/src/main/AndroidManifest.xml')
    if os.path.exists(manifest):
        text = open(manifest).read()
        for kind, name in REFERENCE.findall(text):
            if kind in declared and name not in declared[kind] and kind != 'mipmap':
                problems.append('AndroidManifest.xml: ссылка %s/%s не объявлена' % (kind, name))

    print('проверено файлов ресурсов: %d' % checked)
    if problems:
        for problem in problems:
            print('ПРОВАЛ: ' + problem)
        return 1
    print('РЕСУРСЫ ОК')
    return 0


if __name__ == '__main__':
    sys.exit(main())
