package com.ziggfreed.common.progress.asset;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * One entry of a {@code Rewards} list: a registered reward KIND plus the parameters that kind reads.
 * The SAME entry shape wherever anything in this library pays a player out, so a reward written for
 * one kind of content reads and behaves identically on the next.
 *
 * <pre>{@code
 * "Rewards": [ { "Kind": "yourmod:currency", "Params": { "Id": "coin", "Amount": "50" } },
 *              { "Kind": "yourmod:item",     "Params": { "Item": "Sword_Copper", "Count": "1" } } ]
 * }</pre>
 *
 * <p><b>{@code Params} is deliberately an open map of strings.</b> What a reward needs is decided
 * by whichever mod registered the kind, so pinning a field set here would force every payout
 * through one mod's idea of what a reward is. Keys are matched case-insensitively, so authoring
 * {@code "Amount"} and reading {@code "amount"} agree without anyone being told.
 *
 * <p>{@code Rewards} is ONE leaf as far as inheritance goes: omit it and the parent's list is
 * inherited whole, author it and the parent's list is replaced whole (an empty array is how a child
 * inherits everything else and pays out nothing).
 */
public final class RewardEntryAsset {

    @Nullable protected String kind;
    @Nullable protected Map<String, String> params;

    public static final BuilderCodec<RewardEntryAsset> CODEC =
            BuilderCodec.builder(RewardEntryAsset.class, RewardEntryAsset::new)
                    .appendInherited(new KeyedCodec<>("Kind", Codec.STRING, false),
                            (o, v) -> o.kind = v, o -> o.kind, (o, p) -> o.kind = p.kind)
                    .metadata(new UIEditor(new UIEditor.Dropdown(ProgressEditorDataSets.REWARD_KINDS)))
                    .documentation("Which registered reward kind pays this out. A kind nothing registered is "
                            + "reported rather than silently skipped, so an owner can see which mod was expected "
                            + "to provide it.").add()
                    .appendInherited(new KeyedCodec<>("Params", new InheritMapCodec<>(Codec.STRING), false),
                            (o, v) -> o.params = v, o -> o.params, (o, p) -> o.params = p.params)
                    .documentation("The kind's own parameters, as strings. Which keys matter is documented by "
                            + "whoever registered the kind; nothing here interprets them.").add()
                    .build();

    public RewardEntryAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static RewardEntryAsset of(@Nullable String kind, @Nullable Map<String, String> params) {
        RewardEntryAsset r = new RewardEntryAsset();
        r.kind = kind;
        r.params = params == null ? null : new LinkedHashMap<>(params);
        return r;
    }

    @Nullable
    public String getKind() {
        return kind;
    }

    @Nullable
    public Map<String, String> getParams() {
        return params == null ? null : new LinkedHashMap<>(params);
    }

    /** True when no kind is authored, so this entry can never pay anything out. */
    public boolean isBlank() {
        return kind == null || kind.isBlank();
    }

    /** The engine's reward value. A blank kind yields null rather than an unpayable spec. */
    @Nullable
    public RewardSpec toSpec() {
        if (isBlank()) {
            return null;
        }
        return params == null || params.isEmpty()
                ? RewardSpec.of(kind.trim())
                : RewardSpec.of(kind.trim(), params);
    }
}
