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
