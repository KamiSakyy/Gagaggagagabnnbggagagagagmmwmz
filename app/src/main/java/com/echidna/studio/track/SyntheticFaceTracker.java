package com.echidna.studio.track;

import com.echidna.studio.EchidnaLog;

/**
 * A tracker that invents a face and moves it on a script.
 *
 * <p>It exists so that the whole camera pipeline - signals, mapper, model, renderer - can be
 * exercised without a camera and without anybody sitting in front of it. The automated emulator run
 * switches it on, drives three scripted "performances" (looking around, blinking, talking) and then
 * verifies from the captured frames that the model really followed the script.</p>
 */
public final class SyntheticFaceTracker implements FaceTracker {
    /** One scripted movement: turns the head, blinks and opens the mouth at known times. */
    public static final class Scene {
        public final String name;
        public final float duration;
        public final float yawAmplitude;
        public final float pitchAmplitude;
        public final float rollAmplitude;
        public final boolean blink;
        public final float mouthAmplitude;

        public Scene(String name, float duration, float yawAmplitude, float pitchAmplitude,
                     float rollAmplitude, boolean blink, float mouthAmplitude) {
            this.name = name;
            this.duration = duration;
            this.yawAmplitude = yawAmplitude;
            this.pitchAmplitude = pitchAmplitude;
            this.rollAmplitude = rollAmplitude;
            this.blink = blink;
            this.mouthAmplitude = mouthAmplitude;
        }
    }

    public static final Scene[] DEFAULT_SCENES = {
            new Scene("повороты", 6.0f, 25.0f, 12.0f, 14.0f, true, 0.0f),
            new Scene("моргание", 5.0f, 4.0f, 3.0f, 2.0f, true, 0.0f),
            new Scene("речь", 6.0f, 8.0f, 5.0f, 4.0f, true, 0.85f)
    };

    private final Scene[] scenes;
    private long startMs;
    private int sceneIndex;
    private long sceneStartMs;
    private volatile int completedScenes;
    private float blinkPhase = -1.0f;

    public SyntheticFaceTracker() {
        this(DEFAULT_SCENES);
    }

    public SyntheticFaceTracker(Scene[] scenes) {
        this.scenes = scenes;
    }

    @Override
    public String name() {
        return "Синтетический трекер (самопроверка)";
    }

    @Override
    public void start() {
        startMs = System.currentTimeMillis();
        sceneStartMs = startMs;
        sceneIndex = 0;
        completedScenes = 0;
        EchidnaLog.i("TRACK", "синтетический трекер запущен, сцен " + scenes.length);
    }

    @Override
    public void stop() {
        completedScenes = 0;
    }

    public int completedScenes() {
        return completedScenes;
    }

    public String currentSceneName() {
        return scenes[Math.min(sceneIndex, scenes.length - 1)].name;
    }

    @Override
    public FaceSignals analyze(Frame frame, long timestampMs) {
        final long now = System.currentTimeMillis();
        if (startMs == 0) {
            startMs = now;
            sceneStartMs = now;
        }
        final Scene scene = scenes[Math.min(sceneIndex, scenes.length - 1)];
        float elapsed = (now - sceneStartMs) / 1000.0f;
        if (elapsed >= scene.duration) {
            completedScenes++;
            sceneIndex = Math.min(sceneIndex + 1, scenes.length - 1);
            sceneStartMs = now;
            elapsed = 0.0f;
            EchidnaLog.i("TRACK", "сцена " + sceneIndex + " (\"" + scenes[sceneIndex].name + "\")");
        }

        final FaceSignals signals = new FaceSignals();
        signals.found = true;
        signals.timeMs = now;

        final float t = elapsed;
        signals.yaw = scene.yawAmplitude * (float) Math.sin(t * 1.1f);
        signals.pitch = scene.pitchAmplitude * (float) Math.sin(t * 0.7f + 1.0f);
        signals.roll = scene.rollAmplitude * (float) Math.sin(t * 0.9f + 2.0f);
        signals.centerX = 0.25f * (float) Math.sin(t * 0.8f);
        signals.centerY = 0.12f * (float) Math.sin(t * 0.6f);
        signals.scale = 0.35f;

        // A blink every two seconds, and a mouth that follows a syllable rhythm.
        final float blinkCycle = t % 2.0f;
        final boolean blinking = scene.blink && blinkCycle > 1.8f;
        signals.eyeLeft = blinking ? 0.05f : 1.0f;
        signals.eyeRight = blinking ? 0.05f : 1.0f;
        signals.blendshapes = true;
        signals.blendEyeBlinkLeft = blinking ? 0.95f : 0.0f;
        signals.blendEyeBlinkRight = blinking ? 0.95f : 0.0f;

        final float syllable = Math.max(0.0f, (float) Math.sin(t * 7.0f));
        signals.blendJawOpen = scene.mouthAmplitude * syllable;
        signals.mouthOpen = scene.mouthAmplitude * syllable;
        signals.smile = 0.55f;
        signals.blendMouthSmileLeft = 0.55f;
        signals.blendMouthSmileRight = 0.55f;
        return signals;
    }

    /** True when every scripted scene has been played at least once. */
    public boolean finished() {
        return sceneIndex >= scenes.length - 1
                && (System.currentTimeMillis() - sceneStartMs) / 1000.0f >= scenes[sceneIndex].duration;
    }
}
