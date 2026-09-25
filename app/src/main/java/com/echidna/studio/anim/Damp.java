package com.echidna.studio.anim;

/**
 * Frame rate independent exponential smoothing (a "critically damped" follower).
 *
 * <p>Face tracking data arrives roughly fifteen times per second and is noisy; the model runs at
 * sixty. Smoothing every channel with its own time constant turns the jumps into movement that
 * looks like a living character instead of a puppet on strings.</p>
 */
public final class Damp {
    private final float tau;
    private float value;
    private boolean primed;

    public Damp(float tauSeconds) {
        this.tau = Math.max(0.001f, tauSeconds);
    }

    public void reset() {
        primed = false;
        value = 0.0f;
    }

    public void reset(float v) {
        primed = true;
        value = Pose.safe(v);
    }

    public boolean isPrimed() {
        return primed;
    }

    public float value() {
        return value;
    }

    /**
     * Follows {@code target}; the first call jumps straight to it.
     *
     * <p>A target the tracker cannot represent ({@code NaN}, an infinity) is ignored: the follower
     * keeps its last good value instead of poisoning the whole chain.</p>
     */
    public float update(float target, float dt) {
        if (Float.isNaN(target) || Float.isInfinite(target)) {
            return value;
        }
        if (!primed) {
            value = target;
            primed = true;
            return value;
        }
        if (dt <= 0.0f) {
            return value;
        }
        final float k = 1.0f - (float) Math.exp(-dt / tau);
        value += (target - value) * k;
        return value;
    }

    /** Moves towards the target but never faster than {@code maxDelta} per second. */
    public float updateLimited(float target, float dt, float maxDeltaPerSecond) {
        if (!primed) {
            value = target;
            primed = true;
            return value;
        }
        final float step = update(target, dt);
        final float maxStep = maxDeltaPerSecond * Math.max(dt, 0.0f);
        final float diff = step - value;
        if (diff > maxStep) {
            value += maxStep;
        } else if (diff < -maxStep) {
            value -= maxStep;
        }
        return value;
    }
}
