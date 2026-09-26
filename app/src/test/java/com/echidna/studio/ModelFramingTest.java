package com.echidna.studio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Главная жалоба пользователя была «модель сплющенная». Эти тесты держат ту самую арифметику, из-за
 * которой это происходило, и проверяют, что квадрат в модели остаётся квадратом на экране.
 */
public class ModelFramingTest {

    private static final float[] BOUNDS = {0.0f, 0.0f, 2.0f, 4.0f};   // вдвое выше, чем шире

    @Test
    public void bothAxesGetTheSameScaleOnAPortraitScreen() {
        final ModelFraming.Frame frame = ModelFraming.frame(BOUNDS, 1080, 2400, 1.0f, 0f, 0f);
        final float perUnitX = frame.pixelsPerUnitX(1080);
        final float perUnitY = frame.pixelsPerUnitY(2400);
        assertEquals("горизонтальный и вертикальный масштаб разошлись: модель сплющится",
                perUnitX, perUnitY, 1e-3f);
        assertTrue("масштаб не положительный", perUnitX > 0f);
    }

    @Test
    public void bothAxesGetTheSameScaleOnALandscapeScreen() {
        final ModelFraming.Frame frame = ModelFraming.frame(BOUNDS, 2400, 1080, 1.0f, 0f, 0f);
        assertEquals(frame.pixelsPerUnitX(2400), frame.pixelsPerUnitY(1080), 1e-3f);
    }

    /** Квадрат в модели обязан остаться квадратом: это и есть проверка на «сплющенность». */
    @Test
    public void aSquareInTheModelStaysASquareOnTheScreen() {
        for (int width = 360; width <= 1440; width += 360) {
            for (int height = 640; height <= 2560; height += 640) {
                final ModelFraming.Frame frame =
                        ModelFraming.frame(BOUNDS, width, height, 1.0f, 0f, 0f);
                final float[] left = frame.apply(-1.0f, 0.0f);
                final float[] right = frame.apply(1.0f, 0.0f);
                final float[] bottom = frame.apply(0.0f, -1.0f);
                final float[] top = frame.apply(0.0f, 1.0f);
                // Одна и та же длина в модели (два юнита) должна дать ровно столько же пикселей
                // по обеим осям.
                final float pixelsX = Math.abs(right[0] - left[0]) * width * 0.5f;
                final float pixelsY = Math.abs(top[1] - bottom[1]) * height * 0.5f;
                assertEquals("квадрат перекосило на экране " + width + "x" + height,
                        pixelsX, pixelsY, 0.5f);
            }
        }
    }

    @Test
    public void theCharacterFitsIntoTheScreen() {
        final ModelFraming.Frame frame = ModelFraming.frame(BOUNDS, 1080, 2400, 1.0f, 0f, 0f);
        final float[] topLeft = frame.apply(-1.0f, 2.0f);
        final float[] bottomRight = frame.apply(1.0f, -2.0f);
        assertTrue("левый край за экраном", topLeft[0] >= -1.001f);
        assertTrue("правый край за экраном", bottomRight[0] <= 1.001f);
        assertTrue("верх за экраном", topLeft[1] <= 1.001f);
        assertTrue("низ за экраном", bottomRight[1] >= -1.001f);
        // И при этом персонаж занимает заметную часть экрана, а не точку в середине.
        assertTrue("персонаж слишком мелкий по высоте", topLeft[1] - bottomRight[1] > 1.5f);
    }

    @Test
    public void zoomAndPanMoveTheCharacterWhereTheyShould() {
        final ModelFraming.Frame normal = ModelFraming.frame(BOUNDS, 1080, 2400, 1.0f, 0f, 0f);
        final ModelFraming.Frame zoomed = ModelFraming.frame(BOUNDS, 1080, 2400, 1.5f, 0f, 0f);
        assertEquals("зум не увеличил модель",
                normal.pixelsPerUnitX(1080) * 1.5f, zoomed.pixelsPerUnitX(1080), 1e-3f);

        final ModelFraming.Frame panned = ModelFraming.frame(BOUNDS, 1080, 2400, 1.0f, 0f, 0.25f);
        final float[] center = panned.apply(BOUNDS[0], BOUNDS[1]);
        assertEquals("панорама не подняла модель", 0.5f, center[1], 1e-3f);
    }

    @Test
    public void degenerateInputsDoNotProduceNaN() {
        final ModelFraming.Frame frame = ModelFraming.frame(new float[]{0f, 0f, 0f, 0f}, 0, 0, 0f, 0f, 0f);
        assertTrue("масштаб NaN", !Float.isNaN(frame.scaleX) && !Float.isNaN(frame.scaleY));
        assertTrue("сдвиг NaN", !Float.isNaN(frame.translateX) && !Float.isNaN(frame.translateY));
        assertTrue("нулевая модель должна всё равно попасть в кадр", frame.scaleX > 0f);
    }
}
