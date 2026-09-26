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
    /** Как часто (мс) пробовать перевёрнутый кадр, пока лицо не найдено. */
    private static final long FLIP_PROBE_INTERVAL_MS = 1500;
    /**
     * Тело считается реже лица: плечи и руки не мигают, а два графа MediaPipe на одном кадре
     * съедают телефон. Между замерами используется последний результат - он всё равно сглаживается.
     */
    private static final long POSE_INTERVAL_MS = 80;
    /**
     * Кисть разбирается реже лица: 15 раз в секунду хватает, чтобы жест читался мгновенно, а
     * телефон при этом не греется.
     */
    private static final long HAND_INTERVAL_MS = 66;
    /** Сколько ждём первых результатов трекера, прежде чем считать его зависшим. */
    private static final long TRACKER_START_GRACE_MS = 4000;
    /** Сколько ждём новых результатов, пока трекер молчит. */
    private static final long TRACKER_SILENCE_MS = 6000;
    /**
     * Кадров предпросмотра в обороте.
     *
     * <p>Камера переиспользует свои буферы, поэтому картинку для окошка надо снять с них: иначе
     * рендер читает буфер, в который камера уже пишет следующий кадр, и окошко дёргается. Три
     * копии - это заведомо больше, чем нужно GL-потоку, чтобы успеть загрузить текстуру.</p>
     */
    private static final int PREVIEW_SLOTS = 3;

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
    /**
     * Трекер кисти: 21 точка на руку, счёт пальцев и жест «рука у подбородка».
     *
     * <p>Он третий в цепочке, потому что отвечает на другой вопрос: лицо даёт мимику, поза - тело,
     * а кисть - то, что человек показывает рукой. Работает реже остальных: пальцевый граф тяжелее
     * и не нужен на каждом кадре.</p>
     */
    private FaceTracker handTracker;
    private long lastHandMs;
    private FaceSignals lastHandSignals;
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
    /** Когда последний раз проверяли, не приходит ли кадр вверх ногами. */
    private long lastFlipProbeMs;
    /** Был ли последний отправленный в разбор кадр перевёрнут на 180 градусов. */
    private boolean lastAttemptFlipped;
    private volatile FaceSignals lastSignals;
    private volatile int framesPerSecond;
    /** Готовые кадры для окошка предпросмотра: копии, а не буферы камеры. */
    private final Bitmap[] previewSlots = new Bitmap[PREVIEW_SLOTS];
    private int previewCursor;
    /** Номер последнего отданного кадра: рендер по нему понимает, что картинка новая. */
    private volatile int previewSerial;
    private int previewInFlight = -1;
    /** Копия кадра для разбора: живёт, пока трекеры с ней работают. */
    private Bitmap analysisFrame;
    private long trackerOpenedMs;
    private long lastResultsSeen;
    private long lastResultsChangeMs;

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
        final StringBuilder builder = new StringBuilder(tracker.name());
        if (poseTracker != null) {
            builder.append(" + ").append(poseTracker.name());
        }
        if (handTracker != null) {
            builder.append(" + ").append(handTracker.name());
        }
        return builder.toString();
    }

    /** Есть ли трекер кисти: от него зависят жесты рукой. */
    public boolean handsAvailable() {
        return handTracker != null;
    }

    /** Номер последнего кадра предпросмотра: растёт, когда картинка обновилась. */
    public int previewSerial() {
        return previewSerial;
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
                + ", руки=" + (handsAvailable() ? "да" : "нет")
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

    /**
     * Starts the hand tracker: the third pair of eyes of the app.
     *
     * <p>Without it the camera still works - the face and the body are enough - but there are no
     * gestures: no finger count, no hand following the user's hand, no "touching the chin".</p>
     */
    private void openHandTracker() {
        if (handTracker != null) {
            handTracker.stop();
            handTracker = null;
        }
        if (!MediaPipeHandTracker.assetAvailable(context)) {
            addDiagnostic("модель кисти отсутствует в APK, жесты рукой недоступны");
            return;
        }
        try {
            final MediaPipeHandTracker hands = new MediaPipeHandTracker(context);
            hands.start();
            handTracker = hands;
            addDiagnostic("трекер кисти: " + hands.name() + " (пальцы, ладонь, подбородок)");
            EchidnaLog.i("TRACK", "поднят трекер кисти " + hands.name());
        } catch (Throwable error) {
            EchidnaLog.w("TRACK", "трекер кисти не поднялся: " + error);
            addDiagnostic("трекер кисти не поднялся: " + error.getClass().getSimpleName());
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
                openHandTracker();
                trackerOpenedMs = SystemClock.elapsedRealtime();
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
            openHandTracker();
            trackerOpenedMs = SystemClock.elapsedRealtime();
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
        if (handTracker != null) {
            handTracker.stop();
            handTracker = null;
        }
        lastPoseSignals = null;
        lastPoseMs = 0L;
        lastHandSignals = null;
        lastHandMs = 0L;
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
        if (frame == null) {
            return;
        }
        // Сначала окошко: пользователь должен видеть себя даже тогда, когда разбор кадра занят.
        publishPreview(frame);
        final long now = SystemClock.elapsedRealtime();
        if (!running) {
            camera.releaseFrame(frame);
            return;
        }
        // Кадр нельзя отдавать трекеру, пока он работает над предыдущим: MediaPipe читает картинку
        // асинхронно, в своём потоке. Раньше кадры уходили один за другим, и граф получал то
        // половину старого кадра, то половину нового - лицо пропадало, хотя человек никуда не уходил.
        final boolean trackersFree = !isBusy(tracker) && !isBusy(poseTracker) && !isBusy(handTracker);
        // Пока лицо не найдено, кадры уходят в разбор без паузы: человек только что сел перед
        // камерой или отвернулся, и ждать следующего такта незачем. Как только лицо найдено,
        // включается обычный интервал, чтобы не жечь батарею.
        final FaceSignals known = lastSignals;
        final boolean searching = known == null || (!known.found && !known.poseOnly);
        final boolean mayAnalyze = !analyzing && trackersFree
                && (searching || now - lastAnalysisMs >= ANALYSIS_INTERVAL_MS);
        if (!mayAnalyze) {
            camera.releaseFrame(frame);
            return;
        }
        lastAnalysisMs = now;
        analyzing = true;

        final Bitmap stable;
        try {
            // Копия делается в один и тот же буфер: раньше на каждый кадр выделялось 3.7 МБ, и на
            // телефоне это превращалось в постоянную сборку мусора, из-за которой картинка рвалась.
            analysisFrame = copyInto(analysisFrame, frame);
            stable = analysisFrame;
        } catch (Throwable error) {
            EchidnaLog.w("TRACK", "не удалось скопировать кадр: " + error);
            analyzing = false;
            camera.releaseFrame(frame);
            return;
        }
        // Буфер камеры больше не нужен: у разбора и у окошка свои копии.
        camera.releaseFrame(frame);

        analysisHandler.post(() -> {
            Bitmap probe = null;
            try {
                checkTrackerHealth(now);
                // Пока лицо не найдено, раз в полторы секунды кадр уходит в разбор перевёрнутым:
                // часть телефонов отдаёт картинку вверх ногами, и тогда лицо не находится вовсе.
                // Если на перевёрнутом кадре лицо есть - камера поворачивается сама.
                final boolean flipProbe = searching && now - lastFlipProbeMs > FLIP_PROBE_INTERVAL_MS;
                if (flipProbe) {
                    lastFlipProbeMs = now;
                    probe = rotate180(stable);
                }
                lastAttemptFlipped = probe != null;
                final FaceSignals signals = analyzeFrame(probe != null ? probe : stable);
                if (probe != null) {
                    probe.recycle();
                }
                if (signals == null) {
                    return;
                }
                if (signals.found && lastAttemptFlipped) {
                    // Лицо нашлось только на перевёрнутом кадре: камера смотрит вверх ногами.
                    final int rotation = (camera.extraRotation() + 180) % 360;
                    camera.setExtraRotation(rotation);
                    addDiagnostic("кадр камеры приходит перевёрнутым: доворот " + rotation + "°");
                    EchidnaLog.i("TRACK", "лицо нашлось на перевёрнутом кадре, доворот " + rotation);
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
                if (probe != null && !probe.isRecycled()) {
                    probe.recycle();
                }
            } finally {
                analyzing = false;
            }
        });
    }

    /** Поворот кадра на 180 градусов: проверка «а не вверх ли ногами камера». */
    private static Bitmap rotate180(Bitmap source) {
        final android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postRotate(180f);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, false);
    }

    /** Runs the three trackers on one frame and merges what they saw. */
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
        FaceSignals hands = null;
        if (handTracker != null) {
            if (now - lastHandMs >= HAND_INTERVAL_MS) {
                lastHandMs = now;
                final FaceSignals fresh = handTracker.analyze(wrapper, now);
                if (fresh != null) {
                    lastHandSignals = fresh;
                }
            }
            hands = lastHandSignals;
        }
        if (face == null && pose == null && hands == null) {
            return null;
        }
        return merge(face, pose, hands);
    }

    /**
     * Builds the frame the stage works with: the face supplies the eyes, the mouth and the smile,
     * the body supplies the shoulders, the lean and the hands.
     *
     * <p>When the face is gone but the body is there, the head pose of the pose tracker is used -
     * that is how the avatar keeps following the user instead of falling back to the demo sway the
     * moment the face model loses track.</p>
     */
    private FaceSignals merge(FaceSignals face, FaceSignals pose, FaceSignals hands) {
        final boolean anyHands = hands != null && hands.handsSeen;
        if (face == null && pose == null) {
            // Видна только рука: лицо трекер потерял, но жест всё равно должен дойти до модели.
            if (!anyHands) {
                return null;
            }
            merged.set(new FaceSignals());
            merged.found = true;
            merged.poseOnly = true;
            copyHands(hands, merged, null);
            return merged;
        }
        if (face == null) {
            // Лицо потеряно, но поза видит человека: голова едет за телом, руки - за кистью.
            merged.set(pose);
            if (!merged.found && anyHands) {
                merged.found = true;
                merged.poseOnly = true;
            }
            copyHands(hands, merged, pose);
            return merged;
        }
        if (pose == null || !pose.found) {
            merged.set(face);
            copyHands(hands, merged, face);
            return merged;
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
        copyHands(hands, merged, face);
        return merged;
    }

    /**
     * Adds what the hand model saw to the frame: the fingers, the palm, and how close the fingers are
     * to the chin.
     *
     * <p>The chin is computed here because it needs both trackers: the face supplies the place and
     * the height of the head, the hand supplies the fingertips. Distances are measured in face
     * heights, so the gesture works the same for someone close to the phone and for someone sitting
     * farther away.</p>
     */
    private void copyHands(FaceSignals hands, FaceSignals target, FaceSignals face) {
        if (hands == null || !hands.handsSeen) {
            return;
        }
        target.handsSeen = true;
        target.hands = hands.hands;
        target.fingers = hands.fingers;
        target.fingersLeft = hands.fingersLeft;
        target.fingersRight = hands.fingersRight;
        target.handOpen = hands.handOpen;
        target.handX = hands.handX;
        target.handY = hands.handY;
        target.indexX = hands.indexX;
        target.indexY = hands.indexY;
        target.middleX = hands.middleX;
        target.middleY = hands.middleY;
        target.handSpan = hands.handSpan;
        target.handLeft = hands.handLeft;

        final float faceHeight;
        if (face != null && face.faceHeight > 0.02f) {
            faceHeight = face.faceHeight;
        } else if (target.faceHeight > 0.02f) {
            faceHeight = target.faceHeight;
        } else {
            faceHeight = 0.5f;
        }
        // Подбородок - нижняя граница лица. Y растёт вниз, поэтому он ниже центра на полвысоты.
        final float chinX = face != null ? face.centerX : 0.0f;
        final float chinY = (face != null ? face.centerY : 0.0f) + faceHeight * 0.5f;
        target.chinTouch = HandPose.reachOf(hands.indexX, hands.indexY, hands.middleX, hands.middleY,
                hands.handX, hands.handY, chinX, chinY, faceHeight);
        // Поднятая рука: считается от подбородка вверх, в высотах лица.
        final float raise = clamp01((chinY - hands.handY) / (faceHeight * 1.2f));
        if (raise > target.handUp) {
            target.handUp = raise;
        }
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    /** Занят ли трекер кадром: пока занят, новый кадр ему отдавать нельзя. */
    private static boolean isBusy(FaceTracker tracker) {
        return tracker != null && tracker.busy();
    }

    /**
     * Сторож зависшего трекера.
     *
     * <p>Бывает так, что граф поднимается, принимает кадры и молчит: например, драйвер GPU отвечает
     * ошибкой на каждый кадр. Снаружи это выглядит как "лицо не найдено" и демо-режим, хотя камера
     * работает. Поэтому если трекер молчит несколько секунд при живом потоке кадров, он
     * пересобирается заново - с ML Kit или на CPU, что окажется рабочим.</p>
     */
    private void checkTrackerHealth(long now) {
        if (tracker == null || tracker.resultsSeen() < 0L) {
            return;
        }
        final long seen = tracker.resultsSeen();
        if (seen != lastResultsSeen) {
            lastResultsSeen = seen;
            lastResultsChangeMs = now;
            return;
        }
        final long since = now - Math.max(Math.max(lastResultsChangeMs, trackerOpenedMs), startedAtMs);
        // Кадры должны идти: если камера встала, молчание трекера ничего не значит.
        final boolean framesFlowing = now - lastAnalysisMs < 1500;
        if (framesFlowing && since > TRACKER_SILENCE_MS && analyzedFrames > 3) {
            addDiagnostic("трекер молчит " + (since / 1000) + " с, перезапускаю");
            EchidnaLog.w("TRACK", "трекер не отвечает " + since + " мс, перезапуск");
            lastResultsChangeMs = now;
            openTracker();
        }
    }

    /**
     * Кадр для окошка предпросмотра.
     *
     * <p>Копия снимается с буфера камеры, потому что камера пишет в него следующий кадр: если
     * отдать рендеру сам буфер, на телефоне видно, как картинка дёргается и расползается. Копии
     * идут по кругу, и каждая живёт, пока рендер не загрузит её в текстуру.</p>
     */
    private void publishPreview(Bitmap frame) {
        final CameraPreviewListener listener = previewListener;
        if (listener == null) {
            return;
        }
        try {
            final Bitmap slot = previewSlotFor(frame);
            if (slot == null) {
                return;
            }
            final android.graphics.Canvas canvas = new android.graphics.Canvas(slot);
            canvas.drawBitmap(frame, 0f, 0f, null);
            previewSerial++;
            listener.onPreview(slot);
        } catch (Throwable error) {
            EchidnaLog.w("TRACK", "кадр окошка не подготовлен: " + error);
        }
    }

    private Bitmap previewSlotFor(Bitmap frame) {
        for (int i = 0; i < PREVIEW_SLOTS; i++) {
            final int index = (previewCursor + i) % PREVIEW_SLOTS;
            final Bitmap candidate = previewSlots[index];
            if (candidate == null || candidate.getWidth() != frame.getWidth()
                    || candidate.getHeight() != frame.getHeight()) {
                continue;
            }
            previewCursor = (index + 1) % PREVIEW_SLOTS;
            return candidate;
        }
        final Bitmap created = Bitmap.createBitmap(frame.getWidth(), frame.getHeight(),
                Bitmap.Config.ARGB_8888);
        final int index = previewCursor;
        previewSlots[index] = created;
        previewCursor = (index + 1) % PREVIEW_SLOTS;
        return created;
    }

    /** Копия кадра в переиспользуемый буфер: без выделения памяти на каждый кадр. */
    private static Bitmap copyInto(Bitmap target, Bitmap source) {
        Bitmap out = target;
        if (out == null || out.getWidth() != source.getWidth()
                || out.getHeight() != source.getHeight()) {
            out = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                    Bitmap.Config.ARGB_8888);
        }
        final android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.drawBitmap(source, 0f, 0f, null);
        return out;
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
