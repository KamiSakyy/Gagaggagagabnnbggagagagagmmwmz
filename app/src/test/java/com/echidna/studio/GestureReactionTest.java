package com.echidna.studio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.echidna.studio.anim.GestureReaction;

import org.junit.Test;

/**
 * "Показываю четыре пальца - моделька тоже": кивки идут ровно по счёту, а не бесконечно и не
 * раньше, чем жест распознан уверенно.
 */
public class GestureReactionTest {
    private static final float STEP = 1.0f / 30.0f;

    /** Прогоняет время вперёд и считает, сколько раз голова успела наклониться и вернуться. */
    private static int countNods(GestureReaction reaction, int fingers, float seconds) {
        int nods = 0;
        boolean down = false;
        for (float t = 0; t < seconds; t += STEP) {
            reaction.update(fingers, STEP);
            final boolean nowDown = reaction.nodDegrees() > 1.0f;
            if (down && !nowDown) {
                nods++;
            }
            down = nowDown;
        }
        return nods;
    }

    @Test
    public void fourFingersGiveFourNods() {
        final GestureReaction reaction = new GestureReaction();
        assertEquals(4, countNods(reaction, 4, 4.0f));
        assertEquals(4, reaction.shownCount());
        assertEquals(0, reaction.nodsLeft());
        assertEquals(0.0f, reaction.nodDegrees(), 0.0001f);
    }

    @Test
    public void twoFingersGiveTwoNods() {
        final GestureReaction reaction = new GestureReaction();
        assertEquals(2, countNods(reaction, 2, 3.0f));
    }

    @Test
    public void fistDoesNotNod() {
        final GestureReaction reaction = new GestureReaction();
        assertEquals(0, countNods(reaction, 0, 2.0f));
        assertEquals(0, reaction.shownCount());
    }

    @Test
    public void nothingHappensBeforeTheGestureIsHeld() {
        final GestureReaction reaction = new GestureReaction();
        // 0.1 с короче выдержки: считать жест ещё нельзя.
        for (float t = 0; t < 0.1f; t += STEP) {
            reaction.update(3, STEP);
        }
        assertEquals(-1, reaction.shownCount());
        assertEquals(0.0f, reaction.nodDegrees(), 0.0001f);
    }

    @Test
    public void handLeavingTheFrameStopsCounting() {
        final GestureReaction reaction = new GestureReaction();
        for (float t = 0; t < 1.0f; t += STEP) {
            reaction.update(4, STEP);
        }
        reaction.update(-1, STEP);
        assertEquals(-1, reaction.shownCount());
        // Начатые кивки доигрываются, но новых не появляется.
        for (float t = 0; t < 3.0f; t += STEP) {
            reaction.update(-1, STEP);
        }
        assertEquals(0, reaction.nodsLeft());
        assertEquals(0.0f, reaction.nodDegrees(), 0.0001f);
    }

    @Test
    public void aNewGestureStartsItsOwnCount() {
        final GestureReaction reaction = new GestureReaction();
        countNods(reaction, 5, 4.0f);
        assertEquals(5, reaction.shownCount());
        assertEquals(1, countNods(reaction, 1, 1.5f));
        assertEquals(1, reaction.shownCount());
    }

    @Test
    public void noNodWithoutHands() {
        final GestureReaction reaction = new GestureReaction();
        for (float t = 0; t < 3.0f; t += STEP) {
            reaction.update(-1, STEP);
        }
        assertTrue(reaction.nodDegrees() == 0.0f);
    }
}
