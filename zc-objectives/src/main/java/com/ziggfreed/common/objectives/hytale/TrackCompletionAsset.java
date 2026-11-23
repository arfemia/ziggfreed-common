package com.ziggfreed.common.objectives.hytale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.adventure.objectives.config.completion.ObjectiveCompletionAsset;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.track.TrackRewardKind;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.price.TrackPriceAsset;

/**
 * A completion of one of Hytale's own Objectives that moves a CommonLib track, beside vanilla's
 * {@code Reputation} completion:
 *
 * <pre>{@code
 * "Completions": [ { "Type": "ZigTrack", "Track": "Yourmod_Mining", "Amount": 500 } ]
 * "Completions": [ { "Type": "ZigTrack", "Track": "Yourmod_Mining", "Price": "Quest", "Level": 30 } ]
 * }</pre>
 *
 * <p>It reads as the native {@code ZigTrack} reward file reads, and pays the same way: a Claim paying the shared
 * {@code Track} reward kind, which each participant collects (M428), where the gain seam, the cap and the unlocks
 * apply as for any gain. Registered at setup by {@link NativeObjectivesBootstrap}; it pays through
 * {@link TrackCompletion}.
 */
public final class TrackCompletionAsset extends ObjectiveCompletionAsset {

    /** The authored {@code "Type"} name, the native track reward's own. */
    public static final String TYPE = "ZigTrack";

    @Nonnull
    public static final BuilderCodec<TrackCompletionAsset> CODEC = BuilderCodec.builder(TrackCompletionAsset.class,
                    TrackCompletionAsset::new, ObjectiveCompletionAsset.BASE_CODEC)
            .documentation("Moves a CommonLib track when the objective completes, as one Claim each participant "
                    + "collects on the Claims tab, online or not; the gain applies when they collect it. Every "
                    + "ZigRewards and ZigTrack completion on one objective joins the same Claim.")
            .append(new KeyedCodec<>("Track", Codec.STRING, false),
                    (completion, track) -> completion.track = track, completion -> completion.track)
            .metadata(EditorSchema.assetRef(TrackAsset.class))
            .documentation("The track it moves, by id (a file under Server/ZiggfreedCommon/Tracks). Unauthored, or "
                    + "a track this server does not run, it pays nothing.")
            .add()
            .append(new KeyedCodec<>("Amount", Codec.LONG, false),
                    (completion, amount) -> completion.amount = amount, completion -> completion.amount)
            .documentation("How much value it adds to the track when the player collects it; a negative Amount "
                    + "costs value. Zero, or unauthored, pays nothing unless a Price or Minutes prices it instead.")
            .add()
            .append(new KeyedCodec<>("TitleKey", Codec.STRING, false),
                    (completion, key) -> completion.titleKey = key, completion -> completion.titleKey)
            .documentation("The lang key the Claim's row is titled with on the Claims tab. Unauthored, the "
                    + "objective's own title titles it when a line ships for it, else the track's own reward line.")
            .add()
            .append(new KeyedCodec<>("Price", Codec.STRING, false),
                    (completion, price) -> completion.price = price, completion -> completion.price)
            .metadata(EditorSchema.assetRef(TrackPriceAsset.class))
            .documentation("A row of the XP price table, by id (Quest, Board, ...): pay that many minutes of play "
                    + "at the track's pace instead of an Amount, priced at each participant's level when the Claim "
                    + "is written and paid past every boost.")
            .add()
            .append(new KeyedCodec<>("Minutes", Codec.DOUBLE, false),
                    (completion, minutes) -> completion.minutes = minutes, completion -> completion.minutes)
            .documentation("Minutes of play to pay, over the price row's (or with no Price at all).")
            .add()
            .append(new KeyedCodec<>("Level", Codec.INTEGER, false),
                    (completion, level) -> completion.level = level, completion -> completion.level)
            .documentation("Price the minutes at this level for everyone (one-off content), over the price row's.")
            .add()
            .append(new KeyedCodec<>("MaxLevel", Codec.INTEGER, false),
                    (completion, level) -> completion.maxLevel = level, completion -> completion.maxLevel)
            .documentation("Price the minutes at the participant's level, never above this one (repeatable "
                    + "content), over the price row's.")
            .add()
            .build();

    @Nullable
    private String track;
    @Nullable
    private Long amount;
    @Nullable
    private String titleKey;
    @Nullable
    private String price;
    @Nullable
    private Double minutes;
    @Nullable
    private Integer level;
    @Nullable
    private Integer maxLevel;

    private TrackCompletionAsset() {
    }

    /** The track moved, trimmed, or null. */
    @Nullable
    public String trackId() {
        return track == null || track.isBlank() ? null : track.trim();
    }

    /** The value moved; 0 when unauthored. */
    public long amount() {
        return amount == null ? 0L : amount;
    }

    /** The authored title key, or null. */
    @Nullable
    public String getTitleKey() {
        return titleKey;
    }

    /**
     * The Track reward this completion pays on {@code trackId} ({@code TrackRewardKind.of}): priced in minutes when it
     * names a Price or Minutes, else its fixed Amount; null when it pays nothing.
     */
    @Nullable
    public RewardSpec rewardOn(@Nonnull String trackId) {
        return TrackRewardKind.of(trackId, amount(), price, minutes, level, maxLevel);
    }

    @Nonnull
    @Override
    public String toString() {
        return "TrackCompletionAsset{track=" + track + ", amount=" + amount + ", titleKey=" + titleKey + "} "
                + super.toString();
    }
}
