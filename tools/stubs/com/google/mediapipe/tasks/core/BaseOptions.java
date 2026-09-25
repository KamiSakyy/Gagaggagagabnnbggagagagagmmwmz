package com.google.mediapipe.tasks.core;

/** Compile-time stub for the MediaPipe Tasks API. */
public abstract class BaseOptions {
  public static Builder builder() { return null; }

  public abstract static class Builder {
    public abstract Builder setModelAssetPath(String value);
    public abstract Builder setDelegate(Delegate value);
    public abstract BaseOptions build();
  }
}
