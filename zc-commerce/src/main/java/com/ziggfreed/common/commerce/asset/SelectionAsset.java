package com.ziggfreed.common.commerce.asset;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;

/**
 * WHICH candidates a rotation draws, out of everything eligible.
 *
 * <pre>{@code
 * "Selection": { "Type": "Weighted_Random" }   // the usual: a seeded draw honouring weights
 * "Selection": { "Type": "All" }               // no draw at all; everything eligible is shown
 * }</pre>
 *
 * <p>{@code Type} is a union DISCRIMINATOR over the registered selection vocabulary, not a mode
 * bundling switches: whichever strategy is named decides only how candidates are picked, and every
 * other knob on the rotating thing means the same either way. A mod may register its own strategy
 * and content then names it here with no further code.
 *
 * <p><b>A Type nothing registered is reported, never quietly replaced by the default.</b> Falling
 * back would mean a typo'd strategy silently shipping a different set of offers every day, which is
 * exactly the failure nobody traces back to one misspelled word.
 *
 * <p>{@code Seed} decides what the draw is reproducible ACROSS: unauthored means the current period,
 * so every player sees the same set until it turns over and nothing has to be stored anywhere.
 */
public final class SelectionAsset {

    /** {@code Type} authored as the seeded, weight-honouring draw; the usual one. */
    public static final String TYPE_WEIGHTED_RANDOM = "Weighted_Random";

    /** {@code Type} authored as "no draw": every eligible candidate is shown. */
    public static final String TYPE_ALL = "All";

    /** {@code Seed} authored as the current rotation period; the default. */
    public static final String SEED_PERIOD = "Period";

    @Nullable protected String type;
    @Nullable protected String seed;

    public static final BuilderCodec<SelectionAsset> CODEC =
            BuilderCodec.builder(SelectionAsset.class, SelectionAsset::new)
                    .appendInherited(new KeyedCodec<>("Type", Codec.STRING, false),
                            (o, v) -> o.type = v, o -> o.type, (o, p) -> o.type = p.type)
                    .metadata(new UIEditor(new UIEditor.Dropdown(CommerceEditorDataSets.SELECTION_TYPES)))
                    .documentation("Which registered strategy picks the set: Weighted_Random draws seeded picks "
                            + "honouring each candidate's weight, All shows everything eligible and never draws. "
                            + "A strategy nothing registered is reported rather than replaced by the default, "
                            + "since a silent fallback would quietly ship a different set every rotation.").add()
                    .appendInherited(new KeyedCodec<>("Seed", Codec.STRING, false),
                            (o, v) -> o.seed = v, o -> o.seed, (o, p) -> o.seed = p.seed)
                    .metadata(EditorSchema.defaultValue(SEED_PERIOD))
                    .documentation("What the draw is reproducible across. Unauthored means the current rotation "
                            + "period, so every player sees the same set until it turns over and nothing has to be "
                            + "remembered between restarts.").add()
                    .build();

    public SelectionAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static SelectionAsset of(@Nullable String type, @Nullable String seed) {
        SelectionAsset s = new SelectionAsset();
        s.type = type;
        s.seed = seed;
        return s;
    }

    /** The authored strategy id exactly as written, or null when unauthored. */
    @Nullable
    public String getType() {
        return type == null || type.isBlank() ? null : type.trim();
    }

    /** The strategy id, lower-cased for lookup, or the seeded draw when unauthored. */
    @Nonnull
    public String effectiveType() {
        String authored = getType();
        return (authored == null ? TYPE_WEIGHTED_RANDOM : authored).toLowerCase(Locale.ROOT);
    }

    /** The authored seed word exactly as written, or null when unauthored. */
    @Nullable
    public String getSeed() {
        return seed == null || seed.isBlank() ? null : seed.trim();
    }
}
