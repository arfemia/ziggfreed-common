package com.ziggfreed.common.board.asset;

import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.text.ContentTextAsset;

/**
 * What a player reads about a board: the shared {@link ContentTextAsset} leaves ({@code TitleKey},
 * {@code FlavorKey}, {@code DisplayName}, {@code TextArgs}, ...) plus {@code PillKey}, the one word every
 * contract the board posts wears as a pill on a quest page and in a quest log.
 *
 * <pre>{@code
 * "Text": { "TitleKey": "board.yourmod_errands.title", "PillKey": "board.yourmod_errands.pill" }
 * }</pre>
 *
 * <p>Unauthored, a contract's pill reads the library's own word for a contract. Every leaf inherits on
 * its own under {@code Parent}, so a child board can rename its pill and keep its title.
 */
public final class BoardTextAsset extends ContentTextAsset {

    @Nullable private String pillKey;

    public static final BuilderCodec<BoardTextAsset> CODEC =
            BuilderCodec.builder(BoardTextAsset.class, BoardTextAsset::new, ContentTextAsset.CODEC)
                    .appendInherited(new KeyedCodec<>("PillKey", Codec.STRING, false),
                            (o, v) -> o.pillKey = v, o -> o.pillKey, (o, p) -> o.pillKey = p.pillKey)
                    .documentation("Localization key for the one word every contract this board posts wears as a "
                            + "pill beside its name, on a quest page and in a quest log (an errands board might "
                            + "say Errand). Leave it out and the pill reads the library's own word for a "
                            + "contract.").add()
                    .build();

    public BoardTextAsset() {
    }

    /** The pill word's key as written, trimmed, or null when none is authored. */
    @Nullable
    public String pillKey() {
        return pillKey == null || pillKey.isBlank() ? null : pillKey.trim();
    }
}
