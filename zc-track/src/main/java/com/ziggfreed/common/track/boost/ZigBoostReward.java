package com.ziggfreed.common.track.boost;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * A boost, as a native reward the engine's own {@code GrantReward} action pays: a file at
 * {@code Server/Rewards/<Id>.json} whose {@code Type} is {@code ZigBoost}, named by an encounter script's
 * {@code {"Type": "GrantReward", "Reward": "<Id>"}}.
 *
 * <pre>{@code
 * { "Type": "ZigBoost", "Boost": "Yourmod_Harvest_Boost", "TitleKey": "yourmod.boss.claim.boost" }
 * }</pre>
 *
 * <p>Rewards are collected, never handed over (M428): the grant parks one Claim paying the shared {@code Boost}
 * reward kind ({@link BoostRewardKind}), and the boost starts when the player collects it, its limits, wait,
 * season and {@code Requires} applying as for any start. Registered from the boost bootstrap into the native
 * reward codec, so a file loads, inherits through {@code Parent} and is checked by a script's {@code GrantReward}
 * exactly as the game's own rewards are.
 */
public final class ZigBoostReward extends RewardAsset {

    /** The {@code Type} a reward file writes to be one of these. */
    public static final String TYPE = "ZigBoost";

    /** What a Claim this reward parks starts its id and source with. */
    static final String SOURCE_PREFIX = "reward:";

    /** The name a deferred command keeps for whoever presses Claim. */
    private static final String CLAIMER = "{player}";

    @Nullable private String boost;
    @Nullable private Double percent;
    @Nullable private Long seconds;
    @Nullable private String titleKey;

    public static final BuilderCodec<ZigBoostReward> CODEC =
            BuilderCodec.builder(ZigBoostReward.class, ZigBoostReward::new)
                    .appendInherited(new KeyedCodec<>("Boost", Codec.STRING, false),
                            (o, v) -> o.boost = v, o -> o.boost, (o, p) -> o.boost = p.boost)
                    .metadata(EditorSchema.assetRef(BoostAsset.class))
                    .documentation("The boost the reward starts once collected, by id (a file under "
                            + "Server/ZiggfreedCommon/Boosts). Unauthored, the reward pays nothing.").add()
                    .appendInherited(new KeyedCodec<>("Percent", Codec.DOUBLE, false),
                            (o, v) -> o.percent = v, o -> o.percent, (o, p) -> o.percent = p.percent)
                    .documentation("How much this reward's boost lifts by, as a percent, in place of the boost's "
                            + "own. Leave it out for the boost's own.").add()
                    .appendInherited(new KeyedCodec<>("Seconds", Codec.LONG, false),
                            (o, v) -> o.seconds = v, o -> o.seconds, (o, p) -> o.seconds = p.seconds)
                    .documentation("How long this reward's boost runs, in seconds, in place of the boost's own. "
                            + "Leave it out for the boost's own.").add()
                    .appendInherited(new KeyedCodec<>("TitleKey", Codec.STRING, false),
                            (o, v) -> o.titleKey = v, o -> o.titleKey, (o, p) -> o.titleKey = p.titleKey)
                    .documentation("The lang key the Claim's row is titled with on the Claims tab. Unauthored, "
                            + "the boost's own name titles it.").add()
                    .build();

    public ZigBoostReward() {
    }

    /** The boost started, trimmed, or null. */
    @Nullable
    public String boostId() {
        return boost == null || boost.isBlank() ? null : boost.trim();
    }

    /** The engine's grant: parks one Claim, never throws; a failure costs this player's Claim and a warning. */
    @Override
    public void grant(@Nonnull PlayerRef playerRef) {
        String rewardId = getId() == null ? TYPE : getId();
        try {
            UUID player = playerRef.getUuid();
            BoostAsset live = BoostConfig.getInstance().live(boostId());
            if (player == null || live == null) {
                SafeLog.info("[boost] reward " + rewardId + ": no boost this server has, nothing to pay");
                return;
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("Boost", live.getId());
            if (percent != null && Double.isFinite(percent)) {
                params.put("Percent", Double.toString(percent));
            }
            if (seconds != null && seconds > 0L) {
                params.put("Seconds", Long.toString(seconds));
            }
            String source = SOURCE_PREFIX + rewardId;
            List<InstanceReward> rewards = DeferredRewards.fromSpecs(
                    List.of(RewardSpec.of(BoostRewardKind.KIND, params)), RewardKinds.shared(),
                    Subject.of(player, CLAIMER), source, message -> SafeLog.warn("[boost] " + message));
            long now = System.currentTimeMillis();
            String title = titleKey == null || titleKey.isBlank() ? BoostText.PREFIX + "claim.reward" : titleKey.trim();
            Claim claim = new Claim(source + ":" + player + ":" + now, source, title,
                    List.of(BoostText.claimNameArg(live)), null, rewards, now);
            ClaimStore.shared().award(player, claim, null, source, now);
        } catch (Throwable t) {
            SafeLog.warn("[boost] the " + rewardId + " Claim could not be written: " + t.getMessage());
        }
    }

    @Nonnull
    @Override
    public String toString() {
        return "ZigBoostReward{boost=" + boost + ", percent=" + percent + ", seconds=" + seconds + ", titleKey="
                + titleKey + "} " + super.toString();
    }
}
