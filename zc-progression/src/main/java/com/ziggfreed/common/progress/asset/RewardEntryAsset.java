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
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;

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
 * <p><b>{@code Requires} gates this one row</b>, in the quest block's shape (zc-core's {@code GateSpec}), every
 * condition read. A plain top-level {@code hytale:mod_installed} condition with {@code Min: 1} naming a mod
 * that reads a definite 0 keeps the row off the server: {@link #toSpec} answers null, so the row pays nothing,
 * shows nowhere and no audit reads it, while its siblings pay as written. Every store whose files carry rows
 * counts the ones its fold left out in one line per missing mod ({@code ModGates.reportRewardRows}, through
 * {@link #collectMissingMods}); a row read outside a store's fold (an interaction's or a dialogue action's
 * inline list) is absent the same way and never counted. Every other condition rides the spec
 * ({@code RewardSpec.requires()}) and is asked of the receiving player when the row pays: a row they do not
 * meet pays them nothing. Unauthored, a row is on every server and pays everyone.
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
    @Nullable private GateSpec gate;
    /**
     * The old presence-shape field, no longer written: {@link #getRequires()} derives that view from the block
     * on demand.
     *
     * @deprecated a row's {@code Requires} is read whole: {@link #requires()}. Removed in a later release.
     */
    @Deprecated(since = "2.6.0")
    @Nullable protected PresenceRequiresCodec.Block requires;
    /** The live part of {@link #requires}, lifted once per instance ({@link StoreGates#live}). */
    @Nullable private volatile GateSpec[] live;

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
                    .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                            (o, v) -> o.gate = v, o -> o.gate, (o, p) -> o.gate = p.gate)
                    .documentation(StoreGates.ROW_DOCUMENTATION).add()
                    .build();

    public RewardEntryAsset() {
    }

    /** Java-side factory for an ungated row; sets the same fields the codec fills. */
    @Nonnull
    public static RewardEntryAsset of(@Nullable String kind, @Nullable Map<String, String> params) {
        return of(kind, params, (GateSpec) null);
    }

    /** Java-side factory; sets the same fields the codec fills, {@code requires} included. */
    @Nonnull
    public static RewardEntryAsset of(@Nullable String kind, @Nullable Map<String, String> params,
            @Nullable GateSpec requires) {
        RewardEntryAsset r = new RewardEntryAsset();
        r.kind = kind;
        r.params = params == null ? null : new LinkedHashMap<>(params);
        r.gate = requires;
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

    /**
     * The 2.5 form, whose block was read for mod presence alone.
     *
     * @deprecated a row's {@code Requires} is read whole now: build it with
     *     {@link #of(String, Map, GateSpec)}. Removed in a later release.
     */
    @Deprecated(since = "2.6.0")
    @Nonnull
    public static RewardEntryAsset of(@Nullable String kind, @Nullable Map<String, String> params,
            @Nullable PresenceRequiresCodec.Block requires) {
        return of(kind, params, requires == null ? null
                : GateSpec.of(requires.factorsOrEmpty(), null, null, null, null, null, null));
    }

    /** The row's own {@code Requires}, or null when it authors none (the row is on every server and pays everyone). */
    @Nullable
    public GateSpec requires() {
        return gate;
    }

    /**
     * The block's conditions in the old presence shape, derived from the block on each call.
     *
     * @deprecated a row's {@code Requires} is read whole now: {@link #requires()} is the block. Removed in a
     *     later release.
     */
    @Deprecated(since = "2.6.0")
    @Nullable
    public PresenceRequiresCodec.Block getRequires() {
        return gate == null ? null : PresenceRequiresCodec.Block.of(gate.factorsOrEmpty());
    }

    /** The part of {@link #requires()} asked of the receiving player, lifted once; null when nothing is. */
    @Nullable
    public GateSpec liveRequires() {
        GateSpec[] held = live;
        if (held == null) {
            held = new GateSpec[] {StoreGates.live(gate)};
            live = held;
        }
        return held[0];
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
        return GateSpec.missingMod(gate);
    }

    /** Is this row on this server? False only when its own {@code Requires} names a mod that is missing. */
    public boolean passesModGate() {
        return missingMod() == null;
    }

    /**
     * The engine's reward value. A blank kind yields null rather than an unpayable spec, and so does a row
     * gated on a missing mod, which every payer and every reader of the folded rewards then leaves out. The
     * rest of the row's {@code Requires} rides the spec, asked of the receiving player when it pays.
     */
    @Nullable
    public RewardSpec toSpec() {
        if (isBlank() || !passesModGate()) {
            return null;
        }
        RewardSpec spec = params == null || params.isEmpty()
                ? RewardSpec.of(kind.trim())
                : RewardSpec.of(kind.trim(), params);
        return spec.withRequires(liveRequires());
    }

    /**
     * Do any of {@code rows} carry conditions beyond mod presence (a live {@code Requires})? What a store's
     * fold reads to name, once, the files whose row conditions now apply ({@code StoreGates.reportWidened}).
     */
    public static boolean anyWidened(@Nullable RewardEntryAsset[] rows) {
        if (rows == null) {
            return false;
        }
        for (RewardEntryAsset row : rows) {
            if (row != null && row.passesModGate() && row.liveRequires() != null) {
                return true;
            }
        }
        return false;
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
