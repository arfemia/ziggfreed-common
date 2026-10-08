package com.ziggfreed.common.progress.asset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;
import com.ziggfreed.common.asset.PresenceRequiresCodec;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.codec.ScalarStringCodec;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * One entry of a {@code Rewards} list: a registered reward KIND plus the parameters that kind reads.
 * The SAME entry shape wherever anything in this library pays a player out, so a reward written for
 * one kind of content reads and behaves identically on the next.
 *
 * <pre>{@code
 * "Rewards": [ { "Kind": "Item",         "Params": { "Item": "Sword_Copper", "Count": 1 } },
 *              { "Kind": "Yourmod_Coin", "Params": { "Id": "coin", "Amount": 50 } } ]
 * }</pre>
 *
 * <p><b>Kind ids read like any other asset id</b>: PascalCase with underscores. The framework's own
 * kinds are unprefixed ({@code Item}, {@code Lootable}, {@code Stamped_Item}, {@code Effect},
 * {@code Droplist}) because they belong to nobody in particular; a kind a mod brings carries that
 * mod's prefix ({@code Yourmod_Coin}), which is what keeps two mods' currencies apart. Matching is
 * case-insensitive, so an older file spelling one in lower case still resolves.
 *
 * <p><b>{@code Params} is deliberately an open map of strings.</b> What a reward needs is decided
 * by whichever mod registered the kind, so pinning a field set here would force every payout
 * through one mod's idea of what a reward is. Keys are matched case-insensitively, so authoring
 * {@code "Amount"} and reading {@code "amount"} agree without anyone being told. A VALUE may be
 * written bare where it is a number or a boolean ({@code "Amount": 50} and {@code "Amount": "50"}
 * both decode, to the same text), because a count's natural spelling should not fail the file; a
 * generator substituting a numeric axis value into a parameter slot lands legal for the same
 * reason.
 *
 * <p><b>{@code Requires} keeps one row off a server that lacks its mod</b>, in the file-level presence
 * block's JSON (zc-core's {@code PresenceRequiresCodec}, read through {@code ModGates}): a plain top-level
 * {@code hytale:mod_installed} condition with {@code Min: 1} naming a mod that reads a definite 0 makes
 * {@link #toSpec} answer null, so the row pays nothing, shows nowhere and no audit reads it, while its
 * siblings pay as written. Every store whose files carry rows counts the ones its fold left out in one line
 * per missing mod ({@code ModGates.reportRewardRows}, through {@link #collectMissingMods}); a row read
 * outside a store's fold (an interaction's or a dialogue action's inline list) is absent the same way and
 * never counted. Unauthored, a row is on every server.
 *
 * <pre>{@code
 * { "Kind": "Yourmod_Coin", "Params": { "Amount": 50 },
 *   "Requires": { "Factors": [ { "Factor": "hytale:mod_installed", "Param": "Yourgroup:Yourmod", "Min": 1 } ] } }
 * }</pre>
 *
 * <p>{@code Rewards} is ONE leaf as far as inheritance goes: omit it and the parent's list is
 * inherited whole, author it and the parent's list is replaced whole (an empty array is how a child
 * inherits everything else and pays out nothing).
 */
public final class RewardEntryAsset {

    @Nullable protected String kind;
    @Nullable protected Map<String, String> params;
    @Nullable protected PresenceRequiresCodec.Block requires;

    public static final BuilderCodec<RewardEntryAsset> CODEC =
            BuilderCodec.builder(RewardEntryAsset.class, RewardEntryAsset::new)
                    .appendInherited(new KeyedCodec<>("Kind", Codec.STRING, false),
                            (o, v) -> o.kind = v, o -> o.kind, (o, p) -> o.kind = p.kind)
                    .metadata(new UIEditor(new UIEditor.Dropdown(ProgressEditorDataSets.REWARD_KINDS)))
                    .documentation("Which registered reward kind pays this out, by id: Item, Lootable, "
                            + "Stamped_Item, Effect, Droplist, Command and Flair come with the framework, and a "
                            + "kind a mod brings "
                            + "carries that mod's prefix (Yourmod_Coin). A kind nothing registered is reported "
                            + "rather than silently skipped, so an owner can see which mod was expected to "
                            + "provide it; a row that only some servers can pay names its mod in Requires "
                            + "instead.").add()
                    .appendInherited(new KeyedCodec<>("Params", new InheritMapCodec<>(ScalarStringCodec.INSTANCE), false),
                            (o, v) -> o.params = v, o -> o.params, (o, p) -> o.params = p.params)
                    .documentation("The kind's own parameters. Which keys matter is documented by whoever "
                            + "registered the kind; nothing here interprets them. A number or true/false may be "
                            + "written bare (Amount: 50), a value with any other shape takes quotes.").add()
                    .appendInherited(new KeyedCodec<>("Requires", PresenceRequiresCodec.CODEC, false),
                            (o, v) -> o.requires = v, o -> o.requires, (o, p) -> o.requires = p.requires)
                    .documentation(PresenceRequiresCodec.ROW_DOCUMENTATION).add()
                    .build();

    public RewardEntryAsset() {
    }

    /** Java-side factory for an ungated row; sets the same fields the codec fills. */
    @Nonnull
    public static RewardEntryAsset of(@Nullable String kind, @Nullable Map<String, String> params) {
        return of(kind, params, null);
    }

    /** Java-side factory; sets the same fields the codec fills, {@code requires} included. */
    @Nonnull
    public static RewardEntryAsset of(@Nullable String kind, @Nullable Map<String, String> params,
            @Nullable PresenceRequiresCodec.Block requires) {
        RewardEntryAsset r = new RewardEntryAsset();
        r.kind = kind;
        r.params = params == null ? null : new LinkedHashMap<>(params);
        r.requires = requires;
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

    /** The row's own presence block, or null when it authors none (the row is on every server). */
    @Nullable
    public PresenceRequiresCodec.Block getRequires() {
        return requires;
    }

    /** True when no kind is authored, so this entry can never pay anything out. */
    public boolean isBlank() {
        return kind == null || kind.isBlank();
    }

    /**
     * The mod ({@code Group:Name}) whose absence keeps this row off this server, or null when the row is
     * here: the same reading a gated store gives a file's top-level block.
     */
    @Nullable
    public String missingMod() {
        return PresenceRequiresCodec.missingMod(requires);
    }

    /** Is this row on this server? False only when its own {@code Requires} names a mod that is missing. */
    public boolean passesModGate() {
        return missingMod() == null;
    }

    /**
     * The engine's reward value. A blank kind yields null rather than an unpayable spec, and so does a row
     * gated on a missing mod, which every payer and every reader of the folded rewards then leaves out.
     */
    @Nullable
    public RewardSpec toSpec() {
        if (isBlank() || !passesModGate()) {
            return null;
        }
        return params == null || params.isEmpty()
                ? RewardSpec.of(kind.trim())
                : RewardSpec.of(kind.trim(), params);
    }

    /**
     * The rows of {@code rows} that are on this server, in order: every row but one whose own
     * {@code Requires} names a missing mod. A null or blank row stays, for a validator's own check of it;
     * the form a validator reading authored entries audits, so an absent row raises no finding.
     */
    @Nonnull
    public static RewardEntryAsset[] present(@Nullable RewardEntryAsset[] rows) {
        if (rows == null || rows.length == 0) {
            return new RewardEntryAsset[0];
        }
        List<RewardEntryAsset> out = new ArrayList<>(rows.length);
        for (RewardEntryAsset row : rows) {
            if (row == null || row.passesModGate()) {
                out.add(row);
            }
        }
        return out.toArray(new RewardEntryAsset[0]);
    }

    /**
     * Add to {@code into} the missing mod of each row of {@code rows} its own gate leaves out, one entry
     * per row: what a store's fold hands {@code ModGates.reportRewardRows}. Nothing for null.
     */
    public static void collectMissingMods(@Nullable RewardEntryAsset[] rows, @Nonnull Collection<String> into) {
        if (rows == null) {
            return;
        }
        for (RewardEntryAsset row : rows) {
            String mod = row == null ? null : row.missingMod();
            if (mod != null) {
                into.add(mod);
            }
        }
    }
}
