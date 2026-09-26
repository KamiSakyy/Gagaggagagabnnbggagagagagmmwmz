package com.echidna.studio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Every character the app can show.
 *
 * <p>A model lives in {@code assets/live2d/<папка>} and is described by a single
 * {@code model3.json}; {@link com.echidna.studio.EchidnaModel} loads whatever the file points at, so
 * adding a character is a line in this list plus the files of the model.</p>
 */
public final class ModelCatalog {

    /** One character: identity for the UI, paths for the loader. */
    public static final class ModelSpec {
        public final String id;
        public final String title;
        public final String emoji;
        public final String blurb;
        public final String assetDir;
        public final String modelJson;

        ModelSpec(String id, String title, String emoji, String blurb, String assetDir) {
            this.id = id;
            this.title = title;
            this.emoji = emoji;
            this.blurb = blurb;
            this.assetDir = assetDir.endsWith("/") ? assetDir : assetDir + "/";
            this.modelJson = "model3.json";
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /** The setting the chosen character is stored under. */
    public static final String PREF_KEY = "model-id";
    /** Персонаж по умолчанию: Ехидна из Re:Zero. */
    public static final String DEFAULT_ID = "echidna";

    private static final List<ModelSpec> MODELS = Collections.unmodifiableList(Arrays.asList(
            new ModelSpec("echidna", "Ехидна", "\uD83D\uDDA4",
                    "Ведьма из Re:Zero: спокойная, с хитринкой, 68 движений", "live2d/echidna"),
            new ModelSpec("echidna_valentine", "Ехидна (Валентин)", "\uD83D\uDC9D",
                    "Праздничный наряд: 66 движений", "live2d/echidna_valentine"),
            new ModelSpec("emilia_bunny", "Эмилия (кролик)", "\uD83D\uDC30",
                    "Кроличий костюм, самое большое портфолио - 125 движений", "live2d/emilia_bunny"),
            new ModelSpec("emilia_swimsuit", "Эмилия (пляж)", "\uD83C\uDF0A",
                    "Купальник, 115 движений", "live2d/emilia_swimsuit"),
            new ModelSpec("nahida_genshin", "Нахида (Genshin)", "\uD83C\uDF43",
                    "Архонт мудрости с 13 выражениями лица", "live2d/nahida_genshin")
    ));

    private ModelCatalog() {
    }

    public static List<ModelSpec> models() {
        return MODELS;
    }

    /** Все персонажи, доступные интерфейсу. */
    public static List<ModelSpec> all() {
        return MODELS;
    }

    public static ModelSpec defaultModel() {
        return MODELS.get(0);
    }

    public static ModelSpec byId(String id) {
        if (id != null) {
            for (int i = 0; i < MODELS.size(); i++) {
                if (MODELS.get(i).id.equals(id)) {
                    return MODELS.get(i);
                }
            }
        }
        return defaultModel();
    }

    /** The models that really have files in the APK. */
    public static List<ModelSpec> available(android.content.res.AssetManager assets) {
        final List<ModelSpec> found = new ArrayList<ModelSpec>();
        for (int i = 0; i < MODELS.size(); i++) {
            final ModelSpec spec = MODELS.get(i);
            try {
                assets.open(spec.assetDir + spec.modelJson).close();
                found.add(spec);
            } catch (Exception missing) {
                EchidnaLog.w("MODEL", "модель " + spec.id + " не упакована: " + missing);
            }
        }
        if (found.isEmpty()) {
            found.add(defaultModel());
        }
        return found;
    }
}
