package com.ziggfreed.common.progress.asset;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * The {@code Rewards} group EVERY kind of progression content carries: what a quest, an achievement,
 * or a points milestone pays, split by the two moments a payout can land in.
 *
 * <pre>{@code
 * "Rewards": {
 *   "Auto":  [ { "Kind": "Yourmod_Coin", "Params": { "Id": "coin", "Amount": "50" } } ],
 *   "Claim": [ { "Kind": "Item", "Params": { "Item": "Ingredient_Bar_Iron", "Count": "5" } } ]
 * }
 * }</pre>
 *
 * <p><b>Two lists, because there are two moments.</b> {@code Auto} is paid the instant the content
 * settles (the quest's steps are done, the achievement is earned, the total is crossed), wherever
 * the player happens to be. {@code Claim} waits on a surface for them to collect it, which is what
 * you want for anything that needs backpack room: a player with a full bag keeps the reward instead
 * of losing it on the floor. A reward you do not put in {@code Auto} waits to be collected, so
 * {@code Claim} is the bucket to reach for by default and {@code Auto} is the deliberate choice.
 *
 * <p><b>Why a shared group rather than three similar ones.</b> Three content types pay rewards, and
 * the moment their field names drift an author has to remember which spelling belongs to which kind
 * of content. Declaring the two buckets ONCE and appending the group into each codec makes that
 * drift impossible rather than merely discouraged.
 *
 * <p>Each bucket is one {@code appendInherited} leaf: content with a {@code Parent} may re-author
 * one bucket (replacing that list whole) and keep the other.
 */
public final class ContentRewardsAsset {

    @Nullable protected RewardEntryAsset[] auto;
    @Nullable protected RewardEntryAsset[] claim;

    public static final BuilderCodec<ContentRewardsAsset> CODEC =
            BuilderCodec.builder(ContentRewardsAsset.class, ContentRewardsAsset::new)
                    .appendInherited(new KeyedCodec<>("Auto",
                                    new ArrayCodec<>(RewardEntryAsset.CODEC, RewardEntryAsset[]::new), false),
                            (o, v) -> o.auto = v, o -> o.auto, (o, p) -> o.auto = p.auto)
                    .documentation("Paid the instant the content settles, wherever the player is. Keep it to "
                            + "things that need no bag room. This is ONE leaf: author it and an inherited list "
                            + "is replaced whole.")
                    .add()
                    .appendInherited(new KeyedCodec<>("Claim",
                                    new ArrayCodec<>(RewardEntryAsset.CODEC, RewardEntryAsset[]::new), false),
                            (o, v) -> o.claim = v, o -> o.claim, (o, p) -> o.claim = p.claim)
                    .documentation("Waits on a surface for the player to collect; the bucket a reward belongs "
                            + "in unless it must land on the spot. Where anything needing backpack room goes, "
                            + "so a full bag costs nobody a reward. This is ONE leaf: author it and an "
                            + "inherited list is replaced whole.")
                    .add()
                    .build();

    public ContentRewardsAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static ContentRewardsAsset of(@Nullable List<RewardEntryAsset> auto,
            @Nullable List<RewardEntryAsset> claim) {
        ContentRewardsAsset r = new ContentRewardsAsset();
        r.auto = auto == null ? null : auto.toArray(new RewardEntryAsset[0]);
        r.claim = claim == null ? null : claim.toArray(new RewardEntryAsset[0]);
        return r;
    }

    /** Paid the instant the content settles. */
    @Nonnull
    public List<RewardSpec> auto() {
        return build(auto);
    }

    /** Paid when the player collects. */
    @Nonnull
    public List<RewardSpec> claim() {
        return build(claim);
    }

    /**
     * The {@code Auto} bucket's entries exactly as authored, blanks included - for a validator that
     * must see an entry {@link #auto()} would drop for naming no Kind.
     */
    @Nonnull
    public RewardEntryAsset[] autoEntries() {
        return auto == null ? new RewardEntryAsset[0] : auto.clone();
    }

    /** The {@code Claim} bucket's entries exactly as authored, blanks included. */
    @Nonnull
    public RewardEntryAsset[] claimEntries() {
        return claim == null ? new RewardEntryAsset[0] : claim.clone();
    }

    /** True when neither bucket authors an entry - content that pays nothing at all. */
    public boolean isEmpty() {
        return (auto == null || auto.length == 0) && (claim == null || claim.length == 0);
    }

    @Nonnull
    private static List<RewardSpec> build(@Nullable RewardEntryAsset[] entries) {
        if (entries == null || entries.length == 0) {
            return List.of();
        }
        List<RewardSpec> out = new ArrayList<>(entries.length);
        for (RewardEntryAsset entry : entries) {
            RewardSpec reward = entry == null ? null : entry.toSpec();
            if (reward != null) {
                out.add(reward);
            }
        }
        return out;
    }
}
