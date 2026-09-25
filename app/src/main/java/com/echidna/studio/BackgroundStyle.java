package com.echidna.studio;

/**
 * What is drawn behind the model. The chroma key variants exist because the app is meant to be
 * captured by OBS: with a green (or magenta) background the window source can be keyed out and the
 * model lands on top of any stream layout.
 */
public enum BackgroundStyle {
    NIGHT("Ночь", 0.07f, 0.05f, 0.12f),
    VIOLET("Фиолетовый", 0.16f, 0.08f, 0.26f),
    CHROMA_GREEN("Хромакей зелёный", 0.02f, 0.85f, 0.13f),
    CHROMA_MAGENTA("Хромакей маджента", 0.86f, 0.03f, 0.72f),
    BLACK("Чёрный", 0.0f, 0.0f, 0.0f),
    WHITE("Белый", 1.0f, 1.0f, 1.0f);

    public final String title;
    public final float r;
    public final float g;
    public final float b;

    BackgroundStyle(String title, float r, float g, float b) {
        this.title = title;
        this.r = r;
        this.g = g;
        this.b = b;
    }

    public boolean isChromaKey() {
        return this == CHROMA_GREEN || this == CHROMA_MAGENTA;
    }

    public BackgroundStyle next() {
        final BackgroundStyle[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
