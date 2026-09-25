package com.echidna.studio.track;

import com.echidna.studio.anim.Damp;
import com.echidna.studio.anim.ParamLimits;
import com.echidna.studio.anim.Pose;

/**
 * Turns face tracking data into the pose of the model - the heart of the camera mode.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>map the head rotation of the user to the head and the body of the model,</li>
 *   <li>map the eye openness to the eyelids (a blink of the user becomes a blink of the model),</li>
 *   <li>map the smile and the mouth opening,</li>
 *   <li>smooth and limit everything so that tracking noise never looks like a glitch,</li>
 *   <li>fall back to an authored "demo" pose when no face is visible, so the model keeps living
 *       instead of freezing in the last tracked position.</li>
 * </ul>
 *
 * <p>The class is plain Java: the unit tests drive it with synthetic signals.</p>
 */
public final class TrackingMapper {

    /** How far the head may turn for the model to follow one to one. */
    private static final float YAW_GAIN = 1.15f;
    private static final float PITCH_GAIN = 1.10f;
    private static final float ROLL_GAIN = 1.20f;

    /** Height of the face in the frame when the user sits normally; the zoom reference point. */
    private static final float NEUTRAL_FACE = 0.34f;
    private static final float SHIFT_GAIN = 0.16f;
    private static final float LIFT_GAIN = 0.10f;
    private static final float ZOOM_GAIN = 0.55f;

    private static final float FACE_LOST_GRACE = 0.45f;
    private static final float DEMO_IN_TIME = 0.9f;
    private static final float DEMO_OUT_TIME = 0.5f;

    private final Damp yaw = new Damp(0.10f);
    private final Damp pitch = new Damp(0.10f);
    private final Damp roll = new Damp(0.12f);
    private final Damp eyeL = new Damp(0.045f);
    private final Damp eyeR = new Damp(0.045f);
    private final Damp smile = new Damp(0.14f);
    private final Damp mouth = new Damp(0.06f);
    private final Damp centerX = new Damp(0.16f);
    private final Damp centerY = new Damp(0.16f);
    private final Damp faceSize = new Damp(0.30f);

    private final Pose tracked = new Pose();
    private final Pose demo = new Pose();

    private volatile boolean mirrored = true;
    private float lostFor = 10.0f;
    private float demoBlend = 1.0f;
    private float clock;
    private long lastSignalMs;

    public TrackingMapper() {
    }

    public TrackingMapper(boolean mirrored) {
        this.mirrored = mirrored;
    }

    /**
     * Mirrored tracking feels like a mirror: the user turns the head to their right and the model
     * turns to the same side of the screen. Exactly like VTube Studio's default.
     */
    public void setMirrored(boolean value) {
        mirrored = value;
    }

    public boolean isMirrored() {
        return mirrored;
    }

    public void reset() {
        yaw.reset();
        pitch.reset();
        roll.reset();
        eyeL.reset(1.0f);
        eyeR.reset(1.0f);
        smile.reset(0.0f);
        mouth.reset(0.0f);
        centerX.reset(0.0f);
        centerY.reset(0.0f);
        faceSize.reset(NEUTRAL_FACE);
        lostFor = 10.0f;
        demoBlend = 1.0f;
        clock = 0.0f;
    }

    /** Feeds one analysed frame. */
    public void onSignals(FaceSignals s, float dt) {
        if (!s.found) {
            lostFor += dt;
            return;
        }
        lastSignalMs = s.timeMs;

        final float yawTarget = (mirrored ? -1.0f : 1.0f) * s.yaw;
        final float rollTarget = (mirrored ? -1.0f : 1.0f) * s.roll;
        yaw.update(yawTarget, dt);
        pitch.update(s.pitch, dt);
        roll.update(rollTarget, dt);
        smile.update(s.smile, dt);
        mouth.update(s.mouthOpen, dt);
        centerX.update(s.centerX, dt);
        centerY.update(s.centerY, dt);
        // Only a sensible face size feeds the zoom; a half closed frame would jump otherwise.
        if (s.scale > 0.05f) {
            faceSize.update(s.scale, dt);
        }

        // A blink is fast: closing snaps, opening follows the tracked value smoothly.
        final float rawLeft = s.eyeLeft;
        final float rawRight = s.eyeRight;
        updateEyes(eyeL, rawLeft, dt);
        updateEyes(eyeR, rawRight, dt);

        lostFor = 0.0f;
    }

    private static void updateEyes(Damp follower, float raw, float dt) {
        if (!follower.isPrimed()) {
            follower.reset(raw);
            return;
        }
        if (raw < follower.value() - 0.25f) {
            // Closing: jump halfway immediately, then let the follower finish the movement.
            follower.reset(follower.value() + (raw - follower.value()) * 0.6f);
        }
        follower.update(raw, dt);
    }

    /** True when the tracker is confident that a face is in front of the camera. */
    public boolean faceLive() {
        return lostFor < FACE_LOST_GRACE;
    }

    /** 0 while a face is tracked, 1 while the authored demo pose owns the model. */
    public float demoBlend() {
        return demoBlend;
    }

    /** Human readable state for the UI. */
    public String status() {
        if (faceLive()) {
            return "лицо найдено";
        }
        if (demoBlend > 0.95f) {
            return "демо-режим: лицо не найдено";
        }
        return "ищу лицо…";
    }

    /**
     * Produces the pose of this frame.
     *
     * @param dt  seconds since the previous frame
     * @param out receives the pose
     */
    public Pose pose(float dt, Pose out) {
        clock += dt;
        if (lostFor < FACE_LOST_GRACE) {
            demoBlend = Math.max(0.0f, demoBlend - dt / DEMO_OUT_TIME);
        } else {
            demoBlend = Math.min(1.0f, demoBlend + dt / DEMO_IN_TIME);
        }

        buildTracked();
        buildDemo();

        Pose.lerp(tracked, demo, demoBlend, out);
        return out;
    }

