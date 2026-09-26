package com.echidna.studio.anim;

/**
 * The reaction of the character to the number of fingers the user shows.
 *
 * <p>Live2D rigs of this kind have no separate fingers: the art of the hand is a single drawing, so
 * "show four fingers" cannot be drawn literally. What the character can do is nod exactly as many
 * times as the user shows fingers, which reads on camera the same way a person counting does, and
 * works on every rig - even on the ones without arms.</p>
 *
 * <p>The class is pure: it takes the finger count and the frame time and returns the angle the head
 * should dip by. That makes the whole behaviour testable on a plain JVM, without a camera and
 * without a model.</p>
 */
public final class GestureReaction {
    /** Сколько длится один кивок, секунды. */
    private static final float NOD_TIME = 0.42f;
    /** На сколько градусов наклоняется голова в кивке. */
    private static final float NOD_DEGREES = 7.0f;
    /** Сколько счёт должен продержаться, чтобы считаться жестом, а не дребезгом распознавания. */
    private static final float HOLD_TIME = 0.22f;
    /** Пауза перед первым кивком, чтобы жест и ответ не сливались. */
    private static final float NOD_GAP = 0.10f;

    private int lastFingers = -1;
    private int candidate = -1;
    private float candidateFor;
    /** Что показываем сейчас: сколько кивков обещано. */
    private int shownCount = -1;
    private int nodsLeft;
    private float nodPhase = NOD_GAP;
    private float nodDegrees;

    /**
     * Feeds one frame.
     *
     * @param fingers сколько пальцев показывает человек, -1 если руки не видно
     * @param dt      секунды с прошлого кадра
     */
    public void update(int fingers, float dt) {
        if (dt < 0.0f) {
            dt = 0.0f;
        }
        if (fingers < 0) {
            // Рука ушла из кадра: счёт сбрасывается, но начатые кивки доигрываются.
            lastFingers = -1;
            candidate = -1;
            candidateFor = 0.0f;
            shownCount = -1;
            advance(dt);
            return;
        }
        if (fingers != lastFingers) {
            lastFingers = fingers;
            candidate = fingers;
            candidateFor = 0.0f;
            advance(dt);
            return;
        }
        candidateFor += dt;
        if (candidate >= 0 && candidateFor >= HOLD_TIME && candidate != shownCount) {
            shownCount = candidate;
            nodsLeft = Math.max(0, Math.min(5, candidate));
            nodPhase = NOD_GAP;
        }
        advance(dt);
    }

    private void advance(float dt) {
        if (nodsLeft <= 0) {
            nodDegrees = 0.0f;
            return;
        }
        nodPhase += dt;
        if (nodPhase >= NOD_TIME) {
            nodPhase -= NOD_TIME;
            nodsLeft--;
            if (nodsLeft <= 0) {
                nodDegrees = 0.0f;
                return;
            }
        }
        final float t = Pose.clamp(nodPhase / NOD_TIME, 0.0f, 1.0f);
        // Полуволна синуса: голова плавно уходит вниз и возвращается, без рывка.
        nodDegrees = (float) Math.sin(t * Math.PI) * NOD_DEGREES;
    }

    /** Насколько градусов должна наклониться голова вниз прямо сейчас, 0..7. */
    public float nodDegrees() {
        return nodDegrees;
    }

    /** Сколько пальцев показываем прямо сейчас: -1, если жест не распознан. */
    public int shownCount() {
        return shownCount;
    }

    /** Сколько кивков ещё осталось: ноль значит "ответил". */
    public int nodsLeft() {
        return nodsLeft;
    }

    public void reset() {
        lastFingers = -1;
        candidate = -1;
        candidateFor = 0.0f;
        shownCount = -1;
        nodsLeft = 0;
        nodPhase = NOD_GAP;
        nodDegrees = 0.0f;
    }
}
