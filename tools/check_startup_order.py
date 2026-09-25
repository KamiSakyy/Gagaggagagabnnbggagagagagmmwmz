#!/usr/bin/env python3
"""Guards the startup order of the Live2D framework.

The app once died before drawing a single frame because `CubismFramework.startUp()` was never
called: without it the framework's id manager stays null, the first model build throws a
NullPointerException on the GL thread and Android kills the process. This check is the regression
guard for that bug, and it runs in the local test script and in CI.

Usage: tools/check_startup_order.py [project-root]
"""
import os
import re
import sys

APP = os.path.join('app', 'src', 'main', 'java', 'com', 'echidna', 'studio')


def read(path):
    with open(path, encoding='utf-8') as handle:
        return handle.read()


def strip_comments(text):
    """Removes comments and string literals, so a mention inside a javadoc never counts as code."""
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.S)
    text = re.sub(r'//[^\n]*', ' ', text)
    text = re.sub(r'"([^"\\]|\\.)*"', '""', text)
    return text


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else '.'
    problems = []

    application = os.path.join(root, APP, 'EchidnaApplication.java')
    if not os.path.exists(application):
        problems.append('нет приложения-класса EhdidnaApplication: сторож нечего проверять'.replace('Ehdidna', 'Echidna'))
    else:
        text = strip_comments(read(application))
        if 'CubismFramework.startUp(' not in text:
            problems.append('EchidnaApplication не запускает CubismFramework.startUp')
        if 'Thread.setDefaultUncaughtExceptionHandler' not in text:
            problems.append('EchidnaApplication не ставит обработчик необработанных ошибок')

    manifest = os.path.join(root, 'app/src/main/AndroidManifest.xml')
    if os.path.exists(manifest):
        if 'EchidnaApplication' not in read(manifest):
            problems.append('AndroidManifest не указывает android:name=".EchidnaApplication"')

    # Every file of the app: find id lookups that would break without the framework being started,
    # and reject calls that dispose the framework while the app may still need it.
    for directory, _, names in os.walk(os.path.join(root, APP)):
        for name in names:
            if not name.endswith('.java'):
                continue
            path = os.path.join(directory, name)
            text = read(path)
            relative = os.path.relpath(path, root)
            for match in re.finditer(r'(\w+)\s*=\s*CubismFramework\.getIdManager\(\)', text):
                variable = match.group(1)
                after = text[match.end():]
                # The result has to be checked for null before the first use: either the very next
                # lines test it, or it is handed to the caller which checks it.
                window = after[:500]
                checked = (re.search(re.escape(variable) + r'\s*(==|!=)\s*null', window)
                           or re.search(r'return\s+' + re.escape(variable) + r'\s*;', window)
                           or re.search(r'manager\s*(==|!=)\s*null', window))
                if not checked:
                    problems.append('%s: идентификаторы движка берутся без проверки на null'
                                    % relative)
            if re.search(r'CubismFramework\.dispose\(\)', text) and 'EchidnaRenderer' not in relative:
                problems.append('%s: CubismFramework.dispose() вызывается вне рендерера' % relative)
            if 'CubismFramework.initialize()' in text and 'startUp' not in text \
                    and 'EchidnaRenderer' not in relative and 'EchidnaApplication' not in relative:
                problems.append('%s: initialize() без startUp()' % relative)

    print('проверено правил запуска Live2D')
    if problems:
        for problem in problems:
            print('ПРОВАЛ: ' + problem)
        return 1
    print('ЗАПУСК ДВИЖКА ОК')
    return 0


if __name__ == '__main__':
    sys.exit(main())
