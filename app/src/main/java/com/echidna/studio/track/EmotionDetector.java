package com.echidna.studio.track;

/**
 * Понимание эмоций по лицу.
 *
 * <p>Трекер лица отдаёт 52 коэффициента движения мышц: уголки рта, брови, прищур, сжатые губы,
 * взгляд в сторону. По отдельности это просто числа, но вместе они складываются в выражение лица,
 * которое человек узнаёт мгновенно. Здесь эти числа превращаются в семь понятных состояний -
 * радость, удивление, злость, грусть, смущение, задумчивость, усталость - и в силу каждого из
 * них от нуля до единицы.</p>
 *
 * <p>Класс ничего не знает ни про Android, ни про камеру: на вход идут сигналы лица, на выходе
 * название эмоции. Поэтому его поведение закреплено тестами.</p>
 */
public final class EmotionDetector {

    public static final int NEUTRAL = 0;
    public static final int JOY = 1;
    public static final int SURPRISE = 2;
    public static final int ANGER = 3;
    public static final int SADNESS = 4;
    public static final int SHY = 5;
    public static final int THINKING = 6;
    public static final int TIRED = 7;
    /** Сколько всего состояний: по этому числу заводятся таблицы. */
    public static final int COUNT = 8;
    /** Восторг: та же радость, но с улыбкой во весь рот и прищуренными глазами. */
    public static final int DELIGHT = 8;

    private static final String[] NAMES = {
            "спокойствие", "радость", "удивление", "злость", "грусть",
            "смущение", "задумчивость", "усталость", "восторг"
    };

    /**
     * Порог, с которого эмоция считается показанной.
     *
     * <p>Ниже него лицо считается спокойным: слабые движения мышц есть всегда, и без порога модель
     * жила бы в вечной «радости» или «злости».</p>
     */
    public static final float SHOW_THRESHOLD = 0.22f;
    /** Насколько сильнее должна быть новая эмоция, чтобы вытеснить текущую. */
    private static final float SWITCH_MARGIN = 0.05f;
    /** Сколько эмоция держится, прежде чем её можно сменить: защита от дрожания. */
    private static final float MIN_HOLD = 0.28f;
    /** Время сглаживания силы эмоции, секунды. */
    private static final float SMOOTH = 0.07f;

    private final float[] level = new float[COUNT];
    private int current = NEUTRAL;
    private int pending = NEUTRAL;
    private float pendingFor;
    private float held;
    private float intensity;
    private float lastJoy;
    private float lastSadness;
    private float lastAnger;
    private float lastSurprise;

    /** Одна эмоция: номер и её сила в последнем кадре. */
    public int emotion() {
        return current;
    }

    /** Сила текущей эмоции, 0..1: по ней видно, насколько она выражена. */
    public float intensity() {
        return intensity;
    }

    /** Сила радости в последнем кадре: нужна улыбке, даже когда лицо спокойно. */
    public float joy() {
        return lastJoy;
    }

    public float sadness() {
        return lastSadness;
    }

    public float anger() {
        return lastAnger;
    }

    public float surprise() {
        return lastSurprise;
    }

    /** Сила любой из семи эмоций по её номеру. */
    public float levelOf(int emotion) {
        if (emotion == DELIGHT) {
            return level[JOY];
        }
        if (emotion < 0 || emotion >= COUNT) {
            return 0.0f;
        }
        return level[emotion];
    }

    /** Русское название состояния. */
    public static String name(int emotion) {
        if (emotion < 0 || emotion >= NAMES.length) {
            return NAMES[NEUTRAL];
        }
        return NAMES[emotion];
    }

    /** Название текущей эмоции. */
    public String name() {
        return name(current);
    }

    /** True когда лицо выражает что-то, кроме спокойствия. */
    public boolean expressive() {
        return current != NEUTRAL && intensity >= SHOW_THRESHOLD;
    }

    public void reset() {
        for (int i = 0; i < COUNT; i++) {
            level[i] = 0.0f;
        }
        current = NEUTRAL;
        pending = NEUTRAL;
        pendingFor = 0.0f;
        held = 0.0f;
        intensity = 0.0f;
        lastJoy = 0.0f;
        lastSadness = 0.0f;
        lastAnger = 0.0f;
        lastSurprise = 0.0f;
    }

