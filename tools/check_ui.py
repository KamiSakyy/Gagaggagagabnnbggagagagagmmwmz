#!/usr/bin/env python3
"""Проверяет, что все ресурсы, на которые ссылается код, существуют.

Обращение к несуществующему `R.drawable.…` не ломает компиляцию ресурсов, но падает в момент
создания экрана: приложение закрывается сразу после запуска, а в логе - `Resources$NotFoundException`.
Скрипт проходит по исходникам, собирает имена иконок и сравнивает их с тем, что лежит в
`app/src/main/res`, чтобы такая опечатка не доехала до телефона.

    python3 tools/check_ui.py <корень репозитория>
"""

import os
import re
import sys

REFERENCE_RE = re.compile(r"R\.([a-z]+)\.([A-Za-z0-9_]+)")

# Тип ресурса -> папки, откуда он берётся
SOURCES = {
    "drawable": ["drawable"],
    "mipmap": ["mipmap-anydpi-v26", "mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi",
               "mipmap-xxhdpi", "mipmap-xxxhdpi"],
}


def existing(root, res_type):
    names = set()
    for folder in SOURCES.get(res_type, []):
        directory = os.path.join(root, "app/src/main/res", folder)
        if not os.path.isdir(directory):
            continue
        for name in os.listdir(directory):
            names.add(name.split(".")[0])
    return names


def main(argv):
    root = argv[1] if len(argv) > 1 else "."
    java_root = os.path.join(root, "app/src/main/java")
    if not os.path.isdir(java_root):
        java_root = os.path.join(root, "src/main/java")

    used = {}
    for current, _, files in os.walk(java_root):
        if "com/live2d" in current.replace(os.sep, "/"):
            continue
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(current, name)
            text = open(path, encoding="utf-8").read()
            for res_type, res_name in REFERENCE_RE.findall(text):
                used.setdefault(res_type, set()).add((res_name, path))

    problems = []
    for res_type in sorted(used):
        known = existing(root, res_type)
        for res_name, path in sorted(used[res_type]):
            if res_name == "id" or res_type in ("string", "style", "color", "layout"):
                continue
            if res_name not in known:
                problems.append("{0}: R.{1}.{2} - такого ресурса нет в res/{1}".format(
                    os.path.relpath(path, root), res_type, res_name))

    print("ресурсов в коде: " + str(sum(len(names) for names in used.values())))
    print("иконок в res: " + str(len(existing(root, "drawable"))))
    if problems:
        print("НЕТ РЕСУРСОВ:")
        for problem in problems:
            print("  - " + problem)
        return 1
    print("все обращения к ресурсам существуют")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
