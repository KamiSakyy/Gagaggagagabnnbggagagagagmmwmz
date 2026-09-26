package com.echidna.studio.track;

/**
 * Насколько повернуть кадр камеры, чтобы он стоял ровно.
 *
 * <p>Отдельный класс без единого обращения к Android: именно здесь была ошибка, из-за которой на
 * телефоне картинка и лицо приходили вверх ногами, и эту арифметику нужно проверять тестом на
 * обычной JVM.</p>
 *
 * <p>Основная камера: сенсор повёрнут на {@code sensorOrientation} градусов по часовой стрелке, а
 * поворот самого телефона вычитается. Фронтальная камера смотрит на пользователя, и картинка в её
 * буфере перевёрнута относительно основной: тот же сенсор требует поворота в другую сторону.</p>
 */
public final class CameraRotation {

    private CameraRotation() {
    }

    /**
     * @param front            фронтальная ли камера
     * @param sensorOrientation {@code SENSOR_ORIENTATION} камеры, градусы
     * @param deviceRotation   поворот экрана: 0, 90, 180 или 270
     * @param extraRotation    ручной доворот из интерфейса, любые градусы
     * @return поворот кадра по часовой стрелке, 0, 90, 180 или 270
     */
    public static int upright(boolean front, int sensorOrientation, int deviceRotation,
                              int extraRotation) {
        final int upright = front
                ? (360 - sensorOrientation + deviceRotation) % 360
                : (sensorOrientation - deviceRotation + 360) % 360;
        return ((upright + extraRotation) % 360 + 360) % 360;
    }

    /** Нужно ли менять ширину и высоту кадра: поворот на 90 или 270 градусов. */
    public static boolean swapsSides(int rotation) {
        return rotation == 90 || rotation == 270;
    }
}
