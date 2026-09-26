#!/usr/bin/env python3
"""Не даёт вернуться жёстким путям к папке одной модели.

Ошибка, которая попала в сборку 1.2.0: загрузчик текстуры и движений строил путь от константы
`MODEL_DIR = "live2d/echidna/"`, а не от папки выбранного персонажа. Из-за этого Валентина,
Эмилия и Нахида грузили чужие файлы: модель выглядела белой, а движения не играли.

Скрипт ищет строковые литералы с путём внутри `assets/live2d/...` во всех исходниках приложения и
разрешает их только там, где им положено быть: в каталоге моделей (список персонажей) и в
проверках. Любой другой файл с таким литералом - ошибка сборки.

    python3 tools/check_model_paths.py <корень репозитория>
"""

import os
import re
import sys

# Файлы, которым знать путь к конкретной модели разрешено.
ALLOWED = {
    "app/src/main/java/com/echidna/studio/ModelCatalog.java",   # список персонажей приложения
}

PATH_RE = re.compile(r'"(live2d/[a-z0-9_]+)/?"')


def main(argv):
    root = argv[1] if len(argv) > 1 else "."
    java_root = os.path.join(root, "app/src/main/java/com/echidna")
    problems = []
    seen = 0
    if os.path.isdir(java_root):
        for current, _, files in os.walk(java_root):
            for name in sorted(files):
                if not name.endswith(".java"):
                    continue
                path = os.path.join(current, name)
                relative = os.path.relpath(path, root)
                text = open(path, encoding="utf-8").read()
                for line_number, line in enumerate(text.splitlines(), 1):
                    if line.strip().startswith("*") or line.strip().startswith("//"):
                        continue
                    match = PATH_RE.search(line)
                    if not match:
                        continue
                    seen += 1
                    if relative.replace(os.sep, "/") not in ALLOWED:
                        problems.append("{0}:{1}: жёсткий путь {2} - путь модели берётся из "
                                        "ModelCatalog, иначе персонаж грузит чужие файлы".format(
                                            relative, line_number, match.group(1)))
    print("литералов с путём модели: " + str(seen))
    if problems:
        print("ЖЁСТКИЕ ПУТИ К МОДЕЛЯМ:")
        for problem in problems:
            print("  - " + problem)
        return 1
    print("пути моделей берутся только из каталога персонажей")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
