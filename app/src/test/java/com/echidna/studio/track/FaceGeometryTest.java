package com.echidna.studio.track;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Мимика по точкам лица и определение перевёрнутого кадра.
 *
 * <p>Точки лица задаются искусственно: тест рисует «лицо» нужной формы и проверяет, что измерение
 * видит улыбку, зевок, поднятые брови и сомкнутые веки, а перевёрнутое лицо распознаёт как
 * перевёрнутое. Именно на этом признаке держится автоматический доворот кадра.</p>
 */
public class FaceGeometryTest {

    /** Точка лица: те же поля, что читает FacePose. */
    public static final class Point {
        private final float x;
        private final float y;
        private final float z;
        private final float visibility;

        Point(float x, float y) {
            this(x, y, 0.0f, 1.0f);
        }

        Point(float x, float y, float z, float visibility) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.visibility = visibility;
        }

        public float x() {
            return x;
        }

        public float y() {
            return y;
        }

        public float z() {
            return z;
        }

        public float visibility() {
            return visibility;
        }
    }

    /** Лицо в разметке MediaPipe: лоб сверху, подбородок снизу, между ними глаза, нос и рот. */
    private static List<Point> face() {
        final List<Point> points = new ArrayList<Point>();
        for (int i = 0; i < 478; i++) {
            points.add(new Point(0.5f, 0.5f));
        }
        set(points, 10, 0.50f, 0.20f);   // лоб
        set(points, 152, 0.50f, 0.80f);  // подбородок
        set(points, 4, 0.50f, 0.48f);    // кончик носа
        // Глаза: веки на 42 % высоты лица, углы - по краям.
        set(points, 159, 0.42f, 0.42f);
        set(points, 145, 0.42f, 0.46f);
        set(points, 33, 0.36f, 0.44f);
        set(points, 133, 0.46f, 0.44f);
        set(points, 386, 0.58f, 0.42f);
        set(points, 374, 0.58f, 0.46f);
        set(points, 362, 0.54f, 0.44f);
        set(points, 263, 0.64f, 0.44f);
        // Брови: чуть выше глаз.
        set(points, 105, 0.42f, 0.38f);
        set(points, 334, 0.58f, 0.38f);
        // Рот: уголки и губы.
        set(points, 61, 0.44f, 0.60f);
        set(points, 291, 0.56f, 0.60f);
        set(points, 13, 0.50f, 0.60f);
        set(points, 14, 0.50f, 0.62f);
        set(points, 12, 0.50f, 0.605f);
        set(points, 15, 0.50f, 0.615f);
        return points;
    }

    private static void set(List<Point> points, int index, float x, float y) {
        points.set(index, new Point(x, y));
    }

    @Test
    public void anUprightFaceIsRecognisedAsUpright() {
        final FaceGeometry.Readings readings = FaceGeometry.read(face());
        assertNotNull(readings);
        assertTrue(readings.valid);
        assertTrue("глаза выше рта - кадр стоит ровно", readings.upright);
    }

    @Test
    public void anUpsideDownFaceIsRecognisedAsUpsideDown() {
        final List<Point> points = face();
        // Переворачиваем лицо: то, что было сверху, уходит вниз - так выглядит кадр вверх ногами.
        for (int i = 0; i < points.size(); i++) {
            final Point p = points.get(i);
            points.set(i, new Point(1.0f - p.x, 1.0f - p.y));
        }
        final FaceGeometry.Readings readings = FaceGeometry.read(points);
        assertNotNull(readings);
        assertFalse("глаза ниже рта - кадр перевёрнут", readings.upright);
    }

    @Test
    public void aSmileRaisesTheMouthCorners() {
        final List<Point> neutral = face();
        final FaceGeometry.Readings plain = FaceGeometry.read(neutral);
        assertNotNull(plain);

        final List<Point> smiling = face();
        // Улыбка: уголки рта поднимаются, рот становится шире.
        set(smiling, 61, 0.43f, 0.575f);
        set(smiling, 291, 0.57f, 0.575f);
        final FaceGeometry.Readings joy = FaceGeometry.read(smiling);
        assertNotNull(joy);
        assertTrue("улыбка должна быть видна измерением: " + joy.smile + " против " + plain.smile,
                joy.smile > plain.smile + 0.15f);
    }

    @Test
    public void anOpenMouthIsMeasured() {
        final FaceGeometry.Readings plain = FaceGeometry.read(face());
        final List<Point> open = face();
        set(open, 12, 0.50f, 0.57f);
        set(open, 15, 0.50f, 0.66f);
        final FaceGeometry.Readings yawn = FaceGeometry.read(open);
        assertNotNull(yawn);
        assertTrue("раскрытый рот должен быть виден: " + yawn.mouthOpen,
                yawn.mouthOpen > plain.mouthOpen + 0.2f);
    }

    @Test
    public void raisedBrowsAreMeasured() {
        final FaceGeometry.Readings plain = FaceGeometry.read(face());
        final List<Point> raised = face();
        set(raised, 105, 0.42f, 0.30f);
        set(raised, 334, 0.58f, 0.30f);
        final FaceGeometry.Readings surprise = FaceGeometry.read(raised);
        assertNotNull(surprise);
        assertTrue("поднятые брови должны быть видны: " + surprise.brow,
                surprise.brow > plain.brow + 0.1f);
    }

    @Test
    public void closedEyelidsAreMeasured() {
        final FaceGeometry.Readings plain = FaceGeometry.read(face());
        assertNotNull(plain);
        assertTrue("в покое глаз открыт: " + plain.eyeOpen, plain.eyeOpen > 0.5f);

        final List<Point> blink = face();
        set(blink, 159, 0.42f, 0.4405f);
        set(blink, 145, 0.42f, 0.4415f);
        set(blink, 386, 0.58f, 0.4405f);
        set(blink, 374, 0.58f, 0.4415f);
        final FaceGeometry.Readings shut = FaceGeometry.read(blink);
        assertNotNull(shut);
        assertTrue("сомкнутые веки должны быть видны: " + shut.eyeOpen, shut.eyeOpen < 0.2f);
    }

    @Test
    public void tooFewPointsGiveNoReadings() {
        assertNull("по трём точкам мерить нечего", FaceGeometry.read(new ArrayList<Point>()));
        final List<Point> few = new ArrayList<Point>();
        for (int i = 0; i < 10; i++) {
            few.add(new Point(0.5f, 0.5f));
        }
        assertNull(FaceGeometry.read(few));
    }
}
