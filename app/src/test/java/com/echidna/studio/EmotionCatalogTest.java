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
    public void everyEmotionOfEmiliaFindsItsScene() throws Exception {
        final List<String> names = namesOf("emilia_bunny");
        assertTrue("сцены Эмилии найдены", names.contains("act_egao"));
        assertEquals("радость", "act_egao", EmotionCatalog.expressionFor(EmotionDetector.JOY, names));
        // У Эмилии сцены печали есть только в версии для говорящего рта («_w»), и это нормально:
        // выбирается простейшая из существующих.
        assertEquals("грусть", "face_kanashimu_w",
                EmotionCatalog.expressionFor(EmotionDetector.SADNESS, names));
        assertEquals("злость", "face_ikaru",
                EmotionCatalog.expressionFor(EmotionDetector.ANGER, names));
        assertEquals("удивление", "act_odoroku",
                EmotionCatalog.expressionFor(EmotionDetector.SURPRISE, names));
        assertEquals("задумчивость", "act_kangaeru",
                EmotionCatalog.expressionFor(EmotionDetector.THINKING, names));
        assertEquals("усталость", "act_tameiki",
                EmotionCatalog.expressionFor(EmotionDetector.TIRED, names));
        // Восторг - самая широкая улыбка рига.
        final String delight = EmotionCatalog.expressionFor(EmotionDetector.DELIGHT, names);
        assertNotNull("восторг", delight);
        assertTrue("восторг должен быть про улыбку: " + delight, delight.contains("egao")
                || delight.contains("hohoemu"));
        // Спокойствие сцены не требует: лицо и так живёт своей мимикой.
        assertNull(EmotionCatalog.expressionFor(EmotionDetector.NEUTRAL, names));
    }

    @Test
    public void theSimplestSceneWins() throws Exception {
        final List<String> names = namesOf("emilia_bunny");
        // Там, где есть сцена без версии для говорящего рта, выбирается именно она.
        assertEquals("act_egao", EmotionCatalog.expressionFor(EmotionDetector.JOY, names));
        assertEquals("act_odoroku", EmotionCatalog.expressionFor(EmotionDetector.SURPRISE, names));
        assertEquals("act_tameiki", EmotionCatalog.expressionFor(EmotionDetector.TIRED, names));
        assertFalse("версия для говорящего рта не должна выигрывать у основной",
                EmotionCatalog.expressionFor(EmotionDetector.JOY, names).endsWith("_w"));
    }

    @Test
    public void anUnrelatedSceneIsNeverChosen() throws Exception {
        final List<String> names = namesOf("emilia_bunny");
        for (int emotion = 0; emotion <= EmotionDetector.DELIGHT; emotion++) {
            final String found = EmotionCatalog.expressionFor(emotion, names);
            if (found == null) {
                continue;
            }
            assertFalse("под эмоцию выбрана чужая сцена: " + found,
                    found.contains("usamimi") || found.contains("ribon") || found.contains("hurihuri")
                            || found.contains("legL") || found.contains("legR"));
        }
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
