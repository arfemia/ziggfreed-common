package com.ziggfreed.common.objectives.bonus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InteractivelyPickupItemEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.CommandRunner;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.loot.FactorSnapshot;
import com.ziggfreed.common.loot.LootCues;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.loot.trigger.BonusChance;
import com.ziggfreed.common.loot.trigger.BonusMoment;
import com.ziggfreed.common.loot.trigger.BonusPasses;
import com.ziggfreed.common.loot.trigger.BonusRow;
import com.ziggfreed.common.loot.trigger.BonusRowConfig;
import com.ziggfreed.common.objectives.producer.BlockBreakPayload;
import com.ziggfreed.common.objectives.producer.MobKillPayload;
import com.ziggfreed.common.objectives.producer.PickupPayload;
import com.ziggfreed.common.objectives.producer.ZigBlockBreakProducer;
import com.ziggfreed.common.objectives.producer.ZigMobKillProducer;
import com.ziggfreed.common.objectives.producer.ZigPickupProducer;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentListener;
import com.ziggfreed.common.progress.runtime.MomentPayload;
import com.ziggfreed.common.subject.PlayerRefSubjectHandle;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The library's bonus rows on every produced break, hand harvest and kill: a {@link MomentListener}
 * that reads only the library's own table ({@link BonusRowConfig}), never a consumer's.
 *
 * <p>No gate beyond the row's own: the producers already skip what the player placed and credit a
 * kill to a player only. A row with no {@code Chance} always fires; its odds are its rolls'. The
 * pass pays through the moment's own quest subject (the runtime's, so a consumer's notification
 * preferences reach a cue toast), or a subject over the player reference when the runtime has none.
 * A harvest's pass waits for one world task past the engine's own give and re-reads the event there.
 */
public final class BonusRowReactions implements MomentListener {

    /** The one instance; it holds no state. */
    public static final BonusRowReactions INSTANCE = new BonusRowReactions();

    /** What every pass this listener runs is labelled with, before the row's id. */
    public static final String SOURCE_PREFIX = "bonusrow:";

    /** A library row with no Chance always fires: the odds are its rolls'. */
    static final double UNWRITTEN_CHANCE = 1.0;

    /** The vocabulary a row reads before the wiring root installs the loot one: contributed ids only. */
    private static final FactorRegistry CONTRIBUTIONS_ONLY = new FactorRegistry("bonus-rows");

    private BonusRowReactions() {
    }

    @Override
    public void react(@Nonnull Moment moment) {
        BonusRow row = rowFor(moment);
        if (row == null) {
            return;
        }
        try {
            switch (row.moment()) {
                case BREAK_BLOCK -> onBreak(moment, row, moment.payload(BlockBreakPayload.class));
                case KILL_MOB -> onKill(moment, row, moment.payload(MobKillPayload.class));
                case PICKUP_ITEM -> onPickup(moment, row, moment.payload(PickupPayload.class));
            }
        } catch (Throwable t) {
            SafeLog.warn("Bonus row '" + row.sourceId() + "' failed: " + t.getMessage());
        }
    }

    // ==================== the decision, read before any world handle ====================

    /** The bonus moment a produced kind is, or null for a kind no bonus row answers to. */
    @Nullable
    static BonusMoment momentFor(@Nonnull String kindId) {
        return switch (kindId) {
            case ZigBlockBreakProducer.KIND -> BonusMoment.BREAK_BLOCK;
            case ZigMobKillProducer.KIND -> BonusMoment.KILL_MOB;
            case ZigPickupProducer.KIND -> BonusMoment.PICKUP_ITEM;
            default -> null;
        };
    }

    /** The producer's own record a moment must carry before this listener pays it. */
    @Nonnull
    static Class<? extends MomentPayload> payloadOf(@Nonnull BonusMoment moment) {
        return switch (moment) {
            case BREAK_BLOCK -> BlockBreakPayload.class;
            case KILL_MOB -> MobKillPayload.class;
            case PICKUP_ITEM -> PickupPayload.class;
        };
    }

    /**
     * The row this moment pays, or null when it pays none: a kind no bonus moment answers to, a
     * moment fired without its producer's own record, no row covering the target, or a hole.
     * Reads no world handle.
     */
    @Nullable
    static BonusRow rowFor(@Nonnull Moment moment) {
        BonusMoment kind = momentFor(moment.kindId());
        if (kind == null || moment.payload(payloadOf(kind)) == null) {
            return null;
        }
        BonusRow row = BonusRowConfig.getInstance().bestFor(kind, moment.target());
        return row == null || row.handsNothingOver() ? null : row;
    }

    // ==================== the three moments ====================

    private static void onBreak(@Nonnull Moment moment, @Nonnull BonusRow row, @Nonnull BlockBreakPayload payload) {
        CommandBuffer<EntityStore> buffer = moment.commandBuffer();
        if (buffer == null) {
            return;
        }
        Store<EntityStore> store = moment.store();
        FactorContext about = context(store, moment.ref(), null, null);
        if (!fires(row, about)) {
            return;
        }
        Subject subject = subjectOf(moment);
        String sourceId = SOURCE_PREFIX + row.sourceId();
        BonusPasses.Outcome pass = BonusPasses.run(BonusPasses.atBlock(store, buffer, payload.event().getTargetBlock()),
                BonusRowConfig.getInstance().rolls().resolved(row), new FactorSnapshot(factors(), about),
                ThreadLocalRandom.current()::nextDouble, null, subject, CommandRunner.CONSOLE,
                placeholders(subject, "block", moment.target()), sourceId, SafeLog::warn);
        LootCues.presentAll(pass.result().getCues(), subject, sourceId);
    }

