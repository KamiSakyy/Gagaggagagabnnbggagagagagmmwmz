package com.google.mlkit.vision.face;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;

/** Compile-time stub for the ML Kit face detection API. */
public interface FaceDetector {
  Task<List<Face>> process(InputImage image);
  void close();
}
