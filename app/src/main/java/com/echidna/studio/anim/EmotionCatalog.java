package com.echidna.studio.anim;

import com.echidna.studio.track.EmotionDetector;

import java.util.List;

/**
 * Подбор выражения лица под распознанную эмоцию.
 *
 * <p>Процедурная мимика двигает бровями, глазами и губами у любой модели, но авторские выражения
 * выглядят богаче: у Нахиды это звёзды в глазах, полуприкрытые веки, румянец. Здесь для каждой
 * эмоции задан список подходящих названий, и берётся первое, которое у модели действительно есть.
 * Названия сравниваются по началу строки, потому что у ригов Эмилии к имени добавляется суффикс
 * («egao_w», «ikaru02»).</p>
 *
 * <p>Класс без обращения к Android: таблицу соответствий проверяет тест, сверяясь с настоящими
 * именами выражений и движений в сборке.</p>
 */
public final class EmotionCatalog {

    private EmotionCatalog() {
    }

    /**
     * Названия-кандидаты для эмоции, в порядке предпочтения.
     *
     * <p>Пустой список значит, что показывать нечего: для спокойствия выражение не нужно.</p>
     */
    public static String[] candidates(int emotion) {
        switch (emotion) {
            case EmotionDetector.JOY:
                return new String[]{"Happy1", "egao", "hohoemu", "kusa"};
            case EmotionDetector.DELIGHT:
                return new String[]{"StarEye", "Happy1", "egao02", "kusa"};
            case EmotionDetector.SURPRISE:
                return new String[]{"StarEye", "odoroku", "bikkuri"};
            case EmotionDetector.SADNESS:
                return new String[]{"Sad1", "Sad2", "kanashimu", "uru", "tameiki"};
            case EmotionDetector.ANGER:
                return new String[]{"Angry", "ikaru", "punpun"};
            case EmotionDetector.SHY:
                return new String[]{"Shy", "shy_normal", "tereru", "cheek_on"};
            case EmotionDetector.THINKING:
                return new String[]{"Halfeyes", "kangaeru", "shinken", "sumashi"};
            case EmotionDetector.TIRED:
                return new String[]{"Halfeyes", "nedaru", "tameiki"};
            default:
                return new String[0];
        }
    }

    /**
     * Имя выражения или движения под эмоцию.
     *
     * @param known названия, которые есть у модели: выражения у объёмных ригов, движения у остальных
     * @return подходящее название или {@code null}, если показывать нечего
     */
    public static String expressionFor(int emotion, List<String> known) {
        if (known == null || known.isEmpty()) {
            return null;
        }
        final String[] candidates = candidates(emotion);
        // Три прохода, потому что имена у моделей разные: у объёмных ригов это чистое «Happy1»,
        // у ригов Эмилии - «act_hohoemu_w», у Ехидны - «face_egao». Сначала ищется точное имя,
        // потом имя с приставкой сцены (face_, act_), и только в конце - любое вхождение.
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < candidates.length; i++) {
                final String candidate = candidates[i];
                String best = null;
                for (int k = 0; k < known.size(); k++) {
                    final String name = known.get(k);
                    final boolean matches;
                    if (pass == 0) {
                        matches = name.equals(candidate);
                    } else if (pass == 1) {
                        matches = name.startsWith(candidate)
                                || name.startsWith("face_" + candidate)
                                || name.startsWith("act_" + candidate);
                    } else {
                        matches = name.contains(candidate);
                    }
                    // Из подходящих имён предпочитается основное движение: рядом с ним у ригов
                    // лежит версия для говорящего рта с суффиксом «_w», и она длиннее.
                    final boolean plain = !name.endsWith("_w");
                    if (matches && (best == null
                            || (plain && best.endsWith("_w")))) {
                        best = name;
                    }
                }
                if (best != null) {
                    return best;
                }
            }
        }
        return null;
    }

    /** Русское название эмоции: то же, что показывает детектор. */
    public static String title(int emotion) {
        return EmotionDetector.name(emotion);
    }
}
