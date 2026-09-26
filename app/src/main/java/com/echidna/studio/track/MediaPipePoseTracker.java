package com.echidna.studio.track;

import android.content.Context;

import com.echidna.studio.EchidnaLog;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;

import java.util.List;

/**
 * Body and hands with MediaPipe's Pose Landmarker.
 *
 * <p>This is the second pair of eyes of the app and the reason the camera mode never freezes on
 * "лицо не найдено": the pose model sees the whole person, so it keeps reporting the shoulders, the
 * hips and the hands while the dedicated face model struggles with distance, a turned head or dim
 * light. The character turns with the body instead of only with the head, leans when the user leans
 * and lifts its arms when the hands go up.</p>
 *
 * <p>The landmark set of BlazePose has 33 points; the ones used here are the left and right
 * shoulder (11, 12), the hips (23, 24), the head points (nose 0, eyes 2/5, ears 7/8) and the hand
 * points (17-22).</p>
 */
public final class MediaPipePoseTracker implements FaceTracker {
    /** The full model first: it is the most accurate one, and the CPU keeps up on a phone. */
    public static final String MODEL_FULL = "models/pose_landmarker_full.task";
    /** The lightweight model is the fallback for slower devices. */
    public static final String MODEL_LITE = "models/pose_landmarker_lite.task";

    private static final int NOSE = 0;
    private static final int LEFT_EYE = 2;
    private static final int RIGHT_EYE = 5;
    private static final int LEFT_EAR = 7;
    private static final int RIGHT_EAR = 8;
    private static final int LEFT_SHOULDER = 11;
    private static final int RIGHT_SHOULDER = 12;
    private static final int LEFT_WRIST = 15;
    private static final int RIGHT_WRIST = 16;
    private static final int LEFT_PINKY = 17;
    private static final int RIGHT_PINKY = 18;
    private static final int LEFT_INDEX = 19;
    private static final int RIGHT_INDEX = 20;
    private static final int LEFT_HIP = 23;
    private static final int RIGHT_HIP = 24;

    private final Context context;
    private final String assetPath;
    private PoseLandmarker landmarker;
    private volatile FaceSignals latest;
    private long lastTimestampMs = -1;
    private boolean running;

    public MediaPipePoseTracker(Context context) {
        this(context, assetPathFor(context));
    }

    public MediaPipePoseTracker(Context context, String assetPath) {
        this.context = context.getApplicationContext();
        this.assetPath = assetPath;
    }

    /** The best pose model that is really inside the APK, or null when there is none. */
    public static String assetPathFor(Context context) {
        for (String candidate : new String[]{MODEL_FULL, MODEL_LITE}) {
            try {
                context.getAssets().open(candidate).close();
                return candidate;
            } catch (Exception missing) {
                // try the next one
            }
        }
        return null;
    }

    public static boolean assetAvailable(Context context) {
        return assetPathFor(context) != null;
    }

    @Override
    public String name() {
        return assetPath != null && assetPath.contains("lite")
                ? "MediaPipe Pose (lite)"
                : "MediaPipe Pose (full)";
    }

    @Override
    public void start() throws Exception {
        if (assetPath == null) {
            throw new IllegalStateException("нет модели позы в APK");
        }
        final PoseLandmarker.PoseLandmarkerOptions options =
                PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(baseOptions())
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setNumPoses(1)
                        .setMinPoseDetectionConfidence(0.35f)
                        .setMinPosePresenceConfidence(0.35f)
                        .setMinTrackingConfidence(0.35f)
                        .setOutputSegmentationMasks(false)
                        .setResultListener(this::onResult)
                        .setErrorListener(error -> EchidnaLog.w("POSE",
                                "MediaPipe Pose: " + (error == null ? "unknown" : error.getMessage())))
                        .build();
        landmarker = PoseLandmarker.createFromOptions(context, options);
        running = true;
        EchidnaLog.i("POSE", "MediaPipe Pose поднят, модель " + assetPath);
    }

    /**
     * The GPU delegate is several times faster where it works, but it is missing on some devices and
     * broken on others, so the CPU is the safety net.
     */
    private BaseOptions baseOptions() {
        try {
            return BaseOptions.builder()
                    .setModelAssetPath(assetPath)
                    .setDelegate(Delegate.GPU)
                    .build();
        } catch (Throwable notAvailable) {
            EchidnaLog.i("POSE", "GPU недоступен для позы, считаю на CPU");
            return BaseOptions.builder()
                    .setModelAssetPath(assetPath)
                    .setDelegate(Delegate.CPU)
                    .build();
        }
    }

