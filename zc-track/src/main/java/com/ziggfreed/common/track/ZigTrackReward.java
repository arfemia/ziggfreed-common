package com.ziggfreed.common.track;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.rewards.asset.RewardAsset;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.instance.reward.DeferredRewards;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.util.SafeLog;

/**
 * Value on a track, as a native reward the engine's own {@code GrantReward} action pays: a file at
 * {@code Server/Rewards/<Id>.json} whose {@code Type} is {@code ZigTrack}, named by an encounter script's
 * {@code {"Type": "GrantReward", "Reward": "<Id>"}}.
 *
 * <pre>{@code
 * { "Type": "ZigTrack", "Track": "Yourmod_Mining", "Amount": 2500, "TitleKey": "yourmod.boss.claim.mining" }
 * }</pre>
 *
 * <p>Rewards are collected, never handed over (M428): the grant parks one Claim paying the shared
 * {@code Track} reward kind ({@link TrackRewardKind}), which the player takes on their Claims tab, where the
 * gain seam, the cap and the unlocks apply as for any gain. Registered from the track module's bootstrap into
 * the native reward codec; the type is the engine's own {@code RewardAsset}, so a file loads, inherits through
 * {@code Parent} and is checked by a script's {@code GrantReward} exactly as the game's own rewards are.
 */
public final class ZigTrackReward extends RewardAsset {

    /** The {@code Type} a reward file writes to be one of these. */
    public static final String TYPE = "ZigTrack";

    /** What a Claim this reward parks starts its id and source with. */
    static final String SOURCE_PREFIX = "reward:";

    /**
     * The title a track's Claim reads with when nothing authors one ({@code {0}} the track's name,
     * {@link TrackText#claimNameArg}); an objective's {@code ZigTrack} completion falls back to it too.
     */
    public static final String CLAIM_TITLE_KEY = TrackText.PREFIX + "claim.reward";

    /** The name a deferred command keeps for whoever presses Claim. */
    private static final String CLAIMER = "{player}";

    @Nullable private String track;
    @Nullable private Long amount;
    @Nullable private String titleKey;

    public static final BuilderCodec<ZigTrackReward> CODEC =
            BuilderCodec.builder(ZigTrackReward.class, ZigTrackReward::new)
                    .appendInherited(new KeyedCodec<>("Track", Codec.STRING, false),
                            (o, v) -> o.track = v, o -> o.track, (o, p) -> o.track = p.track)
                    .metadata(EditorSchema.assetRef(TrackAsset.class))
                    .documentation("The track the reward moves, by id (a file under "
                            + "Server/ZiggfreedCommon/Tracks). Unauthored, the reward pays nothing.").add()
                    .appendInherited(new KeyedCodec<>("Amount", Codec.LONG, false),
                            (o, v) -> o.amount = v, o -> o.amount, (o, p) -> o.amount = p.amount)
                    .documentation("How much value the reward adds to the track when the player collects it; "
                            + "a negative Amount costs value. Zero, or unauthored, pays nothing.").add()
                    .appendInherited(new KeyedCodec<>("TitleKey", Codec.STRING, false),
                            (o, v) -> o.titleKey = v, o -> o.titleKey, (o, p) -> o.titleKey = p.titleKey)
                    .documentation("The lang key the Claim's row is titled with on the Claims tab. Unauthored, "
                            + "the track's own name titles it.").add()
                    .build();

    public ZigTrackReward() {
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

    /** The engine's grant: parks one Claim, never throws; a failure costs this player's Claim and a warning. */
    @Override
    public void grant(@Nonnull PlayerRef playerRef) {
        String rewardId = getId() == null ? TYPE : getId();
        try {
            UUID player = playerRef.getUuid();
            String trackId = trackId();
            TrackDef def = TrackService.def(trackId);
            if (player == null || def == null || amount() == 0) {
                SafeLog.info("[track] reward " + rewardId + ": no usable track or no amount, nothing to pay");
                return;
            }
            String source = SOURCE_PREFIX + rewardId;
            List<InstanceReward> rewards = DeferredRewards.fromSpecs(
                    List.of(TrackRewardKind.spec(def.asset().getId(), amount())), RewardKinds.shared(),
                    Subject.of(player, CLAIMER), source, message -> SafeLog.warn("[track] " + message));
            long now = System.currentTimeMillis();
            String title = titleKey == null || titleKey.isBlank() ? CLAIM_TITLE_KEY : titleKey.trim();
            Claim claim = new Claim(source + ":" + player + ":" + now, source, title,
                    List.of(TrackText.claimNameArg(def.asset())), null, rewards, now);
            ClaimStore.shared().award(player, claim, null, source, now);
        } catch (Throwable t) {
            SafeLog.warn("[track] the " + rewardId + " Claim could not be written: " + t.getMessage());
        }
    }

    @Nonnull
    @Override
    public String toString() {
        return "ZigTrackReward{track=" + track + ", amount=" + amount + ", titleKey=" + titleKey + "} "
                + super.toString();
    }
}
