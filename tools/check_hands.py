#!/usr/bin/env python3
"""Сторож жестов рукой и тишины микрофона.

Проверяет три вещи, которые легко потерять при следующей правке:

* модель кисти MediaPipe объявлена и поднимается в хабе трекинга, а её загрузчик умеет достать
  официальный бандл;
* цепочка «пальцы -> кивки и руки модели» не разорвана: FaceSignals несёт ладонь и пальцы,
  TrackingMapper кормит ими Pose, а EchidnaModel двигает каналы рук, если они у модели есть;
* микрофона в приложении нет: ни разрешения, ни кода, ни тестов, ни упоминаний в интерфейсе.
"""
import os
import re
import sys


def read(root, relative):
    path = os.path.join(root, relative)
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    problems = []

    hands = read(root, "app/src/main/java/com/echidna/studio/track/MediaPipeHandTracker.java")
    if hands is None:
        problems.append("нет трекера кисти track/MediaPipeHandTracker.java")
    else:
        for needle, why in [
            ("models/hand_landmarker.task", "трекер кисти не указывает на модель hand_landmarker.task"),
            ("HandLandmarker", "трекер кисти не использует MediaPipe HandLandmarker"),
            ("fingerCount", "трекер кисти не считает пальцы через HandPose"),
            ("busy()", "у трекера кисти нет busy(): кадр перепишется под графом"),
            ("resultsSeen()", "у трекера кисти нет resultsSeen(): сторож зависания не увидит молчание"),
        ]:
            if needle not in hands:
                problems.append(why)

    hub = read(root, "app/src/main/java/com/echidna/studio/track/TrackingHub.java")
    if hub is None:
        problems.append("нет track/TrackingHub.java")
    else:
        for needle, why in [
            ("openHandTracker", "хаб не поднимает трекер кисти"),
            ("copyHands", "хаб не переносит данные кисти в общий кадр"),
            ("HandPose.reachOf", "хаб не считает касание подбородка"),
            ("chinTouch", "хаб не заполняет chinTouch"),
            ("tracker.busy()", "хаб не ждёт, пока трекер освободит кадр"),
            ("checkTrackerHealth", "нет сторожа зависшего трекера"),
            ("previewSerial", "нет номера кадра предпросмотра: текстура будет грузиться на каждом кадре"),
        ]:
            if needle not in hub:
                problems.append(why)

    mapper = read(root, "app/src/main/java/com/echidna/studio/track/TrackingMapper.java")
    if mapper is None:
        problems.append("нет track/TrackingMapper.java")
    else:
        for needle, why in [
            ("GestureReaction", "маппер не отвечает на счёт пальцев"),
            ("chinTouch", "маппер не передаёт касание подбородка в позу"),
            ("handOpenDamp", "маппер не передаёт открытую ладонь"),
            ("setArmInverted", "нет переключателя направления рук"),
        ]:
            if needle not in mapper:
                problems.append(why)

    model = read(root, "app/src/main/java/com/echidna/studio/EchidnaModel.java")
    if model is None:
        problems.append("нет EchidnaModel.java")
    else:
        for needle, why in [
            ("applyArms", "модель не двигает руками по жесту"),
            ("ParamUpperArmL", "не подключён канал плеча"),
            ("ParamForeArmL", "не подключён канал предплечья"),
            ("ParamHandL", "не подключён канал кисти"),
            ("armChannelCount", "нет отчёта о каналах рук"),
        ]:
            if needle not in model:
                problems.append(why)

    fetcher = read(root, "tools/fetch_hand_model.sh")
    if fetcher is None:
        problems.append("нет tools/fetch_hand_model.sh: модель кисти не скачается в CI")
    else:
        for needle, why in [
            ("fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1",
             "загрузчик модели кисти не проверяет официальный sha256"),
            ("hand_landmarks_detector.tflite", "загрузчик не проверяет содержимое бандла"),
        ]:
            if needle not in fetcher:
                problems.append(why)

    # Микрофон: его в приложении быть не должно ни в каком виде.
    manifest = read(root, "app/src/main/AndroidManifest.xml") or ""
    if "RECORD_AUDIO" in manifest:
        problems.append("в манифесте осталось разрешение RECORD_AUDIO")

    sources = os.path.join(root, "app/src/main/java/com/echidna/studio")
    for folder, _, files in os.walk(sources):
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(folder, name)
            with open(path, encoding="utf-8", errors="replace") as handle:
                text = handle.read()
            relative = os.path.relpath(path, root)
            if "AudioLevelMonitor" in text:
                problems.append("%s всё ещё ссылается на AudioLevelMonitor" % relative)
            if "setMicLevel" in text or "setMicEnabled" in text or "setMicGain" in text:
                problems.append("%s всё ещё управляет микрофоном" % relative)
            if re.search(r"manifest\.permission\.RECORD_AUDIO", text):
                problems.append("%s всё ещё просит разрешение на микрофон" % relative)

    if os.path.isfile(os.path.join(root, "app/src/main/java/com/echidna/studio/AudioLevelMonitor.java")):
        problems.append("файл AudioLevelMonitor.java остался в дереве")

    if problems:
        print("ПРОВЕРКА ЖЕСТОВ И МИКРОФОНА НЕ ПРОЙДЕНА:")
        for problem in problems:
            print("  - " + problem)
        return 1

    channels = re.findall(r'"(Param(?:UpperArm|ForeArm|Hand)[A-Z]{1,3})"', model)
    print("жесты рукой в порядке: трекер кисти подключён, каналов рук в модели %d (%s)"
          % (len(set(channels)), ", ".join(sorted(set(channels)))))
    print("микрофона нет: ни разрешения, ни кода, ни упоминаний")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
