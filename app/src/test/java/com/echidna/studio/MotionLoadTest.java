package com.echidna.studio;

import com.live2d.sdk.cubism.framework.CubismFramework;
import com.live2d.sdk.cubism.framework.id.CubismIdManager;
import com.live2d.sdk.cubism.framework.motion.CubismMotion;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Loads every motion of the model through the real Cubism motion parser.
 *
 * The model shipped with a broken Meta block: the counters were smaller than the curve data, so
 * Cubism ran out of its pre-sized point list and the renderer reported a drawing failure instead of
 * showing the character. These tests keep that from coming back, and they also check that a motion
 * with damaged counters still loads.
 */
public class MotionLoadTest {
    private static final int EXPECTED_MOTIONS = 68;

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

    private static File motionsDirectory() {
        String[] candidates = {
            "src/main/assets/live2d/Echidna/motions",
            "app/src/main/assets/live2d/Echidna/motions",
            "../app/src/main/assets/live2d/Echidna/motions",
        };
        for (String candidate : candidates) {
            File directory = new File(candidate);
            if (directory.isDirectory()) {
                return directory;
            }
        }
        fail("не найден каталог с движениями модели: " + Paths.get("").toAbsolutePath());
        return null;
    }

    private static List<File> motions() {
        List<File> files = new ArrayList<>();
        File directory = motionsDirectory();
        File[] entries = directory.listFiles();
        assertNotNull(entries);
        for (File entry : entries) {
            if (entry.getName().endsWith(".motion3.json")) {
                files.add(entry);
            }
        }
        Collections.sort(files, (left, right) -> left.getName().compareTo(right.getName()));
        return files;
    }

    @Test
    public void allMotionsOfTheModelArePresent() throws Exception {
        prepareFramework();
        assertEquals("набор движений модели изменился", EXPECTED_MOTIONS, motions().size());
    }

    @Test
    public void everyMotionLoadsWithoutThrowing() throws Exception {
        prepareFramework();
        List<String> failures = new ArrayList<>();
        for (File file : motions()) {
            try {
                byte[] data = Files.readAllBytes(file.toPath());
                CubismMotion motion = CubismMotion.create(data);
                assertNotNull("движение не создано: " + file.getName(), motion);
            } catch (Throwable error) {
                failures.add(file.getName() + ": " + error);
            }
        }
        assertTrue("движения, которые не разобрались:\n" + String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void aMotionWithUnderstatedCountersStillLoads() throws Exception {
        prepareFramework();
        File source = motions().get(0);
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        // «Убитые» счётчики — именно то, из-за чего приложение показывало ошибку отрисовки.
        String damaged = text
            .replaceFirst("\"TotalSegmentCount\":\\s*\\d+", "\"TotalSegmentCount\": 1")
            .replaceFirst("\"TotalPointCount\":\\s*\\d+", "\"TotalPointCount\": 1");
        assertTrue("не удалось испортить счётчики в " + source.getName(), damaged.contains("\"TotalPointCount\": 1"));

        CubismMotion motion = CubismMotion.create(damaged.getBytes(StandardCharsets.UTF_8));
        assertNotNull(motion);
    }

    @Test
    public void aTruncatedCurveDoesNotBreakTheParser() throws Exception {
        prepareFramework();
        File source = motions().get(1);
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        int start = text.indexOf("\"Segments\"");
        assertTrue("в движении нет сегментов", start > 0);
        int open = text.indexOf('[', start);
        int close = text.indexOf(']', open);
        // Оставляем от первой кривой только начало сегментов: данные обрываются посреди сегмента.
        String damaged = text.substring(0, open) + "[0.0, 0.0, 2, 0.5" + text.substring(close);

        CubismMotion motion = CubismMotion.create(damaged.getBytes(StandardCharsets.UTF_8));
        assertNotNull(motion);
    }

    @Test
    public void directoriesOfTheMotionSetAreReadable() throws Exception {
        prepareFramework();
        Path directory = motionsDirectory().toPath();
        int counted = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.motion3.json")) {
            for (Path ignored : stream) {
                counted += 1;
            }
        }
        assertEquals(EXPECTED_MOTIONS, counted);
    }
}
