#!/usr/bin/env python3
"""Сторож содержимого: проверяет, что рабочее дерево не откатилось к старой версии.

Один раз дерево уже потеряло движок 3D и четыре из пяти Live2D-моделей: синхронизация выложила
поверх старое состояние, и приложение собиралось, но показывало только Ехидну. Сборка при этом
проходила молча, поэтому проверку делает этот скрипт - он запускается первым шагом
`tools/local_check.sh` и падает с понятным сообщением, если чего-то из шести персонажей нет.

    python3 tools/check_tree.py <корень репозитория>
"""

import json
import os
import sys

# Каталог модели -> (сколько файлов движений обязано быть, есть ли своя мимика)
MODELS = {
    "echidna": 68,
    "echidna_valentine": 66,
    "emilia_bunny": 125,
    "emilia_swimsuit": 115,
    "nahida_genshin": 0,
}

ENGINE = [
    "app/src/main/java/com/echidna/studio/three/Model3D.java",
    "app/src/main/java/com/echidna/studio/three/Model3DStage.java",
    "app/src/main/java/com/echidna/studio/three/Camera3D.java",
    "app/src/main/java/com/echidna/studio/three/Gltf.java",
    "app/src/main/java/com/echidna/studio/three/Mat4.java",
    "app/src/main/java/com/echidna/studio/three/Json.java",
]

ANIM = [
    "app/src/main/java/com/echidna/studio/anim/MotionPicker.java",
    "app/src/main/java/com/echidna/studio/anim/ExpressionPicker.java",
    "app/src/main/java/com/echidna/studio/anim/ShowLibrary.java",
    "app/src/main/java/com/echidna/studio/anim/IdleDirector.java",
]


def count_motions(directory):
    if not os.path.isdir(directory):
        return -1
    return sum(1 for name in os.listdir(directory) if name.endswith(".motion3.json"))


def main(argv):
    root = argv[1] if len(argv) > 1 else "."
    problems = []
    warnings = []

    for path in ENGINE + ANIM:
        if not os.path.isfile(os.path.join(root, path)):
            problems.append("нет файла: " + path)

    print("модели в дереве:")
    for name, expected in sorted(MODELS.items()):
        directory = os.path.join(root, "app/src/main/assets/live2d", name)
        motions = count_motions(os.path.join(directory, "motions"))
        if motions < 0 and expected == 0:
            # У рига без движений каталога движений может не быть вовсе: у Нахиды только мимика.
            motions = 0
        has_moc = os.path.isfile(os.path.join(directory, "model.moc3"))
        has_json = os.path.isfile(os.path.join(directory, "model3.json"))
        print("  {0:<18} движений {1:<4} moc3 {2}  model3.json {3}".format(
            name, motions if motions >= 0 else "нет", "да" if has_moc else "НЕТ",
            "да" if has_json else "НЕТ"))
        if not has_moc or not has_json:
            problems.append("модель " + name + " без model.moc3 или model3.json")
        elif motions != expected:
            problems.append("{0}: движений {1}, ожидалось {2}".format(name, motions, expected))

    portrait = os.path.join(root, "app/src/main/assets/three/character.vrm")
    if os.path.isfile(portrait):
        size = os.path.getsize(portrait)
        print("объёмная модель: {0} байт".format(size))
        if size < 1024 * 1024:
            problems.append("файл объёмной модели подозрительно мал: {0} байт".format(size))
    else:
        warnings.append("нет app/src/main/assets/three/character.vrm "
                        "(качается: tools/fetch_vrm_model.sh)")

    catalog = os.path.join(root, "app/src/main/java/com/echidna/studio/ModelCatalog.java")
    if os.path.isfile(catalog):
        text = open(catalog, encoding="utf-8").read()
        for name in MODELS:
            if name not in text:
                problems.append("в каталоге моделей нет " + name)
        if "three" not in text:
            problems.append("в каталоге моделей нет объёмного персонажа")
    else:
        problems.append("нет файла ModelCatalog.java")

    for model_json in [os.path.join(root, "app/src/main/assets/live2d", name, "model3.json")
                       for name in MODELS]:
        if not os.path.isfile(model_json):
            continue
        try:
            data = json.load(open(model_json, encoding="utf-8"))
            groups = data.get("FileReferences", {}).get("Motions", {})
            if not groups and "nahida" not in model_json:
                warnings.append("в " + model_json + " нет ссылок на движения")
        except (ValueError, KeyError) as error:
            problems.append("сломанный model3.json: " + model_json + ": " + str(error))

    for warning in warnings:
        print("предупреждение: " + warning)
    if problems:
        print("ДЕРЕВО ПОТЕРЯЛО ЧАСТЬ ПРОЕКТА:")
        for problem in problems:
            print("  - " + problem)
        return 1
    print("содержимое дерева на месте: шесть персонажей, движок 3D, сцена и шоу")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
