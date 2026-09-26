package com.echidna.studio.anim;

import java.util.List;

/**
 * Подбирает выражение лица по названию движения, которое попросило шоу.
 *
 * <p>У Live2D-ригов настроение шоу выражается файлом движения: «act_egao» — это улыбка. У
 * объёмного персонажа (VRM) и у ригов вроде Нахиды движений нет вообще — есть только выражения
 * лица. Чтобы шоу не превращалось для них в неподвижную картинку, название движения переводится в
 * ближайшее выражение: «act_egao02» становится {@code happy}, «face_ikaru» — {@code angry},
 * «face_bikkuri» — {@code surprised}.</p>
 *
 * <p>Правила записаны парами «слова в названии | желаемые выражения»; внутри пары варианты идут от
 * самого точного к самому общему, поэтому персонаж с нестандартным набором выражений всё равно
 * получает осмысленную мимику.</p>
 */
public final class ExpressionPicker {

    /** Ключевые слова названия движения и выражения, которыми это настроение играется. */
    private static final String[][] RULES = {
            {"egao|hohoemu|happy|smile|warai|warau|niko|yorokobi",
                    "happy|fun|joy|smile|egao"},
            {"ikaru|okoru|angry|punpun|mukatsuku|ikari|funny",
                    "angry|anger|ikari"},
            {"kanashimu|nayamu|uru|sad|cry|shizumu|tameiki|metozi|konwaku|shinken",
                    "sad|sorrow|cry|kanasimi"},
            {"bikkuri|odoroku|surprise|tamagete",
                    "surprised|surprise|odoroki"},
            {"tereru|shy|hazukashi|tere",
                    "relaxed|shy|tere|happy"},
            {"kouyou|talk|shaberi|hanasu|serif|utau",
                    "aa|ah|oh|happy"},
            {"nedaru|nemuru|nebore|sleep|yururi",
                    "relaxed|closed|sleep|happy"},
            {"doya|sumashi|proud|smug|kangaeru|think",
                    "neutral|normal|relaxed"},
            {"unazuku|nod|kyoton|kubi|tilt|kashige|tomadoi",
                    "neutral|lookDown|normal"},
    };

    /** Выражения, которые подходят почти любому настроению; берутся, когда правила не сработали. */
    private static final String[] FALLBACK = {"happy", "relaxed", "neutral", "fun", "normal"};

    /** Каналы, которые сами по себе не выражают настроение: ими управляют моргание и взгляд. */
    private static final String[] NON_MOOD = {"blink", "lookup", "lookdown", "lookleft",
            "lookright", "eye", "brow", "mouth"};

    private ExpressionPicker() {
    }

    /**
     * Выражение персонажа, которое играет роль {@code wanted}.
     *
     * @param available выражения модели (для VRM это имена пресетов, для рига — имена из
     *                  {@code expressions.json})
     * @param wanted    название движения, которое попросило шоу; может быть null
     * @return имя существующего выражения или null, если у персонажа нет мимики
     */
    public static String resolve(List<String> available, String wanted) {
        if (available == null || available.isEmpty()) {
            return null;
        }
        final String target = MotionPicker.normalize(wanted).toLowerCase();
        if (!target.isEmpty()) {
            for (int r = 0; r < RULES.length; r++) {
                if (!containsAny(target, RULES[r][0])) {
                    continue;
                }
                final String found = firstOf(available, RULES[r][1]);
                if (found != null) {
                    return found;
                }
            }
        }
        final String fallback = firstOf(available, join(FALLBACK));
        if (fallback != null) {
            return fallback;
        }
        // Совсем незнакомый набор выражений: берём первое, что не является взглядом или веками.
        for (int i = 0; i < available.size(); i++) {
            final String candidate = MotionPicker.normalize(available.get(i)).toLowerCase();
            if (!containsAny(candidate, join(NON_MOOD))) {
                return available.get(i);
            }
        }
        return null;
    }

    /** Первое из списка {@code wanted} (через «|»), которое существует у персонажа. */
    private static String firstOf(List<String> available, String wanted) {
        final String[] variants = wanted.split("\\|");
        for (int v = 0; v < variants.length; v++) {
            final String variant = variants[v].trim();
            for (int i = 0; i < available.size(); i++) {
                if (MotionPicker.normalize(available.get(i)).toLowerCase().equals(variant)) {
                    return available.get(i);
                }
            }
        }
        return null;
    }

    private static boolean containsAny(String haystack, String keywords) {
        final String[] parts = keywords.split("\\|");
        for (int i = 0; i < parts.length; i++) {
            if (haystack.contains(parts[i])) {
                return true;
            }
        }
        return false;
    }

    private static String join(String[] values) {
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append('|');
            }
            builder.append(values[i]);
        }
        return builder.toString();
    }
}
