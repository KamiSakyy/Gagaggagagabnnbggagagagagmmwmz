package com.echidna.studio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.echidna.studio.anim.EmotionCatalog;
import com.echidna.studio.track.EmotionDetector;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Выражения лица под распознанные эмоции.
 *
 * <p>Проверяется по настоящим именам из сборки: у Нахиды выражения лежат отдельными файлами
 * («Happy1», «Sad1», «Angry», «Shy», «Halfeyes», «StarEye»), а у ригов Эмилии и Ехидны роль
 * выражений играют движения с говорящими именами («face_egao», «act_odoroku», «face_kanashimu»).
 * Тест следит и за тем, чтобы под эмоцию не подставилось неподходящее выражение - вроде «black»,
 * которое гасит модель целиком.</p>
 */
public class EmotionCatalogTest {

    private static File assetsRoot() {
        final String[] candidates = {"app/src/main/assets", "src/main/assets", "../app/src/main/assets"};
        for (int i = 0; i < candidates.length; i++) {
            final File directory = new File(candidates[i]);
            if (directory.isDirectory()) {
                return directory;
            }
        }
        return new File(candidates[0]);
    }

    /** Имена выражений и движений модели так, как их видит приложение. */
    private static List<String> namesOf(String id) throws Exception {
        final File setting = new File(new File(new File(assetsRoot(), "live2d"), id), "model3.json");
        final String json = new String(Files.readAllBytes(setting.toPath()), Charset.forName("UTF-8"));
        final List<String> names = new ArrayList<String>();
        final Matcher expressions = Pattern.compile("\"Name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (expressions.find()) {
            names.add(expressions.group(1));
        }
        final Matcher motions = Pattern.compile("motions/([^\"/]+)\\.motion3\\.json").matcher(json);
        while (motions.find()) {
            names.add(motions.group(1));
        }
        return names;
    }

    @Test
    public void everyEmotionOfNahidaFindsItsExpression() throws Exception {
        final List<String> names = namesOf("nahida_genshin");
        assertTrue("выражения Нахиды найдены", names.contains("Happy1"));
        assertEquals("радость", "Happy1",
                EmotionCatalog.expressionFor(EmotionDetector.JOY, names));
        assertEquals("грусть", "Sad1",
                EmotionCatalog.expressionFor(EmotionDetector.SADNESS, names));
        assertEquals("злость", "Angry",
                EmotionCatalog.expressionFor(EmotionDetector.ANGER, names));
        assertEquals("смущение", "Shy",
                EmotionCatalog.expressionFor(EmotionDetector.SHY, names));
        assertEquals("задумчивость", "Halfeyes",
                EmotionCatalog.expressionFor(EmotionDetector.THINKING, names));
        assertEquals("восторг", "StarEye",
                EmotionCatalog.expressionFor(EmotionDetector.DELIGHT, names));
        // Спокойствие выражения не требует: лицо и так живёт своей мимикой.
        assertNull(EmotionCatalog.expressionFor(EmotionDetector.NEUTRAL, names));
    }

    @Test
    public void theRigsFindTheirOwnMotionsForEmotions() throws Exception {
        final List<String> echidna = namesOf("echidna");
        assertEquals("радость у Ехидны", "face_egao",
                EmotionCatalog.expressionFor(EmotionDetector.JOY, echidna));
        assertEquals("удивление", "face_odoroku",
                EmotionCatalog.expressionFor(EmotionDetector.SURPRISE, echidna));
        assertEquals("злость", "face_ikaru",
                EmotionCatalog.expressionFor(EmotionDetector.ANGER, echidna));
        assertEquals("смущение", "act_tereru",
                EmotionCatalog.expressionFor(EmotionDetector.SHY, echidna));
        // У Эмилии к именам добавляется суффикс, поэтому совпадение ищется по началу имени.
        final List<String> emilia = namesOf("emilia_bunny");
        final String sad = EmotionCatalog.expressionFor(EmotionDetector.SADNESS, emilia);
        assertNotNull("грусть у Эмилии", sad);
        assertTrue("имя должно быть про грусть: " + sad, sad.startsWith("face_kanashimu"));
    }

    @Test
    public void anUnrelatedExpressionIsNeverChosen() throws Exception {
        final List<String> nahida = namesOf("nahida_genshin");
        for (int emotion = 0; emotion <= EmotionDetector.DELIGHT; emotion++) {
            final String found = EmotionCatalog.expressionFor(emotion, nahida);
            if (found == null) {
                continue;
            }
            assertFalse("под эмоцию выбрано чужое выражение: " + found,
                    found.equals("black") || found.equals("HandChange") || found.equals("mouthchange"));
        }
    }

    @Test
    public void aModelWithoutExpressionsSimplyShowsNothing() {
        assertNull("без выражений показывать нечего",
                EmotionCatalog.expressionFor(EmotionDetector.JOY, new ArrayList<String>()));
        assertNull("пустой список", EmotionCatalog.expressionFor(EmotionDetector.JOY, null));
        assertNull("чужие имена не подходят",
                EmotionCatalog.expressionFor(EmotionDetector.JOY, Arrays.asList("kabe", "usamimiL")));
    }

    @Test
    public void everyEmotionHasCandidatesAndATitle() {
        for (int emotion = EmotionDetector.JOY; emotion <= EmotionDetector.TIRED; emotion++) {
            final String[] candidates = EmotionCatalog.candidates(emotion);
            assertTrue("у эмоции " + EmotionDetector.name(emotion) + " нет кандидатов",
                    candidates.length > 0);
            assertTrue("название эмоции пустое",
                    EmotionCatalog.title(emotion) != null && !EmotionCatalog.title(emotion).isEmpty());
        }
        assertEquals("у спокойствия кандидатов нет", 0,
                EmotionCatalog.candidates(EmotionDetector.NEUTRAL).length);
    }
}
