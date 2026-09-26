package com.echidna.studio.track;

/**
 * Решение о том, переворачивать ли кадр камеры.
 *
 * <p>Часть телефонов отдаёт картинку вверх ногами, и раньше приложение переворачивало камеру само,
 * как только лицо находилось на перевёрнутом кадре. Этого мало: детектор находит лицо и на
 * перевёрнутой картинке, поэтому одно ложное срабатывание оставляло камеру вверх ногами до конца
 * сеанса. Здесь сравнивается окно кадров: камера поворачивается только тогда, когда на обычных
 * кадрах лицо не находится НИ РАЗУ, а на перевёрнутых - находится уверенно.</p>
 *
 * <p>Класс ничего не знает ни про Android, ни про камеру, поэтому проверяется тестом.</p>
 */
public final class FlipDecision {
    /** Сколько перевёрнутых кадров должно быть в окне, чтобы вывод был осмысленным. */
    public static final int MIN_FLIPPED_FRAMES = 6;
    /** Сколько обычных кадров должно быть в окне. */
    public static final int MIN_NORMAL_FRAMES = 4;
    /** Сколько раз лицо должно найтись на перевёрнутых кадрах. */
    public static final int MIN_FLIPPED_HITS = 3;
    /** После переворота проверки замолкают: второй раз поворачивать нечего. */
    public static final long QUIET_AFTER_FLIP_MS = 10_000L;

    private long lastFlipMs = Long.MIN_VALUE / 2;

    /**
     * Итог окна наблюдений.
     *
     * @param normalFrames  сколько кадров отправлено в обычном положении
     * @param normalHits    на скольких из них найдено лицо
     * @param flippedFrames сколько кадров отправлено перевёрнутыми
     * @param flippedHits   на скольких из них найдено лицо
     * @param nowMs         текущее время
     * @return true, если камеру нужно повернуть на 180 градусов
     */
    public boolean evaluate(int normalFrames, int normalHits, int flippedFrames, int flippedHits,
                            long nowMs) {
        if (nowMs - lastFlipMs < QUIET_AFTER_FLIP_MS) {
            return false;
        }
        if (normalFrames < MIN_NORMAL_FRAMES || flippedFrames < MIN_FLIPPED_FRAMES) {
            return false;
        }
        // Лицо находится в обычном положении: кадр не перевёрнут, чем бы ни казался перевёрнутый.
        if (normalHits > 0) {
            return false;
        }
        return flippedHits >= MIN_FLIPPED_HITS;
    }

    /** Запоминает, что камера только что повёрнута: следующий переворот не раньше, чем через 10 с. */
    public void onFlipped(long nowMs) {
        lastFlipMs = nowMs;
    }

    /** Сколько времени прошло с последнего переворота. */
    public long sinceLastFlip(long nowMs) {
        return nowMs - lastFlipMs;
    }
}
