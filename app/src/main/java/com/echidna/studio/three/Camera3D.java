package com.echidna.studio.three;

/**
 * The camera of the 3D stage: it frames the character the same way the Live2D path frames its model.
 *
 * <p>VRM characters look at the positive Z axis, so the camera sits in front of the model, aims at the
 * middle of the body a little above the chest - that is where a portrait lens points - and backs away
 * until both the height and the width of the character fit. The result is one matrix, built here in
 * plain Java so that the software renderer of the tests and the OpenGL renderer of the app see
 * exactly the same picture.</p>
 */
public final class Camera3D {

    private float fieldOfView = 24f;
    private float[] target = {0f, 1f, 0f};
    private float distance = 3f;
    private float verticalOffset;

    /** Frames a character: {@code bounds} is {@code minX, minY, minZ, maxX, maxY, maxZ}. */
    public void frame(float[] bounds, float aspect, float zoom, float verticalOffset) {
        this.verticalOffset = verticalOffset;
        final float centerX = (bounds[0] + bounds[3]) * 0.5f;
        final float centerZ = (bounds[2] + bounds[5]) * 0.5f;
        final float height = Math.max(0.1f, bounds[4] - bounds[1]);
        final float width = Math.max(0.1f, bounds[3] - bounds[0]);
        // The face of a standing character sits well above the middle of its bounding box, and the
        // feet are usually hidden behind the bottom of the screen: aiming a little high is what makes
        // the picture look like a portrait instead of a specimen.
        final float centerY = bounds[1] + height * 0.62f + verticalOffset * height;
        this.target = new float[]{centerX, centerY, centerZ};

        final float halfFov = (float) Math.toRadians(fieldOfView) * 0.5f;
        final float tan = (float) Math.tan(halfFov);
        final float safeZoom = zoom <= 0.05f ? 0.05f : zoom;
        final float forHeight = (height * 0.62f) / tan;
        final float forWidth = (width * 0.62f) / (tan * Math.max(0.2f, aspect));
        // The margin keeps the top of the hair and the sides of the arms inside the frame.
        this.distance = Math.max(forHeight, forWidth) * 1.28f / safeZoom;
    }

    public float[] eye() {
        return new float[]{target[0], target[1], target[2] + distance};
    }

    public float distance() {
        return distance;
    }

    public void setFieldOfView(float degrees) {
        fieldOfView = degrees;
    }

    /** The view-projection matrix, column major, ready for {@code glUniformMatrix4fv}. */
    public float[] viewProjection(float aspect) {
        final float[] projection = new float[16];
        perspective(projection, fieldOfView, Math.max(0.2f, aspect), distance * 0.05f, distance * 6f);
        final float[] view = new float[16];
        lookAt(view, eye(), target, new float[]{0f, 1f, 0f});
        final float[] out = new float[16];
        Mat4.multiply(out, projection, view);
        return out;
    }

    /** A left handed style perspective for a camera that looks down its own negative Z axis. */
    static void perspective(float[] out, float fovDegrees, float aspect, float near, float far) {
        final float f = 1f / (float) Math.tan(Math.toRadians(fovDegrees) * 0.5f);
        for (int i = 0; i < 16; i++) {
            out[i] = 0f;
        }
        out[0] = f / aspect;
        out[5] = f;
        out[10] = (far + near) / (near - far);
        out[11] = -1f;
        out[14] = (2f * far * near) / (near - far);
    }

    /** The standard OpenGL look-at matrix. */
    static void lookAt(float[] out, float[] eye, float[] center, float[] up) {
        final float[] forward = normalize(new float[]{
                center[0] - eye[0], center[1] - eye[1], center[2] - eye[2]});
        final float[] side = normalize(cross(forward, up));
        final float[] trueUp = cross(side, forward);
        out[0] = side[0];
        out[1] = trueUp[0];
        out[2] = -forward[0];
        out[3] = 0f;
        out[4] = side[1];
        out[5] = trueUp[1];
        out[6] = -forward[1];
        out[7] = 0f;
        out[8] = side[2];
        out[9] = trueUp[2];
        out[10] = -forward[2];
        out[11] = 0f;
        out[12] = -dot(side, eye);
        out[13] = -dot(trueUp, eye);
        out[14] = dot(forward, eye);
        out[15] = 1f;
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static float[] normalize(float[] v) {
        final float length = (float) Math.sqrt(dot(v, v));
        if (length < 1e-8f) {
            return new float[]{0f, 0f, -1f};
        }
        return new float[]{v[0] / length, v[1] / length, v[2] / length};
    }
}
