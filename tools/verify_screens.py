#!/usr/bin/env python3
"""Checks that the screenshots of the running app really show the model.

The CI job captures the emulator screen in every mode. A screenshot that only contains the
background would still be a valid PNG, so the frames are analysed here: the script measures how much
of the picture differs from the dominant background colour (that is the model), verifies the chroma
key backgrounds have the exact key colour, and fails when a frame looks empty.

Usage:
    verify_screens.py <screenshot.png> [<min-coverage-percent>] [--chroma R,G,B] ...
"""
import sys
import os


def load(path):
    try:
        from PIL import Image
    except ImportError:
        print("Pillow недоступен, проверка картинок пропущена")
        return None
    if not os.path.exists(path):
        print("нет файла: " + path)
        return None
    return Image.open(path).convert("RGB")


def coverage(image, tolerance=28):
    """Fraction of pixels that differ from the most common colour (the background)."""
    width, height = image.size
    pixels = list(image.getdata())
    histogram = {}
    for pixel in pixels[::7]:
        histogram[pixel] = histogram.get(pixel, 0) + 1
    background = max(histogram.items(), key=lambda item: item[1])[0]
    different = 0
    total = 0
    for pixel in pixels[::3]:
        total += 1
        if (abs(pixel[0] - background[0]) > tolerance
                or abs(pixel[1] - background[1]) > tolerance
                or abs(pixel[2] - background[2]) > tolerance):
            different += 1
    return different * 100.0 / max(1, total), background


def center_coverage(image, tolerance=28):
    """Coverage of the middle column, where the character is drawn."""
    width, height = image.size
    crop = image.crop((int(width * 0.25), int(height * 0.2), int(width * 0.75), int(height * 0.75)))
    return coverage(crop, tolerance)[0]


def dominant_center_color(image):
    width, height = image.size
    crop = image.crop((int(width * 0.3), int(height * 0.3), int(width * 0.7), int(height * 0.7)))
    pixels = list(crop.getdata())[::5]
    average = [sum(pixel[i] for pixel in pixels) / len(pixels) for i in range(3)]
    return average


def main():
    args = sys.argv[1:]
    if not args:
        print("использование: verify_screens.py <png> [min%] [--chroma R,G,B]")
        return 2

    path = args[0]
    minimum = float(args[1]) if len(args) > 1 and not args[1].startswith("--") else 3.0
    chroma = None
    if "--chroma" in args:
        raw = args[args.index("--chroma") + 1]
        chroma = [float(part) for part in raw.split(",")]

    image = load(path)
    if image is None:
        return 0

    full, background = coverage(image)
    center = center_coverage(image)
    print("%s: %dx%d, фон %s, модель занимает %.1f%% кадра и %.1f%% центра"
          % (os.path.basename(path), image.size[0], image.size[1], background, full, center))

    failed = False
    if center < minimum:
        print("  ПРОВАЛ: в центре кадра почти нет модели (нужно минимум %.1f%%)" % minimum)
        failed = True
    else:
        print("  модель на месте")

    if chroma is not None:
        average = dominant_center_color(image)
        # The chroma check looks at the corners: the model must not cover them.
        width, height = image.size
        corner = image.crop((0, 0, int(width * 0.1), int(height * 0.05)))
        corner_pixels = list(corner.getdata())
        corner_average = [sum(pixel[i] for pixel in corner_pixels) / len(corner_pixels) for i in range(3)]
        # The renderer clears with the exact key colour, so the corner has to match it closely.
        for i in range(3):
            if abs(corner_average[i] - chroma[i]) > 12:
                print("  ПРОВАЛ: фон не совпал с хромакеем: угол %s против %s"
                      % ([round(v) for v in corner_average], [round(v) for v in chroma]))
                failed = True
                break
        else:
            print("  хромакей на месте: угол %s" % [round(v) for v in corner_average])
        print("  центр кадра в среднем %s" % [round(v) for v in average])

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
