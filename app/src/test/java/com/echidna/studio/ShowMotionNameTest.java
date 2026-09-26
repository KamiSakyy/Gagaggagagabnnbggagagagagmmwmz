package com.echidna.studio;

import com.echidna.studio.anim.Segment;
import com.echidna.studio.anim.Show;
import com.echidna.studio.anim.ShowLibrary;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Checks that the shows and the idle behaviour only start motions the model really has.
 *
 * A show that names a motion the model does not contain would fail silently on the device: the
 * engine simply refuses it and the character stays still, which looks like a broken animation.
 */
public class ShowMotionNameTest {

    private static File modelFile() {
        String[] candidates = {
            "src/main/assets/live2d/Echidna/Echidna.model3.json",
            "app/src/main/assets/live2d/Echidna/Echidna.model3.json",
            "../app/src/main/assets/live2d/Echidna/Echidna.model3.json",
        };
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile()) {
                return file;
            }
        }
        fail("не найден файл модели: " + new File(".").getAbsolutePath());
        return null;
    }

    /** Names of the motions the model file declares. */
    private static Set<String> motionsOfTheModel() throws Exception {
        String json = new String(Files.readAllBytes(modelFile().toPath()), StandardCharsets.UTF_8);
        Set<String> names = new LinkedHashSet<>();
        int index = 0;
        while (true) {
            int found = json.indexOf("\"File\":", index);
            if (found < 0) {
                break;
            }
            int open = json.indexOf('"', found + 7);
            int close = json.indexOf('"', open + 1);
            String file = json.substring(open + 1, close);
            index = close + 1;
            if (!file.endsWith(".motion3.json")) {
                continue;
            }
            names.add(nameOf(file));
        }
        return names;
    }

    private static String nameOf(String file) {
        String name = file.substring(file.lastIndexOf('/') + 1);
        return name.endsWith(".motion3.json")
                ? name.substring(0, name.length() - ".motion3.json".length())
                : name;
    }

    @Test
    public void everyMotionOfTheShowsExistsInTheModel() throws Exception {
        Set<String> known = motionsOfTheModel();
        assertFalse("в модели не найдено ни одного движения", known.isEmpty());

        List<String> missing = new ArrayList<>();
        for (Show show : ShowLibrary.shows()) {
            for (Segment segment : show.segments) {
                if (segment.motion != null && !known.contains(segment.motion)) {
                    missing.add("шоу " + show.id + " -> " + segment.motion);
                }
            }
        }
        for (String name : ShowLibrary.idleMotions()) {
            if (!known.contains(name)) {
                missing.add("ожидание -> " + name);
            }
        }
        for (String name : ShowLibrary.cameraIdleMotions()) {
            if (!known.contains(name)) {
                missing.add("камера -> " + name);
            }
        }
        assertTrue("движения, которых нет в модели:\n" + String.join("\n", missing), missing.isEmpty());
    }

    @Test
    public void theFiveShowsArePresentAndNonEmpty() {
        List<Show> shows = ShowLibrary.shows();
        assertTrue("шоу должно быть пять, а их " + shows.size(), shows.size() >= 5);
        for (Show show : shows) {
            assertFalse("шоу " + show.id + " пустое", show.segments.isEmpty());
            assertTrue("шоу " + show.id + " без длительности", show.duration > 1.0f);
            assertTrue("шоу " + show.id + " без названия", show.title != null && !show.title.isEmpty());
        }
    }
}
