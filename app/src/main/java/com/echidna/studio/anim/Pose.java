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

    /**
     * How much the arms are raised, 0..1. The body tracker measures how high the hands are above
     * the shoulders, and the 3D character lifts its arms with them; rigs without arm parameters
     * simply receive the cheerful face that the hands also drive.
     */
    public float armY;

    /** Strength of the whole pose, 0..1. */
    /**
     * Shift of the whole model inside the frame, in projection units: 1.0 is the half width of the
     * screen, so ±0.1 moves the model by five percent of the screen. Driven by the position of the
     * face in the camera frame, which is what makes the avatar follow the user sideways.
     */
    public float offsetX;
    public float offsetY;

    /** Size multiplier of the model; 1 is the calibrated size, larger means the user leaned in. */
    public float zoom = 1.0f;

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
        armY = other.armY;
        offsetX = other.offsetX;
        offsetY = other.offsetY;
        zoom = other.zoom;
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
        armY = 0;
        offsetX = 0;
        offsetY = 0;
        zoom = 1.0f;
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
        out.armY = mix(a.armY, b.armY, t);
        out.offsetX = mix(a.offsetX, b.offsetX, t);
        out.offsetY = mix(a.offsetY, b.offsetY, t);
        out.zoom = mix(a.zoom, b.zoom, t);
        out.weight = mix(a.weight, b.weight, t);
        out.eyeWeight = mix(a.eyeWeight, b.eyeWeight, t);
    }

    public static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** True when this pose would move the model on the screen. */
    public boolean shiftsModel() {
        return Math.abs(offsetX) > 0.0001f || Math.abs(offsetY) > 0.0001f
                || Math.abs(zoom - 1.0f) > 0.0001f;
    }

    public static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    /**
     * Replaces a value the model cannot use with a safe one.
     *
     * <p>Trackers do produce {@code NaN} and infinities - a degenerate transformation matrix, a
     * division by a zero sized face box - and a single such number would spread through the pose and
     * reach the native renderer, which either freezes the character or disappears it entirely. Every
     * value that crosses the border into the model goes through here.</p>
     */
    public static float safe(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 0.0f;
        }
        return value;
    }

    /** Like {@link #safe(float)} but for the values that are 1.0 by default. */
    public static float safeUnit(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 1.0f;
        }
        return value;
    }

    /** Repairs every field of the pose, in place. The last line of defence before the model. */
    public void sanitize() {
        angleX = clamp(safe(angleX), ParamLimits.ANGLE_X_MIN, ParamLimits.ANGLE_X_MAX);
        angleY = clamp(safe(angleY), ParamLimits.ANGLE_Y_MIN, ParamLimits.ANGLE_Y_MAX);
        angleZ = clamp(safe(angleZ), ParamLimits.ANGLE_Z_MIN, ParamLimits.ANGLE_Z_MAX);
        bodyX = clamp(safe(bodyX), ParamLimits.BODY_X_MIN, ParamLimits.BODY_X_MAX);
        bodyY = clamp(safe(bodyY), ParamLimits.BODY_Y_MIN, ParamLimits.BODY_Y_MAX);
        bodyZ = clamp(safe(bodyZ), ParamLimits.BODY_Z_MIN, ParamLimits.BODY_Z_MAX);
        eyeLOpen = clamp(safe(eyeLOpen), 0.0f, 1.0f);
        eyeROpen = clamp(safe(eyeROpen), 0.0f, 1.0f);
        eyeLSmile = clamp(safe(eyeLSmile), 0.0f, 1.0f);
        eyeRSmile = clamp(safe(eyeRSmile), 0.0f, 1.0f);
        eyeBallX = clamp(safe(eyeBallX), ParamLimits.EYE_BALL_X_MIN, ParamLimits.EYE_BALL_X_MAX);
        eyeBallY = clamp(safe(eyeBallY), ParamLimits.EYE_BALL_Y_MIN, ParamLimits.EYE_BALL_Y_MAX);
        mouthOpenY = clamp(safe(mouthOpenY), 0.0f, 1.0f);
        mouthForm = clamp(safe(mouthForm), -1.0f, 1.0f);
        browLY = clamp(safe(browLY), -1.0f, 1.0f);
        browRY = clamp(safe(browRY), -1.0f, 1.0f);
        cheek = clamp(safe(cheek), 0.0f, 1.0f);
        offsetX = clamp(safe(offsetX), ParamLimits.OFFSET_MIN, ParamLimits.OFFSET_MAX);
        offsetY = clamp(safe(offsetY), ParamLimits.OFFSET_MIN, ParamLimits.OFFSET_MAX);
        zoom = clamp(safeUnit(zoom), ParamLimits.ZOOM_MIN, ParamLimits.ZOOM_MAX);
        weight = clamp(safe(weight), 0.0f, 1.0f);
        eyeWeight = clamp(safe(eyeWeight), 0.0f, 1.0f);
    }

    @Override
    public String toString() {
        return "Pose{angle=(" + angleX + "," + angleY + "," + angleZ + ")"
                + " body=(" + bodyX + "," + bodyY + "," + bodyZ + ")"
                + " eyes=(" + eyeLOpen + "," + eyeROpen + ")"
                + " mouth=(" + mouthOpenY + "," + mouthForm + ")"
                + " offset=(" + offsetX + "," + offsetY + ") zoom=" + zoom
                + " weight=" + weight + "}";
    }
}
