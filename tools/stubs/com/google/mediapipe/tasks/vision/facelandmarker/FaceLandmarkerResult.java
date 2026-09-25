package com.google.mediapipe.tasks.vision.facelandmarker;

import java.util.List;
import java.util.Optional;

/** Compile-time stub for the MediaPipe Tasks API. */
public abstract class FaceLandmarkerResult {
  public abstract long timestampMs();
  public abstract List<List<Object>> faceLandmarks();
  public abstract Optional<List<List<Object>>> faceBlendshapes();
  public abstract Optional<List<float[]>> facialTransformationMatrixes();
}
