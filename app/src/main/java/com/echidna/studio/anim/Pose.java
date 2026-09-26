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
    /**
     * Эмоции лица.
     *
     * <p>Кроме высоты бровей у моделей есть их наклон и форма, прищур, зрачок «в кучку», бледность,
     * румянец, злые глаза и слёзы. Раньше из этого набора использовалась половина, и лицо модели
     * двигалось крупными мазками. Теперь каждая эмоция раскладывается по своим каналам: радость
     * поднимает щёки и щурит глаза, злость сводит брови и поджимает губы, удивление вскидывает
     * брови и раскрывает глаза, грусть тянет уголки рта вниз и добавляет блеск слёз.</p>
     */
    public float browAngle;
    public float browForm;
    public float browX;
    public float eyeWideL;
    public float eyeWideR;
    public float glareL;
    public float glareR;
    public float tears;
    public float pale;
    public float angryFace;
    public float mouthTension;
    public float eyeYorime;
    /** Номер распознанной эмоции и её сила: по ним работает интерфейс. */
    public int emotion;
    public float emotionWeight;

    public float cheek;

    /**
     * How much the arms are raised, 0..1. The body tracker measures how high the hands are above
     * the shoulders, and the 3D character lifts its arms with them; rigs without arm parameters
     * simply receive the cheerful face that the hands also drive.
     */
    public float armY;

    /**
     * How open the hand is: 0 a fist, 1 an open palm. Models whose rig has hand parameters pose the
     * hand with it; the others simply receive the reaction on the face and the head.
     */
    public float handOpen;

    /** How much the tracked hand reaches the chin: 0 far, 1 touching. */
    public float chinTouch;

    /** True when the arm channels of the rig run the other way round (set by the user). */
    public boolean armInverted;

    /** Fingers shown by the leading hand, -1 when no hand is seen. Feeds the reaction on the head. */
    public int fingers = -1;

    /** Without hands the arm channels stay still, however strong the rest of the pose is. */
    public boolean handsSeen;

    /**
     * Каждая рука отдельно: подъём, открытая ладонь и касание подбородка.
     *
     * <p>Сторона указана так, как её видят зрители модели: armLeft - левая рука персонажа, которая
     * повторяет правую руку человека, когда картинка зеркальная. Если рука человека не видна, её
     * сторона остаётся неизменной (-1), и модель держит ту позу, которую нарисовал художник.</p>
     */
    public float armLeft = -1.0f;
    public float armRight = -1.0f;
    public float handOpenLeft = -1.0f;
    public float handOpenRight = -1.0f;
    public float chinTouchLeft;
    public float chinTouchRight;
    public boolean chinLeft;
    public boolean chinRight;
    /** Видна ли каждая рука: невидимая сторона не трогается вовсе. */
    public boolean handSeenLeft;
    public boolean handSeenRight;

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
        browAngle = other.browAngle;
        browForm = other.browForm;
        browX = other.browX;
        eyeWideL = other.eyeWideL;
        eyeWideR = other.eyeWideR;
        glareL = other.glareL;
        glareR = other.glareR;
        tears = other.tears;
        pale = other.pale;
        angryFace = other.angryFace;
        mouthTension = other.mouthTension;
        eyeYorime = other.eyeYorime;
        emotion = other.emotion;
        emotionWeight = other.emotionWeight;
        cheek = other.cheek;
        armY = other.armY;
        handOpen = other.handOpen;
        armLeft = other.armLeft;
        armRight = other.armRight;
        handOpenLeft = other.handOpenLeft;
        handOpenRight = other.handOpenRight;
        chinTouchLeft = other.chinTouchLeft;
        chinTouchRight = other.chinTouchRight;
        chinLeft = other.chinLeft;
        chinRight = other.chinRight;
        handSeenLeft = other.handSeenLeft;
        handSeenRight = other.handSeenRight;
        chinTouch = other.chinTouch;
        armInverted = other.armInverted;
        fingers = other.fingers;
        handsSeen = other.handsSeen;
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
        browAngle = 0;
        browForm = 0;
        browX = 0;
        eyeWideL = 0;
        eyeWideR = 0;
        glareL = 0;
        glareR = 0;
        tears = 0;
        pale = 0;
        angryFace = 0;
        mouthTension = 0;
        eyeYorime = 0;
        emotion = 0;
        emotionWeight = 0;
        cheek = 0;
        armY = 0;
        handOpen = 0.0f;
        armLeft = -1.0f;
        armRight = -1.0f;
        handOpenLeft = -1.0f;
        handOpenRight = -1.0f;
        chinTouchLeft = 0.0f;
        chinTouchRight = 0.0f;
        chinLeft = false;
        chinRight = false;
        handSeenLeft = false;
        handSeenRight = false;
        chinTouch = 0.0f;
        armInverted = false;
        fingers = -1;
        handsSeen = false;
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
        out.browAngle = mix(a.browAngle, b.browAngle, t);
        out.browForm = mix(a.browForm, b.browForm, t);
        out.browX = mix(a.browX, b.browX, t);
        out.eyeWideL = mix(a.eyeWideL, b.eyeWideL, t);
        out.eyeWideR = mix(a.eyeWideR, b.eyeWideR, t);
        out.glareL = mix(a.glareL, b.glareL, t);
        out.glareR = mix(a.glareR, b.glareR, t);
        out.tears = mix(a.tears, b.tears, t);
        out.pale = mix(a.pale, b.pale, t);
        out.angryFace = mix(a.angryFace, b.angryFace, t);
        out.mouthTension = mix(a.mouthTension, b.mouthTension, t);
        out.eyeYorime = mix(a.eyeYorime, b.eyeYorime, t);
        out.emotion = t < 1.0f ? a.emotion : b.emotion;
        out.emotionWeight = mix(a.emotionWeight, b.emotionWeight, t);
        out.cheek = mix(a.cheek, b.cheek, t);
        out.armY = mix(a.armY, b.armY, t);
        out.handOpen = mix(a.handOpen, b.handOpen, t);
        out.armLeft = mix(a.armLeft, b.armLeft, t);
        out.armRight = mix(a.armRight, b.armRight, t);
        out.handOpenLeft = mix(a.handOpenLeft, b.handOpenLeft, t);
        out.handOpenRight = mix(a.handOpenRight, b.handOpenRight, t);
        out.chinTouchLeft = mix(a.chinTouchLeft, b.chinTouchLeft, t);
        out.chinTouchRight = mix(a.chinTouchRight, b.chinTouchRight, t);
        out.chinLeft = t < 1.0f ? a.chinLeft : b.chinLeft;
        out.chinRight = t < 1.0f ? a.chinRight : b.chinRight;
        out.handSeenLeft = t < 1.0f ? a.handSeenLeft : b.handSeenLeft;
        out.handSeenRight = t < 1.0f ? a.handSeenRight : b.handSeenRight;
        out.chinTouch = mix(a.chinTouch, b.chinTouch, t);
        out.handsSeen = t < 1.0f ? a.handsSeen : b.handsSeen;
        out.armInverted = t < 1.0f ? a.armInverted : b.armInverted;
        out.fingers = t < 1.0f ? a.fingers : b.fingers;
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
        browAngle = clamp(safe(browAngle), -1.0f, 1.0f);
        browForm = clamp(safe(browForm), -1.0f, 1.0f);
        browX = clamp(safe(browX), -1.0f, 1.0f);
        eyeWideL = clamp(safe(eyeWideL), 0.0f, 1.0f);
        eyeWideR = clamp(safe(eyeWideR), 0.0f, 1.0f);
        glareL = clamp(safe(glareL), 0.0f, 1.0f);
        glareR = clamp(safe(glareR), 0.0f, 1.0f);
        tears = clamp(safe(tears), 0.0f, 1.0f);
        pale = clamp(safe(pale), 0.0f, 1.0f);
        angryFace = clamp(safe(angryFace), 0.0f, 1.0f);
        mouthTension = clamp(safe(mouthTension), -1.0f, 1.0f);
        eyeYorime = clamp(safe(eyeYorime), 0.0f, 1.0f);
        emotionWeight = clamp(safe(emotionWeight), 0.0f, 1.0f);
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
