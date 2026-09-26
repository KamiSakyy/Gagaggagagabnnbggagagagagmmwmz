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
    public void theFrontCameraTurnsTheOtherWay() {
        // Сенсор 270 (так стоит фронтальная камера почти на всех телефонах): кадр поворачивается на
        // 90 градусов. Такое правило проверено на телефоне и даёт ровную картинку.
        assertEquals("сенсор 270, телефон вертикально", 90,
                CameraRotation.upright(true, 270, 0, 0));
        assertEquals("сенсор 90, телефон вертикально", 270,
                CameraRotation.upright(true, 90, 0, 0));
        assertEquals("сенсор 270 и телефон на 90 градусов", 180,
                CameraRotation.upright(true, 270, 90, 0));
        assertEquals("сенсор 90 и телефон на 90 градусов", 0,
                CameraRotation.upright(true, 90, 90, 0));
    }

    @Test
    public void theTwoCamerasDifferByHalfATurn() {
        // Именно из-за этой разницы в 180 градусов кадр и уезжал вверх ногами: правило основной
        // камеры, применённое к фронтальной (или наоборот), переворачивает картинку.
        // Расходятся именно «настоящие» сенсоры: 90 и 270 градусов - так стоят камеры у всех
        // телефонов. При нулевом угле правила совпадают, и это тоже проверяется.
        final int[] sensors = {90, 270};
        for (int i = 0; i < sensors.length; i++) {
            final int sensor = sensors[i];
            final int front = CameraRotation.upright(true, sensor, 0, 0);
            final int back = CameraRotation.upright(false, sensor, 0, 0);
            assertEquals("сенсор " + sensor, 180, CameraRotation.normalize(back - front));
        }
        assertEquals("при сенсоре 0 правила совпадают",
                CameraRotation.upright(false, 0, 0, 0), CameraRotation.upright(true, 0, 0, 0));
    }



    @Test
    public void theWrongRuleFlippedTheCameraByHalfATurn() {
        // Так выглядела ошибка, которая дважды доезжала до телефона: то же самое, но с прямым
        // знаком у фронтальной камеры. Разница с правильным правилом - ровно 180 градусов.
        final int[] sensors = {90, 270};
        for (int i = 0; i < sensors.length; i++) {
            final int sensor = sensors[i];
            final int wrong = sensor;
            final int right = CameraRotation.upright(true, sensor, 0, 0);
            assertEquals("сенсор " + sensor, 180, CameraRotation.normalize(wrong - right));
        }
    }

    @Test
    public void theManualTurnIsAddedOnTop() {
        // Кнопка «камера: перевернуть» доворачивает кадр на 180 градусов и обратно.
        assertEquals(270, CameraRotation.upright(true, 270, 0, 180));
        assertEquals(90, CameraRotation.upright(true, 270, 0, 360));
        assertEquals(0, CameraRotation.upright(true, 270, 0, 270));
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
