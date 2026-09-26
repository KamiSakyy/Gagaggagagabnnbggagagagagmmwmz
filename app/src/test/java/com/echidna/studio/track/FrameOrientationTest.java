package com.echidna.studio.track;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Автоматический доворот кадра по лицу.
 *
 * <p>Правило нарочно осторожное: кадр поворачивается только по уверенному большинству наблюдений,
 * не чаще двух раз за сеанс и с паузой после поворота. Тест проверяет и осторожность, и то, что
 * перевёрнутый кадр в конце концов исправляется.</p>
 */
public class FrameOrientationTest {

    @Test
    public void aFewObservationsAreNotEnoughToTurnAnything() {
        final FrameOrientation orientation = new FrameOrientation();
        for (int i = 0; i < FrameOrientation.MIN_VOTES - 1; i++) {
            orientation.record(false);
        }
        assertEquals(FrameOrientation.UNSURE, orientation.decide(1_000L));
    }

    @Test
    public void anUpsideDownFaceTurnsTheFrame() {
        final FrameOrientation orientation = new FrameOrientation();
        for (int i = 0; i < FrameOrientation.MIN_VOTES; i++) {
            orientation.record(false);
        }
        assertEquals(FrameOrientation.FLIP, orientation.decide(10_000L));
    }

    @Test
    public void afterTheFrameIsRightTheCheckKeepsWatching() {
        final FrameOrientation orientation = new FrameOrientation();
        for (int i = 0; i < FrameOrientation.MIN_VOTES; i++) {
            orientation.record(true);
        }
        assertEquals(FrameOrientation.KEEP, orientation.decide(5_000L));
        // Кадр ровный: приложение очищает счётчики, но проверка продолжается - второй раз решать
        // есть чем, наблюдения снова копятся.
        orientation.onConfirmedUpright();
        assertEquals(0, orientation.uprightVotes());
        for (int i = 0; i < FrameOrientation.MIN_VOTES; i++) {
            orientation.record(true);
        }
        assertEquals(FrameOrientation.KEEP, orientation.decide(8_000L));
    }

    @Test
    public void anUprightFaceKeepsTheFrameAsItIs() {
        final FrameOrientation orientation = new FrameOrientation();
        for (int i = 0; i < FrameOrientation.MIN_VOTES; i++) {
            orientation.record(true);
        }
        assertEquals(FrameOrientation.KEEP, orientation.decide(10_000L));
    }

    @Test
    public void mixedObservationsMeanUnsure() {
        final FrameOrientation orientation = new FrameOrientation();
        for (int i = 0; i < 4; i++) {
            orientation.record(false);
        }
        for (int i = 0; i < 4; i++) {
            orientation.record(true);
        }
        assertEquals("противоречивые наблюдения ничего не решают",
                FrameOrientation.UNSURE, orientation.decide(10_000L));
    }

    @Test
    public void afterATurnTheCountersStartOverAndTheAppWaits() {
        final FrameOrientation orientation = new FrameOrientation();
        for (int i = 0; i < FrameOrientation.MIN_VOTES; i++) {
            orientation.record(false);
        }
        assertEquals(FrameOrientation.FLIP, orientation.decide(10_000L));
        orientation.onFlipped(10_000L);
        assertEquals("сразу после поворота решений нет",
                FrameOrientation.UNSURE, orientation.decide(10_100L));
        assertEquals("счётчики начаты заново", 0, orientation.invertedVotes());
        // Прошло время, и лицо по-прежнему вверх ногами: второй доворот разрешён.
        for (int i = 0; i < FrameOrientation.MIN_VOTES; i++) {
            orientation.record(false);
        }
        assertEquals(FrameOrientation.FLIP, orientation.decide(20_000L));
        orientation.onFlipped(20_000L);
        for (int i = 0; i < FrameOrientation.MIN_VOTES; i++) {
            orientation.record(false);
        }
        // Поворот оказался не тем: лицо снова вверх ногами. Приложение исправляет себя - застрять
        // в перевёрнутом положении оно не может.
        assertEquals("кадр всегда можно довернуть обратно",
                FrameOrientation.FLIP, orientation.decide(40_000L));
        orientation.onFlipped(40_000L);
        assertEquals(3, orientation.flips());
    }
}
