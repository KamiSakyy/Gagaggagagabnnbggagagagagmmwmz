#!/usr/bin/env python3
"""Собирает R.java для оффлайн-проверки.

Gradle генерирует класс `R` сам, но `tools/local_check.sh` компилирует приложение без Gradle, и
любое обращение к `R.drawable.…` там не находилось. Скрипт проходит по `app/src/main/res/`,
собирает имена ресурсов и пишет такой же класс, какой сделал бы aapt2.

    python3 tools/gen_r_java.py <корень репозитория> <куда писать>
"""

import os
import re
import sys

# Папка ресурсов -> имя вложенного класса R
RESOURCE_TYPES = {
    "drawable": "drawable",
    "mipmap-anydpi-v26": "mipmap",
    "mipmap-mdpi": "mipmap",
    "mipmap-hdpi": "mipmap",
    "mipmap-xhdpi": "mipmap",
    "mipmap-xxhdpi": "mipmap",
    "mipmap-xxxhdpi": "mipmap",
    "layout": "layout",
    "menu": "menu",
    "anim": "anim",
}

# values/*.xml: тип берётся из имени тега внутри файла
VALUE_TAGS = {"string": "string", "color": "color", "style": "style", "dimen": "dimen",
              "integer": "integer", "bool": "bool", "array": "array"}

NAME_RE = re.compile(r'android:name="([^"]+)"')


def resource_names(path):
    """Имя ресурса из имени файла: ic_models.xml -> ic_models."""
    base = os.path.basename(path)
    return base.split(".")[0]


def collect(root):
    found = {}
    res_dir = os.path.join(root, "app/src/main/res")
    if not os.path.isdir(res_dir):
        return found
    for folder in sorted(os.listdir(res_dir)):
        directory = os.path.join(res_dir, folder)
        if not os.path.isdir(directory):
            continue
        if folder.startswith("values"):
            for name in sorted(os.listdir(directory)):
                if not name.endswith(".xml"):
                    continue
                text = open(os.path.join(directory, name), encoding="utf-8").read()
                for tag, res_type in VALUE_TAGS.items():
                    pattern = re.compile(r"<{0}[^>]*".format(tag))
                    for match in pattern.finditer(text):
                        block = text[match.start():text.find(">", match.start()) + 1]
                        named = NAME_RE.search(block)
                        if named:
                            found.setdefault(res_type, set()).add(named.group(1))
            continue
        res_type = RESOURCE_TYPES.get(folder)
        if res_type is None:
            continue
        for name in sorted(os.listdir(directory)):
            if name.endswith(".xml") or name.endswith(".png") or name.endswith(".webp"):
                found.setdefault(res_type, set()).add(resource_names(os.path.join(directory, name)))
    return found


def write_java(found, target):
    lines = ["package com.echidna.studio;", "", "/** Сгенерировано tools/gen_r_java.py для оффлайн-проверки. */",
             "public final class R {"]
    for res_type in sorted(found):
        lines.append("    public static final class {0} {{".format(res_type))
        for name in sorted(found[res_type]):
            lines.append("        public static final int {0} = {1};".format(
                name, 0x7F000000 + abs(hash(name)) % 0xFFFFF))
        lines.append("    }")
    lines.append("}")
    lines.append("")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines))
    return sum(len(names) for names in found.values())


def main(argv):
    root = argv[1] if len(argv) > 1 else "."
    target = argv[2] if len(argv) > 2 else "/tmp/echidna-build/gen/com/echidna/studio/R.java"
    found = collect(root)
    count = write_java(found, target)
    print("R.java: {0} ресурсов, типы: {1}".format(count, " ".join(sorted(found))))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
