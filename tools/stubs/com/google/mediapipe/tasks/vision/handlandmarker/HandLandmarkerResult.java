package com.google.mediapipe.tasks.vision.handlandmarker;

import java.util.List;

/** Compile-time stub for the MediaPipe Tasks API. */
public abstract class HandLandmarkerResult {
  public abstract long timestampMs();
  public abstract List<List<Object>> landmarks();
  public abstract List<List<Object>> handednesses();
  public abstract List<List<Object>> worldLandmarks();
}
