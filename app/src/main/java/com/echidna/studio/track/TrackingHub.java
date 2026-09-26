package com.echidna.studio.track;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.echidna.studio.EchidnaLog;
import com.echidna.studio.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the camera, the trackers and the analysis loop.
 *
 * <p>Camera frames arrive on the camera thread; the heavy tracker runs on its own thread so that the
 * camera is never blocked. Results are published as {@link FaceSignals} and picked up by the
 * renderer on the GL thread. Every layer below the hub can be swapped: MediaPipe first, ML Kit if
 * MediaPipe cannot start, and a synthetic source for the automated check.</p>
 */
public final class TrackingHub {
    public interface SignalsListener {
        void onSignals(FaceSignals signals);
    }

    public interface CameraPreviewListener {
        void onPreview(Bitmap frame);
    }

    private static final long ANALYSIS_INTERVAL_MS = 33;
    /**
     * Тело считается реже лица: плечи и руки не мигают, а два графа MediaPipe на одном кадре
     * съедают телефон. Между замерами используется последний результат - он всё равно сглаживается.
     */
    private static final long POSE_INTERVAL_MS = 80;

    private final Context context;
    private final CameraController camera;
    private final HandlerThread analysisThread;
    private final Handler analysisHandler;
    private final List<String> diagnostics = new ArrayList<String>();

    private FaceTracker tracker;
    /** Трекер тела и рук: работает вместе с лицом и подстраховывает его. */
    private FaceTracker poseTracker;
    private long lastPoseMs;
    private FaceSignals lastPoseSignals;
    private final FaceSignals merged = new FaceSignals();
    private SignalsListener signalsListener;
    private CameraPreviewListener previewListener;

    private volatile boolean running;
    private volatile boolean analyzing;
    private volatile boolean syntheticOnly;
    private long lastAnalysisMs;
    private long analyzedFrames;
    private long analyzedEmpty;
    private long startedAtMs;
    private volatile FaceSignals lastSignals;
    private volatile int framesPerSecond;

    public TrackingHub(Context context) {
        this.context = context.getApplicationContext();
        this.camera = new CameraController(context);
        this.analysisThread = new HandlerThread("echidna-analysis");
        this.analysisThread.start();
        this.analysisHandler = new Handler(analysisThread.getLooper());

        camera.setFrameListener(this::onCameraFrame);
        camera.setStateListener((running, message) -> addDiagnostic("камера: " + message));
    }

    public CameraController camera() {
        return camera;
    }

    public void setSignalsListener(SignalsListener listener) {
        signalsListener = listener;
    }

