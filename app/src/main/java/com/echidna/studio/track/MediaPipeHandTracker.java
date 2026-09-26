package com.echidna.studio.track;

import android.content.Context;
import android.os.SystemClock;

import com.echidna.studio.EchidnaLog;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;

import java.util.List;

/**
 * Hands and fingers with MediaPipe's Hand Landmarker.
 *
 * <p>This is what lets the character follow the hands: the model returns 21 points per hand, up to two
 * hands, thirty times a second and completely offline. The landmark set is the standard one - the
 * wrist, the four knuckle rows and the five tips - which is enough to count the raised fingers, to
 * see where the palm is and to tell whether the fingers reach the chin.</p>
 *
 * <p>The counting is done by {@link HandPose}, so the interesting part is testable without a camera:
 * a fist reads as zero, an open palm as five, four raised fingers as four.</p>
 */
public final class MediaPipeHandTracker implements FaceTracker {
    /** Официальная модель кисти: детектор ладони плюс модель 21 точки. */
    public static final String MODEL_ASSET = "models/hand_landmarker.task";
    /** Сколько ждём ответа графа, прежде чем считать кадр потерянным. */
    private static final long BUSY_TIMEOUT_MS = 500;

    private final Context context;
    private HandLandmarker landmarker;
    private volatile boolean usingGpu;
    private volatile FaceSignals latest;
    private long lastTimestampMs = -1;
    private boolean running;
    private volatile long submitted;
    private volatile long results;
    private volatile long lastSubmitMs;
    /** Сколько раз граф сам себя перезапускал после ошибки. */
    private volatile int restarts;

