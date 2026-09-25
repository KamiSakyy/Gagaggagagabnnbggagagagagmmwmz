package com.google.mediapipe.tasks.vision.facelandmarker;

import android.content.Context;

import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.ErrorListener;
import com.google.mediapipe.tasks.core.TaskOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;

/** Compile-time stub for the MediaPipe Tasks API. */
public class FaceLandmarker {
  public static FaceLandmarker createFromOptions(Context context, FaceLandmarkerOptions options) { return null; }

  public void detectAsync(MPImage image, long timestampMs) { }

  public void close() { }

  public abstract static class FaceLandmarkerOptions extends TaskOptions {
    public static Builder builder() { return null; }

    public abstract static class Builder {
      public abstract Builder setBaseOptions(BaseOptions value);
      public abstract Builder setRunningMode(RunningMode value);
      public abstract Builder setNumFaces(Integer value);
      public abstract Builder setMinFaceDetectionConfidence(Float value);
      public abstract Builder setMinFacePresenceConfidence(Float value);
      public abstract Builder setMinTrackingConfidence(Float value);
      public abstract Builder setOutputFaceBlendshapes(boolean value);
      public abstract Builder setOutputFacialTransformationMatrixes(boolean value);
      public abstract Builder setResultListener(ResultListener<FaceLandmarkerResult, MPImage> value);
      public abstract Builder setErrorListener(ErrorListener value);
      public abstract FaceLandmarkerOptions build();
    }
  }
}
