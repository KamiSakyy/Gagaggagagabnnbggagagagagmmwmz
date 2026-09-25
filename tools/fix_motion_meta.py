#!/usr/bin/env python3
"""Recalculates the Meta block of Live2D motion3.json files.

Some of the files in this project were exported with a hand written counter block, and the numbers
are wrong. Cubism does not ignore them: CubismMotion.parse() pre-sizes its segment and point lists
from Meta.TotalSegmentCount and Meta.TotalPointCount before it walks the curves, so a counter that
is too small makes the list shorter than the data and the motion throws

    IndexOutOfBoundsException: Index 115 out of bounds for length 115

which the renderer reports as a drawing failure, leaving an empty screen instead of the model.

The counters follow the exact walk Cubism performs over the Segments array of a curve:

    [first point time, first point value, segment type, ...]
    * the first two entries are the starting point of the curve (one point, one segment);
    * every further entry introduces a segment: the type marker plus its points — one point for
      LINEAR (0), STEPPED (2) and INVERSESTEPPED (3), three points for BEZIER (1);
    * the opening pass over the array counts as a segment as well, so a curve of five numbers has
      one starting point and two segments, exactly as Cubism counts them;
    * TotalSegmentCount and TotalPointCount are the sums over all curves, CurveCount the amount of
      curves.

Usage:
  tools/fix_motion_meta.py <file-or-directory> [more...]   пересчитывает Meta
  tools/fix_motion_meta.py --check <file-or-directory>     только проверяет (для сборки)
"""
import json
import os
import sys


SEGMENT_POINTS = {0: 1, 1: 3, 2: 1, 3: 1}
SEGMENT_NAMES = {0: 'LINEAR', 1: 'BEZIER', 2: 'STEPPED', 3: 'INVERSESTEPPED'}


def walk_curve(segments, where):
    """Mirrors CubismMotion.parse() exactly and returns (segments, points) of one curve.

    Cubism walks the array with a while loop; every pass through that loop registers one segment,
    and the first pass also reads the starting point of the curve. The first entry of the array is
    the time of that starting point, not a counter, even though exporters write a segment count
    there.
    """
    if not isinstance(segments, list):
        raise ValueError('%s: сегменты не массив' % where)

    position = 0
    length = len(segments)
    segments_total = 0
    points_total = 0

    while position < length:
        if position == 0:
            if length < 2:
                raise ValueError('%s: у кривой нет начальной точки' % where)
            points_total += 1
            position += 2
        if position >= length:
            raise ValueError('%s: за начальной точкой нет сегмента' % where)
        kind = segments[position]
        if kind not in SEGMENT_POINTS:
            raise ValueError('%s: неизвестный тип сегмента %r на позиции %d' % (where, kind, position))
        step = 1 + 2 * SEGMENT_POINTS[kind]
        if position + step > length:
            raise ValueError('%s: сегмент %s обрывается на позиции %d' % (where, SEGMENT_NAMES[kind], position))
        points_total += SEGMENT_POINTS[kind]
        segments_total += 1
        position += step

    return segments_total, points_total


def measure(document, where):
    curves = document.get('Curves')
    if not isinstance(curves, list):
        raise ValueError('%s: нет раздела Curves' % where)
    segments_total = 0
    points_total = 0
    for index, curve in enumerate(curves):
        if not isinstance(curve, dict) or 'Segments' not in curve:
            raise ValueError('%s: кривая %d без сегментов' % (where, index))
        name = curve.get('Id', 'кривая %d' % index)
        counted, points = walk_curve(curve['Segments'], '%s/%s' % (where, name))
        segments_total += counted
        points_total += points
    return {'CurveCount': len(curves), 'TotalSegmentCount': segments_total, 'TotalPointCount': points_total}


def describe(meta):
    return 'CurveCount %s, TotalSegmentCount %s, TotalPointCount %s' % (
        meta.get('CurveCount'), meta.get('TotalSegmentCount'), meta.get('TotalPointCount'))


def process_file(path, check_only):
    """Returns (changed, problem_text)."""
    with open(path, encoding='utf-8') as handle:
        document = json.load(handle)
    try:
        measured = measure(document, os.path.basename(path))
    except ValueError as error:
        return False, str(error)

    meta = document.get('Meta')
    if not isinstance(meta, dict):
        return False, '%s: нет раздела Meta' % path

    current = {key: meta.get(key) for key in measured}
    if current == measured:
        return False, None
    if check_only:
        return True, '%s: Meta неверен — записано (%s), данные дают (%s)' % (
            os.path.basename(path), describe(current), describe(measured))

    meta.update(measured)
    with open(path, 'w', encoding='utf-8') as handle:
        json.dump(document, handle, ensure_ascii=False, separators=(',', ':'))
    return True, None


def collect(paths):
    for path in paths:
        if os.path.isdir(path):
            for root, _dirs, files in os.walk(path):
                for name in sorted(files):
                    if name.endswith('.motion3.json'):
                        yield os.path.join(root, name)
        else:
            yield path


def main():
    arguments = sys.argv[1:]
    check_only = False
    if arguments and arguments[0] == '--check':
        check_only = True
        arguments = arguments[1:]
    if not arguments:
        print(__doc__)
        return 2

    files = list(collect(arguments))
    if not files:
        print('не найдено ни одного motion3.json')
        return 2

    fixed = 0
    for path in files:
        try:
            changed, problem = process_file(path, check_only)
        except (OSError, json.JSONDecodeError) as error:
            problem = '%s: %s' % (path, error)
            changed = False
        if problem:
            print('ОШИБКА ' + problem)
            return 1
        if changed:
            fixed += 1
            if check_only:
                print('НЕВЕРНЫЙ Meta: ' + path)
            else:
                print('исправлено: ' + path)

    if check_only:
        print('проверка: файлов %d, все Meta верны' % len(files) if not fixed
              else 'проверка: файлов %d, неверных Meta %d' % (len(files), fixed))
    else:
        print('пересчёт: файлов %d, исправлено %d' % (len(files), fixed))
    return 0 if (not check_only or fixed == 0) else 1


if __name__ == '__main__':
    sys.exit(main())
