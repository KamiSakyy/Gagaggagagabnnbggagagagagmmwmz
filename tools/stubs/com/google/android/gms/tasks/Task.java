package com.google.android.gms.tasks;

/** Compile-time stub for the Google Play services task API. */
public abstract class Task<T> {
  public abstract Task<T> addOnSuccessListener(OnSuccessListener<? super T> listener);
  public abstract Task<T> addOnFailureListener(OnFailureListener listener);
}
