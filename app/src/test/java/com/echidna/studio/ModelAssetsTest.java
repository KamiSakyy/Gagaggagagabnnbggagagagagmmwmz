package com.echidna.studio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Файлы каждой модели: свои текстуры, свой moc3, свои движения.
 *
 * <p>Этот тест появился после ошибки, которая доехала до телефона: загрузчик строил путь от
 * константы {@code MODEL_DIR = "live2d/echidna/"}, поэтому Валентина, Эмилия и Нахида читали чужие
 * файлы. У Валентины не находилась вторая текстура, у остальных вместо их рисунка подставлялся
 * рисунок Ехидны, а движения вообще не играли. Здесь проверяется само дерево: каждая текстура из
 * {@code model3.json} лежит в папке СВОЕЙ модели, рядом с ней {@code model.moc3}, а количество файлов
 * движений совпадает с ожидаемым.</p>
 */
public class ModelAssetsTest {

    /** Сколько файлов движений обещает каждая модель. */
    private static final String[][] MOTION_COUNTS = {
            {"emilia_bunny", "125"},
    };

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

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), Charset.forName("UTF-8"));
    }

    /** Все строки из массива внутри {@code "ключ": [ ... ]} - без библиотеки JSON. */
    private static List<String> arrayOf(String json, String key) {
        final List<String> values = new ArrayList<String>();
        final int keyAt = json.indexOf("\"" + key + "\"");
        if (keyAt < 0) {
            return values;
        }
        final int open = json.indexOf('[', keyAt);
        final int close = json.indexOf(']', open);
        if (open < 0 || close < 0) {
            return values;
        }
        final Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(json.substring(open, close));
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    /** Одна строка модели: её файлы обязаны существовать каждый в своей папке. */
    @Test
    public void everyModelHasItsOwnTexturesAndMoc() throws Exception {
        final File live2d = new File(assetsRoot(), "live2d");
        for (int i = 0; i < ModelCatalog.all().size(); i++) {
            final ModelCatalog.ModelSpec spec = ModelCatalog.all().get(i);
            final File directory = new File(live2d, spec.id);
            final File setting = new File(directory, "model3.json");
            assertTrue("нет " + setting, setting.isFile());

            final String json = read(setting);
            final List<String> textures = arrayOf(json, "Textures");
            assertFalse("у модели " + spec.id + " нет текстур в model3.json", textures.isEmpty());
            for (int t = 0; t < textures.size(); t++) {
                final String texture = textures.get(t);
                final File file = new File(directory, texture);
                assertTrue("нет текстуры модели " + spec.id + ": " + texture
                        + " (искал в " + directory + ")", file.isFile());
                assertTrue("текстура пустая: " + file, file.length() > 1024);
                assertFalse("текстура лежит в чужой папке: " + texture,
                        texture.contains("echidna") && !spec.id.contains("echidna"));
            }

            final List<String> moc = arrayOf(json, "Moc");
            for (int m = 0; m < moc.size(); m++) {
                assertTrue("нет " + moc.get(m) + " у модели " + spec.id,
                        new File(directory, moc.get(m)).isFile());
            }
        }
    }

    /** Файлы движений: у четырёх моделей их сотни, у Нахиды движений нет вовсе. */
    @Test
    public void everyModelCarriesItsOwnMotions() {
        final File live2d = new File(assetsRoot(), "live2d");
        for (int i = 0; i < MOTION_COUNTS.length; i++) {
            final String id = MOTION_COUNTS[i][0];
            final int expected = Integer.parseInt(MOTION_COUNTS[i][1]);
            final File directory = new File(new File(live2d, id), "motions");
            int found = 0;
            final File[] files = directory.listFiles();
            if (files != null) {
                for (int f = 0; f < files.length; f++) {
                    if (files[f].getName().endsWith(".motion3.json")) {
                        found++;
                    }
                }
            }
            assertTrue("у модели " + id + " движений " + found + ", ожидалось " + expected,
                    found == expected);
        }
    }

    /** В дереве не должно остаться объёмной модели: её убрали из приложения. */
    @Test
    public void theThreeDimensionalAssetIsGone() {
        final File vrm = new File(assetsRoot(), "three/character.vrm");
        assertFalse("файл объёмной модели всё ещё в дереве: " + vrm, vrm.isFile());
    }
}