    /**
     * Считает эмоции по одному кадру лица.
     *
     * @param s  сигналы лица: коэффициенты мимики и раскрытость глаз
     * @param dt секунды с прошлого кадра
     */
    public void update(FaceSignals s, float dt) {
        if (s == null || !s.blendshapes) {
            // Без коэффициентов мимики (ML Kit) лицо всё равно двигается: улыбка и рот приходят
            // отдельными полями, поэтому эмоции берутся из них.
            updateFromPlainSignals(s, dt);
            return;
        }
        if (dt <= 0.0f || dt > 0.5f) {
            dt = 1.0f / 60.0f;
        }

        // Улыбка берётся из двух источников: из коэффициентов мимики и из измерения по точкам
        // лица. Второе надёжнее на слабых выражениях - там, где сеть ещё видит «спокойное лицо»,
        // поднятые уголки рта уже заметны.
        final float smile = Math.max((s.blendMouthSmileLeft + s.blendMouthSmileRight) * 0.5f,
                Math.max(0.0f, s.smileGeo) * 1.15f);
        final float dimple = (s.blendMouthDimpleLeft + s.blendMouthDimpleRight) * 0.5f;
        final float cheekSquint = (s.blendCheekSquintLeft + s.blendCheekSquintRight) * 0.5f;
        final float squint = (s.blendEyeSquintLeft + s.blendEyeSquintRight) * 0.5f;
        final float browInner = s.blendBrowInnerUp;
        final float browDown = (s.blendBrowDownLeft + s.blendBrowDownRight) * 0.5f;
        final float browOuter = (s.blendBrowOuterUpLeft + s.blendBrowOuterUpRight) * 0.5f;
        final float frown = (s.blendMouthFrownLeft + s.blendMouthFrownRight) * 0.5f;
        final float press = (s.blendMouthPressLeft + s.blendMouthPressRight) * 0.5f;
        final float lowerDown = (s.blendMouthLowerDownLeft + s.blendMouthLowerDownRight) * 0.5f;
        final float sneer = (s.blendNoseSneerLeft + s.blendNoseSneerRight) * 0.5f;
        final float stretch = (s.blendMouthStretchLeft + s.blendMouthStretchRight) * 0.5f;
        // Брови: к коэффициентам добавляется измеренная высота бровей над глазами.
        final float browGeoUp = s.geometric ? Math.max(0.0f, s.browGeo) * 0.8f : 0.0f;
        // Опущенные брови и уголки рта вниз измерение видит так же хорошо, как поднятые: это
        // хмурый взгляд и недовольство, и без них лицо модели оставалось спокойным.
        final float browGeoDown = s.geometric ? Math.max(0.0f, -s.browGeo) * 0.7f : 0.0f;
        final float frownGeo = s.geometric ? Math.max(0.0f, -s.smileGeo) * 0.8f : 0.0f;
        final float eyeWide = (s.blendEyeWideLeft + s.blendEyeWideRight) * 0.5f;
        final float lookAside = (s.blendEyeLookOutLeft + s.blendEyeLookOutRight
                + s.blendEyeLookInLeft + s.blendEyeLookInRight) * 0.25f;
        final float lookDown = (s.blendEyeLookDownLeft + s.blendEyeLookDownRight) * 0.5f;
        final float lookUp = (s.blendEyeLookUpLeft + s.blendEyeLookUpRight) * 0.5f;
        final float jaw = s.blendJawOpen;
        final float pucker = s.blendMouthPucker;
        final float roll = Math.abs(s.roll);
        // Раскрытость глаз: уставший человек держит глаза полуприкрытыми.
        final float eyesClosed = 1.0f - Math.min(s.eyeLeft, s.eyeRight);

        // Радость: улыбка и поднятые щёки вместе с прищуром - именно так выглядит настоящая улыбка.
        final float joy = clamp(smile * 0.85f + dimple * 0.35f + cheekSquint * 0.25f + squint * 0.15f);
        // Восторг: то же самое, но шире - берётся как усиленная радость.
        final float delight = clamp((smile - 0.42f) * 2.1f + cheekSquint * 0.4f + squint * 0.3f
                + (s.geometric ? Math.max(0.0f, s.smileGeo - 0.5f) * 1.5f : 0.0f));
        // Удивление: брови вскинуты (внутренние и внешние концы), глаза широко, рот открыт.
        final float surprise = clamp(browInner * 0.45f + browOuter * 0.45f + browGeoUp * 0.6f
                + eyeWide * 0.45f + jaw * 0.5f
                + (s.geometric ? s.mouthOpenGeo * 0.3f : 0.0f) + lookUp * 0.15f);
        // Злость: брови сведены, губы сжаты, уголки рта вниз, нос сморщен.
        final float anger = clamp(browDown * 0.8f + press * 0.45f + frown * 0.4f
                + sneer * 0.35f + stretch * 0.2f + browGeoDown * 0.8f + frownGeo * 0.5f
                - smile * 0.5f);
        // Грусть: внутренние концы бровей вверх, уголки рта и нижняя губа вниз, взгляд вниз.
        final float sadness = clamp(browInner * 0.75f + frown * 0.5f + lowerDown * 0.45f
                + lookDown * 0.35f + s.blendMouthShrugLower * 0.25f + frownGeo * 0.6f
                - smile * 0.6f);
        // Смущение: лёгкая улыбка, вскинутые внутренние брови, взгляд отведён в сторону, румянец.
        final float shy = clamp(browInner * 0.45f + smile * 0.4f + lookAside * 0.5f
                + cheekSquint * 0.3f + roll * 0.01f - jaw * 0.3f - browOuter * 0.3f
                - Math.max(0.0f, s.browGeo) * 0.4f);
        // Задумчивость: брови чуть сведены, губы поджаты, взгляд уходит вверх-в сторону.
        final float thinking = clamp(browDown * 0.5f + press * 0.5f + pucker * 0.4f
                + lookAside * 0.35f + lookUp * 0.3f + s.blendMouthClose * 0.25f
                - smile * 0.7f - jaw * 0.4f);
        // Усталость: глаза прикрыты, рот приоткрыт (зевок), брови опущены.
        final float tired = clamp(eyesClosed * 1.6f + jaw * 0.35f + browDown * 0.2f
                + s.blendMouthRollLower * 0.2f - smile * 0.5f - eyeWide * 0.6f);

        applyLevels(dt, joy, surprise, anger, sadness, shy, thinking, tired);
        lastJoy = Math.max(level[JOY], delight * 0.9f);
        lastSurprise = level[SURPRISE];
        lastAnger = level[ANGER];
        lastSadness = level[SADNESS];
        // Восторг показывается как радость, но сильнее: по нему модель играет звезду в глазах.
        final float shown = level[JOY];
        if (delight > 0.55f && shown > 0.25f) {
            level[JOY] = Math.min(1.0f, Math.max(level[JOY], delight));
        }
    }

