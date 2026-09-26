package com.echidna.studio.track;

import android.content.Context;
import android.os.SystemClock;

import com.echidna.studio.EchidnaLog;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;

import java.util.List;

/**
 * Face tracking with MediaPipe's Face Landmarker.
 *
 * <p>This is the high quality tracker: it returns 52 blendshape coefficients (eye blink, jaw open,
 * smile, brow raise, cheek puff ...) and a 4x4 transformation matrix of the head - exactly what a
 * virtual avatar needs to mimic a face one to one. The model file is bundled in the assets, so the
 * tracker needs no network connection at all.</p>
 *
 * <p>The payload objects of the result are read through {@link FacePose}'s shims so that a change of
 * the container types between MediaPipe releases cannot break the app.</p>
 */
public final class MediaPipeFaceTracker implements FaceTracker {
    public static final String MODEL_ASSET = "models/face_landmarker.task";
    public static final String MODEL_ASSET_ALTERNATIVE = "models/face_landmarker_v2.task";
    /** Сколько ждём ответа графа, прежде чем считать кадр потерянным. */
    private static final long BUSY_TIMEOUT_MS = 500;

    private final Context context;
    private final String assetPath;
    private FaceLandmarker landmarker;
    /** Считает ли модель сейчас на GPU: упавший драйвер уводит её на CPU. */
    private volatile boolean usingGpu;
    private volatile FaceSignals latest;
    private long lastTimestampMs = -1;
    private boolean running;
    /** Сколько кадров отдано графу и сколько результатов он вернул. */
    private volatile long submitted;
    private volatile long results;
    private volatile long lastSubmitMs;
    /**
     * Сколько времени ждать ответа на только что отданный кадр.
     *
     * <p>В потоковом режиме {@code detectAsync} возвращается сразу, а результат приходит позже, и
     * раньше трекер отдавал предыдущий результат: мимика отставала на кадр-два. Ожидание свежего
     * ответа убирает эту задержку - на телефоне это заметно сразу.</p>
     */
    private static final long RESULT_WAIT_MS = 45;
    /** Был ли последний отданный кадр перевёрнутым (проверка положения камеры). */
    private volatile boolean lastSubmitFlipped;
    /** Наблюдения за положением кадра: по ним решается, переворачивать ли камеру. */
    private volatile int normalFrames;
    private volatile int normalHits;
    private volatile int flippedFrames;
    private volatile int flippedHits;

    public MediaPipeFaceTracker(Context context) {
        this(context, MODEL_ASSET);
    }

    public MediaPipeFaceTracker(Context context, String assetPath) {
        this.context = context.getApplicationContext();
        this.assetPath = assetPath;
    }

    @Override
    public String name() {
        return usingGpu ? "MediaPipe Face Landmarker (GPU)" : "MediaPipe Face Landmarker";
    }

    @Override
    public void start() throws Exception {
        // The GPU delegate is an order of magnitude faster where it is available, which is what
        // turns a laggy avatar into a mirror. It is missing on some devices, so the CPU stays as the
        // safety net and the app never loses the camera mode because of a driver.
        // Сборка с GPU может не только не собраться, но и упасть уже в первом кадре (драйвер
        // телефона), поэтому на первую же ошибку трекер сам пересобирается на CPU и говорит об этом
        // в логе: молча уходить на слабый ML Kit нельзя.
        try {
            landmarker = create(Delegate.GPU);
            usingGpu = true;
            EchidnaLog.i("TRACK", "MediaPipe поднят на GPU, модель " + assetPath);
        } catch (Throwable noGpu) {
            EchidnaLog.w("TRACK", "GPU для лица не завёлся (" + noGpu + "), считаю на CPU");
            landmarker = create(Delegate.CPU);
            usingGpu = false;
            EchidnaLog.i("TRACK", "MediaPipe поднят на CPU, модель " + assetPath);
        }
        running = true;
    }

    /** Собирает Face Landmarker на указанном ускорителе. */
    private FaceLandmarker create(Delegate delegate) {
        final BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath(assetPath)
                .setDelegate(delegate)
                .build();

        final FaceLandmarker.FaceLandmarkerOptions options =
                FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setNumFaces(1)
                        // Пороги чуть выше минимума: слишком мягкие дают дрожащие углы и мнимые
                        // лица в тёмной комнате, слишком строгие - теряют лицо. Поза подхватывает
                        // голову, если лицо всё-таки потерялось.
                        .setMinFaceDetectionConfidence(0.35f)
                        .setMinFacePresenceConfidence(0.35f)
                        .setMinTrackingConfidence(0.30f)
                        .setOutputFaceBlendshapes(true)
                        .setOutputFacialTransformationMatrixes(true)
                        .setResultListener(this::onResult)
                        .setErrorListener(error -> EchidnaLog.w("TRACK",
                                "MediaPipe: " + (error == null ? "unknown error" : error.getMessage())))
                        .build();
        return FaceLandmarker.createFromOptions(context, options);
    }

