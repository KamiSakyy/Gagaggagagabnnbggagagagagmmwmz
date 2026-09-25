package com.echidna.studio.anim;

/**
 * One step of a show: between {@link #start} and {@link #end} seconds the pose travels from
 * {@link #from} to {@link #to}, and at {@link #start} a motion file may be started.
 */
public final class Segment {
    public final float start;
    public final float end;
    public final Pose from;
    public final Pose to;
    public final Ease ease;
    public final String motion;
    public final float motionFade;
    public final int priority;

    public Segment(float start, float end, Pose from, Pose to, Ease ease,
                   String motion, float motionFade, int priority) {
        this.start = start;
        this.end = end;
        this.from = from;
        this.to = to;
        this.ease = ease;
        this.motion = motion;
        this.motionFade = motionFade;
        this.priority = priority;
    }

    public float duration() {
        return end - start;
    }

    /** Normalised position of {@code time} inside the segment (0..1). */
    public float phase(float time) {
        final float d = end - start;
        if (d <= 0.0001f) {
            return 1.0f;
        }
        float p = (time - start) / d;
        if (p < 0.0f) {
            p = 0.0f;
        } else if (p > 1.0f) {
            p = 1.0f;
        }
        return p;
    }
}
