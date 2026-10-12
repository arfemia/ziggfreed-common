package com.ziggfreed.common.encounter.payout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.rewards.asset.RewardAsset;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.diminish.DiminishSpec;

/**
 * A boss's Claim, as a native reward the engine's own {@code GrantReward} action pays: a file at
 * {@code Server/Rewards/<Id>.json} whose {@code Type} is {@code ZigClaim}, named by an encounter script's
 * {@code {"Type": "GrantReward", "Reward": "<Id>"}} after the boss falls.
 *
 * <pre>{@code
 * {
 *   "Type": "ZigClaim",
 *   "Lootable": "My_Boss_Daily",
 *   "Diminish": { "Steps": [1, 0], "Window": "Daily" },
 *   "ShareScaled": true,
 *   "TitleKey": "mymod.boss.claim.title"
 * }
 * }</pre>
 *
 * <p>The engine grants it to every member of the encounter and hands it the player alone, never the run,
 * so the grant finds the player's run itself ({@link EncounterClaims#grant}): a credited player of a
 * defeated run is paid one Claim from the shares the defeat settled, a member below the rule's minimum
 * share and a run that was lost, leashed or never fought pay nothing, and a player in no run at all (a
 * world event's grant) is paid at a full share. Rewards are collected, never handed over (M428): what the
 * roll lands waits on the player's Claims tab.
 *
 * <p>Registered from the encounter module's bootstrap into the native reward codec; the type is the
 * engine's own {@code RewardAsset}, so a file loads, inherits through {@code Parent} and is checked by a
 * script's {@code GrantReward} exactly as the game's own rewards are.
 */
public final class ZigClaimReward extends RewardAsset {

    /** The {@code Type} a reward file writes to be one of these. */
    public static final String TYPE = "ZigClaim";

    @Nullable private String lootable;
    @Nullable private DiminishSpec diminish;
    @Nullable private Boolean shareScaled;
    @Nullable private String titleKey;

    public static final BuilderCodec<ZigClaimReward> CODEC =
            BuilderCodec.builder(ZigClaimReward.class, ZigClaimReward::new)
                    .appendInherited(new KeyedCodec<>("Lootable", Codec.STRING, false),
                            (o, v) -> o.lootable = v, o -> o.lootable, (o, p) -> o.lootable = p.lootable)
                    .metadata(EditorSchema.assetRef(LootableAsset.class))
                    .documentation("The loot table the Claim rolls, by id (a file under "
                            + "Server/ZiggfreedCommon/Lootables/). Rolled once for each player it pays; what it "
                            + "lands waits on that player's Claims tab. Unauthored, the reward pays nothing.")
                    .add()
                    .appendInherited(new KeyedCodec<>("Diminish", DiminishSpec.CODEC, false),
                            (o, v) -> o.diminish = v, o -> o.diminish, (o, p) -> o.diminish = p.diminish)
                    .documentation("How much a repeat earns in one window, counted per player for this reward on "
                            + "this boss and difficulty. Unauthored, the first one each day pays in full and the "
                            + "rest of that day pay nothing (Steps [1, 0], Window Daily).")
                    .add()
                    .appendInherited(new KeyedCodec<>("ShareScaled", Codec.BOOLEAN, false),
                            (o, v) -> o.shareScaled = v, o -> o.shareScaled, (o, p) -> o.shareScaled = p.shareScaled)
                    .metadata(EditorSchema.defaultValue(true))
                    .documentation("True keeps each roll a player wins with a chance equal to their share of the "
                            + "fight, so the top contributor keeps everything and a latecomer keeps a little. False "
                            + "pays every credited player the whole roll. A player in no fight is always paid in full.")
                    .add()
                    .appendInherited(new KeyedCodec<>("TitleKey", Codec.STRING, false),
                            (o, v) -> o.titleKey = v, o -> o.titleKey, (o, p) -> o.titleKey = p.titleKey)
                    .documentation("The lang key the Claim's row is titled with on the Claims tab. Unauthored, "
                            + "the fight's own name titles it, or the tab's own word for a reward outside a fight.")
                    .add()
                    .build();

    public ZigClaimReward() {
    }

    /** The loot table rolled, as written, or null when none is. */
    @Nullable
    public String lootable() {
        return lootable == null || lootable.isBlank() ? null : lootable.trim();
    }

    /** The diminishing returns as written, or null when none are. */
    @Nullable
    public DiminishSpec diminish() {
        return diminish;
    }

    /** The diminishing returns the Claim is counted under: as written, else the first each day ({@link EncounterClaims#FIRST_EACH_DAY}). */
    @Nonnull
    public DiminishSpec diminishOrDefault() {
        return diminish == null ? EncounterClaims.FIRST_EACH_DAY : diminish;
    }

    /** Whether each won roll is kept at the player's share; true when unauthored. */
    public boolean shareScaled() {
        return shareScaled == null || shareScaled;
    }

    /** The Claim's title key as written, or null when none is. */
    @Nullable
    public String titleKey() {
        return titleKey == null || titleKey.isBlank() ? null : titleKey.trim();
    }

    /** The engine's grant: always answers, a failure costing this one player's Claim and a warning. */
    @Override
    public void grant(@Nonnull PlayerRef playerRef) {
        EncounterClaims.grant(this, playerRef);
    }

    @Nonnull
    @Override
    public String toString() {
        return "ZigClaimReward{lootable=" + lootable + ", diminish=" + diminish + ", shareScaled=" + shareScaled
                + ", titleKey=" + titleKey + "} " + super.toString();
    }
}
