package com.echidna.studio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Каталог персонажей: приложение обещает шесть моделей, и интерфейс должен получить их все.
 *
 * <p>Тест ловит три ошибки разом: потерянный id (кнопка есть, а персонажа нет), дубль id (переключатель
 * открывает не того) и мусор в описании, который пользователь видит на кнопке.</p>
 */
public class ModelCatalogTest {

    @Test
    public void theCatalogCarriesEveryCharacter() {
        final List<ModelCatalog.ModelSpec> all = ModelCatalog.all();
        assertEquals("персонажей в каталоге", 5, all.size());

        final Set<String> ids = new HashSet<String>();
        for (int i = 0; i < all.size(); i++) {
            final ModelCatalog.ModelSpec spec = all.get(i);
            assertNotNull("у персонажа нет id", spec.id);
            assertTrue("пустой id", spec.id.length() > 2);
            assertTrue("дубль id: " + spec.id, ids.add(spec.id));
            assertTrue("у " + spec.id + " нет названия", spec.title != null && !spec.title.isEmpty());
            assertTrue("у " + spec.id + " нет значка", spec.emoji != null && !spec.emoji.isEmpty());
            assertTrue("пустое описание у " + spec.id,
                    spec.blurb != null && spec.blurb.length() > 8);
            assertTrue("путь модели сломан: " + spec.assetDir, spec.assetDir.endsWith("/")
                    && (spec.assetDir.startsWith("live2d/") || spec.assetDir.startsWith("three/")));
            if (spec.threeD) {
                assertTrue("у 3D модели должен быть .vrm: " + spec.modelJson,
                        spec.modelJson.endsWith(".vrm"));
            } else {
                assertEquals("файл модели у " + spec.id, "model3.json", spec.modelJson);
            }
        }
    }

    @Test
    public void theDefaultCharacterIsTheFirstOne() {
        assertEquals("персонаж по умолчанию", ModelCatalog.all().get(0).id, ModelCatalog.DEFAULT_ID);
        assertEquals("поиск по своему же id", ModelCatalog.DEFAULT_ID,
                ModelCatalog.byId(ModelCatalog.DEFAULT_ID).id);
    }

    @Test
    public void anUnknownIdFallsBackToTheDefault() {
        assertEquals(ModelCatalog.DEFAULT_ID, ModelCatalog.byId(null).id);
        assertEquals(ModelCatalog.DEFAULT_ID, ModelCatalog.byId("нет-такой-модели").id);
    }

    /** Пользователь читает описания: опечатки вида «Кощачий костюм» недопустимы. */
    @Test
    public void theDescriptionsAreClean() {
        final List<ModelCatalog.ModelSpec> all = ModelCatalog.all();
        for (int i = 0; i < all.size(); i++) {
            final ModelCatalog.ModelSpec spec = all.get(i);
            assertFalse("опечатка в описании " + spec.id, spec.blurb.contains("Кощачий"));
            assertFalse("описание " + spec.id + " обрывается пробелом", spec.blurb.endsWith(" "));
        }
    }

    /**
     * В приложении только Live2D-персонажи: объёмную модель убрали.
     *
     * <p>Тест не даёт вернуть её в список случайно вместе с десятимегабайтным файлом .vrm.</p>
     */
    @Test
    public void thereAreNoThreeDimensionalCharacters() {
        final List<ModelCatalog.ModelSpec> all = ModelCatalog.all();
        for (int i = 0; i < all.size(); i++) {
            assertFalse("объёмный персонаж в списке: " + all.get(i).id, all.get(i).threeD);
            assertTrue("модель обязана лежать в live2d: " + all.get(i).assetDir,
                    all.get(i).assetDir.startsWith("live2d/"));
        }
    }

    /** Все персонажи, обещанные пользователю, лежат в каталоге под своими именами. */
    @Test
    public void everyPromisedCharacterIsInTheCatalog() {
        final String[] promised = {
                "echidna", "echidna_valentine", "emilia_bunny", "emilia_swimsuit", "nahida_genshin"
        };
        for (int i = 0; i < promised.length; i++) {
            boolean found = false;
            final List<ModelCatalog.ModelSpec> all = ModelCatalog.all();
            for (int k = 0; k < all.size(); k++) {
                if (all.get(k).id.equals(promised[i])) {
                    found = true;
                }
            }
            assertTrue("персонаж " + promised[i] + " потерялся", found);
        }
    }

    /**
     * Каждый персонаж каталога обязан лежать в дереве проекта.
     *
     * <p>Это проверка на «в списке одна модель»: если файлы модели не упакованы, приложение честно
     * покажет только тех, кто есть, и человек решит, что моделей вообще нет. Тест проходит по
     * дереву assets и убеждается, что для каждой из шести моделей есть её файл-описание.</p>
     */
    @Test
    public void everyCharacterOfTheCatalogIsInTheTree() {
        final List<ModelCatalog.ModelSpec> all = ModelCatalog.all();
        for (int i = 0; i < all.size(); i++) {
            final ModelCatalog.ModelSpec spec = all.get(i);
            final File file = findAsset(spec.assetDir + spec.modelJson);
            assertTrue("нет файла модели " + spec.id + ": " + file, file.isFile());
            assertTrue("пустой файл модели " + spec.id, file.length() > 1024);
            final File moc = findAsset(spec.assetDir + "model.moc3");
            assertTrue("нет .moc3 у " + spec.id, moc.isFile());
        }
    }

    /** Файл из assets ищется и при запуске из корня проекта, и из каталога модуля. */
    private static File findAsset(String path) {
        final String[] roots = {"app/src/main/assets", "src/main/assets", "../app/src/main/assets"};
        for (int i = 0; i < roots.length; i++) {
            final File file = new File(roots[i], path);
            if (file.isFile()) {
                return file;
            }
        }
        return new File(roots[0], path);
    }
}
