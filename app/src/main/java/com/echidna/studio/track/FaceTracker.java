package com.echidna.studio.track;

/**
 * Something that watches the user and produces {@link FaceSignals} frames.
 *
 * <p>Two real implementations exist (MediaPipe Face Landmarker and ML Kit face detection) plus a
 * synthetic one used by the self test, which is what makes the camera pipeline verifiable without a
 * camera and without a face in front of it.</p>
 */
public interface FaceTracker {
    /** Human readable name shown in the UI. */
    String name();

    /**
     * Starts tracking.
     *
     * @throws Exception when the tracker cannot start; the hub then falls back to the next one
     */
    void start() throws Exception;

    /** Stops tracking and releases every resource. */
    void stop();

    /**
     * Analyses one frame.
     *
     * @param frame   the camera frame; a tracker may take ownership until {@link Frame#release()}
     * @param timestampMs monotonic timestamp of the frame
     * @return signals of this frame, or {@code null} when the frame was only queued
     */
    FaceSignals analyze(Frame frame, long timestampMs);

    /**
     * True while the tracker still works on a frame that was handed to it.
     *
     * <p>The camera reuses its bitmaps, so handing a new frame to a tracker that is not finished
     * with the previous one means the native code reads a picture that is being overwritten right
     * under it: the face then disappears out of the blue. The hub therefore asks before reusing a
     * buffer. Trackers that finish inside {@link #analyze} answer {@code false}.</p>
     */
    default boolean busy() {
        return false;
    }

    /**
     * How many results the tracker produced since it started.
     *
     * <p>A tracker that is alive but never answers is worse than a slow one: this number is what
     * lets the hub notice a dead graph and switch to the next tracker instead of telling the user
     * "лицо не найдено" forever.</p>
     */
    default long resultsSeen() {
        return -1L;
    }

    /** A camera frame in a form both trackers can consume. */
    final class Frame {
        public final android.graphics.Bitmap bitmap;
        public final int rotationDegrees;
        private final Runnable releaser;

        public Frame(android.graphics.Bitmap bitmap, int rotationDegrees, Runnable releaser) {
            this.bitmap = bitmap;
            this.rotationDegrees = rotationDegrees;
            this.releaser = releaser;
        }

        public void release() {
            if (releaser != null) {
                releaser.run();
            }
        }
    }
}
