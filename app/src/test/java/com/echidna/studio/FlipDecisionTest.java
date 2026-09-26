package com.echidna.studio.track;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Переворот кадра: камера обязана поворачиваться только тогда, когда лицо видно ИСКЛЮЧИТЕЛЬНО на
 * перевёрнутых кадрах. Именно из-за мягкой проверки камера у пользователя оказывалась вверх ногами.
 */
public class FlipDecisionTest {
    private static final int NORMAL = 8;
    private static final int FLIPPED = 8;

    @Test
    public void aFaceInTheNormalPositionIsNeverFlipped() {
        final FlipDecision decision = new FlipDecision();
        assertFalse("лицо видно как надо - переворачивать нечего",
                decision.evaluate(NORMAL, 3, FLIPPED, 8, 1000L));
        assertFalse(decision.evaluate(NORMAL, 1, FLIPPED, 8, 5000L));
    }

    @Test
    public void aFaceOnlyOnTheFlippedFramesTurnsTheCamera() {
        final FlipDecision decision = new FlipDecision();
        assertTrue("на обычных кадрах лица нет вовсе, на перевёрнутых - есть",
                decision.evaluate(NORMAL, 0, FLIPPED, 5, 1000L));
    }

    @Test
    public void tooFewObservationsAreNotEnough() {
        final FlipDecision decision = new FlipDecision();
        assertFalse("мало обычных кадров", decision.evaluate(1, 0, FLIPPED, 8, 0L));
        assertFalse("мало перевёрнутых кадров", decision.evaluate(NORMAL, 0, 2, 2, 0L));
        assertFalse("на перевёрнутых лица почти нет", decision.evaluate(NORMAL, 0, FLIPPED, 1, 0L));
    }

    @Test
    public void noFaceAnywhereNeverFlips() {
        final FlipDecision decision = new FlipDecision();
        assertFalse(decision.evaluate(NORMAL, 0, FLIPPED, 0, 0L));
    }

    @Test
    public void aLuckyFrameDoesNotFlipTheCamera() {
        final FlipDecision decision = new FlipDecision();
        // Одно ложное срабатывание на смазанном кадре, дальше лицо находится нормально.
        assertFalse(decision.evaluate(6, 0, 8, 2, 0L));
        assertFalse(decision.evaluate(12, 5, 8, 2, 2000L));
    }

    @Test
    public void afterAFlipThereIsNoSecondFlip() {
        final FlipDecision decision = new FlipDecision();
        assertTrue(decision.evaluate(NORMAL, 0, FLIPPED, 5, 1000L));
        decision.onFlipped(1000L);
        assertFalse("сразу после переворота проверки молчат",
                decision.evaluate(NORMAL, 0, FLIPPED, 8, 2000L));
        assertFalse(decision.evaluate(NORMAL, 0, FLIPPED, 8, 10500L));
        assertTrue("через десять секунд проверка снова возможна",
                decision.evaluate(NORMAL, 0, FLIPPED, 8, 12000L));
    }
}
