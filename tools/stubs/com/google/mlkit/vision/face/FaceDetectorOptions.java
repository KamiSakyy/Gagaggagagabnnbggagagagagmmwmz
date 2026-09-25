package com.google.mlkit.vision.face;

/** Compile-time stub for the ML Kit face detection API. */
public class FaceDetectorOptions {
  public static final int PERFORMANCE_MODE_FAST = 1;
  public static final int PERFORMANCE_MODE_ACCURATE = 2;
  public static final int LANDMARK_MODE_NONE = 1;
  public static final int LANDMARK_MODE_ALL = 2;
  public static final int CONTOUR_MODE_NONE = 1;
  public static final int CONTOUR_MODE_ALL = 2;
  public static final int CLASSIFICATION_MODE_NONE = 1;
  public static final int CLASSIFICATION_MODE_ALL = 2;

  public static class Builder {
    public Builder setPerformanceMode(int mode) { return this; }
    public Builder setLandmarkMode(int mode) { return this; }
    public Builder setContourMode(int mode) { return this; }
    public Builder setClassificationMode(int mode) { return this; }
    public Builder setMinFaceSize(float value) { return this; }
    public Builder enableTracking() { return this; }
    public FaceDetectorOptions build() { return null; }
  }
}
