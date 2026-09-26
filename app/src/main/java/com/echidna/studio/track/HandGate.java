package com.echidna.studio.track;

/**
 * Подтверждение руки перед тем, как модель её повторит.
 *
 * <p>Главная жалоба на камеру - «она думает, что я поднял руку, а я не поднимал»: трекер иногда
 * находит кисть на секунду, принимает за руку локоть или участок фона, и модель дёргает рукой.
 * Здесь наблюдения проходят проверку: рука считается видимой только после нескольких кадров подряд,
 * а движение вверх - только если оно держится достаточно долго. Случайный кадр не проходит.</p>
 *
 * <p>Проверка нужна и в другую сторону: когда человек опускает руку, модель должна опустить её
 * быстро, без залипания. Поэтому «рука исчезла» подтверждается коротким окном, а «рука поднялась» -
 * длинным. Класс не знает ни про Android, ни про камеру и проверяется тестом.</p>
 */
public final class HandGate {

    /** Сколько времени рука должна быть видна, прежде чем модель её покажет. */
    private static final float SEEN_CONFIRM = 0.08f;
    /** Сколько времени рука должна отсутствовать, чтобы модель её отпустила. */
    private static final float GONE_CONFIRM = 0.12f;
    /** С какой высоты подъём считается настоящим: ниже этого рука просто лежит на столе. */
    public static final float LIFT_THRESHOLD = 0.35f;
    /** Сколько времени подъём должен держаться, прежде чем модель поднимет руку. */
    private static final float LIFT_CONFIRM = 0.14f;
    /** Резкое движение вниз подтверждается сразу: опущенная рука не должна залипать. */
    private static final float DROP_CONFIRM = 0.04f;

    /** Что известно про эту руку сейчас. */
    public static final class State {
        /** Видна ли рука достаточно долго, чтобы её показывать. */
        public boolean visible;
        /** Подъём руки, подтверждённый по времени: 0..1. */
        public float lift;
        /** Счёт пальцев, подтверждённый по времени: -1, если рука не видна. */
        public int fingers = -1;
    }

    private final State state = new State();

    private float seenFor;
    private float goneFor;
    private float liftFor;
    private float dropFor;
    private boolean confirmed;
    private float liftValue;
    private int lastLiftFingers = -1;

    public void reset() {
        seenFor = 0.0f;
        goneFor = 0.0f;
        liftFor = 0.0f;
        dropFor = 0.0f;
        confirmed = false;
        liftValue = 0.0f;
        lastLiftFingers = -1;
        state.visible = false;
        state.lift = 0.0f;
        state.fingers = -1;
    }

    /**
     * Один кадр наблюдения.
     *
     * @param seen      нашёл ли трекер кисть на этом кадре
     * @param lift      высота кисти относительно подбородка, 0..1 (как её считает хаб)
     * @param fingers   сколько пальцев показано, -1 если неизвестно
     * @param dt        секунды с прошлого кадра
     */
    public State update(boolean seen, float lift, int fingers, float dt) {
        if (dt <= 0.0f || dt > 0.5f) {
            dt = 1.0f / 60.0f;
        }
        if (seen) {
            seenFor += dt;
            goneFor = 0.0f;
            if (seenFor >= SEEN_CONFIRM) {
                confirmed = true;
            }
        } else {
            goneFor += dt;
            seenFor = 0.0f;
            if (goneFor >= GONE_CONFIRM) {
                confirmed = false;
                lastLiftFingers = -1;
            }
        }

        final float wanted = seen ? clamp01(lift) : 0.0f;
        if (wanted > liftValue + 0.02f) {
            // Подъём: подтверждается по времени, чтобы дрожь трекера не поднимала руку модели.
            liftFor += dt;
            dropFor = 0.0f;
            if (liftFor >= LIFT_CONFIRM && wanted >= LIFT_THRESHOLD) {
                liftValue = wanted;
            } else if (liftFor >= LIFT_CONFIRM && wanted > liftValue) {
                liftValue = wanted;
            }
        } else if (wanted < liftValue - 0.02f) {
            // Опускание: подтверждается сразу, рука не должна залипать в воздухе.
            dropFor += dt;
            liftFor = 0.0f;
            if (dropFor >= DROP_CONFIRM || wanted <= 0.05f) {
                liftValue = wanted;
            }
        } else {
            liftFor = Math.max(0.0f, liftFor - dt);
            dropFor = 0.0f;
        }

        if (seen && fingers >= 0 && confirmed) {
            lastLiftFingers = fingers;
        }
        state.visible = confirmed;
        state.lift = confirmed ? liftValue : 0.0f;
        state.fingers = confirmed ? lastLiftFingers : -1;
        return state;
    }

    /** Текущее состояние без обновления: нужно тестам и отчёту. */
    public State state() {
        return state;
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 0.0f;
        }
        return value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
    }
}
