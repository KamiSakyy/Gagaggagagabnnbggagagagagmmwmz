package com.echidna.studio.anim;

/**
 * A complete set of animation targets for the Echidna model.
 *
 * <p>Values are in the units of the Live2D parameters themselves (degrees for the head and body
 * angles, 0..1 for eye openness and the mouth opening, -1..1 for the mouth form). A pose is always
 * complete: the neutral pose means "do not deform anything".</p>
 *
 * <p>{@link #weight} says how strongly the pose is applied on top of what the motion files and the
 * effects (breathing, blinking) produced for the current frame. {@link #eyeWeight} exists because
 * the eyes need a different treatment: in camera mode the tracked eye openness must win over the
 * automatic blink, while in the demo fallback the automatic blink has to stay in charge.</p>
 */
public final class Pose {
    public float angleX;
    public float angleY;
    public float angleZ;

    public float bodyX;
    public float bodyY;
    public float bodyZ;

    public float eyeLOpen = 1.0f;
    public float eyeROpen = 1.0f;
    public float eyeLSmile;
    public float eyeRSmile;
    public float eyeBallX;
    public float eyeBallY;

    public float mouthOpenY;
    public float mouthForm;

    public float browLY;
    public float browRY;

    public float cheek;

    /** Strength of the whole pose, 0..1. */
    public float weight = 1.0f;

    /** Strength of the eye part of the pose, 0..1. */
    public float eyeWeight = 1.0f;

    public Pose() {
    }

    public Pose(Pose other) {
        set(other);
    }

    public void set(Pose other) {
        angleX = other.angleX;
        angleY = other.angleY;
        angleZ = other.angleZ;
        bodyX = other.bodyX;
        bodyY = other.bodyY;
        bodyZ = other.bodyZ;
        eyeLOpen = other.eyeLOpen;
        eyeROpen = other.eyeROpen;
        eyeLSmile = other.eyeLSmile;
        eyeRSmile = other.eyeRSmile;
        eyeBallX = other.eyeBallX;
        eyeBallY = other.eyeBallY;
        mouthOpenY = other.mouthOpenY;
        mouthForm = other.mouthForm;
        browLY = other.browLY;
        browRY = other.browRY;
        cheek = other.cheek;
        weight = other.weight;
        eyeWeight = other.eyeWeight;
    }

    /** Turns the pose into the neutral one (weights included). */
    public void reset() {
        angleX = 0;
        angleY = 0;
        angleZ = 0;
        bodyX = 0;
        bodyY = 0;
        bodyZ = 0;
        eyeLOpen = 1.0f;
        eyeROpen = 1.0f;
        eyeLSmile = 0;
        eyeRSmile = 0;
        eyeBallX = 0;
        eyeBallY = 0;
        mouthOpenY = 0;
        mouthForm = 0;
        browLY = 0;
        browRY = 0;
        cheek = 0;
        weight = 1.0f;
        eyeWeight = 1.0f;
    }

    /**
     * Writes the linear interpolation of two poses into {@code out}. The weights are interpolated
     * as well, so a pose can be brought in and out smoothly.
     */
    public static void lerp(Pose a, Pose b, float t, Pose out) {
        if (t <= 0.0f) {
            out.set(a);
            return;
        }
        if (t >= 1.0f) {
            out.set(b);
            return;
        }
        out.angleX = mix(a.angleX, b.angleX, t);
        out.angleY = mix(a.angleY, b.angleY, t);
        out.angleZ = mix(a.angleZ, b.angleZ, t);
        out.bodyX = mix(a.bodyX, b.bodyX, t);
        out.bodyY = mix(a.bodyY, b.bodyY, t);
        out.bodyZ = mix(a.bodyZ, b.bodyZ, t);
        out.eyeLOpen = mix(a.eyeLOpen, b.eyeLOpen, t);
        out.eyeROpen = mix(a.eyeROpen, b.eyeROpen, t);
        out.eyeLSmile = mix(a.eyeLSmile, b.eyeLSmile, t);
        out.eyeRSmile = mix(a.eyeRSmile, b.eyeRSmile, t);
        out.eyeBallX = mix(a.eyeBallX, b.eyeBallX, t);
        out.eyeBallY = mix(a.eyeBallY, b.eyeBallY, t);
        out.mouthOpenY = mix(a.mouthOpenY, b.mouthOpenY, t);
        out.mouthForm = mix(a.mouthForm, b.mouthForm, t);
        out.browLY = mix(a.browLY, b.browLY, t);
        out.browRY = mix(a.browRY, b.browRY, t);
        out.cheek = mix(a.cheek, b.cheek, t);
        out.weight = mix(a.weight, b.weight, t);
        out.eyeWeight = mix(a.eyeWeight, b.eyeWeight, t);
    }

    public static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    @Override
    public String toString() {
        return "Pose{angle=(" + angleX + "," + angleY + "," + angleZ + ")"
                + " body=(" + bodyX + "," + bodyY + "," + bodyZ + ")"
                + " eyes=(" + eyeLOpen + "," + eyeROpen + ")"
                + " mouth=(" + mouthOpenY + "," + mouthForm + ")"
                + " weight=" + weight + "}";
    }
}