    public MediaPipeHandTracker(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String name() {
        return usingGpu ? "MediaPipe Hand Landmarker (GPU)" : "MediaPipe Hand Landmarker";
    }

    @Override
    public void start() throws Exception {
        try {
            landmarker = create(Delegate.GPU);
            usingGpu = true;
            EchidnaLog.i("TRACK", "руки: MediaPipe Hand Landmarker на GPU");
        } catch (Throwable noGpu) {
            EchidnaLog.w("TRACK", "руки: GPU не завёлся (" + noGpu + "), считаю на CPU");
            landmarker = create(Delegate.CPU);
            usingGpu = false;
            EchidnaLog.i("TRACK", "руки: MediaPipe Hand Landmarker на CPU");
        }
        running = true;
    }

    private HandLandmarker create(Delegate delegate) {
        final BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(delegate)
                .build();
        final HandLandmarker.HandLandmarkerOptions options =
                HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        // Две руки: жест показывают одной, но вторая не должна ломать детекцию.
                        .setNumHands(2)
                        // Пороги мягкие: потерять руку на мгновение хуже, чем редко ошибиться.
                        .setMinHandDetectionConfidence(0.3f)
                        .setMinHandPresenceConfidence(0.3f)
                        .setMinTrackingConfidence(0.3f)
                        .setResultListener(this::onResult)
                        .setErrorListener(error -> EchidnaLog.w("TRACK", "руки: "
                                + (error == null ? "неизвестная ошибка" : error.getMessage())))
                        .build();
        return HandLandmarker.createFromOptions(context, options);
    }

    /** Драйвер может отказать на кадре: тогда трекер один раз пересобирается на CPU. */
    private void fallBackToCpu(Throwable reason) {
        if (!usingGpu || landmarker == null) {
            return;
        }
        EchidnaLog.w("TRACK", "руки: GPU отказал (" + reason + "), перехожу на CPU");
        try {
            landmarker.close();
        } catch (RuntimeException ignored) {
            // сборку всё равно выбрасываем
        }
        landmarker = null;
        try {
            landmarker = create(Delegate.CPU);
            usingGpu = false;
            restarts++;
        } catch (Throwable fatal) {
            EchidnaLog.e("TRACK", "руки: не собрались и на CPU", fatal);
            running = false;
        }
    }

    @Override
    public void stop() {
        running = false;
        if (landmarker != null) {
            try {
                landmarker.close();
            } catch (RuntimeException e) {
                EchidnaLog.w("TRACK", "руки: close: " + e);
            }
            landmarker = null;
        }
        latest = null;
        lastTimestampMs = -1;
        submitted = 0L;
        results = 0L;
        lastSubmitMs = 0L;
    }

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

    /** Сколько раз трекер пересобирался: ненулевое значение видно в отчёте самопроверки. */
    public int restarts() {
        return restarts;
    }

    @Override
    public FaceSignals analyze(Frame frame, long timestampMs) {
        if (!running || landmarker == null || frame == null || frame.bitmap == null) {
            return null;
        }
        if (timestampMs <= lastTimestampMs) {
            timestampMs = lastTimestampMs + 1;
        }
        lastTimestampMs = timestampMs;
        try {
            final MPImage image = new BitmapImageBuilder(frame.bitmap).build();
            final HandLandmarker target = landmarker;
            if (target == null) {
                return null;
            }
            target.detectAsync(image, timestampMs);
            submitted++;
            lastSubmitMs = SystemClock.elapsedRealtime();
        } catch (Throwable t) {
            EchidnaLog.w("TRACK", "руки: кадр не принят: " + t);
            fallBackToCpu(t);
            return null;
        }
        return latest;
    }

    /**
     * Turns the landmarks of every visible hand into the numbers the avatar uses.
     *
     * <p>The "leading" hand is the one that shows more fingers, and between two equal hands the one
     * held higher: that is the hand the user is paying attention to, so it is the one that steers the
     * character.</p>
     */
    private void onResult(Object result, Object inputImage) {
        results++;
        if (result == null) {
            return;
        }
        final FaceSignals signals = new FaceSignals();
        signals.timeMs = System.currentTimeMillis();

        final List<?> hands = FacePose.asList(read(result, "landmarks", "handLandmarks"));
        if (hands.isEmpty()) {
            latest = signals;
            return;
        }

        int handsSeen = 0;
        int bestFingers = -1;
        float bestHeight = Float.MAX_VALUE;
        boolean bestIsLeft = false;
        for (int h = 0; h < hands.size() && h < 2; h++) {
            final List<?> points = FacePose.asList(hands.get(h));
            if (points.size() < HandPose.POINTS) {
                continue;
            }
            final float[] xs = new float[HandPose.POINTS];
            final float[] ys = new float[HandPose.POINTS];
            for (int i = 0; i < HandPose.POINTS; i++) {
                final Object landmark = points.get(i);
                xs[i] = (float) FacePose.component(landmark, "x");
                ys[i] = (float) FacePose.component(landmark, "y");
            }
            handsSeen++;
            final int fingers = HandPose.fingerCount(xs, ys);
            final float palmX = HandPose.palmX(xs, ys);
            final float palmY = HandPose.palmY(ys);
            // Сторона: у зеркальной картинки модели считают руку наоборот, но здесь важна рука
            // человека, поэтому значение берётся как есть: Left - его левая.
            final int handedness = handednessOf(result, h);
            final boolean isLeft = handedness <= 0;
            if (isLeft) {
                signals.handSeenLeft = true;
                signals.fingersLeft = fingers;
                signals.handOpenLeft = fingers / 5.0f;
                signals.handXLeft = palmX * 2.0f - 1.0f;
                signals.handYLeft = palmY * 2.0f - 1.0f;
                signals.indexXLeft = xs[HandPose.INDEX_TIP] * 2.0f - 1.0f;
                signals.indexYLeft = ys[HandPose.INDEX_TIP] * 2.0f - 1.0f;
                signals.middleXLeft = xs[HandPose.MIDDLE_TIP] * 2.0f - 1.0f;
                signals.middleYLeft = ys[HandPose.MIDDLE_TIP] * 2.0f - 1.0f;
                signals.palmXLeft = palmX * 2.0f - 1.0f;
                signals.palmYLeft = palmY * 2.0f - 1.0f;
            } else {
                signals.handSeenRight = true;
                signals.fingersRight = fingers;
                signals.handOpenRight = fingers / 5.0f;
                signals.handXRight = palmX * 2.0f - 1.0f;
                signals.handYRight = palmY * 2.0f - 1.0f;
                signals.indexXRight = xs[HandPose.INDEX_TIP] * 2.0f - 1.0f;
                signals.indexYRight = ys[HandPose.INDEX_TIP] * 2.0f - 1.0f;
                signals.middleXRight = xs[HandPose.MIDDLE_TIP] * 2.0f - 1.0f;
                signals.middleYRight = ys[HandPose.MIDDLE_TIP] * 2.0f - 1.0f;
                signals.palmXRight = palmX * 2.0f - 1.0f;
                signals.palmYRight = palmY * 2.0f - 1.0f;
            }

            // Ведущая рука - та, что показывает больше пальцев, а при равенстве - поднятая выше:
            // именно на неё смотрит человек, когда показывает жест.
            final boolean leading = h == 0
                    || fingers > bestFingers
                    || (fingers == bestFingers && palmY < bestHeight);
            if (leading) {
                bestFingers = Math.max(bestFingers, fingers);
                bestHeight = palmY;
                bestIsLeft = isLeft;
                signals.fingers = fingers;
                signals.handOpen = fingers / 5.0f;
                signals.handX = palmX * 2.0f - 1.0f;
                signals.handY = palmY * 2.0f - 1.0f;
                signals.indexX = xs[HandPose.INDEX_TIP] * 2.0f - 1.0f;
                signals.indexY = ys[HandPose.INDEX_TIP] * 2.0f - 1.0f;
                signals.middleX = xs[HandPose.MIDDLE_TIP] * 2.0f - 1.0f;
                signals.middleY = ys[HandPose.MIDDLE_TIP] * 2.0f - 1.0f;
                signals.handSpan = HandPose.span(xs, ys) * 2.0f;
                signals.handLeft = isLeft;
            }
        }
        if (handsSeen == 0) {
            latest = signals;
            return;
        }
        signals.hands = handsSeen;
        signals.handsSeen = true;
        latest = signals;
    }

    /**
     * Which hand the model thinks it sees: -1 for the left hand of the person, +1 for the right, 0
     * when the model did not say.
     */
    private static int handednessOf(Object result, int index) {
        final List<?> lists = FacePose.asList(read(result, "handednesses", "handedness"));
        if (index >= lists.size()) {
            return 0;
        }
        final List<?> categories = FacePose.asList(lists.get(index));
        if (categories.isEmpty()) {
            return 0;
        }
        final FacePose.Category category = FacePose.categoryOf(categories.get(0));
        if (category == null || category.name == null) {
            return 0;
        }
        if (category.name.startsWith("Left")) {
            return -1;
        }
        if (category.name.startsWith("Right")) {
            return 1;
        }
        return 0;
    }

    private static Object read(Object result, String first, String second) {
        try {
            final java.lang.reflect.Method m = result.getClass().getMethod(first);
            m.setAccessible(true);
            return m.invoke(result);
        } catch (Exception e) {
            try {
                final java.lang.reflect.Method m = result.getClass().getMethod(second);
                m.setAccessible(true);
                return m.invoke(result);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** True when the hand model is present in the APK. */
    public static boolean assetAvailable(Context context) {
        try {
            context.getAssets().open(MODEL_ASSET).close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
