package com.echidna.studio.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Поворот кадра камеры.
 *
 * <p>На телефоне кадр приходил вверх ногами: и превью, и распознавание лица видели перевёрнутую
 * картинку, поэтому лицо «не находилось». Тест закрепляет правило для фронтальной камеры, чтобы
 * ошибка не вернулась, и проверяет ручной доворот из интерфейса.</p>
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
    }

    @Test
    public void theFrontCameraIsTurnedTheOtherWay() {
        // Телефон с сенсором 270: кадр нужно повернуть на 90 градусов, а не на 270.
        assertEquals(90, CameraRotation.upright(true, 270, 0, 0));
        assertEquals(270, CameraRotation.upright(true, 90, 0, 0));
        // Разворот отличается ровно на 180 градусов от правила основной камеры - это и была
        // ошибка, из-за которой лицо в кадре оказывалось вверх ногами.
        final int front = CameraRotation.upright(true, 270, 0, 0);
        final int back = CameraRotation.upright(false, 270, 0, 0);
        assertEquals("фронтальная камера отличается на 180 градусов",
                (front + 180) % 360, back);
    }

    @Test
    public void theManualTurnIsAddedOnTop() {
        assertEquals(270, CameraRotation.upright(true, 270, 0, 180));
        assertEquals(0, CameraRotation.upright(true, 90, 0, 90));
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
