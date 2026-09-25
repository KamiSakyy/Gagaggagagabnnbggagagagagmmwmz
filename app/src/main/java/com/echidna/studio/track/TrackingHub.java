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

    private static final long ANALYSIS_INTERVAL_MS = 33;   // up to 30 analyses per second

    private final Context context;
    private final CameraController camera;
    private final HandlerThread analysisThread;
    private final Handler analysisHandler;
    private final List<String> diagnostics = new ArrayList<String>();

    private FaceTracker tracker;
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
        return tracker == null ? "нет" : tracker.name();
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
            return true;
        } catch (Throwable t) {
            EchidnaLog.e("TRACK", "ни один трекер не запустился", t);
            addDiagnostic("трекеры недоступны: " + t.getClass().getSimpleName());
            return false;
        }
    }

    public void stop() {
        running = false;
        if (tracker != null) {
            tracker.stop();
            tracker = null;
        }
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

    private void onCameraFrame(Bitmap frame, long timestampMs) {
        if (previewListener != null) {
            previewListener.onPreview(frame);
        }
        if (!running || analyzing) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (now - lastAnalysisMs < ANALYSIS_INTERVAL_MS) {
            return;
        }
        lastAnalysisMs = now;
        analyzing = true;
        analysisHandler.post(() -> {
            try {
                if (tracker == null) {
                    return;
                }
                final FaceSignals signals = tracker.analyze(
                        new FaceTracker.Frame(frame, 0, null), SystemClock.elapsedRealtime());
                analyzedFrames++;
                if (signals == null) {
                    return;
                }
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
