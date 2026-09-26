package com.echidna.studio.track;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;

import com.echidna.studio.EchidnaLog;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceContour;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Fallback tracker built on ML Kit's face detection.
 *
 * <p>It returns the head rotation and the per eye / mouth probabilities but no blendshapes, so the
 * mapping is coarser than with MediaPipe. It exists because the model is bundled inside the APK: if
 * the MediaPipe runtime cannot start on a given device, the camera mode still works instead of
 * dying.</p>
 */
public final class MlKitFaceTracker implements FaceTracker {
    /** Сколько ждём результат разбора, миллисекунды. */
    private static final long RESULT_WAIT_MS = 70;

    private final Context context;
    private FaceDetector detector;
    private volatile FaceSignals latest;
    /** Наблюдения за положением кадра: по ним решается, переворачивать ли камеру. */
    private boolean lastSubmitFlipped;
    private int normalFrames;
    private int normalHits;
    private int flippedFrames;
    private int flippedHits;

    public MlKitFaceTracker(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String name() {
        return "ML Kit Face Detection";
    }

    @Override
    public void start() {
        final FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.12f)
                .build();
        detector = FaceDetection.getClient(options);
        EchidnaLog.i("TRACK", "ML Kit готов");
    }

    @Override
    public void stop() {
        if (detector != null) {
            detector.close();
            detector = null;
        }
        latest = null;
        resetFlipStats();
    }

    @Override
    public FaceSignals analyze(Frame frame, long timestampMs) {
        return submit(frame, timestampMs, false);
    }

    @Override
    public FaceSignals submit(Frame frame, long timestampMs, boolean flipped) {
        if (detector == null || frame == null || frame.bitmap == null) {
            return null;
        }
        lastSubmitFlipped = flipped;
        final Bitmap bitmap = frame.bitmap;
        final CountDownLatch latch = new CountDownLatch(1);
        final InputImage image = InputImage.fromBitmap(bitmap, 0);
        try {
            final Task<List<Face>> task = detector.process(image);
            task.addOnSuccessListener(faces -> {
                final FaceSignals signals = toSignals(faces, bitmap);
                recordObservation(signals.found);
                latest = signals;
                latch.countDown();
            }).addOnFailureListener(error -> {
                EchidnaLog.w("TRACK", "ML Kit: " + error.getMessage());
                latch.countDown();
            });
            // Ждём результат, но недолго: долгое ожидание - это задержка движения на экране.
            if (!latch.await(RESULT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                return latest;
            }
        } catch (Throwable t) {
            EchidnaLog.w("TRACK", "ML Kit не принял кадр: " + t);
            return null;
        }
        return latest;
    }

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

    /** Наблюдения по положению кадра: сколько кадров и на скольких найдено лицо. */
    public int[] flipStats() {
        return new int[]{normalFrames, normalHits, flippedFrames, flippedHits};
    }

    public void resetFlipStats() {
        normalFrames = 0;
        normalHits = 0;
        flippedFrames = 0;
        flippedHits = 0;
    }

    private static FaceSignals toSignals(List<Face> faces, Bitmap bitmap) {
        final FaceSignals signals = new FaceSignals();
        signals.timeMs = System.currentTimeMillis();
        if (faces == null || faces.isEmpty()) {
            signals.found = false;
            return signals;
        }
        final Face face = faces.get(0);
        signals.found = true;

        // ML Kit: X is the pitch, Y the yaw and Z the roll of the head.
        signals.pitch = face.getHeadEulerAngleX();
        signals.yaw = face.getHeadEulerAngleY();
        signals.roll = face.getHeadEulerAngleZ();

        final Float left = face.getLeftEyeOpenProbability();
        final Float right = face.getRightEyeOpenProbability();
        signals.eyeLeft = left == null ? 1.0f : clamp01(left);
        signals.eyeRight = right == null ? 1.0f : clamp01(right);

        final Float smiling = face.getSmilingProbability();
        signals.smile = smiling == null ? 0.0f : clamp01(smiling);

        final android.graphics.Rect box = face.getBoundingBox();
        if (box != null && bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
            signals.centerX = (box.exactCenterX() / bitmap.getWidth()) * 2.0f - 1.0f;
            signals.centerY = (box.exactCenterY() / bitmap.getHeight()) * 2.0f - 1.0f;
            signals.scale = (float) box.height() / bitmap.getHeight();
        }

        signals.mouthOpen = mouthOpenFromContours(face, box);
        return signals;
    }

    /**
     * Mouth opening from the lip contours: the gap between the upper and the lower lip divided by
     * the height of the face. ML Kit reports the contours in image pixels.
     */
    private static float mouthOpenFromContours(Face face, android.graphics.Rect box) {
        if (box == null || box.height() <= 0) {
            return 0.0f;
        }
        final Float upper = averageY(face.getContour(FaceContour.UPPER_LIP_BOTTOM));
        final Float lower = averageY(face.getContour(FaceContour.LOWER_LIP_TOP));
        if (upper == null || lower == null) {
            return 0.0f;
        }
        final float gap = Math.abs(lower - upper);
        return clamp01(gap / box.height() * 7.0f);
    }

    private static Float averageY(FaceContour contour) {
        if (contour == null) {
            return null;
        }
        final List<PointF> points = contour.getPoints();
        if (points == null || points.isEmpty()) {
            return null;
        }
        float sum = 0.0f;
        for (int i = 0; i < points.size(); i++) {
            sum += points.get(i).y;
        }
        return sum / points.size();
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }
}