    /** Эмоции по простым сигналам: улыбка, рот, глаза. Ветка для ML Kit без мимики. */
    private void updateFromPlainSignals(FaceSignals s, float dt) {
        if (s == null || !s.found) {
            applyLevels(dt <= 0.0f ? 1.0f / 60.0f : dt, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
            return;
        }
        if (dt <= 0.0f || dt > 0.5f) {
            dt = 1.0f / 60.0f;
        }
        final float eyesClosed = 1.0f - Math.min(s.eyeLeft, s.eyeRight);
        // Без коэффициентов мимики остаются улыбка, рот и измерения по точкам лица. Их хватает,
        // чтобы понимать радость, удивление, злость и грусть: измерение видит и поднятые, и
        // сведённые брови, и опущенные уголки рта, а знак улыбки показывает, доволен человек или нет.
        final float smile = Math.max(s.smile, s.geometric ? Math.max(0.0f, s.smileGeo) : 0.0f);
        final float frownGeo = s.geometric ? Math.max(0.0f, -s.smileGeo) : 0.0f;
        final float browUp = s.geometric ? Math.max(0.0f, s.browGeo) : 0.0f;
        final float browLow = s.geometric ? Math.max(0.0f, -s.browGeo) : 0.0f;
        final float open = Math.max(s.mouthOpen, s.geometric ? s.mouthOpenGeo : 0.0f);
        applyLevels(dt,
                clamp(smile * 1.2f),
                clamp(open * 1.1f - smile * 0.5f + browUp * 0.6f),
                clamp(browLow * 0.9f + frownGeo * 0.5f),
                clamp(frownGeo * 0.9f + (1.0f - s.eyeLeft) * 0.3f),
                clamp(smile * 0.5f),
                clamp(browLow * 0.4f),
                clamp(eyesClosed * 1.4f));
        lastJoy = level[JOY];
        lastSurprise = level[SURPRISE];
        lastAnger = 0.0f;
        lastSadness = level[SADNESS];
    }

    private void applyLevels(float dt, float joy, float surprise, float anger, float sadness,
                             float shy, float thinking, float tired) {
        final float[] raw = {0.0f, joy, surprise, anger, sadness, shy, thinking, tired};
        final float step = Math.min(1.0f, dt / SMOOTH);
        int best = NEUTRAL;
        for (int i = 0; i < COUNT; i++) {
            level[i] += (raw[i] - level[i]) * step;
            if (level[i] > level[best]) {
                best = i;
            }
        }
        if (level[best] < SHOW_THRESHOLD) {
            best = NEUTRAL;
        }

        held += dt;
        if (best == current) {
            pending = best;
            pendingFor = 0.0f;
            intensity = level[current];
            return;
        }
        // Новая эмоция должна продержаться, прежде чем заменит текущую: иначе дрожь мышц давала бы
        // мигание «радость-удивление-радость» и модель дёргалась бы вместе с ней.
        if (best == pending) {
            pendingFor += dt;
        } else {
            pending = best;
            pendingFor = 0.0f;
        }
        final boolean stronger = level[best] >= level[current] + SWITCH_MARGIN;
        if (pendingFor >= MIN_HOLD && held >= MIN_HOLD && (stronger || level[current] < SHOW_THRESHOLD)) {
            current = best;
            held = 0.0f;
            pendingFor = 0.0f;
        }
        intensity = level[current];
    }

    private static float clamp(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 0.0f;
        }
        return value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
    }
}
