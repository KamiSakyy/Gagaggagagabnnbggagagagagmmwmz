package com.google.mediapipe.tasks.vision.handlandmarker;

import android.content.Context;

import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.ErrorListener;
import com.google.mediapipe.tasks.core.TaskOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;

/** Compile-time stub for the MediaPipe Tasks API. */
public class HandLandmarker {
  public static HandLandmarker createFromOptions(Context context, HandLandmarkerOptions options) { return null; }

  public void detectAsync(MPImage image, long timestampMs) { }

  public void close() { }

  public abstract static class HandLandmarkerOptions extends TaskOptions {
    public static Builder builder() { return null; }

    public abstract static class Builder {
      public abstract Builder setBaseOptions(BaseOptions value);
      public abstract Builder setRunningMode(RunningMode value);
      public abstract Builder setNumHands(Integer value);
      public abstract Builder setMinHandDetectionConfidence(Float value);
      public abstract Builder setMinHandPresenceConfidence(Float value);
      public abstract Builder setMinTrackingConfidence(Float value);
      public abstract Builder setResultListener(ResultListener<HandLandmarkerResult, MPImage> value);
      public abstract Builder setErrorListener(ErrorListener value);
      public abstract HandLandmarkerOptions build();
    }
  }
}
