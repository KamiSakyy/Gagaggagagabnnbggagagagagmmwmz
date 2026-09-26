package com.echidna.studio;

import com.live2d.sdk.cubism.framework.CubismFramework;
import com.live2d.sdk.cubism.framework.id.CubismIdManager;
import com.live2d.sdk.cubism.framework.motion.CubismMotion;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Loads every motion of every character through the real Cubism motion parser.
 *
 * <p>The model shipped with a broken Meta block: the counters were smaller than the curve data, so
 * Cubism ran out of its pre-sized point list and the renderer reported a drawing failure instead of
 * showing the character. These tests keep that from coming back for all characters at once, and they
 * also check that a motion with damaged counters still loads.</p>
 */
public class MotionLoadTest {

    /** How many motions each character is expected to bring. */
    private static final Map<String, Integer> EXPECTED = new LinkedHashMap<String, Integer>();

    static {
        EXPECTED.put("echidna", 68);
        EXPECTED.put("echidna_valentine", 66);
        EXPECTED.put("emilia_bunny", 125);
        EXPECTED.put("emilia_swimsuit", 115);
        EXPECTED.put("nahida_genshin", 0);
    }

    private static void prepareFramework() throws Exception {
        // The parser itself is plain Java, but curves that drive the model (not a single parameter)
        // ask the framework for an identifier. The framework normally gets that manager in startUp(),
        // which also loads the native core, so the test installs it directly.
        Field manager = CubismFramework.class.getDeclaredField("s_cubismIdManager");
        manager.setAccessible(true);
        if (manager.get(null) == null) {
            manager.set(null, new CubismIdManager());
        }
    }

    private static File assetsRoot() {
        String[] candidates = {
            "src/main/assets/live2d",
            "app/src/main/assets/live2d",
            "../app/src/main/assets/live2d",
        };
        for (String candidate : candidates) {
            File directory = new File(candidate);
            if (directory.isDirectory()) {
                return directory;
            }
        }
        fail("не найден каталог моделей: " + new File(".").getAbsolutePath());
        return null;
    }

    private static File motionsOf(String modelId) {
        File[] entries = assetsRoot().listFiles();
        assertNotNull(entries);
        for (File entry : entries) {
            if (entry.getName().equals(modelId)) {
                File motions = new File(entry, "motions");
                if (!motions.isDirectory()) {
                    // Модель без движений (VTuber-риг с одними выражениями) - это нормально.
                    return new File(entry, "motions");
                }
                return motions;
            }
        }
        fail("нет папки модели " + modelId);
        return null;
    }

    private static List<File> motionFiles(String modelId) {
        List<File> files = new ArrayList<File>();
        File[] entries = motionsOf(modelId).listFiles();
        if (entries == null) {
            return files;
        }
        for (File entry : entries) {
            if (entry.getName().endsWith(".motion3.json")) {
                files.add(entry);
            }
        }
        Collections.sort(files, (left, right) -> left.getName().compareTo(right.getName()));
        return files;
    }

    @Test
    public void everyModelHasTheMotionsItWasInstalledWith() {
        for (Map.Entry<String, Integer> expected : EXPECTED.entrySet()) {
            final List<File> files = motionFiles(expected.getKey());
            assertEquals("набор движений модели " + expected.getKey() + " изменился",
                    (int) expected.getValue(), files.size());
        }
    }

    @Test
    public void everyMotionOfEveryModelLoadsWithoutThrowing() throws Exception {
        prepareFramework();
        final List<String> failures = new ArrayList<String>();
        int total = 0;
        for (String modelId : EXPECTED.keySet()) {
            for (File file : motionFiles(modelId)) {
                total += 1;
                try {
                    final byte[] data = Files.readAllBytes(file.toPath());
                    assertNotNull("движение не создано: " + file.getName(), CubismMotion.create(data));
                } catch (Throwable error) {
                    failures.add(modelId + "/" + file.getName() + ": " + error);
                }
            }
        }
        assertTrue("не разобралось ни одного движения", total > 300);
        assertTrue("движения, которые не разобрались:\n" + String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void aMotionWithUnderstatedCountersStillLoads() throws Exception {
        prepareFramework();
        final File source = motionFiles("echidna").get(0);
        final String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        // «Убитые» счётчики - именно то, из-за чего приложение показывало ошибку отрисовки.
        final String damaged = text
                .replaceFirst("\"TotalSegmentCount\":\\s*\\d+", "\"TotalSegmentCount\": 1")
                .replaceFirst("\"TotalPointCount\":\\s*\\d+", "\"TotalPointCount\": 1");
        assertTrue("не удалось испортить счётчики в " + source.getName(),
                damaged.contains("\"TotalPointCount\": 1"));

        assertNotNull(CubismMotion.create(damaged.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void aTruncatedCurveDoesNotBreakTheParser() throws Exception {
        prepareFramework();
        final File source = motionFiles("echidna").get(1);
        final String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        final int start = text.indexOf("\"Segments\"");
        assertTrue("в движении нет сегментов", start > 0);
        final int open = text.indexOf('[', start);
        final int close = text.indexOf(']', open);
        // Оставляем от первой кривой только начало сегментов: данные обрываются посреди сегмента.
        final String damaged = text.substring(0, open) + "[0.0, 0.0, 2, 0.5" + text.substring(close);

        assertNotNull(CubismMotion.create(damaged.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void everyModelHasAReadableModelFile() throws Exception {
        for (String modelId : EXPECTED.keySet()) {
            final File folder = new File(assetsRoot(), modelId);
            final File modelJson = new File(folder, "model3.json");
            assertTrue("нет model3.json у " + modelId, modelJson.isFile());
            final String text = new String(Files.readAllBytes(modelJson.toPath()), StandardCharsets.UTF_8);
            assertTrue("model3.json без moc3: " + modelId, text.contains("moc3"));
            assertTrue("файла moc3 нет на месте у " + modelId,
                    new File(folder, "model.moc3").isFile());
        }
    }
}