    /** Драйвер GPU может отвалиться посреди работы: тогда трекер один раз уходит на CPU. */
    private void fallBackToCpu(Throwable reason) {
        if (!usingGpu || landmarker == null) {
            return;
        }
        EchidnaLog.w("TRACK", "GPU отказал на кадре (" + reason + "), перехожу на CPU");
        try {
            landmarker.close();
        } catch (RuntimeException ignored) {
            // ничего страшного: сборку всё равно выбрасываем
        }
        landmarker = null;
        try {
            landmarker = create(Delegate.CPU);
            usingGpu = false;
            EchidnaLog.i("TRACK", "MediaPipe пересобран на CPU");
        } catch (Throwable fatal) {
            EchidnaLog.e("TRACK", "MediaPipe не собрался и на CPU", fatal);
            running = false;
        }
    }

    /**
     * True while the graph has a frame in its hands.
     *
     * <p>In the live stream mode {@code detectAsync} returns at once and the picture is read by the
     * graph later, on its own thread. Until the answer comes back the bitmap must not be touched,
     * so the hub waits (with a timeout - a hung graph must not freeze the camera).</p>
     */
    @Override
    public boolean busy() {
        if (!running || submitted == 0L || results >= submitted) {
            return false;
        }
        return SystemClock.elapsedRealtime() - lastSubmitMs < BUSY_TIMEOUT_MS;
    }

    @Override
    public long resultsSeen() {
        return results;
    }

    @Override
    public void stop() {
        running = false;
        if (landmarker != null) {
            try {
                landmarker.close();
            } catch (RuntimeException e) {
                EchidnaLog.w("TRACK", "MediaPipe close: " + e);
            }
            landmarker = null;
        }
        latest = null;
        lastTimestampMs = -1;
        submitted = 0L;
        results = 0L;
        lastSubmitMs = 0L;
        resetFlipStats();
    }

    @Override
    public FaceSignals analyze(Frame frame, long timestampMs) {
        return submit(frame, timestampMs, false);
    }

