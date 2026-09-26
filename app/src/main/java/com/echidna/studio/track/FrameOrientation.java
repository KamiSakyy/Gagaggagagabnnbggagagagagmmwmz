package com.echidna.studio.track;

/**
 * Решение о том, вверх ли ногами приходит кадр, по самому лицу.
 *
 * <p>Производители вешают фронтальный сенсор по-разному, и правило {@code SENSOR_ORIENTATION}
 * описывает не все телефоны: у части моделей картинка приходит перевёрнутой, и никакая арифметика
 * этого не подскажет. Зато это видно по лицу: у человека глаза выше рта, а если кадр стоит вверх
 * ногами - ниже. Здесь копятся такие наблюдения, и когда лицо несколько раз подряд оказалось
 * «наоборот», кадр доворачивается на 180 градусов.</p>
 *
 * <p>Правило нарочно осторожное: кадр поворачивается только по уверенному большинству наблюдений и
 * не более двух раз за сеанс, чтобы ошибочное решение не гоняло картинку туда-сюда. Класс не знает
 * ни про Android, ни про камеру, поэтому проверяется тестом.</p>
 */
public final class FrameOrientation {

    /** Ничего менять не нужно: кадр стоит ровно. */
    public static final int KEEP = 0;
    /** Кадр перевёрнут: нужен доворот на 180 градусов. */
    public static final int FLIP = 1;
    /** Наблюдений мало или они противоречат друг другу. */
    public static final int UNSURE = -1;

    /** Сколько наблюдений нужно, прежде чем делать вывод. */
    public static final int MIN_VOTES = 6;
    /** Какая доля наблюдений должна говорить об одном и том же. */
    private static final float MAJORITY = 0.75f;
    /** Больше двух раз за сеанс кадр не трогаем: это уже не исправление, а дрожание. */
    public static final int MAX_FLIPS = 2;

    private int upright;
    private int inverted;
    private int flips;
    private long lastFlipMs = Long.MIN_VALUE / 2;
    /** Сколько ждать после поворота, прежде чем снова что-то решать. */
    private static final long QUIET_AFTER_FLIP_MS = 1500L;

    /** Записывает одно наблюдение: лицо на кадре стоит правильно или вверх ногами. */
    public void record(boolean uprightFace) {
        if (uprightFace) {
            upright++;
        } else {
            inverted++;
        }
    }

    /**
     * Что делать с кадром по накопленным наблюдениям.
     *
     * @param nowMs текущее время
     * @return {@link #FLIP}, {@link #KEEP} или {@link #UNSURE}
     */
    public int decide(long nowMs) {
        if (flips >= MAX_FLIPS || nowMs - lastFlipMs < QUIET_AFTER_FLIP_MS) {
            return UNSURE;
        }
        final int total = upright + inverted;
        if (total < MIN_VOTES) {
            return UNSURE;
        }
        final int needed = (int) Math.ceil(total * MAJORITY);
        if (inverted >= needed) {
            return FLIP;
        }
        if (upright >= needed) {
            return KEEP;
        }
        return UNSURE;
    }

    /** Кадр повёрнут: счётчики начинаются заново, иначе решение повторится на тех же числах. */
    public void onFlipped(long nowMs) {
        flips++;
        upright = 0;
        inverted = 0;
        lastFlipMs = nowMs;
    }

    /** Кадр оказался ровным: наблюдения больше не копятся. */
    public void onConfirmedUpright() {
        upright = 0;
        inverted = 0;
    }

    public void reset() {
        upright = 0;
        inverted = 0;
        flips = 0;
        lastFlipMs = Long.MIN_VALUE / 2;
    }

    /** Сколько раз кадр уже доворачивался: видно в отчёте. */
    public int flips() {
        return flips;
    }

    public int uprightVotes() {
        return upright;
    }

    public int invertedVotes() {
        return inverted;
    }
}