    @Override
    public void stop() {
        running = false;
        if (landmarker != null) {
            try {
                landmarker.close();
            } catch (RuntimeException error) {
                EchidnaLog.w("POSE", "Pose close: " + error);
            }
            landmarker = null;
        }
        latest = null;
        lastTimestampMs = -1;
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
            landmarker.detectAsync(image, timestampMs);
        } catch (Throwable error) {
            EchidnaLog.w("POSE", "кадр не принят: " + error);
            return null;
        }
        return latest;
    }

    private void onResult(Object result, Object inputImage) {
        if (result == null) {
            return;
        }
        final FaceSignals signals = new FaceSignals();
        signals.timeMs = System.currentTimeMillis();
        signals.body = false;

        final List<?> all = FacePose.asList(invoke(result, "landmarks"));
        if (all.isEmpty()) {
            latest = signals;
            return;
        }
        final List<?> body = FacePose.asList(all.get(0));
        final float[] x = new float[33];
        final float[] y = new float[33];
        final float[] visibility = new float[33];
        int usable = 0;
        for (int i = 0; i < 33; i++) {
            final FacePose.Landmark point = FacePose.landmarkOf(body, i);
            if (point == null) {
                x[i] = 0.0f;
                y[i] = 0.0f;
                visibility[i] = 0.0f;
                continue;
            }
            x[i] = point.x;
            y[i] = point.y;
            visibility[i] = point.visibility;
            if (point.visibility > 0.4f) {
                usable++;
            }
        }
        if (usable < 6) {
            latest = signals;
            return;
        }

        final boolean shoulders = visible(visibility, LEFT_SHOULDER) && visible(visibility, RIGHT_SHOULDER);
        if (!shoulders) {
            latest = signals;
            return;
        }
        signals.body = true;

        // Turn of the body: the more the shoulders overlap in the picture the more the user has
        // turned away. The width of the shoulder line is compared with a typical full frontal width
        // (about 0.22 of the frame at a normal distance), and the depth difference adds a sign.
        final float shoulderCenterX = (x[LEFT_SHOULDER] + x[RIGHT_SHOULDER]) * 0.5f;
        final float shoulderCenterY = (y[LEFT_SHOULDER] + y[RIGHT_SHOULDER]) * 0.5f;
        final float shoulderWidth = Math.abs(x[RIGHT_SHOULDER] - x[LEFT_SHOULDER]);
        final float hipWidth = visible(visibility, LEFT_HIP) && visible(visibility, RIGHT_HIP)
                ? Math.abs(x[RIGHT_HIP] - x[LEFT_HIP]) : shoulderWidth * 0.8f;
        final float reference = Math.max(0.06f, Math.max(shoulderWidth, hipWidth) * 1.15f);
        // Depth: the shoulder that is closer to the camera is the one with the bigger z... the tasks
        // API gives plain 2D landmarks here, so the overlap itself is the signal.
        final float turn = clamp((reference - shoulderWidth) / reference, 0.0f, 1.0f);
        final boolean rightCloser = visible(visibility, LEFT_EAR) && visible(visibility, NOSE)
                ? x[NOSE] > (x[LEFT_EAR] + x[RIGHT_EAR]) * 0.5f
                : x[LEFT_SHOULDER] < x[RIGHT_SHOULDER];
        signals.bodyYaw = (rightCloser ? -1.0f : 1.0f) * turn * 42.0f;

        // Tilt of the shoulders, and how much of the body leans sideways.
        final float shoulderSlope = (y[RIGHT_SHOULDER] - y[LEFT_SHOULDER]) / Math.max(0.001f, shoulderWidth);
        signals.bodyRoll = clamp(-shoulderSlope * 45.0f, -35.0f, 35.0f);
        signals.bodyShift = clamp((shoulderCenterX - 0.5f) * 2.4f, -1.0f, 1.0f);

        // Height: compare the shoulder line with where it sits in a relaxed standing pose.
        final float noseToShoulder = shoulderCenterY - y[NOSE];
        signals.bodyLift = clamp((0.10f - noseToShoulder) * 4.0f, -1.0f, 1.0f);

        // Hands: how high above the shoulder line the highest hand point is.
        float hand = -1.0f;
        final int[] handPoints = {LEFT_WRIST, RIGHT_WRIST, LEFT_INDEX, RIGHT_INDEX, LEFT_PINKY, RIGHT_PINKY};
        for (int i = 0; i < handPoints.length; i++) {
            final int index = handPoints[i];
            if (!visible(visibility, index)) {
                continue;
            }
            final float height = (shoulderCenterY - y[index]) / Math.max(0.05f, shoulderCenterY);
            if (height > hand) {
                hand = height;
            }
        }
        signals.handUp = clamp(hand * 2.2f, 0.0f, 1.0f);

        // Turn of the head, measured on the pose landmarks: the nose between the ears is a yaw, the
        // nose above the shoulder line is a pitch. Coarse, but it never loses the user.
        final boolean nose = visible(visibility, NOSE);
        final boolean ears = visible(visibility, LEFT_EAR) && visible(visibility, RIGHT_EAR);
        final boolean eyes = visible(visibility, LEFT_EYE) && visible(visibility, RIGHT_EYE);
        if (nose && (ears || eyes)) {
            final float earCenter = ears ? (x[LEFT_EAR] + x[RIGHT_EAR]) * 0.5f
                                         : (x[LEFT_EYE] + x[RIGHT_EYE]) * 0.5f;
            final float earSpan = ears ? Math.abs(x[RIGHT_EAR] - x[LEFT_EAR])
                                       : Math.abs(x[RIGHT_EYE] - x[LEFT_EYE]);
            final float offset = (x[NOSE] - earCenter) / Math.max(0.02f, earSpan);
            signals.yaw = clamp(offset * 60.0f, -55.0f, 55.0f);
            signals.pitch = clamp((0.09f - noseToShoulder) * 260.0f, -35.0f, 35.0f);
            signals.roll = signals.bodyRoll * 0.6f;
            signals.centerX = clamp((x[NOSE] - 0.5f) * 2.0f, -1.0f, 1.0f);
            signals.centerY = clamp((y[NOSE] - 0.42f) * 2.0f, -1.0f, 1.0f);
            // The image is mirrored for the user, so the eyes are only used for a coarse openness:
            // the pose model does not report blinks, which is why the lids fall back to the engine.
            signals.eyeLeft = 1.0f;
            signals.eyeRight = 1.0f;
            signals.scale = clamp(shoulderWidth * 1.6f, 0.05f, 1.0f);
            signals.poseOnly = true;
            signals.found = true;
        }
        latest = signals;
    }

    private static boolean visible(float[] visibility, int index) {
        return visibility[index] > 0.4f;
    }

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value)) {
            return 0.0f;
        }
        return value < min ? min : (value > max ? max : value);
    }

    private static Object invoke(Object target, String method) {
        try {
            final java.lang.reflect.Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Exception error) {
            return null;
        }
    }
}