    /**
     * Отдаёт кадр графу и ждёт свежий ответ.
     *
     * @param flipped кадр перевёрнут на 180 градусов (проверка положения камеры)
     */
    @Override
    public FaceSignals submit(Frame frame, long timestampMs, boolean flipped) {
        if (!running || landmarker == null || frame == null || frame.bitmap == null) {
            return null;
        }
        if (timestampMs <= lastTimestampMs) {
            timestampMs = lastTimestampMs + 1;
        }
        lastTimestampMs = timestampMs;

        final long seenBefore = results;
        try {
            final MPImage image = new BitmapImageBuilder(frame.bitmap).build();
            final FaceLandmarker target = landmarker;
            if (target == null) {
                return null;
            }
            lastSubmitFlipped = flipped;
            target.detectAsync(image, timestampMs);
            submitted++;
            lastSubmitMs = SystemClock.elapsedRealtime();
        } catch (Throwable t) {
            EchidnaLog.w("TRACK", "MediaPipe не принял кадр: " + t);
            fallBackToCpu(t);
            return null;
        }

        // Ждём именно ответ на этот кадр: иначе мимика показывала бы то, что было полтора кадра
        // назад, и модель двигалась бы с задержкой.
        final long deadline = SystemClock.elapsedRealtime() + RESULT_WAIT_MS;
        while (results == seenBefore && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(1L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return results != seenBefore ? latest : null;
    }

    /** Наблюдения по положению кадра: сколько кадров и на скольких найдено лицо. */
    public int[] flipStats() {
        return new int[]{normalFrames, normalHits, flippedFrames, flippedHits};
    }

    /** Начинает окно наблюдений заново. */
    public void resetFlipStats() {
        normalFrames = 0;
        normalHits = 0;
        flippedFrames = 0;
        flippedHits = 0;
    }

    private void onResult(Object result, Object inputImage) {
        results++;
        if (result == null) {
            return;
        }
        final FaceSignals signals = new FaceSignals();
        signals.timeMs = System.currentTimeMillis();

        final List<?> landmarks = FacePose.asList(invoke(result, "faceLandmarks"));
        if (landmarks.isEmpty()) {
            signals.found = false;
            recordObservation(false);
            latest = signals;
            return;
        }
        recordObservation(true);
        signals.found = true;
        final List<?> firstFace = FacePose.asList(landmarks.get(0));

        // Геометрия лица: улыбка, раскрытие рта, брови и веки, измеренные по точкам. Она не
        // зависит от уверенности нейросети в отдельных движениях мышц и служит второй опорой для
        // мимики и эмоций - а заодно говорит, не приходит ли кадр вверх ногами.
        final FaceGeometry.Readings geometry = FaceGeometry.read(firstFace);
        if (geometry != null) {
            signals.geometric = true;
            signals.faceUprightKnown = true;
            signals.faceUpright = geometry.upright;
            signals.smileGeo = geometry.smile;
            signals.mouthOpenGeo = geometry.mouthOpen;
            signals.browGeo = geometry.brow;
            signals.eyeOpenGeo = geometry.eyeOpen;
        }

        // Head pose.
        final List<?> matrices = FacePose.asList(invoke(result, "facialTransformationMatrixes"));
        if (!matrices.isEmpty()) {
            final float[] matrix = FacePose.matrixOf(matrices.get(0));
            if (matrix != null && matrix.length >= 16) {
                final float[] angles = FacePose.eulerFromMatrix(matrix);
                signals.pitch = angles[0];
                signals.yaw = angles[1];
                signals.roll = angles[2];
            }
        }

        // Blendshapes.
        final List<?> blendshapeLists = FacePose.asList(invoke(result, "faceBlendshapes"));
        if (!blendshapeLists.isEmpty()) {
            final List<?> categories = FacePose.asList(blendshapeLists.get(0));
            signals.blendshapes = !categories.isEmpty();
            for (int i = 0; i < categories.size(); i++) {
                final FacePose.Category category = FacePose.categoryOf(categories.get(i));
                if (category == null) {
                    continue;
                }
                applyBlendshape(signals, category.name, category.score);
            }
            if (signals.blendshapes) {
                // Eyes: a blink is reported as a probability; the eyelid of the model follows it
                // almost one to one, and a squint is folded in so that a smile reaches the eyes.
                final float squint = (signals.blendEyeSquintLeft + signals.blendEyeSquintRight) * 0.5f;
                final float byBlendLeft = clamp01(1.0f - signals.blendEyeBlinkLeft * 1.35f - squint * 0.15f);
                final float byBlendRight = clamp01(1.0f - signals.blendEyeBlinkRight * 1.35f - squint * 0.15f);
                // Измерение по векам точнее коэффициента моргания (у человека веко закрывается
                // мгновенно, а сеть успевает его «увидеть» не всегда), поэтому берётся более
                // закрытый глаз из двух оценок: так моргание не теряется.
                signals.eyeLeft = geometry != null ? Math.min(byBlendLeft, geometry.eyeOpen) : byBlendLeft;
                signals.eyeRight = geometry != null ? Math.min(byBlendRight, geometry.eyeOpen) : byBlendRight;
                // Mouth: the jaw opening is the main channel, the pucker and the closed lips make it
                // narrower, which is what keeps the model from looking like it only says "a".
                final float jaw = signals.blendJawOpen;
                final float pucker = signals.blendMouthPucker;
                signals.mouthOpen = clamp01(jaw * 1.35f + pucker * 0.12f);
                final float smileLeft = signals.blendMouthSmileLeft;
                final float smileRight = signals.blendMouthSmileRight;
                signals.smile = clamp01((smileLeft + smileRight) * 0.5f);
                if (signals.smile < 0.05f) {
                    signals.smile = clamp01(pucker * 0.3f);
                }
            }
        }
        if (!signals.blendshapes) {
            signals.mouthOpen = FacePose.mouthOpenFromLandmarks(firstFace);
        }

        // Face position and size, used for the small parallax of the gaze.
        final float[] box = FacePose.boundingBox(firstFace);
        if (box != null) {
            signals.centerX = box[0];
            signals.centerY = box[1];
            signals.scale = Math.max(0.01f, box[3]);
        }
        latest = signals;
    }

    /** Записывает, нашлось ли лицо на кадре этого положения - для проверки переворота камеры. */
    private void recordObservation(boolean found) {
        if (lastSubmitFlipped) {
            flippedFrames++;
            if (found) {
                flippedHits++;
            }
        } else {
            normalFrames++;
            if (found) {
                normalHits++;
            }
        }
    }

    private static void applyBlendshape(FaceSignals s, String name, float score) {
        switch (name) {
            case "eyeBlinkLeft":
                s.blendEyeBlinkLeft = score;
                break;
            case "eyeBlinkRight":
                s.blendEyeBlinkRight = score;
                break;
            case "jawOpen":
                s.blendJawOpen = score;
                break;
            case "jawLeft":
                s.blendJawLeft = score;
                break;
            case "jawRight":
                s.blendJawRight = score;
                break;
            case "mouthSmileLeft":
                s.blendMouthSmileLeft = score;
                break;
            case "mouthSmileRight":
                s.blendMouthSmileRight = score;
                break;
            case "mouthPucker":
                s.blendMouthPucker = score;
                break;
            case "mouthFunnel":
                s.blendMouthFunnel = score;
                break;
            case "mouthClose":
                s.blendMouthClose = score;
                break;
            case "mouthLeft":
                s.blendMouthLeft = score;
                break;
            case "mouthRight":
                s.blendMouthRight = score;
                break;
            case "browInnerUp":
                s.blendBrowInnerUp = score;
                break;
            case "browDownLeft":
                s.blendBrowDownLeft = score;
                break;
            case "browDownRight":
                s.blendBrowDownRight = score;
                break;
            case "browOuterUpLeft":
                s.blendBrowOuterUpLeft = score;
                break;
            case "browOuterUpRight":
                s.blendBrowOuterUpRight = score;
                break;
            case "cheekPuff":
                s.blendCheekPuff = score;
                break;
            case "cheekSquintLeft":
                s.blendCheekSquintLeft = score;
                break;
            case "cheekSquintRight":
                s.blendCheekSquintRight = score;
                break;
            case "noseSneerLeft":
                s.blendNoseSneerLeft = score;
                break;
            case "noseSneerRight":
                s.blendNoseSneerRight = score;
                break;
            case "eyeSquintLeft":
                s.blendEyeSquintLeft = score;
                break;
            case "eyeSquintRight":
                s.blendEyeSquintRight = score;
                break;
            case "eyeWideLeft":
                s.blendEyeWideLeft = score;
                break;
            case "eyeWideRight":
                s.blendEyeWideRight = score;
                break;
            case "eyeLookUpLeft":
                s.blendEyeLookUpLeft = score;
                break;
            case "eyeLookUpRight":
                s.blendEyeLookUpRight = score;
                break;
            case "eyeLookDownLeft":
                s.blendEyeLookDownLeft = score;
                break;
            case "eyeLookDownRight":
                s.blendEyeLookDownRight = score;
                break;
            case "eyeLookOutLeft":
                s.blendEyeLookOutLeft = score;
                break;
            case "eyeLookOutRight":
                s.blendEyeLookOutRight = score;
                break;
            case "eyeLookInLeft":
                s.blendEyeLookInLeft = score;
                break;
            case "eyeLookInRight":
                s.blendEyeLookInRight = score;
                break;
            case "mouthFrownLeft":
                s.blendMouthFrownLeft = score;
                break;
            case "mouthFrownRight":
                s.blendMouthFrownRight = score;
                break;
            case "mouthPressLeft":
                s.blendMouthPressLeft = score;
                break;
            case "mouthPressRight":
                s.blendMouthPressRight = score;
                break;
            case "mouthShrugUpper":
                s.blendMouthShrugUpper = score;
                break;
            case "mouthShrugLower":
                s.blendMouthShrugLower = score;
                break;
            case "mouthStretchLeft":
                s.blendMouthStretchLeft = score;
                break;
            case "mouthStretchRight":
                s.blendMouthStretchRight = score;
                break;
            case "mouthUpperUpLeft":
                s.blendMouthUpperUpLeft = score;
                break;
            case "mouthUpperUpRight":
                s.blendMouthUpperUpRight = score;
                break;
            case "mouthLowerDownLeft":
                s.blendMouthLowerDownLeft = score;
                break;
            case "mouthLowerDownRight":
                s.blendMouthLowerDownRight = score;
                break;
            case "mouthDimpleLeft":
                s.blendMouthDimpleLeft = score;
                break;
            case "mouthDimpleRight":
                s.blendMouthDimpleRight = score;
                break;
            case "mouthRollLower":
                s.blendMouthRollLower = score;
                break;
            case "mouthRollUpper":
                s.blendMouthRollUpper = score;
                break;
            case "tongueOut":
                s.blendTongueOut = score;
                break;
            default:
                break;
        }
    }

    private static Object invoke(Object target, String method) {
        try {
            final java.lang.reflect.Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    /** True when the model asset is present in the APK. */
    public static boolean assetAvailable(Context context) {
        try {
            context.getAssets().open(MODEL_ASSET).close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
