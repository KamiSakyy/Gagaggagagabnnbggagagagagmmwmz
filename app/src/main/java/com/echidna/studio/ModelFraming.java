package com.echidna.studio;

/**
 * The numbers that put a Live2D character on the screen without squashing it.
 *
 * <p>The first version of the app scaled the projection by the aspect ratio of the model canvas:
 * {@code scale(1, viewAspect / canvasAspect)}. A canvas twice as tall as it is wide drawn on a
 * portrait screen came out narrow, and the user saw exactly that - "она сплющенная". The fix is to
 * work in pixels per model unit: one number that both axes share, so a circle in the model stays a
 * circle on the screen.</p>
 *
 * <p>The class is deliberately free of Android and of Live2D: the arithmetic that decides how the
 * character looks is the one piece that must be covered by tests, and it is.</p>
 */
public final class ModelFraming {

    /** How much of the screen the widest side of the character may take. */
    public static final float WIDTH_MARGIN = 0.94f;
    /** And the tallest side. */
    public static final float HEIGHT_MARGIN = 0.96f;

    private ModelFraming() {
    }

    /**
     * The projection of one frame: a uniform scale plus a translation, in normalised device
     * coordinates.
     *
     * @param bounds      {@code centerX, centerY, width, height} of the character in model units
     * @param width       surface width in pixels
     * @param height      surface height in pixels
     * @param zoom        user and camera zoom, 1 is the fitted size
     * @param panX        sideways shift in fractions of the screen
     * @param panY        vertical shift in fractions of the screen
     */
    public static Frame frame(float[] bounds, int width, int height, float zoom,
                              float panX, float panY) {
        final float safeWidth = Math.max(1.0f, width);
        final float safeHeight = Math.max(1.0f, height);
        final float boundsWidth = Math.max(0.0001f, bounds[2]);
        final float boundsHeight = Math.max(0.0001f, bounds[3]);
        final float boundsCenterX = bounds[0];
        final float boundsCenterY = bounds[1];

        // One scale for both axes: this single line is what keeps the character from being squashed.
        final float pixelsPerUnit = Math.min(
                safeWidth * WIDTH_MARGIN / boundsWidth,
                safeHeight * HEIGHT_MARGIN / boundsHeight);
        final float scale = pixelsPerUnit * Math.max(0.05f, zoom);

        // Pixels to normalised device coordinates: one axis unit is half a screen, hence the two.
        final float scaleX = 2.0f * scale / safeWidth;
        final float scaleY = 2.0f * scale / safeHeight;

        // The centre of the character goes to the middle of the screen plus the pan.
        final float targetX = 2.0f * (safeWidth * 0.5f + panX * safeWidth) / safeWidth - 1.0f;
        final float targetY = 2.0f * (safeHeight * 0.5f + panY * safeHeight) / safeHeight - 1.0f;

        return new Frame(scaleX, scaleY,
                targetX - boundsCenterX * scaleX,
                targetY - boundsCenterY * scaleY);
    }

    /** The result: two scales and two translations. */
    public static final class Frame {
        public final float scaleX;
        public final float scaleY;
        public final float translateX;
        public final float translateY;

        Frame(float scaleX, float scaleY, float translateX, float translateY) {
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.translateX = translateX;
            this.translateY = translateY;
        }

        /** Where a point of the model lands, in normalised device coordinates. */
        public float[] apply(float x, float y) {
            return new float[]{x * scaleX + translateX, y * scaleY + translateY};
        }

        /** Pixels per model unit along X. */
        public float pixelsPerUnitX(int surfaceWidth) {
            return scaleX * surfaceWidth * 0.5f;
        }

        /** Pixels per model unit along Y: it has to be the same number as along X. */
        public float pixelsPerUnitY(int surfaceHeight) {
            return scaleY * surfaceHeight * 0.5f;
        }
    }
}
