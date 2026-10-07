package com.ziggfreed.common.board.asset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.text.ContentTextAsset;

/**
 * What a board calls one difficulty band, and what colour it wears: the shared {@link ContentTextAsset}
 * leaves ({@code TitleKey}, {@code DisplayName}, {@code TextArgs}, ...) plus {@code Color}.
 *
 * <pre>{@code
 * "Grades": { "Harvest_Feast_Skill": { "TitleKey": "board.harvest_feast_daily.grade.skill", "Color": "#c08a3a" } }
 * }</pre>
 *
 * <p>The colour is kept exactly as written; a surface paints it only through {@code BoardSpec.gradeColor},
 * which clamps it to read on a row. Every leaf inherits on its own under {@code Parent}, so a child board
 * can recolour a band and keep its name.
 */
public final class BoardGradeSpec extends ContentTextAsset {

    @Nullable private String color;

    public static final BuilderCodec<BoardGradeSpec> CODEC =
            BuilderCodec.builder(BoardGradeSpec.class, BoardGradeSpec::new, ContentTextAsset.CODEC)
                    .appendInherited(new KeyedCodec<>("Color", Codec.STRING, false),
                            (o, v) -> o.color = v, o -> o.color, (o, p) -> o.color = p.color)
                    .documentation("The colour this band's grade word on the board and its pill in a quest log "
                            + "wear, as #rrggbb. One too dark to read against a row paints in the shared accent "
                            + "instead. Leave it out and each surface uses its own colour for the band.").add()
                    .build();

    public BoardGradeSpec() {
    }

    /** Java-side factory: a band called by {@code key} and wearing {@code color}. */
    @Nonnull
    public static BoardGradeSpec of(@Nullable String key, @Nullable String color) {
        BoardGradeSpec grade = new BoardGradeSpec();
        grade.titleKey = key;
        grade.color = color;
        return grade;
    }

    /** The key this band is called by (its {@code TitleKey}), or null when none is authored. */
    @Nullable
    public String key() {
        return getTitleKey();
    }

    /** The colour as written, trimmed, or null when none is authored. */
    @Nullable
    public String color() {
        return color == null || color.isBlank() ? null : color.trim();
    }
}
