package com.echidna.studio.anim;

/**
 * The parameter ranges the Echidna model really accepts, extracted from the 66 motion files that
 * ship with it. Keeping the procedural layer inside these ranges prevents the SDK from clamping
 * values mid animation (which shows up as a sudden stop).
 */
public final class ParamLimits {
    public static final float ANGLE_X_MIN = -30.0f;
    public static final float ANGLE_X_MAX = 30.0f;
    public static final float ANGLE_Y_MIN = -30.0f;
    public static final float ANGLE_Y_MAX = 30.0f;
    public static final float ANGLE_Z_MIN = -16.0f;
    public static final float ANGLE_Z_MAX = 12.0f;

    public static final float BODY_X_MIN = -10.0f;
    public static final float BODY_X_MAX = 10.0f;
    public static final float BODY_Y_MIN = -6.0f;
    public static final float BODY_Y_MAX = 10.0f;
    public static final float BODY_Z_MIN = -5.0f;
    public static final float BODY_Z_MAX = 3.0f;

    public static final float EYE_BALL_X_MIN = -0.43f;
    public static final float EYE_BALL_X_MAX = 0.6f;
    public static final float EYE_BALL_Y_MIN = -0.8f;
    public static final float EYE_BALL_Y_MAX = 0.4f;

    public static final float MOUTH_OPEN_MIN = 0.0f;
    public static final float MOUTH_OPEN_MAX = 1.0f;
    public static final float MOUTH_FORM_MIN = -1.0f;
    public static final float MOUTH_FORM_MAX = 1.0f;

    public static final float EYE_OPEN_MIN = 0.0f;
    public static final float EYE_OPEN_MAX = 1.0f;

    /** How far the model may travel inside the frame; a tenth of the half width is plenty. */
    public static final float OFFSET_MIN = -0.12f;
    public static final float OFFSET_MAX = 0.12f;

    /** Size multiplier limits: leaning in enlarges the model, leaning back shrinks it. */
    public static final float ZOOM_MIN = 0.9f;
    public static final float ZOOM_MAX = 1.14f;

    private ParamLimits() {
    }

    public static float offset(float v) {
        return Pose.clamp(Pose.safe(v), OFFSET_MIN, OFFSET_MAX);
    }

    public static float zoom(float v) {
        return Pose.clamp(Pose.safe(v), ZOOM_MIN, ZOOM_MAX);
    }

    public static float angleX(float v) {
        return Pose.clamp(Pose.safe(v), ANGLE_X_MIN, ANGLE_X_MAX);
    }

    public static float angleY(float v) {
        return Pose.clamp(Pose.safe(v), ANGLE_Y_MIN, ANGLE_Y_MAX);
    }

    public static float angleZ(float v) {
        return Pose.clamp(Pose.safe(v), ANGLE_Z_MIN, ANGLE_Z_MAX);
    }

    public static float bodyX(float v) {
        return Pose.clamp(Pose.safe(v), BODY_X_MIN, BODY_X_MAX);
    }

    public static float bodyY(float v) {
        return Pose.clamp(Pose.safe(v), BODY_Y_MIN, BODY_Y_MAX);
    }

    public static float bodyZ(float v) {
        return Pose.clamp(Pose.safe(v), BODY_Z_MIN, BODY_Z_MAX);
    }

    public static float eyeBallX(float v) {
        return Pose.clamp(Pose.safe(v), EYE_BALL_X_MIN, EYE_BALL_X_MAX);
    }

    public static float eyeBallY(float v) {
        return Pose.clamp(Pose.safe(v), EYE_BALL_Y_MIN, EYE_BALL_Y_MAX);
    }

    public static float eyeOpen(float v) {
        return Pose.clamp(Pose.safe(v), EYE_OPEN_MIN, EYE_OPEN_MAX);
    }

    public static float mouthOpen(float v) {
        return Pose.clamp(Pose.safe(v), MOUTH_OPEN_MIN, MOUTH_OPEN_MAX);
    }

    public static float mouthForm(float v) {
        return Pose.clamp(Pose.safe(v), MOUTH_FORM_MIN, MOUTH_FORM_MAX);
    }

    public static float unit(float v) {
        return Pose.clamp(Pose.safe(v), 0.0f, 1.0f);
    }
}
