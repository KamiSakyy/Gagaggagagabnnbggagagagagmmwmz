package com.echidna.studio.track;

import android.content.Context;

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

    private final Context context;
    private final String assetPath;
    private FaceLandmarker landmarker;
    private volatile FaceSignals latest;
    private long lastTimestampMs = -1;
    private boolean running;

    public MediaPipeFaceTracker(Context context) {
        this(context, MODEL_ASSET);
    }

    public MediaPipeFaceTracker(Context context, String assetPath) {
        this.context = context.getApplicationContext();
        this.assetPath = assetPath;
    }

    @Override
    public String name() {
        return "MediaPipe Face Landmarker";
    }

    @Override
    public void start() throws Exception {
        final BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath(assetPath)
                .setDelegate(Delegate.CPU)
                .build();

        final FaceLandmarker.FaceLandmarkerOptions options =
                FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setNumFaces(1)
                        .setMinFaceDetectionConfidence(0.35f)
                        .setMinFacePresenceConfidence(0.35f)
                        .setMinTrackingConfidence(0.35f)
                        .setOutputFaceBlendshapes(true)
                        .setOutputFacialTransformationMatrixes(true)
                        .setResultListener(this::onResult)
                        .setErrorListener(error -> EchidnaLog.w("TRACK",
                                "MediaPipe: " + (error == null ? "unknown error" : error.getMessage())))
                        .build();

        landmarker = FaceLandmarker.createFromOptions(context, options);
        running = true;
        EchidnaLog.i("TRACK", "MediaPipe поднят, модель " + assetPath);
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
        } catch (Throwable t) {
            EchidnaLog.w("TRACK", "MediaPipe не принял кадр: " + t);
            return null;
        }
        // Results arrive on the MediaPipe thread: hand out the newest available one.
        return latest;
    }

    private void onResult(Object result, Object inputImage) {
        if (result == null) {
            return;
        }
        final FaceSignals signals = new FaceSignals();
        signals.timeMs = System.currentTimeMillis();

        final List<?> landmarks = FacePose.asList(invoke(result, "faceLandmarks"));
        if (landmarks.isEmpty()) {
            signals.found = false;
            latest = signals;
            return;
        }
        signals.found = true;
        final List<?> firstFace = FacePose.asList(landmarks.get(0));

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
                signals.eyeLeft = clamp01(1.0f - signals.blendEyeBlinkLeft * 1.25f);
                signals.eyeRight = clamp01(1.0f - signals.blendEyeBlinkRight * 1.25f);
                signals.mouthOpen = clamp01(signals.blendJawOpen * 1.4f);
                signals.smile = clamp01((signals.blendMouthSmileLeft + signals.blendMouthSmileRight) * 0.5f);
                if (signals.smile < 0.05f) {
                    signals.smile = clamp01(signals.blendMouthPucker * 0.3f);
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
            case "mouthSmileLeft":
                s.blendMouthSmileLeft = score;
                break;
            case "mouthSmileRight":
                s.blendMouthSmileRight = score;
                break;
            case "mouthPucker":
                s.blendMouthPucker = score;
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
            case "cheekPuff":
                s.blendCheekPuff = score;
                break;
            case "eyeSquintLeft":
                s.blendEyeSquintLeft = score;
                break;
            case "eyeSquintRight":
                s.blendEyeSquintRight = score;
                break;
            case "mouthFrownLeft":
                s.blendMouthFrownLeft = score;
                break;
            case "mouthFrownRight":
                s.blendMouthFrownRight = score;
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
