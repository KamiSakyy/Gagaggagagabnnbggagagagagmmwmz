package com.echidna.studio.anim;

import com.echidna.studio.track.EmotionDetector;

import java.util.List;

/**
 * Подбор выражения лица под распознанную эмоцию.
 *
 * <p>Процедурная мимика двигает бровями, глазами и губами, но у рига есть и готовые сцены: у
 * Эмилии-кролика это 125 движений с говорящими именами - «act_egao» (улыбка), «face_kanashimu»
 * (печаль), «act_punpun» (недовольство), «act_doro...», «face_uru» (слёзы). Здесь для каждой эмоции
 * задан список подходящих имён, и берётся первое, которое у модели действительно есть.</p>
 *
 * <p>Имена сравниваются в три прохода: точное совпадение, потом имя с приставкой сцены
 * ({@code face_}, {@code act_}), потом любое вхождение. Из подходящих предпочитается основная сцена
 * без суффикса {@code _w} - тот же сюжет, но с движением рта для разговора. Класс без обращения к
 * Android: таблицу проверяет тест по настоящим именам файлов из сборки.</p>
 */
public final class EmotionCatalog {

    private EmotionCatalog() {
    }

    /**
     * Названия-кандидаты для эмоции, в порядке предпочтения.
     *
     * <p>Пустой список значит, что показывать нечего: для спокойствия сцена не нужна.</p>
     */
    public static String[] candidates(int emotion) {
        switch (emotion) {
            case EmotionDetector.JOY:
                return new String[]{"egao", "hohoemu", "Happy1", "kusa"};
            case EmotionDetector.DELIGHT:
                return new String[]{"egao04", "egao03", "hohoemu04", "StarEye", "kusa"};
            case EmotionDetector.SURPRISE:
                return new String[]{"odoroku", "bikkuri", "StarEye"};
            case EmotionDetector.SADNESS:
                return new String[]{"kanashimu", "uru", "Sad1", "Sad2"};
            case EmotionDetector.ANGER:
                return new String[]{"ikaru", "punpun", "suneru", "Angry"};
            case EmotionDetector.SHY:
                return new String[]{"hohoemu02", "cheek_on", "doya", "tereru", "Shy"};
            case EmotionDetector.THINKING:
                return new String[]{"kangaeru", "shinken", "doya", "Halfeyes"};
            case EmotionDetector.TIRED:
                return new String[]{"tameiki", "nayamu", "normal_soft", "Halfeyes"};
            default:
                return new String[0];
        }
    }

    /**
     * Имя сцены под эмоцию.
     *
     * @param known названия, которые есть у модели: движения или выражения лица
     * @return подходящее название или {@code null}, если показывать нечего
     */
    public static String expressionFor(int emotion, List<String> known) {
        if (known == null || known.isEmpty()) {
            return null;
        }
        final String[] candidates = candidates(emotion);
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < candidates.length; i++) {
                final String candidate = candidates[i];
                // Из подходящих имён выбирается самое простое: без версии для говорящего рта
                // («_w») и без номеров. Так «face_kanashimu_w» уступает «face_kanashimu», а
                // «act_egao02_w» - «act_egao».
                String best = null;
                int bestScore = Integer.MAX_VALUE;
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
                    if (!matches) {
                        continue;
                    }
                    final int score = (name.endsWith("_w") ? 100 : 0) + countDigits(name);
                    if (score < bestScore) {
                        bestScore = score;
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

    /** Сколько цифр в имени: «act_egao» проще, чем «act_egao02», и лучше описывает эмоцию. */
    private static int countDigits(String name) {
        int digits = 0;
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) >= '0' && name.charAt(i) <= '9') {
                digits++;
            }
        }
        return digits;
    }

    /** Русское название эмоции: то же, что показывает детектор. */
    public static String title(int emotion) {
        return EmotionDetector.name(emotion);
    }
}
