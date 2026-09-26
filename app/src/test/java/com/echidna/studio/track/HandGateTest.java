package com.echidna.studio.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Подтверждение руки: модель не должна поднимать руку из-за одного случайного кадра.
 *
 * <p>Трекер иногда находит кисть там, где её нет - на локте, на краю стола, на лице. Тест подаёт
 * именно такие случаи: короткую вспышку, редкие вспышки и настоящую поднятую руку. Настоящая рука
 * проходит, вспышки - нет.</p>
 */
public class HandGateTest {

    private static final float STEP = 1.0f / 30.0f;

    private static HandGate.State feed(HandGate gate, boolean seen, float lift, float seconds) {
        final int frames = Math.round(seconds / STEP);
        HandGate.State state = gate.state();
        for (int i = 0; i < frames; i++) {
            state = gate.update(seen, lift, seen ? 4 : -1, STEP);
        }
        return state;
    }

    @Test
    public void aSingleFlashOfAHandIsIgnored() {
        final HandGate gate = new HandGate();
        gate.update(true, 0.9f, 4, STEP);
        final HandGate.State state = gate.update(false, 0.0f, -1, STEP);
        assertFalse("одного кадра мало, чтобы поднять руку", state.visible);
        assertEquals("рука не считается поднятой", 0.0f, state.lift, 0.001f);
    }

    @Test
    public void aRealRaisedHandIsAccepted() {
        final HandGate gate = new HandGate();
        final HandGate.State state = feed(gate, true, 0.8f, 0.4f);
        assertTrue("настоящая рука должна пройти", state.visible);
        assertTrue("подъём должен дойти до модели: " + state.lift, state.lift > 0.5f);
        assertEquals("пальцы должны считаться", 4, state.fingers);
    }

    @Test
    public void aHandThatRestsLowNeverRaisesTheArm() {
        final HandGate gate = new HandGate();
        final HandGate.State state = feed(gate, true, 0.2f, 1.0f);
        assertTrue("рука видна", state.visible);
        assertTrue("рука лежит низко - подъём не должен дойти до модели: " + state.lift,
                state.lift < HandGate.LIFT_THRESHOLD);
    }

    @Test
    public void aDroppedHandIsReleasedAtOnce() {
        final HandGate gate = new HandGate();
        feed(gate, true, 0.85f, 0.4f);
        final HandGate.State state = feed(gate, false, 0.0f, 0.2f);
        assertFalse("опущенная рука должна отпускаться", state.visible);
        assertEquals("подъём сбрасывается", 0.0f, state.lift, 0.001f);
        assertEquals("пальцы больше не считаются", -1, state.fingers);
    }

    @Test
    public void flickeringHandsNeverReachTheModel() {
        final HandGate gate = new HandGate();
        // Кисть то находится, то нет - так выглядит ложное срабатывание на фоне.
        for (int i = 0; i < 40; i++) {
            gate.update(i % 2 == 0, 0.9f, 4, STEP);
        }
        final HandGate.State state = gate.state();
        assertTrue("дрожащая кисть не должна поднимать руку: " + state.lift,
                state.lift < HandGate.LIFT_THRESHOLD);
    }

    @Test
    public void aRaisedHandThatFallsIsReleasedQuickly() {
        final HandGate gate = new HandGate();
        feed(gate, true, 0.9f, 0.5f);
        assertTrue(gate.state().lift > 0.5f);
        final HandGate.State lowered = feed(gate, true, 0.1f, 0.1f);
        assertTrue("рука опустилась, модель должна опустить её: " + lowered.lift,
                lowered.lift < 0.3f);
    }
}
