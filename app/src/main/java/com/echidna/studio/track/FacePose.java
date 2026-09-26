package com.echidna.studio.track;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Geometry helpers for the trackers: rotation decomposition and small reflection shims.
 *
 * <p>The shims exist because the payload types of the MediaPipe result objects differ between SDK
 * releases ({@code faceBlendshapes()} returns a plain list in some versions and an {@code Optional}
 * in others, the matrix container lives in different packages). Reading them reflectively keeps the
 * app compiling against several versions instead of pinning one, and a missing field degrades to
 * "no blendshapes" rather than to a crash.</p>
 */
public final class FacePose {

    private FacePose() {
    }

    // ------------------------------------------------------------------ rotation

    /** A category name plus its score, decoupled from the MediaPipe class of the moment. */
    public static final class Category {
        public final String name;
        public final float score;

        Category(String name, float score) {
            this.name = name;
            this.score = score;
        }
    }

    /**
     * Turns a column major 4x4 rotation matrix into {@code {pitch, yaw, roll}} in degrees, using the
     * usual Z-Y-X decomposition. Values are in a right handed coordinate system; the mapper applies
     * the mirroring and the sign conventions of the avatar.
     */
    public static float[] eulerFromMatrix(float[] m) {
        if (m == null || m.length < 16) {
            return new float[]{0.0f, 0.0f, 0.0f};
        }
        // column major -> row major
        final float r00 = m[0], r10 = m[1], r20 = m[2];
        final float r01 = m[4], r11 = m[5], r21 = m[6];
        final float r02 = m[8], r12 = m[9], r22 = m[10];

        final float yaw = (float) Math.asin(clamp(-r20, -1.0f, 1.0f));
        final float pitch = (float) Math.atan2(r21, r22);
        final float roll = (float) Math.atan2(r10, r00);

        final float[] out = new float[3];
        out[0] = (float) Math.toDegrees(pitch);
        out[1] = (float) Math.toDegrees(yaw);
        out[2] = (float) Math.toDegrees(roll);

        // The decomposition above is undefined when the head is upside down; the tracker never
        // needs to survive that, but a NaN must not reach the model, so fall back to zero.
        for (int i = 0; i < 3; i++) {
            if (Float.isNaN(out[i]) || Float.isInfinite(out[i])) {
                out[i] = 0.0f;
            }
        }
        return out;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ------------------------------------------------------------------ shims

    /**
     * Unwraps an {@code Optional} (empty or not), a {@code List} or an array into a list. A plain
     * value - a single landmark or a single matrix - becomes a one element list, which is what makes
     * the callers of this shim read the same whatever container the SDK of the day uses.
     */
    public static List<?> asList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof List) {
            return (List<?>) value;
        }
        if (value instanceof Object[]) {
            return Arrays.asList((Object[]) value);
        }
        // Optional style containers.
        try {
            final Method isPresent = value.getClass().getMethod("isPresent");
            final Object present = isPresent.invoke(value);
            if (present instanceof Boolean) {
                if (!((Boolean) present)) {
                    return Collections.emptyList();
                }
                return asList(invokeNoArg(value, "get"));
            }
        } catch (Exception ignored) {
            // not an Optional: fall through
        }
        return Collections.singletonList(value);
    }

    /** Reads a blendshape category (name + score) out of a MediaPipe category object. */
    public static Category categoryOf(Object category) {
        if (category == null) {
            return null;
        }
        final Object score = invokeNoArg(category, "score");
        if (!(score instanceof Float) && !(score instanceof Double)) {
            return null;
        }
        Object name = invokeNoArg(category, "categoryName");
        if (!(name instanceof String)) {
            name = invokeNoArg(category, "displayName");
        }
        if (!(name instanceof String)) {
            return null;
        }
        return new Category((String) name, ((Number) score).floatValue());
    }

    /** Reads the float array of a MediaPipe matrix container (Optional or plain). */
    public static float[] matrixOf(Object matrixData) {
        if (matrixData == null) {
            return null;
        }
        if (matrixData instanceof float[]) {
            return (float[]) matrixData;
        }
        // Optional style wrapper: unwrap and retry with the value inside.
        final Object unwrapped = invokeNoArg(matrixData, "get");
        if (unwrapped != null && unwrapped != matrixData) {
            final float[] inner = matrixOf(unwrapped);
            if (inner != null) {
                return inner;
            }
        }
        final String[] candidates = {"matrix", "getMatrix", "data", "values", "toArray"};
        for (int i = 0; i < candidates.length; i++) {
            final Object value = invokeNoArg(matrixData, candidates[i]);
            if (value instanceof float[]) {
                return (float[]) value;
            }
            if (value instanceof Object[]) {
                final Object[] array = (Object[]) value;
                if (array.length >= 16 && array[0] instanceof Number) {
                    final float[] out = new float[array.length];
                    for (int k = 0; k < array.length; k++) {
                        out[k] = ((Number) array[k]).floatValue();
                    }
                    return out;
                }
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String name) {
        try {
            final Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    // --------------------------------------------------------------- landmarks

    /**
     * Mouth opening derived from the landmark geometry: the vertical gap between the upper and the
     * lower lip divided by the height of the face. Used when no blendshapes are available.
     */
    public static float mouthOpenFromLandmarks(List<?> landmarks) {
        if (landmarks == null || landmarks.size() < 270) {
            return 0.0f;
        }
        final float upperY = yOf(landmarks, 13);   // upper lip
        final float lowerY = yOf(landmarks, 14);   // lower lip
        final float chinY = yOf(landmarks, 152);   // chin
        final float foreheadY = yOf(landmarks, 10); // forehead
        final float faceHeight = Math.abs(chinY - foreheadY);
        if (faceHeight < 0.0001f) {
            return 0.0f;
        }
        final float gap = Math.abs(lowerY - upperY);
        return clamp(gap / faceHeight * 6.0f, 0.0f, 1.0f);
    }

    /** Face bounds inside the frame: {@code {centerX, centerY, width, height}}, -1..1 on both axes. */
    public static float[] boundingBox(List<?> landmarks) {
        if (landmarks == null || landmarks.isEmpty()) {
            return null;
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < landmarks.size(); i++) {
            final Object landmark = landmarks.get(i);
            final float x = (float) component(landmark, "x");
            final float y = (float) component(landmark, "y");
            if (x < minX) {
                minX = x;
            }
            if (x > maxX) {
                maxX = x;
            }
            if (y < minY) {
                minY = y;
            }
            if (y > maxY) {
                maxY = y;
            }
        }
        final float centerX = (minX + maxX) * 0.5f * 2.0f - 1.0f;
        final float centerY = (minY + maxY) * 0.5f * 2.0f - 1.0f;
        return new float[]{centerX, centerY, (maxX - minX) * 2.0f, (maxY - minY) * 2.0f};
    }

    /** One landmark of a pose: its place in the frame and how sure the model is about it. */
    public static final class Landmark {
        public final float x;
        public final float y;
        public final float z;
        public final float visibility;

        Landmark(float x, float y, float z, float visibility) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.visibility = visibility;
        }
    }

    /**
     * Reads one landmark of a pose result by index.
     *
     * <p>Pose landmarks carry a visibility score that the face landmarks do not have; it is what the
     * pose tracker uses to ignore the parts of the body that are behind something or out of frame.</p>
     */
    public static Landmark landmarkOf(List<?> landmarks, int index) {
        if (landmarks == null || index < 0 || index >= landmarks.size()) {
            return null;
        }
        final Object landmark = landmarks.get(index);
        if (landmark == null) {
            return null;
        }
        return new Landmark(
                (float) component(landmark, "x"),
                (float) component(landmark, "y"),
                (float) component(landmark, "z"),
                (float) component(landmark, "visibility"));
    }

    private static float yOf(List<?> landmarks, int index) {
        if (index >= landmarks.size()) {
            return 0.0f;
        }
        return (float) component(landmarks.get(index), "y");
    }

    /** Reads the public coordinate of a MediaPipe landmark, whichever field name it uses. */
    static double component(Object landmark, String name) {
        if (landmark == null) {
            return 0.0;
        }
        final Object raw = invokeNoArg(landmark, name);
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        // The pose landmarks carry the coordinates as plain floats but the extra scores (visibility,
        // presence) as an Optional, so unwrap those before giving up.
        if (raw != null) {
            final Object unwrapped = invokeNoArg(raw, "get");
            if (unwrapped instanceof Number) {
                return ((Number) unwrapped).doubleValue();
            }
        }
        final Object value = raw;
        try {
            final java.lang.reflect.Field field = landmark.getClass().getField(name);
            final Object fieldValue = field.get(landmark);
            if (fieldValue instanceof Number) {
                return ((Number) fieldValue).doubleValue();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return 0.0;
    }
}
