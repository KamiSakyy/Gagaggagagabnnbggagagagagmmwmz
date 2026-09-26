package com.echidna.studio.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Поворот кадра камеры.
 *
 * <p>На телефоне кадр приходил вверх ногами: и окошко, и распознавание лица видели перевёрнутую
 * картинку, поэтому лицо «не находилось». Правило взято из примера Google для ML Kit: для
 * фронтальной камеры угол сенсора складывается с поворотом телефона. Тест закрепляет его, чтобы
 * ошибка на 180 градусов не вернулась.</p>
 */
public class CameraRotationTest {

    @Test
    public void theBackCameraFollowsTheSensor() {
        assertEquals("сенсор 90, телефон вертикально", 90,
                CameraRotation.upright(false, 90, 0, 0));
        assertEquals("сенсор 270, телефон вертикально", 270,
                CameraRotation.upright(false, 270, 0, 0));
        assertEquals("сенсор 90, телефон повёрнут на 90", 0,
                CameraRotation.upright(false, 90, 90, 0));
        assertEquals("сенсор 270, телефон повёрнут на 90", 180,
                CameraRotation.upright(false, 270, 90, 0));
    }

    @Test
    public void theFrontCameraAddsTheDeviceRotation() {
        assertEquals("сенсор 270, телефон вертикально", 270,
                CameraRotation.upright(true, 270, 0, 0));
        assertEquals("сенсор 90, телефон вертикально", 90,
                CameraRotation.upright(true, 90, 0, 0));
        assertEquals("сенсор 90 и телефон на 90 градусов", 180,
                CameraRotation.upright(true, 90, 90, 0));
        assertEquals("сенсор 270 и телефон на 90 градусов", 0,
                CameraRotation.upright(true, 270, 90, 0));
        // Телефон лежит на боку на 180 градусов: кадр доворачивается на те же 180.
        assertEquals(90, CameraRotation.upright(true, 270, 180, 0));
    }

    @Test
    public void atEqualSensorAnglesTheTwoCamerasMatchOnlyAccidentally() {
        // Сенсор 90: фронтальная камера даёт 90, основная - тоже 90.
        assertEquals(CameraRotation.upright(false, 90, 0, 0),
                CameraRotation.upright(true, 90, 0, 0));
        // Сенсор 270: фронтальная даёт 270, основная - 270.
        assertEquals(CameraRotation.upright(false, 270, 0, 0),
                CameraRotation.upright(true, 270, 0, 0));
        // А вот при повёрнутом телефоне знак разный: фронтальная прибавляет, основная вычитает.
        assertEquals(180, CameraRotation.upright(true, 90, 90, 0));
        assertEquals(0, CameraRotation.upright(false, 90, 90, 0));
    }

    @Test
    public void theRealmeStyleCameraGetsTherightTurn() {
        // realme RMX3624 и большинство телефонов: фронтальный сенсор 270. Раньше приложение
        // поворачивало такой кадр на 90 градусов - ровно вверх ногами.
        final int old = CameraRotation.normalize(360 - 270);
        final int now = CameraRotation.upright(true, 270, 0, 0);
        assertEquals("правильный поворот", 270, now);
        assertEquals("старая ошибка отличалась на 180 градусов", 180,
                CameraRotation.normalize(now - old));
    }

    @Test
    public void theManualTurnIsAddedOnTop() {
        assertEquals(90, CameraRotation.upright(true, 270, 0, 180));
        assertEquals(0, CameraRotation.upright(true, 90, 0, 270));
        assertEquals(90, CameraRotation.upright(false, 90, 0, 360));
        assertEquals(350, CameraRotation.upright(false, 90, 0, 260));
    }

    @Test
    public void theResultIsAlwaysAFullTurn() {
        for (int sensor = 0; sensor < 360; sensor += 90) {
            for (int device = 0; device < 360; device += 90) {
                for (int extra = -360; extra <= 720; extra += 90) {
                    final int rotation = CameraRotation.upright(
                            sensor % 180 == 0, sensor, device, extra);
                    assertTrue("поворот вне диапазона: " + rotation,
                            rotation >= 0 && rotation < 360);
                    assertEquals("поворот кратен 90 градусам", 0, rotation % 90);
                }
            }
        }
    }
}
