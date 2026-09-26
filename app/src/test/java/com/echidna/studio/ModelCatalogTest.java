package com.echidna.studio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
            assertTrue("каталог ссылается не на live2d: " + spec.assetDir,
                    spec.assetDir.startsWith("live2d/") && spec.assetDir.endsWith("/"));
            assertEquals("файл модели у " + spec.id, "model3.json", spec.modelJson);
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
}