    private void buildTracked() {
        final float yawValue = Pose.clamp(yaw.value(), -26.0f, 26.0f);
        final float pitchValue = Pose.clamp(pitch.value(), -26.0f, 26.0f);
        final float rollValue = Pose.clamp(roll.value(), -26.0f, 26.0f);

        tracked.angleY = ParamLimits.angleY(yawValue * YAW_GAIN);
        tracked.angleX = ParamLimits.angleX(-pitchValue * PITCH_GAIN);
        tracked.angleZ = ParamLimits.angleZ(rollValue * ROLL_GAIN);

        // The body follows the head with a delay and only a third of the amplitude.
        tracked.bodyX = ParamLimits.bodyX(yawValue * 0.28f);
        tracked.bodyZ = ParamLimits.bodyZ(rollValue * 0.22f);
        tracked.bodyY = ParamLimits.bodyY(-pitchValue * 0.12f);

        // The whole model follows the user sideways as well, exactly like a mirror image of the
        // head position: this is the part that makes the avatar feel like it stands next to you
        // rather than being pinned to the middle of the frame.
        final float shiftX = (mirrored ? -1.0f : 1.0f) * centerX.value();
        tracked.offsetX = ParamLimits.offset(shiftX * SHIFT_GAIN);
        tracked.offsetY = ParamLimits.offset(centerY.value() * -LIFT_GAIN);
        tracked.zoom = ParamLimits.zoom(1.0f + (faceSize.value() - NEUTRAL_FACE) * ZOOM_GAIN);

        // The gaze leads the head a little, which is what makes eye contact feel alive.
        tracked.eyeBallX = ParamLimits.eyeBallX(yawValue / 26.0f * 0.55f + centerX.value() * 0.35f);
        tracked.eyeBallY = ParamLimits.eyeBallY(pitchValue / 26.0f * 0.45f - centerY.value() * 0.25f);

        final float smileValue = ParamLimits.unit(smile.value());
        tracked.mouthOpenY = ParamLimits.mouthOpen(smoothStep(0.02f, 0.34f, mouth.value()));
        tracked.mouthForm = ParamLimits.mouthForm(-0.15f + smileValue * 1.0f);
        tracked.cheek = ParamLimits.unit((smileValue - 0.45f) * 2.0f);
        tracked.eyeLSmile = ParamLimits.unit(smileValue * 0.8f);
        tracked.eyeRSmile = ParamLimits.unit(smileValue * 0.8f);

        // No brow tracking in the SDK: looking up raises them slightly, which reads as surprise.
        final float brow = Pose.clamp(-pitchValue * 0.012f + (smileValue - 0.5f) * 0.2f, -0.3f, 0.6f);
        tracked.browLY = brow;
        tracked.browRY = brow;

        // The eyes of the model follow the lids of the user one to one.
        final float openLeft = mirrored ? eyeR.value() : eyeL.value();
        final float openRight = mirrored ? eyeL.value() : eyeR.value();
        tracked.eyeLOpen = ParamLimits.eyeOpen(smoothStep(0.18f, 0.62f, openLeft));
        tracked.eyeROpen = ParamLimits.eyeOpen(smoothStep(0.18f, 0.62f, openRight));
        tracked.eyeWeight = 1.0f;
        tracked.weight = 1.0f;
    }

    private void buildDemo() {
        final float t = clock;
        demo.angleX = ParamLimits.angleX(1.8f * (float) Math.sin(t * 0.31f) + 0.6f * (float) Math.sin(t * 1.7f));
        demo.angleY = ParamLimits.angleY(3.0f * (float) Math.sin(t * 0.23f + 1.0f));
        demo.angleZ = ParamLimits.angleZ(2.6f * (float) Math.sin(t * 0.19f + 2.0f));
        demo.bodyX = ParamLimits.bodyX(1.2f * (float) Math.sin(t * 0.17f));
        demo.bodyZ = ParamLimits.bodyZ(1.0f * (float) Math.sin(t * 0.21f + 0.8f));
        demo.bodyY = 0.0f;
        demo.eyeBallX = ParamLimits.eyeBallX(0.28f * (float) Math.sin(t * 0.27f + 0.5f));
        demo.eyeBallY = ParamLimits.eyeBallY(0.12f * (float) Math.sin(t * 0.21f + 1.4f));
        demo.mouthOpenY = ParamLimits.mouthOpen(0.04f + 0.03f * (float) Math.sin(t * 0.9f));
        demo.mouthForm = ParamLimits.mouthForm(0.25f);
        demo.browLY = Pose.clamp(0.22f * (float) Math.max(0.0, Math.sin(t * 0.13f + 0.4f)), 0.0f, 0.4f);
        demo.browRY = demo.browLY;
        demo.cheek = ParamLimits.unit(0.15f);
        demo.eyeLSmile = ParamLimits.unit(0.15f);
        demo.eyeRSmile = ParamLimits.unit(0.15f);
        demo.eyeLOpen = 1.0f;
        demo.eyeROpen = 1.0f;
        demo.offsetX = 0.0f;
        demo.offsetY = 0.0f;
        demo.zoom = 1.0f;
        // The automatic blinking of the framework stays in charge while the demo plays.
        demo.eyeWeight = 0.0f;
        demo.weight = 0.6f;
    }

    static float smoothStep(float edge0, float edge1, float x) {
        if (edge1 <= edge0) {
            return x >= edge1 ? 1.0f : 0.0f;
        }
        float t = Pose.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
