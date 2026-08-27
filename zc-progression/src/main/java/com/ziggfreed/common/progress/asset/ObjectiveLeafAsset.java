package com.ziggfreed.common.progress.asset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveDef;

/**
 * The leaves EVERY authored objective carries, whatever kind of content owns it: what counts, which
 * one specifically, how it is compared, a secondary filter, how many, where, and what the player
 * reads.
 *
 * <pre>{@code
 * { "Kind": "PICKUP_ITEM", "Target": "Copper_Ore", "MatchMode": "EXACT", "Amount": 10 }
 * }</pre>
 *
 * <p><b>Why a shared base rather than two similar codecs.</b> Two lifecycle engines author
 * objectives, and the moment their field names drift an author has to remember which spelling
 * belongs to which kind of content - and a shared renderer starts needing two branches. Declaring
 * the leaves ONCE here and appending them into both makes that drift impossible rather than merely
 * discouraged. An engine adds its own leaves on top ({@code Order} and a hand-in place mean nothing
 * to always-on criteria, so they belong to the engine that has them, not here).
 *
 * <p>Every leaf is {@code appendInherited}, so content with a {@code Parent} can retune one number
 * and keep the rest.
 */
public class ObjectiveLeafAsset {

    @Nullable protected String kind;
    @Nullable protected String target;
    @Nullable protected String matchMode;
    @Nullable protected String qualifier;
    @Nullable protected Long amount;
    @Nullable protected String zone;
    @Nullable protected String textKey;

    public static final BuilderCodec<ObjectiveLeafAsset> CODEC =
            appendLeaves(BuilderCodec.builder(ObjectiveLeafAsset.class, ObjectiveLeafAsset::new)).build();

    /**
     * Register the seven shared leaves on {@code builder}. Every engine's own objective codec starts
     * from this call, which is what keeps the field names from drifting apart.
     */
    @Nonnull
    protected static <T extends ObjectiveLeafAsset, S extends BuilderCodec.BuilderBase<T, S>> S appendLeaves(
            @Nonnull S builder) {
        return builder
                .appendInherited(new KeyedCodec<>("Kind", Codec.STRING, false),
                        (o, v) -> o.kind = v, o -> o.kind, (o, p) -> o.kind = p.kind)
                .metadata(new UIEditor(new UIEditor.Dropdown(ProgressEditorDataSets.OBJECTIVE_KINDS)))
                .documentation("Which kind of moment this listens for. It must be a kind some mod actually "
                        + "produces, or it can never progress.").add()
                .appendInherited(new KeyedCodec<>("Target", Codec.STRING, false),
                        (o, v) -> o.target = v, o -> o.target, (o, p) -> o.target = p.target)
                .documentation("Which one specifically (a block id, an entity id, a place id). Leave it out "
                        + "for 'any': an empty target matches everything, whatever MatchMode says.").add()
                .appendInherited(new KeyedCodec<>("MatchMode", Codec.STRING, false),
                        (o, v) -> o.matchMode = v, o -> o.matchMode, (o, p) -> o.matchMode = p.matchMode)
                .metadata(EditorSchema.oneOfDocumented(
                        "EXACT", "The whole identifier must equal the target",
                        "CONTAINS", "The identifier must contain the target anywhere inside it",
                        "PREFIX", "The identifier must start with the target"))
                .metadata(EditorSchema.defaultValue("CONTAINS"))
                .documentation("How Target is compared: EXACT, CONTAINS, or PREFIX. Unauthored means CONTAINS, "
                        + "so 'Copper' also counts Copper_Ore; author EXACT when only one id may count.").add()
                .appendInherited(new KeyedCodec<>("Qualifier", Codec.STRING, false),
                        (o, v) -> o.qualifier = v, o -> o.qualifier, (o, p) -> o.qualifier = p.qualifier)
                .documentation("Optional secondary filter whose meaning belongs to the kind's producer (a tool, "
                        + "a difficulty, a variant). Unauthored means any.").add()
                .appendInherited(new KeyedCodec<>("Amount", Codec.LONG, false),
                        (o, v) -> o.amount = v, o -> o.amount, (o, p) -> o.amount = p.amount)
                .metadata(EditorSchema.defaultValue(1))
                .documentation("How many are needed. Unauthored means 1.").add()
                .appendInherited(new KeyedCodec<>("Zone", Codec.STRING, false),
                        (o, v) -> o.zone = v, o -> o.zone, (o, p) -> o.zone = p.zone)
                .documentation("Only count it inside this zone or region. Unauthored means anywhere.").add()
                .appendInherited(new KeyedCodec<>("TextKey", Codec.STRING, false),
                        (o, v) -> o.textKey = v, o -> o.textKey, (o, p) -> o.textKey = p.textKey)
                .documentation("Localization key for the line a player reads for this step. Unauthored leaves the "
                        + "wording to whatever renders it.").add();
    }

    public ObjectiveLeafAsset() {
    }

    @Nullable
    public String getKind() {
        return kind;
    }

    @Nullable
    public String getTarget() {
        return target;
    }

    /** The authored comparison name, unparsed; {@link #effectiveMatchMode()} is the read. */
    @Nullable
    public String getMatchMode() {
        return matchMode;
    }

    @Nullable
    public String getQualifier() {
        return qualifier;
    }

    @Nullable
    public Long getAmount() {
        return amount;
    }

    @Nullable
    public String getZone() {
        return zone;
    }

    @Nullable
    public String getTextKey() {
        return textKey;
    }

    /** The authored comparison, defaulting to the forgiving one the engine parses to. */
    @Nonnull
    public MatchMode effectiveMatchMode() {
        return MatchMode.fromString(matchMode);
    }

    /** True when no kind is authored, so nothing could ever progress this. */
    public boolean isBlank() {
        return kind == null || kind.isBlank();
    }

    /**
     * A builder for the engine's objective under {@code objectiveId}, with the shared leaves already
     * applied. An engine's own codec finishes it off with whatever leaves it added.
     */
    @Nonnull
    public ObjectiveDef.Builder toDefBuilder(@Nonnull String objectiveId) {
        return ObjectiveDef.builder(objectiveId, kind == null ? "" : kind.trim())
                .target(target)
                .matchMode(effectiveMatchMode())
                .qualifier(qualifier)
                .amount(amount == null ? 1L : amount)
                .zone(zone);
    }
}