    private static void onKill(@Nonnull Moment moment, @Nonnull BonusRow row, @Nonnull MobKillPayload payload) {
        CommandBuffer<EntityStore> buffer = moment.commandBuffer();
        if (buffer == null) {
            return;
        }
        Store<EntityStore> store = moment.store();
        Ref<EntityStore> victim = payload.victimRef();
        FactorContext about = context(store, moment.ref(), victim, null);
        if (!fires(row, about)) {
            return;
        }
        Subject subject = subjectOf(moment);
        String sourceId = SOURCE_PREFIX + row.sourceId();
        BonusPasses.Outcome pass = BonusPasses.run(BonusPasses.atCorpse(store, buffer, victim),
                BonusRowConfig.getInstance().rolls().resolved(row), new FactorSnapshot(factors(), about),
                ThreadLocalRandom.current()::nextDouble, null, subject, CommandRunner.CONSOLE,
                placeholders(subject, "mob", moment.target()), sourceId, SafeLog::warn);
        LootCues.presentAll(pass.result().getCues(), subject, sourceId);
    }

    private static void onPickup(@Nonnull Moment moment, @Nonnull BonusRow row, @Nonnull PickupPayload payload) {
        Store<EntityStore> store = moment.store();
        Ref<EntityStore> ref = moment.ref();
        Subject subject = subjectOf(moment);
        String target = moment.target();
        InteractivelyPickupItemEvent event = payload.event();
        BonusPasses.afterHarvest(store, () -> runPickup(store, ref, row, event, subject, target));
    }

    /** The deferred harvest pass, on the world thread after the engine's own give. */
    private static void runPickup(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull BonusRow row, @Nonnull InteractivelyPickupItemEvent event, @Nonnull Subject subject,
            @Nonnull String target) {
        try {
            ItemStack harvest = BonusPasses.harvestOf(event);
            if (harvest == null || !ref.isValid()) {
                return;
            }
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                return;
            }
            FactorContext about = context(store, ref, null, harvest);
            if (!fires(row, about)) {
                return;
            }
            String sourceId = SOURCE_PREFIX + row.sourceId();
            BonusPasses.Outcome pass = BonusPasses.run(BonusPasses.atHarvester(ref, player),
                    BonusRowConfig.getInstance().rolls().resolved(row), new FactorSnapshot(factors(), about),
                    ThreadLocalRandom.current()::nextDouble, harvest, subject, CommandRunner.CONSOLE,
                    placeholders(subject, "item", target), sourceId, SafeLog::warn);
            LootCues.presentAll(pass.result().getCues(), subject, sourceId);
        } catch (Throwable t) {
            SafeLog.warn("Bonus row '" + row.sourceId() + "' harvest pass failed: " + t.getMessage());
        }
    }

    // ==================== shared by the three ====================

    private static boolean fires(@Nonnull BonusRow row, @Nonnull FactorContext about) {
        double chance = BonusChance.fraction(row.chance(), UNWRITTEN_CHANCE, factors(), about, row.sourceId(),
                SafeLog::warn);
        return BonusChance.fires(chance, ThreadLocalRandom.current()::nextDouble);
    }

    /** The loot vocabulary the rolling kinds read, or contributed ids only before the root installs one. */
    @Nonnull
    static FactorRegistry factors() {
        FactorRegistry installed = LootRewardKinds.installedFactors();
        return installed != null ? installed : CONTRIBUTIONS_ONLY;
    }

    /** The question a row's odds and rolls are asked: the player, a kill's victim, a harvest's stack. */
    @Nonnull
    static FactorContext context(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> player,
            @Nullable Ref<EntityStore> victim, @Nullable ItemStack item) {
        FactorContext.Builder ctx = FactorContext.builder().store(store).item(item);
        if (player != null && player.isValid()) {
            ctx.subject(player);
        }
        if (victim != null && victim.isValid()) {
            ctx.target(victim);
        }
        return ctx.build();
    }

    /** The moment's own quest subject, or a subject over the player reference when it has none. */
    @Nonnull
    static Subject subjectOf(@Nonnull Moment moment) {
        Subject quest = moment.questSubject();
        if (quest != null) {
            return quest;
        }
        PlayerRef playerRef = PlayerAccess.playerRef(moment.store(), moment.ref());
        String username = playerRef == null ? null : playerRef.getUsername();
        return PlayerRefSubjectHandle.subjectFor(playerRef, username == null ? "" : username);
    }

    /** A row's command placeholders: {@code {player}} and the moment's own key. */
    @Nonnull
    static Map<String, String> placeholders(@Nonnull Subject subject, @Nonnull String key, @Nonnull String value) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("player", subject.name());
        out.put(key, value);
        return out;
    }
}