    public void setPreviewListener(CameraPreviewListener listener) {
        previewListener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public String trackerName() {
        if (tracker == null) {
            return "нет";
        }
        return poseTracker == null ? tracker.name() : tracker.name() + " + " + poseTracker.name();
    }

    public long analyzedFrames() {
        return analyzedFrames;
    }

    public long emptyFrames() {
        return analyzedEmpty;
    }

    public int framesPerSecond() {
        return framesPerSecond;
    }

    public FaceSignals lastSignals() {
        return lastSignals;
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    public void addDiagnostic(String line) {
        if (diagnostics.size() > 40) {
            diagnostics.remove(0);
        }
        diagnostics.add(line);
    }

    /** True while a face is in front of the camera (or the synthetic source says so). */
    public boolean faceVisible() {
        final FaceSignals signals = lastSignals;
        return signals != null && signals.found;
    }

    /** Diagnostic report, also used by the self test. */
    public String report() {
        return "трекер=" + trackerName()
                + ", кадров камеры=" + camera.frameCount()
                + ", проанализировано=" + analyzedFrames
                + ", без лица=" + analyzedEmpty
                + ", камер " + camera.frameWidth() + "x" + camera.frameHeight()
                + ", " + framesPerSecond + " анализ/с";
    }

    /**
     * Starts the camera and the tracker.
     *
     * @param synthetic run the synthetic source instead of the camera (self test)
     * @return true when tracking is up
     */
    public boolean start(boolean synthetic) {
        if (running) {
            return true;
        }
        syntheticOnly = synthetic;
        startedAtMs = SystemClock.elapsedRealtime();
        analyzedFrames = 0;
        analyzedEmpty = 0;

        if (synthetic) {
            tracker = new SyntheticFaceTracker();
            try {
                tracker.start();
            } catch (Exception e) {
                EchidnaLog.e("TRACK", "синтетический трекер не запустился", e);
                return false;
            }
            running = true;
            addDiagnostic("трекер: синтетический (самопроверка)");
            analysisHandler.post(this::syntheticLoop);
            return true;
        }

        if (!CameraController.hasPermission(context)) {
            addDiagnostic("нет разрешения на камеру");
            return false;
        }
        if (!camera.start()) {
            addDiagnostic("камера не запустилась: " + camera.lastError());
            return false;
        }
        if (!openTracker()) {
            camera.stop();
            return false;
        }
        running = true;
        return true;
    }

    /**
     * Starts the body tracker next to the face tracker.
     *
     * <p>The two see different things: the face model is precise about the eyes and the mouth, the
     * pose model is robust and knows the shoulders, the hips and the hands. Running both is what
     * makes the camera mode feel like a mirror instead of a guessing game.</p>
     */
    private void openPoseTracker() {
        if (poseTracker != null) {
            poseTracker.stop();
            poseTracker = null;
        }
        if (!MediaPipePoseTracker.assetAvailable(context)) {
            addDiagnostic("модель позы отсутствует в APK, тело не отслеживается");
            return;
        }
        try {
            final MediaPipePoseTracker pose = new MediaPipePoseTracker(context);
            pose.start();
            poseTracker = pose;
            addDiagnostic("трекер тела: " + pose.name() + " (плечи, наклон, руки)");
            EchidnaLog.i("TRACK", "поднят трекер тела " + pose.name());
        } catch (Throwable error) {
            EchidnaLog.w("TRACK", "трекер тела не поднялся: " + error);
            addDiagnostic("трекер тела не поднялся: " + error.getClass().getSimpleName());
        }
    }

    /** Picks the best tracker that actually starts on this device. */
    private boolean openTracker() {
        if (tracker != null) {
            tracker.stop();
            tracker = null;
        }
        if (MediaPipeFaceTracker.assetAvailable(context)) {
            try {
                final MediaPipeFaceTracker mediaPipe = new MediaPipeFaceTracker(context);
                mediaPipe.start();
                tracker = mediaPipe;
                addDiagnostic("трекер: MediaPipe Face Landmarker (52 blendshape)");
                EchidnaLog.i("TRACK", "выбран MediaPipe Face Landmarker");
                openPoseTracker();
                return true;
            } catch (Throwable t) {
                EchidnaLog.w("TRACK", "MediaPipe не поднялся (" + t + "), пробуем ML Kit");
                addDiagnostic("MediaPipe не поднялся: " + t.getClass().getSimpleName());
            }
        } else {
            addDiagnostic("модель MediaPipe отсутствует в APK, используется ML Kit");
        }
        try {
            final MlKitFaceTracker mlKit = new MlKitFaceTracker(context);
            mlKit.start();
            tracker = mlKit;
            addDiagnostic("трекер: ML Kit Face Detection");
            EchidnaLog.i("TRACK", "выбран ML Kit Face Detection");
            openPoseTracker();
            return true;
        } catch (Throwable t) {
            EchidnaLog.w("TRACK", "ни один трекер не запустился: " + t);
            addDiagnostic("трекеры недоступны: " + t.getClass().getSimpleName());
        }
        // Never leave the streamer with a dead camera mode: the synthetic source keeps the avatar
        // alive and the diagnostics say exactly what is missing.
        try {
            final SyntheticFaceTracker fallback = new SyntheticFaceTracker();
            fallback.start();
            tracker = fallback;
            addDiagnostic("трекер: демонстрационный (живая мимика без лица)");
            EchidnaLog.i("TRACK", "трекеры недоступны, включён демонстрационный источник");
            return true;
        } catch (Throwable t) {
            EchidnaLog.e("TRACK", "демонстрационный источник тоже не поднялся", t);
            return false;
        }
    }

    public void stop() {
        running = false;
        if (tracker != null) {
            tracker.stop();
            tracker = null;
        }
        if (poseTracker != null) {
            poseTracker.stop();
            poseTracker = null;
        }
        lastPoseSignals = null;
        lastPoseMs = 0L;
        camera.stop();
    }

    /** Switches between the front and the back camera while running. */
    public boolean restartCamera() {
        if (!running || syntheticOnly) {
            return false;
        }
        final boolean wasRunning = running;
        camera.stop();
        if (!camera.start()) {
            running = false;
            return false;
        }
        running = wasRunning;
        return true;
    }

    public void release() {
        stop();
        analysisThread.quitSafely();
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * A frame arrived from the camera.
     *
     * <p>The camera recycles its buffers, so the bitmap it hands over is overwritten by the next
     * frame. It used to be given to the analysis thread as it was: the tracker then read half of the
     * old picture and half of the new one and reported "лицо не найдено" every other frame. The copy
     * below is what makes the analysis see a complete, frozen frame.</p>
     */
    private void onCameraFrame(Bitmap frame, long timestampMs) {
        if (!running) {
            if (previewListener != null) {
                previewListener.onPreview(frame);
            }
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        final boolean mayAnalyze = !analyzing && now - lastAnalysisMs >= ANALYSIS_INTERVAL_MS;
        if (previewListener != null) {
            previewListener.onPreview(frame);
        }
        if (!mayAnalyze) {
            return;
        }
        lastAnalysisMs = now;
        analyzing = true;

        final Bitmap stable;
        try {
            stable = frame.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable error) {
            EchidnaLog.w("TRACK", "не удалось скопировать кадр: " + error);
            analyzing = false;
            return;
        }

        analysisHandler.post(() -> {
            try {
                final FaceSignals signals = analyzeFrame(stable);
                if (signals == null) {
                    return;
                }
                analyzedFrames++;
                if (!signals.found) {
                    analyzedEmpty++;
                }
                lastSignals = signals;
                if (signalsListener != null) {
                    signalsListener.onSignals(signals);
                }
            } catch (Throwable t) {
                EchidnaLog.w("TRACK", "анализ кадра: " + t);
            } finally {
                analyzing = false;
            }
        });
    }

    /** Runs both trackers on one frame and merges what they saw. */
    private FaceSignals analyzeFrame(Bitmap frame) {
        final long now = SystemClock.elapsedRealtime();
        final FaceTracker.Frame wrapper = new FaceTracker.Frame(frame, 0, null);
        FaceSignals face = null;
        if (tracker != null) {
            face = tracker.analyze(wrapper, now);
        }
        FaceSignals pose = null;
        if (poseTracker != null) {
            if (now - lastPoseMs >= POSE_INTERVAL_MS) {
                lastPoseMs = now;
                final FaceSignals fresh = poseTracker.analyze(wrapper, now);
                if (fresh != null) {
                    lastPoseSignals = fresh;
                }
            }
            pose = lastPoseSignals;
        }
        if (face == null && pose == null) {
            return null;
        }
        return merge(face, pose);
    }

    /**
     * Builds the frame the stage works with: the face supplies the eyes, the mouth and the smile,
     * the body supplies the shoulders, the lean and the hands.
     *
     * <p>When the face is gone but the body is there, the head pose of the pose tracker is used -
     * that is how the avatar keeps following the user instead of falling back to the demo sway the
     * moment the face model loses track.</p>
     */
    private FaceSignals merge(FaceSignals face, FaceSignals pose) {
        if (face == null) {
            return pose;
        }
        if (pose == null || !pose.found) {
            return face;
        }
        merged.set(face);
        merged.body = pose.body;
        merged.bodyYaw = pose.bodyYaw;
        merged.bodyRoll = pose.bodyRoll;
        merged.bodyLift = pose.bodyLift;
        merged.bodyShift = pose.bodyShift;
        merged.handUp = pose.handUp;
        if (!face.found) {
            // The face tracker lost the user: the pose tracker takes over the head as well.
            merged.found = true;
            merged.poseOnly = true;
            merged.yaw = pose.yaw;
            merged.pitch = pose.pitch;
            merged.roll = pose.roll;
            merged.centerX = pose.centerX;
            merged.centerY = pose.centerY;
            if (pose.scale > 0.05f) {
                merged.scale = pose.scale;
            }
            merged.eyeLeft = 1.0f;
            merged.eyeRight = 1.0f;
            merged.smile = 0.0f;
            merged.mouthOpen = 0.0f;
            merged.blendshapes = false;
        } else {
            merged.poseOnly = false;
        }
        return merged;
    }

    private void syntheticLoop() {
        while (running && syntheticOnly) {
            try {
                final FaceSignals signals = tracker == null
                        ? null
                        : tracker.analyze(new FaceTracker.Frame(null, 0, null),
                                SystemClock.elapsedRealtime());
                if (signals != null) {
                    analyzedFrames++;
                    if (!signals.found) {
                        analyzedEmpty++;
                    }
                    lastSignals = signals;
                    if (signalsListener != null) {
                        signalsListener.onSignals(signals);
                    }
                }
                final long seconds = Math.max(1, (SystemClock.elapsedRealtime() - startedAtMs) / 1000);
                framesPerSecond = (int) (analyzedFrames / seconds);
                Thread.sleep(33);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                EchidnaLog.w("TRACK", "синтетический цикл: " + t);
            }
        }
    }

    /** Called once per second from the renderer to keep the rate display honest. */
    public void tickRate() {
        final long seconds = Math.max(1, (SystemClock.elapsedRealtime() - startedAtMs) / 1000);
        framesPerSecond = (int) (analyzedFrames / seconds);
    }

    /** Snapshot of the current signals, JSON encoded, for the self test report. */
    public String signalsJson() {
        final FaceSignals s = lastSignals;
        if (s == null) {
            return "{}";
        }
        return Json.object()
                .put("found", s.found)
                .put("yaw", round(s.yaw))
                .put("pitch", round(s.pitch))
                .put("roll", round(s.roll))
                .put("eyeLeft", round(s.eyeLeft))
                .put("eyeRight", round(s.eyeRight))
                .put("mouth", round(s.mouthOpen))
                .put("smile", round(s.smile))
                .put("blendshapes", s.blendshapes)
                .put("body", s.body)
                .put("bodyYaw", round(s.bodyYaw))
                .put("bodyRoll", round(s.bodyRoll))
                .put("bodyLift", round(s.bodyLift))
                .put("handUp", round(s.handUp))
                .put("poseOnly", s.poseOnly)
                .toString();
    }

    private static float round(float value) {
        return Math.round(value * 100.0f) / 100.0f;
    }

    @Override
    public String toString() {
        return "TrackingHub{" + report() + "}";
    }
}
