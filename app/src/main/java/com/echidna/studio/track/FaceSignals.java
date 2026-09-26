package com.echidna.studio.track;

/**
 * One analysed camera frame, already normalised into a coordinate system the mapper can use.
 *
 * <p>The fields come in two flavours. The head pose ({@link #yaw}, {@link #pitch}, {@link #roll}) is
 * available from every tracker. The eleven blendshape channels are only filled in by the MediaPipe
 * tracker; when they are absent {@link #blendshapes} stays {@code false} and the mapper falls back
 * to the coarser probabilities of ML Kit. Nothing here depends on Android, so the whole mapping
 * layer stays testable on the JVM.</p>
 */
public final class FaceSignals {
    /** Whether a face was seen in the analysed frame. */
    public boolean found;

    /** Head rotation in degrees: yaw (turn), pitch (nod), roll (tilt). */
    public float yaw;
    public float pitch;
    public float roll;

    /** Eye openness 0..1 (as reported for the subject's own left and right eye). */
    public float eyeLeft = 1.0f;
    public float eyeRight = 1.0f;

    /** Smile probability 0..1. */
    public float smile;

    /** Mouth opening 0..1. */
    public float mouthOpen;

    /** Face centre inside the frame: -1 is the left edge, +1 the right edge. */
    public float centerX;
    public float centerY;

    /** Face height relative to the frame height. */
    public float scale;

    /** True when {@link #blendEyeBlinkLeft} and friends hold real data. */
    public boolean blendshapes;

    public float blendEyeBlinkLeft;
    public float blendEyeBlinkRight;
    public float blendJawOpen;
    public float blendMouthSmileLeft;
    public float blendMouthSmileRight;
    public float blendMouthPucker;
    public float blendBrowInnerUp;
    public float blendBrowDownLeft;
    public float blendBrowDownRight;
    public float blendCheekPuff;
    public float blendEyeSquintLeft;
    public float blendEyeSquintRight;
    public float blendMouthFrownLeft;
    public float blendMouthFrownRight;
    public float blendTongueOut;

    /**
     * Body data from the pose tracker (shoulders, hips, hands).
     *
     * <p>The face model alone cannot tell a tilt of the body from a tilt of the head, and it loses
     * the user as soon as they turn away or step back. The pose model sees the whole person, which is
     * what lets the character turn with the body and keep moving when the face is out of view.</p>
     */
    public boolean body;
    /** Turn of the shoulders in degrees, positive when the right shoulder comes closer to the camera. */
    public float bodyYaw;
    /** Tilt of the shoulder line in degrees. */
    public float bodyRoll;
    /** Vertical movement of the body: -1 crouching, +1 standing tall. */
    public float bodyLift;
    /** Sideways lean of the body, -1 left, +1 right. */
    public float bodyShift;
    /** Highest hand above the shoulders, 0 (at the shoulder line) to 1 (raised high). */
    public float handUp;
    /** The pose source found a person (the avatar keeps working from it alone). */
    public boolean poseOnly;

    /** Timestamp of the analysed frame, milliseconds. */
    public long timeMs;

    public void set(FaceSignals other) {
        found = other.found;
        body = other.body;
        bodyYaw = other.bodyYaw;
        bodyRoll = other.bodyRoll;
        bodyLift = other.bodyLift;
        bodyShift = other.bodyShift;
        handUp = other.handUp;
        poseOnly = other.poseOnly;
        yaw = other.yaw;
        pitch = other.pitch;
        roll = other.roll;
        eyeLeft = other.eyeLeft;
        eyeRight = other.eyeRight;
        smile = other.smile;
        mouthOpen = other.mouthOpen;
        centerX = other.centerX;
        centerY = other.centerY;
        scale = other.scale;
        blendshapes = other.blendshapes;
        blendEyeBlinkLeft = other.blendEyeBlinkLeft;
        blendEyeBlinkRight = other.blendEyeBlinkRight;
        blendJawOpen = other.blendJawOpen;
        blendMouthSmileLeft = other.blendMouthSmileLeft;
        blendMouthSmileRight = other.blendMouthSmileRight;
        blendMouthPucker = other.blendMouthPucker;
        blendBrowInnerUp = other.blendBrowInnerUp;
        blendBrowDownLeft = other.blendBrowDownLeft;
        blendBrowDownRight = other.blendBrowDownRight;
        blendCheekPuff = other.blendCheekPuff;
        blendEyeSquintLeft = other.blendEyeSquintLeft;
        blendEyeSquintRight = other.blendEyeSquintRight;
        blendMouthFrownLeft = other.blendMouthFrownLeft;
        blendMouthFrownRight = other.blendMouthFrownRight;
        blendTongueOut = other.blendTongueOut;
        timeMs = other.timeMs;
    }

    @Override
    public String toString() {
        return "FaceSignals{found=" + found + " yaw=" + yaw + " pitch=" + pitch + " roll=" + roll
                + " eyes=(" + eyeLeft + "," + eyeRight + ") smile=" + smile + " mouth=" + mouthOpen
                + " blendshapes=" + blendshapes + "}";
    }
}
