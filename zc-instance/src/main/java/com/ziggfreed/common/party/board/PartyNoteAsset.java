package com.ziggfreed.common.party.board;

import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * One set note a poster may add to a party board's notice (two at most): its words and where it sits among the
 * others. A notice carries no free text, only these. The FILE NAME is the note's id.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/PartyNotes/<Id>.json}; the library ships its three
 * ({@code Zc_New_Players}, {@code Zc_Learning}, {@code Zc_Quick_Run}), and a mod adds its own beside them with a file
 * of its own, in its own jar, so a note that only makes sense with that mod shows only where it is installed. A
 * same-id file switches one off ({@code "Enabled": false}) or rewords it.
 * <pre>{@code
 * { "TextKey": "mymod.board.note.healer", "Order": 40 }
 * }</pre>
 */
public final class PartyNoteAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, PartyNoteAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/PartyNotes";

    /** Where a note sits unless it says, after every note that does. */
    public static final int DEFAULT_ORDER = 1000;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected String textKey;
    @Nullable protected Integer order;
    @Nullable protected Boolean enabled;

    public static final AssetBuilderCodec<String, PartyNoteAsset> CODEC = AssetBuilderCodec.builder(
                    PartyNoteAsset.class,
                    PartyNoteAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("TextKey", Codec.STRING, false),
                    (a, v) -> a.textKey = v, a -> a.textKey, (a, p) -> a.textKey = p.textKey)
            .documentation("The note's words, a lang key (without a final full stop: the notice adds one). A note "
                    + "with no words is not offered.").add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .metadata(EditorSchema.defaultValue(DEFAULT_ORDER))
            .documentation("Where the note sits among the others, lowest first; then by id.").add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether a poster is offered the note. Unauthored means true.").add()
            .build();

    public PartyNoteAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The note's lang key, trimmed, or null when it names none. */
    @Nullable
    public String textKey() {
        return textKey == null || textKey.isBlank() ? null : textKey.trim();
    }

    /** Where it sits, lowest first. */
    public int order() {
        return order == null ? DEFAULT_ORDER : order;
    }

    /** Is it offered: switched on, with words. */
    public boolean offered() {
        return (enabled == null || enabled) && textKey() != null;
    }
}
