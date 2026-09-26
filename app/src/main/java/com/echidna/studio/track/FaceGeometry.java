package com.echidna.studio.track;

import java.util.List;

/**
 * Мимика, измеренная по точкам лица.
 *
 * <p>Трекер отдаёт 478 точек лица, и по ним видно то же, что видит человек: улыбка - это поднятые
 * уголки рта, зевок - раскрытые губы, удивление - поднятые брови, моргание - сомкнутые веки. Эти
 * величины не зависят от того, насколько уверенно нейросеть распознала движение мышцы: там, где
 * коэффициенты мимики дают ноль, геометрия всё равно видит улыбку.</p>
 *
 * <p>Отсюда же берётся и решающий признак перевёрнутого кадра: у человека глаза выше рта, а если
 * кадр стоит вверх ногами - ниже. Класс не знает ни про Android, ни про MediaPipe, поэтому его
 * поведение закреплено тестами на искусственных лицах.</p>
 */
public final class FaceGeometry {

    // Точки лица в разметке MediaPipe: углы и середины глаз, рот, брови, подбородок и лоб.
    private static final int EYE_LEFT_UPPER = 159;
    private static final int EYE_LEFT_LOWER = 145;
    private static final int EYE_LEFT_OUTER = 33;
    private static final int EYE_LEFT_INNER = 133;
    private static final int EYE_RIGHT_UPPER = 386;
    private static final int EYE_RIGHT_LOWER = 374;
    private static final int EYE_RIGHT_INNER = 362;
    private static final int EYE_RIGHT_OUTER = 263;
    private static final int BROW_LEFT = 105;
    private static final int BROW_RIGHT = 334;
    private static final int MOUTH_LEFT = 61;
    private static final int MOUTH_RIGHT = 291;
    private static final int MOUTH_UPPER = 13;
    private static final int MOUTH_LOWER = 14;
    private static final int MOUTH_INNER_UPPER = 12;
    private static final int MOUTH_INNER_LOWER = 15;
    private static final int CHIN = 152;
    private static final int FOREHEAD = 10;
    private static final int NOSE = 4;

    /** Сколько точек должно быть в наборе, чтобы измерения имели смысл. */
    private static final int MIN_POINTS = 400;
    /** Раскрытость век в спокойном состоянии: по ней нормируется моргание. */
    private static final float EYE_OPEN_NEUTRAL = 0.30f;
    /** Раскрытость век при моргании: ниже этого глаз считается закрытым. */
    private static final float EYE_OPEN_SHUT = 0.10f;

    private FaceGeometry() {
    }

    /** Итог одного измерения: то, что уходит в сигналы лица. */
    public static final class Readings {
        public boolean valid;
        public boolean upright;
        public float smile;
        public float mouthOpen;
        public float brow;
        public float eyeOpen;
    }

