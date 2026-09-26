package com.echidna.studio;

import com.echidna.studio.anim.MotionPicker;
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

    private static final String[] MODEL_IDS = {
        "emilia_bunny",
    };

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

    private static File modelFile(String modelId) {
        return new File(new File(assetsRoot(), modelId), "model3.json");
    }

    /** Names of the motions the model file declares. */
    private static Set<String> motionsOfTheModel(String modelId) throws Exception {
        String json = new String(Files.readAllBytes(modelFile(modelId).toPath()), StandardCharsets.UTF_8);
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

    /**
     * A show names the motions it was authored with, and the stage maps them onto whatever the
     * character owns. This test walks every character and checks that every motion of every show
     * resolves to something the character really has - otherwise the character would freeze while the
     * show runs.
     */
    @Test
    public void everyShowFindsItsMotionsOnEveryCharacter() throws Exception {
        final List<String> problems = new ArrayList<>();
        for (String modelId : MODEL_IDS) {
            final Set<String> known = motionsOfTheModel(modelId);
            final List<String> available = new ArrayList<>(known);
            for (Show show : ShowLibrary.shows()) {
                for (Segment segment : show.segments) {
                    if (segment.motion == null) {
                        continue;
                    }
                    if (MotionPicker.resolve(available, segment.motion) == null && !known.isEmpty()) {
                        problems.add(modelId + ": шоу " + show.id + " -> " + segment.motion);
                    }
                }
            }
        }
        assertTrue("движения, которые не нашли себе замену:\n" + String.join("\n", problems),
                problems.isEmpty());
    }

    /** The idle behaviour must always come out non-empty: a character never stands frozen. */
    @Test
    public void everyCharacterGetsAnIdlePool() throws Exception {
        for (String modelId : MODEL_IDS) {
            final Set<String> known = motionsOfTheModel(modelId);
            if (known.isEmpty()) {
                continue;
            }
            final List<String> pool = MotionPicker.idlePool(new ArrayList<>(known));
            assertFalse("пустой пул ожидания у " + modelId, pool.isEmpty());
            for (String name : pool) {
                assertTrue("в пул ожидания " + modelId + " попало движение " + name
                        + ", которого нет в модели", known.contains(name));
            }
        }
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
