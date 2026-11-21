package com.ziggfreed.common.instance.dungeon.tier;

import java.util.Locale;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.instance.dungeon.DungeonAsset;
import com.ziggfreed.common.instance.dungeon.DungeonConfig;
import com.ziggfreed.common.instance.dungeon.DungeonRuns;

/**
 * The factor {@value #FACTOR}: the {@code Tier.Rank} of the dungeon run the subject stands in, so one loot table, chance
 * or gate steps up by depth (a {@code Ladder} on it pays the highest floor reached). A player is placed by the area they
 * stand in, anything else by where it stands, as the omen reading is ({@code omen/RunOmens}). A dungeon that writes no
 * {@code Tier} reads 1, and a daily reads the tier its {@code Parent} is; a subject in no run reads nothing, so a gate on
 * it shuts and a term on it adds 0. It answers until the run's party leaves, after a clear too (R1000), so a chest a
 * room raises after the last boss still reads its depth. It takes no {@code Param}.
 *
 * <p>Every vocabulary on the server reads it ({@link FactorContributions}): a boss fight's Claim rolled about its
 * player, a dungeon's clear and a reward chest's {@code Contents} rolled as the player ({@code claims/ClaimRolls}).
 */
public final class RunTiers {

    /** Who the factor is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    /** The depth reading: the run's {@code Tier.Rank}, 1 for an untiered dungeon. */
    public static final String FACTOR = "ziggfreedcommon:dungeon_tier";

    private RunTiers() {
    }

    /** Claim {@value #FACTOR} process-wide. Once, at setup. */
    public static void contribute() {
        FactorContributions.register(FACTOR, OWNER, RunTiers::resolve);
    }

    /**
     * {@value #FACTOR}: the {@code Tier.Rank} (at least 1) of the run the subject stands in, 1 for a dungeon no longer
     * loaded; null for no live subject or a subject in no run. On the subject's world thread.
     */
    @Nullable
    static Double resolve(@Nonnull FactorContext ctx) {
        if (!ctx.hasLiveSubject()) {
            return null;
        }
        Store<EntityStore> store = ctx.store();
        Ref<EntityStore> subject = ctx.subject();
        if (store == null || subject == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return null;
        }
        UUID worldUuid = world.getWorldConfig().getUuid();
        PlayerRef player = store.getComponent(subject, PlayerRef.getComponentType());
        DungeonRuns.LiveRun run;
        if (player != null) {
            run = DungeonRuns.shared().liveRun(player.getUuid(), worldUuid, null);
        } else {
            TransformComponent at = store.getComponent(subject, TransformComponent.getComponentType());
            run = at == null ? null : DungeonRuns.shared().liveRun(null, worldUuid, new Vector3d(at.getPosition()));
        }
        if (run == null) {
            return null;
        }
        DungeonAsset dungeon = DungeonConfig.getInstance().resolve(run.dungeonId().toLowerCase(Locale.ROOT));
        return (double) (dungeon == null ? 1 : dungeon.tierRank());
    }
}