    /**
     * Читает геометрию лица.
     *
     * @param landmarks точки лица из трекера: 478 штук в разметке MediaPipe
     * @return измерения или {@code null}, если точек мало и мерить нечего
     */
    public static Readings read(List<?> landmarks) {
        if (landmarks == null || landmarks.size() < MIN_POINTS) {
            return null;
        }
        final FacePose.Landmark leftUpper = FacePose.landmarkOf(landmarks, EYE_LEFT_UPPER);
        final FacePose.Landmark leftLower = FacePose.landmarkOf(landmarks, EYE_LEFT_LOWER);
        final FacePose.Landmark leftOuter = FacePose.landmarkOf(landmarks, EYE_LEFT_OUTER);
        final FacePose.Landmark leftInner = FacePose.landmarkOf(landmarks, EYE_LEFT_INNER);
        final FacePose.Landmark rightUpper = FacePose.landmarkOf(landmarks, EYE_RIGHT_UPPER);
        final FacePose.Landmark rightLower = FacePose.landmarkOf(landmarks, EYE_RIGHT_LOWER);
        final FacePose.Landmark rightInner = FacePose.landmarkOf(landmarks, EYE_RIGHT_INNER);
        final FacePose.Landmark rightOuter = FacePose.landmarkOf(landmarks, EYE_RIGHT_OUTER);
        final FacePose.Landmark browLeft = FacePose.landmarkOf(landmarks, BROW_LEFT);
        final FacePose.Landmark browRight = FacePose.landmarkOf(landmarks, BROW_RIGHT);
        final FacePose.Landmark mouthLeft = FacePose.landmarkOf(landmarks, MOUTH_LEFT);
        final FacePose.Landmark mouthRight = FacePose.landmarkOf(landmarks, MOUTH_RIGHT);
        final FacePose.Landmark mouthUpper = FacePose.landmarkOf(landmarks, MOUTH_UPPER);
        final FacePose.Landmark mouthLower = FacePose.landmarkOf(landmarks, MOUTH_LOWER);
        final FacePose.Landmark innerUpper = FacePose.landmarkOf(landmarks, MOUTH_INNER_UPPER);
        final FacePose.Landmark innerLower = FacePose.landmarkOf(landmarks, MOUTH_INNER_LOWER);
        final FacePose.Landmark chin = FacePose.landmarkOf(landmarks, CHIN);
        final FacePose.Landmark forehead = FacePose.landmarkOf(landmarks, FOREHEAD);
        final FacePose.Landmark nose = FacePose.landmarkOf(landmarks, NOSE);
        if (leftUpper == null || rightUpper == null || chin == null || forehead == null
                || mouthLeft == null || mouthRight == null || mouthUpper == null
                || mouthLower == null || nose == null || browLeft == null || browRight == null) {
            return null;
        }

        final float faceHeight = Math.abs(chin.y - forehead.y);
        final float faceWidth = Math.abs(distance(leftOuter, rightOuter));
        if (faceHeight < 0.001f || faceWidth < 0.001f) {
            return null;
        }

        final Readings out = new Readings();
        out.valid = true;

        // Вверх ли ногами кадр: у человека глаза выше рта. Голосуют сразу три пары точек, чтобы
        // одна неудачная точка не переворачивала картинку.
        int upVotes = 0;
        int downVotes = 0;
        final float eyeY = (leftUpper.y + rightUpper.y) * 0.5f;
        final float mouthY = (mouthUpper.y + mouthLower.y) * 0.5f;
        upVotes += eyeY < mouthY ? 1 : 0;
        downVotes += eyeY > mouthY ? 1 : 0;
        upVotes += forehead.y < chin.y ? 1 : 0;
        downVotes += forehead.y > chin.y ? 1 : 0;
        upVotes += nose.y < mouthY ? 1 : 0;
        downVotes += nose.y > mouthY ? 1 : 0;
        out.upright = upVotes > downVotes;

        // Улыбка: уголки рта поднялись к линии бровей относительно середины губ и рта стало шире.
        final float cornerY = (mouthLeft.y + mouthRight.y) * 0.5f;
        final float mouthCenterY = (mouthUpper.y + mouthLower.y) * 0.5f;
        final float lift = (mouthCenterY - cornerY) / faceHeight;
        final float width = Math.abs(mouthRight.x - mouthLeft.x) / faceWidth;
        out.smile = clamp((lift * 22.0f) + (width - 0.32f) * 1.6f, -1.0f, 1.0f);

        // Раскрытие рта: зазор между внутренними краями губ относительно высоты лица.
        final float gap = innerUpper != null && innerLower != null
                ? Math.abs(innerLower.y - innerUpper.y)
                : Math.abs(mouthLower.y - mouthUpper.y) * 0.6f;
        out.mouthOpen = clamp(gap / faceHeight * 9.0f, 0.0f, 1.0f);

        // Брови: как высоко они стоят над глазами относительно спокойного положения (5 % высоты).
        final float browGap = ((eyeY - browLeft.y) + (eyeY - browRight.y)) * 0.5f / faceHeight;
        out.brow = clamp((browGap - 0.055f) * 22.0f, -1.0f, 1.0f);

        // Веки: отношение высоты глаза к его ширине. В покое около 0.3, при моргании падает до нуля.
        final float leftRatio = ratio(leftUpper, leftLower, leftInner, leftOuter);
        final float rightRatio = ratio(rightUpper, rightLower, rightInner, rightOuter);
        final float ear = (leftRatio + rightRatio) * 0.5f;
        out.eyeOpen = clamp((ear - EYE_OPEN_SHUT) / (EYE_OPEN_NEUTRAL - EYE_OPEN_SHUT), 0.0f, 1.0f);

        return out;
    }

    /** Отношение высоты глаза к его ширине: стандартная мера раскрытости века. */
    private static float ratio(FacePose.Landmark upper, FacePose.Landmark lower,
                               FacePose.Landmark inner, FacePose.Landmark outer) {
        if (upper == null || lower == null || inner == null || outer == null) {
            return EYE_OPEN_NEUTRAL;
        }
        final float width = Math.abs(distance(inner, outer));
        if (width < 0.0001f) {
            return EYE_OPEN_NEUTRAL;
        }
        return Math.abs(upper.y - lower.y) / width;
    }

    private static float distance(FacePose.Landmark a, FacePose.Landmark b) {
        final float dx = a.x - b.x;
        final float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 0.0f;
        }
        return value < min ? min : (value > max ? max : value);
    }
}
