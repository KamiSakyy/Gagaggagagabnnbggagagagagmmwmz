package com.google.mediapipe.tasks.vision.poselandmarker;

import android.content.Context;

import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.ErrorListener;
import com.google.mediapipe.tasks.core.TaskOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.ResultListener;

/** Compile-time stub for the MediaPipe Tasks Pose Landmarker API. */
public class PoseLandmarker {
  public static PoseLandmarker createFromOptions(Context context, PoseLandmarkerOptions options) { return null; }

  public void detectAsync(MPImage image, long timestampMs) { }

  public void close() { }

  public abstract static class PoseLandmarkerOptions extends TaskOptions {
    public static Builder builder() { return null; }

    public abstract static class Builder {
      public abstract Builder setBaseOptions(BaseOptions value);
      public abstract Builder setRunningMode(RunningMode value);
      public abstract Builder setNumPoses(Integer value);
      public abstract Builder setMinPoseDetectionConfidence(Float value);
      public abstract Builder setMinPosePresenceConfidence(Float value);
      public abstract Builder setMinTrackingConfidence(Float value);
      public abstract Builder setOutputSegmentationMasks(boolean value);
      public abstract Builder setResultListener(ResultListener<PoseLandmarkerResult, MPImage> value);
      public abstract Builder setErrorListener(ErrorListener value);
      public abstract PoseLandmarkerOptions build();
    }
  }

  public static class PoseLandmarkerResult {
    public java.util.List<java.util.List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>> landmarks() { return null; }
  }
}
