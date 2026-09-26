package com.echidna.studio.track;

/**
 * Geometry of a hand: how many fingers are up, where the palm is and whether the fingers touch a
 * point of the face.
 *
 * <p>The math lives here, away from Android and away from MediaPipe, because it is the part that
 * decides what the avatar shows: a fist reads as zero, an open palm as five, and holding the index
 * tip near the chin reads as "трогаю подбородок". Keeping it pure means every one of those cases is
 * covered by a unit test on a plain JVM, with the same 21 points the hand model returns.</p>
 *
 * <p>The points are the standard MediaPipe hand model, in image coordinates: X grows to the right,
 * Y grows downwards, both 0..1. The helper methods take the points as two flat arrays so a test can
 * spell a hand out in three lines.</p>
 */
public final class HandPose {
    // Индексы точек кисти по модели MediaPipe: запястье, большой палец, указательный, средний,
    // безымянный, мизинец. Названия - чтобы код читался как анатомия, а не как набор чисел.
    public static final int WRIST = 0;
    public static final int THUMB_CMC = 1;
    public static final int THUMB_MCP = 2;
    public static final int THUMB_IP = 3;
    public static final int THUMB_TIP = 4;
    public static final int INDEX_MCP = 5;
    public static final int INDEX_PIP = 6;
    public static final int INDEX_TIP = 8;
    public static final int MIDDLE_MCP = 9;
    public static final int MIDDLE_PIP = 10;
    public static final int MIDDLE_TIP = 12;
    public static final int RING_MCP = 13;
    public static final int RING_PIP = 14;
    public static final int RING_TIP = 16;
    public static final int PINKY_MCP = 17;
    public static final int PINKY_PIP = 18;
    public static final int PINKY_TIP = 20;

    /** Сколько точек в руке. */
    public static final int POINTS = 21;

    private HandPose() {
    }

    /**
     * How many fingers are pointing up: 0 (fist) to 5 (open palm).
     *
     * <p>A finger counts as extended when its tip is clearly farther from the wrist than its middle
     * joint is. The ratio test survives a rotation of the hand - a hand held sideways or upside down
     * still reads correctly - which a plain "tip above the joint" comparison would not.</p>
     */
    public static int fingerCount(float[] xs, float[] ys) {
        if (xs == null || ys == null || xs.length < POINTS || ys.length < POINTS) {
            return 0;
        }
        int count = 0;
        if (extended(xs, ys, INDEX_PIP, INDEX_TIP)) {
            count++;
        }
        if (extended(xs, ys, MIDDLE_PIP, MIDDLE_TIP)) {
            count++;
        }
        if (extended(xs, ys, RING_PIP, RING_TIP)) {
            count++;
        }
        if (extended(xs, ys, PINKY_PIP, PINKY_TIP)) {
            count++;
        }
        if (thumbExtended(xs, ys)) {
            count++;
        }
        return count;
    }

    /** True when the finger with that middle joint and that tip is stretched out. */
    public static boolean extended(float[] xs, float[] ys, int joint, int tip) {
        final float wristToJoint = distance(xs, ys, WRIST, joint);
        final float wristToTip = distance(xs, ys, WRIST, tip);
        return wristToTip > wristToJoint * EXTENDED_RATIO;
    }

    /**
     * The thumb is judged differently: it leaves the palm sideways, so the test is whether its tip
     * is far from the base of the index finger.
     */
    public static boolean thumbExtended(float[] xs, float[] ys) {
        final float tipToPinky = distance(xs, ys, THUMB_TIP, PINKY_MCP);
        final float ipToPinky = distance(xs, ys, THUMB_IP, PINKY_MCP);
        return tipToPinky > ipToPinky * THUMB_RATIO;
    }

    /** Distance between the thumb tip and the index tip, 0..1; small values mean a pinch. */
    public static float pinchDistance(float[] xs, float[] ys) {
        return distance(xs, ys, THUMB_TIP, INDEX_TIP);
    }

    /** Rough size of the hand in the frame: the distance from the wrist to the middle knuckle. */
    public static float span(float[] xs, float[] ys) {
        return distance(xs, ys, WRIST, MIDDLE_MCP);
    }

    /** Centre of the palm: between the wrist and the middle knuckle. */
    public static float palmX(float[] xs, float[] ys) {
        return (xs[WRIST] + xs[MIDDLE_MCP]) * 0.5f;
    }

    public static float palmY(float[] ys) {
        return (ys[WRIST] + ys[MIDDLE_MCP]) * 0.5f;
    }

    /**
     * How much the hand reaches for a point of the face, 0 (far) to 1 (touching).
     *
     * <p>Used for the chin: the index tip, the middle tip and the palm all count, so the gesture is
     * recognised whether the user pokes the chin with one finger or rests the whole hand under it.
     * The distance is measured in face heights, which keeps it working near and far from the
     * camera.</p>
     *
     * @param xs         hand points, X
     * @param ys         hand points, Y
     * @param targetX    X of the chin, same coordinate system as the hand
     * @param targetY    Y of the chin
     * @param faceHeight height of the face in the same units
     */
    public static float reach(float[] xs, float[] ys, float targetX, float targetY, float faceHeight) {
        if (xs == null || ys == null || xs.length < POINTS || ys.length < POINTS) {
            return 0.0f;
        }
        return reachOf(xs[INDEX_TIP], ys[INDEX_TIP], xs[MIDDLE_TIP], ys[MIDDLE_TIP],
                xs[MIDDLE_MCP], ys[MIDDLE_MCP], targetX, targetY, faceHeight);
    }

    /**
     * Same test from three points instead of the whole hand: the index tip, the middle tip and the
     * palm. The hub keeps only those three per frame, so this is the form it calls.
     */
    public static float reachOf(float indexTipX, float indexTipY, float middleTipX, float middleTipY,
                                float palmX, float palmY, float targetX, float targetY,
                                float faceHeight) {
        if (faceHeight < 0.0001f) {
            return 0.0f;
        }
        final float indexTip = distance(indexTipX, indexTipY, targetX, targetY);
        final float middleTip = distance(middleTipX, middleTipY, targetX, targetY);
        final float palm = distance(palmX, palmY, targetX, targetY);
        final float closest = Math.min(indexTip, Math.min(middleTip, palm));
        final float relative = closest / faceHeight;
        if (relative <= TOUCH_NEAR) {
            return 1.0f;
        }
        if (relative >= TOUCH_FAR) {
            return 0.0f;
        }
        return (TOUCH_FAR - relative) / (TOUCH_FAR - TOUCH_NEAR);
    }

    /** True when the hand is close enough to the chin to count as touching it. */
    public static boolean touches(float[] xs, float[] ys, float targetX, float targetY,
                                  float faceHeight) {
        return reach(xs, ys, targetX, targetY, faceHeight) > 0.5f;
    }

    public static float distance(float[] xs, float[] ys, int a, int b) {
        return distance(xs[a], ys[a], xs[b], ys[b]);
    }

    public static float distance(float ax, float ay, float bx, float by) {
        final float dx = ax - bx;
        final float dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Во сколько раз кончик пальца должен быть дальше от запястья, чем средний сустав. */
    private static final float EXTENDED_RATIO = 1.12f;
    /** То же для большого пальца, но от основания указательного. */
    private static final float THUMB_RATIO = 1.10f;
    /** Ближе этого расстояния (в высотах лица) рука считается касающейся. */
    private static final float TOUCH_NEAR = 0.35f;
    /** Дальше этого - рука просто рядом. */
    private static final float TOUCH_FAR = 0.85f;
}
