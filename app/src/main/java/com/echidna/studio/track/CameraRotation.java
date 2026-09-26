package com.echidna.studio.track;

/**
 * Насколько повернуть кадр камеры, чтобы он стоял ровно.
 *
 * <p>Отдельный класс без единого обращения к Android: именно здесь была ошибка, из-за которой на
 * телефоне картинка и лицо приходили вверх ногами, и эту арифметику нужно проверять тестом на
 * обычной JVM.</p>
 *
 * <p>Правило взято из документации Google (та же формула в примере ML Kit для фронтальной камеры):
 * для основной камеры из угла сенсора вычитается поворот телефона, для фронтальной - поворот
 * телефона прибавляется. Раньше во фронтальной ветке стояло вычитание, и на всех телефонах с
 * сенсором 90 или 270 градусов (это почти все телефоны) кадр отличался от правильного ровно на
 * 180 градусов: человек видел себя в окошке вверх ногами, а детектор лиц на перевёрнутой картинке
 * лицо не находил - отсюда и "лицо не распознано", и вялый поворот головы.</p>
 */
public final class CameraRotation {

    private CameraRotation() {
    }

    /**
     * Поворот кадра без ручного доворота: то, что подсказывают характеристики камеры.
     *
     * @param front             фронтальная ли камера
     * @param sensorOrientation {@code SENSOR_ORIENTATION} камеры, градусы
     * @param deviceRotation    поворот экрана: 0, 90, 180 или 270
     * @return поворот кадра по часовой стрелке, 0, 90, 180 или 270
     */
    public static int base(boolean front, int sensorOrientation, int deviceRotation) {
        final int sensor = normalize(sensorOrientation);
        final int device = normalize(deviceRotation);
        // Фронтальная камера: поворот телефона прибавляется к углу сенсора. Основная: вычитается.
        return front ? (sensor + device) % 360 : (sensor - device + 360) % 360;
    }

    /**
     * @param front             фронтальная ли камера
     * @param sensorOrientation {@code SENSOR_ORIENTATION} камеры, градусы
     * @param deviceRotation    поворот экрана: 0, 90, 180 или 270
     * @param extraRotation     ручной доворот из интерфейса, любые градусы
     * @return поворот кадра по часовой стрелке, 0, 90, 180 или 270
     */
    public static int upright(boolean front, int sensorOrientation, int deviceRotation,
                              int extraRotation) {
        return normalize(base(front, sensorOrientation, deviceRotation) + extraRotation);
    }

    /** Приводит любой угол к диапазону 0..359. */
    public static int normalize(int degrees) {
        return ((degrees % 360) + 360) % 360;
    }

    /** Нужно ли менять ширину и высоту кадра: поворот на 90 или 270 градусов. */
    public static boolean swapsSides(int rotation) {
        return rotation == 90 || rotation == 270;
    }
}
