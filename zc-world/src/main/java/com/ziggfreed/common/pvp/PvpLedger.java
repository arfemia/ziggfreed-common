package com.ziggfreed.common.pvp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.meta.MetaKey;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.damage.pvp.PvpHits;
import com.ziggfreed.common.entity.Allies;
import com.ziggfreed.common.entity.PlayerActivity;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.health.HealthUtil;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.loot.diminish.DiminishLedger;
import com.ziggfreed.common.playerdata.PlayerRecords;
import com.ziggfreed.common.util.SafeLog;

/**
 * The PvP ledger (SK18, R729): who struck whom, and what each player's death credits. It works on a server running the
 * library alone, and nothing pays until a track source, a quest or an achievement names one of its moments.
 *
 * <ul>
 *   <li><b>Who struck whom</b>: every applied hit one player lands on another ({@link PvpSystems.Struck}, after the
 *       engine has said the hit may land at all: its PvP switch, spawn protection, a safe zone) goes into the victim's
 *       {@link PvpTally}, in memory, kept a short while and forgotten when they die, change worlds or leave.</li>
 *   <li><b>A death</b> ({@link PvpSystems.Died}): the victim's streak ends; under the world's rules
 *       ({@link PvpWorldRules}) the death's credits are decided ({@link PvpCredit}), the repeat guard counting each
 *       credited player once (zc-loot's one {@link DiminishLedger}, keyed per player and victim), then the records are
 *       written ({@link PvpRecord}), the killer's streak grows, a bounty that stood ends ({@link PvpBounty}), the lines
 *       go out, and {@link ZigPlayerKilledEvent} fires for zc-objectives' PvP moments.</li>
 *   <li><b>Leaving mid-fight</b>: a player who leaves the server within the rules' {@code LogoutSeconds} of another
 *       player's hit loses their streak (and the bounty on it) before the engine saves them, and pays no one.</li>
 * </ul>
 *
 * <p>World thread for every engine read and record write: the death and the hit run on the victim's world thread, and
 * a departure hops there first. The maps are concurrent so a departure on another thread can forget a player.
 */
public final class PvpLedger {

    /** The effect tag that makes an aura's owner share in a kill its wearer lands: its value, whole percent. */
    public static final String AURA_ASSIST_TAG = "ZigPvpAssist";

    /** The game's own damage cause a command deals (its asset id: {@code /kill}, a stat run down by a command). */
    private static final String COMMAND_CAUSE = "Command";

    /** How long a victim remembers who killed them, whatever a grudge condition asks: half an hour. */
    static final long KILLER_MEMORY_MS = 30L * 60L * 1000L;

    /** Marks a hit the tally already holds, so the death never counts its killing blow twice. */
    @Nonnull
    static final MetaKey<Boolean> TALLIED = Damage.META_REGISTRY.registerMetaObject();

    /** Who killed one player, and when. */
    record Killed(@Nonnull UUID killer, long atMs) {
    }

    private static final Map<UUID, PvpTally> TALLIES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_STRUCK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> RESPAWNED = new ConcurrentHashMap<>();
    private static final Map<UUID, Killed> LAST_KILLER = new ConcurrentHashMap<>();

    /** The repeat guard's counts, in memory: a restart starts every window over. */
    private static final DiminishLedger REPEATS = new DiminishLedger("pvp-repeats");

    private static final NativeEventSeam SEAM = new NativeEventSeam("[pvp]");

    private PvpLedger() {
    }

    // ==================== who struck whom ====================

    /**
     * A hit of {@code amount} by the player {@code attacker} landed on the player {@code victim} at {@code nowMs}, as
     * applied. Marks the hit so the death reads it once. World thread.
     */
    static void struck(@Nonnull UUID victim, @Nonnull UUID attacker, double amount, @Nonnull Damage damage,
            long nowMs, long keepMs) {
        PvpTally tally = TALLIES.computeIfAbsent(victim, id -> new PvpTally());
        tally.prune(nowMs, keepMs);
        tally.add(attacker, amount, nowMs);
        LAST_STRUCK.put(victim, nowMs);
        try {
            damage.putMetaObject(TALLIED, Boolean.TRUE);
        } catch (Throwable ignored) {
            // A hit whose meta cannot be written is read again at the death, at worst counted once more.
        }
    }

    /** The player came back from death at {@code nowMs}. */
    static void respawned(@Nonnull UUID player, long nowMs) {
        RESPAWNED.put(player, nowMs);
    }

    /** Forget a player's hits taken (they died, changed worlds or left). */
    static void forgetHits(@Nullable UUID player) {
        if (player != null) {
            TALLIES.remove(player);
            LAST_STRUCK.remove(player);
        }
    }

    // ==================== a death ====================

    /**
     * The player at {@code victim} died, the engine's lethal hit {@code killing} (null when the death named none):
     * decide and pay its credits. World thread, inside the engine's death system.
     */
    static void died(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> victim, @Nullable Damage killing,
            long nowMs) {
        UUID victimId = idOf(store, victim);
        if (victimId == null) {
            return;
        }
        World world = store.getExternalData().getWorld();
        PvpRules rules = PvpWorldRules.forWorld(world);
        PvpRecord victimRecord = PvpRecord.of(store, victim);
        int streakBefore = victimRecord == null ? 0 : victimRecord.streak();
        if (victimRecord != null && streakBefore > 0) {
            victimRecord.endStreak();
            PlayerRecords.changed(victimId, PvpRecord.REGISTRY_ID);
        }
        try {
            if (!rules.enabled()) {
                if (PvpBounty.stands(streakBefore, rules.bounty())) {
                    PvpText.bountyLost(world, rules.bounty(), store, victim, streakBefore);
                }
                return;
            }
            PvpCredit.Outcome outcome = decide(store, victim, victimId, killing, rules, nowMs);
            pay(store, world, victim, victimId, streakBefore, outcome, rules, nowMs);
        } finally {
            forgetHits(victimId);
        }
    }

    /** Read the death and its victim, and ask the credit rules. */
    @Nonnull
    private static PvpCredit.Outcome decide(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> victim,
            @Nonnull UUID victimId, @Nullable Damage killing, @Nonnull PvpRules rules, long nowMs) {
        PvpRules.Credit credit = rules.credit();
        Ref<EntityStore> killerRef = killing == null ? null : PvpHits.playerAttacker(store, killing, victim);
        UUID directKiller = killerRef == null ? null : idOf(store, killerRef);
        boolean noOnesDeath = noOnesDeath(killing);
        PvpTally tally = TALLIES.computeIfAbsent(victimId, id -> new PvpTally());
        if (directKiller != null && killing.getIfPresentMetaObject(TALLIED) == null) {
            // The killing blow reached the death before the Inspect group tallied it: count it now, once.
            tally.add(directKiller, killing.getAmount(), nowMs);
        }
        Map<UUID, Double> shares = tally.shares(nowMs, credit.assistMillis());
        UUID lastHitter = tally.lastAttacker(nowMs, credit.environmentMillis());
        Map<UUID, Double> auras = killerRef != null ? auraShares(store, killerRef, directKiller)
                : auraOfLastHitter(store, noOnesDeath ? lastHitter : null);
        Long back = RESPAWNED.get(victimId);
        boolean fresh = back != null && credit.freshSpawnMillis() > 0L && nowMs - back <= credit.freshSpawnMillis();
        boolean idle = PlayerActivity.isIdle(victimId, credit.idleMillis(), nowMs);
        boolean pvpOn = Allies.pvpOn(store);
        PvpCredit.Input input = new PvpCredit.Input(victimId, directKiller, noOnesDeath, shares, lastHitter, auras,
                idle, fresh);
        return PvpCredit.decide(input, (player, other) -> Allies.areAllies(player, other, pvpOn),
                player -> REPEATS.count(player, "victim:" + victimId, credit.repeat(), nowMs));
    }

    /**
     * Was the death no one's (a fall, lava, drowning): a lethal hit with no entity behind it that no command dealt
     * (an admin's {@code /kill}, a stat run down by a command), so a player who struck just before may be credited.
     */
    private static boolean noOnesDeath(@Nullable Damage killing) {
        if (killing == null || killing.getSource() instanceof Damage.EntitySource
                || killing.getSource() instanceof Damage.CommandSource) {
            return false;
        }
        try {
            DamageCause cause = DamageCause.getAssetMap().getAsset(killing.getDamageCauseIndex());
            return cause == null || !COMMAND_CAUSE.equalsIgnoreCase(cause.getId());
        } catch (Throwable t) {
            return true;
        }
    }

    /** Write the records, settle the streaks and the bounty, say it, and announce the credits. */
    private static void pay(@Nonnull Store<EntityStore> store, @Nullable World world, @Nonnull Ref<EntityStore> victim,
            @Nonnull UUID victimId, int streakBefore, @Nonnull PvpCredit.Outcome outcome, @Nonnull PvpRules rules,
            long nowMs) {
        PvpRules.Bounty bountyRules = rules.bounty();
        boolean bountyStood = PvpBounty.stands(streakBefore, bountyRules);
        if (outcome.killer() != null && !Allies.areAllies(victimId, outcome.killer(), Allies.pvpOn(store))) {
            LAST_KILLER.put(victimId, new Killed(outcome.killer(), nowMs));
        }
        if (outcome.credits().isEmpty()) {
            if (bountyStood) {
                PvpText.bountyLost(world, bountyRules, store, victim, streakBefore);
            }
            return;
        }
        PvpRecord victimRecord = PvpRecord.of(store, victim);
        if (victimRecord != null) {
            victimRecord.addDeath();
            PlayerRecords.changed(victimId, PvpRecord.REGISTRY_ID);
        }
        double victimMax = HealthUtil.max(store, victim);
        List<ZigPlayerKilledEvent.Credit> credits = new ArrayList<>();
        ZigPlayerKilledEvent.Bounty bounty = null;
        for (PvpCredit.Credit credit : outcome.credits()) {
            // Only a player still in this world is paid: one who struck and then left for another world is read and
            // written on that world's thread, never this one, so their credit goes with them.
            Ref<EntityStore> ref = PlayerAccess.liveRef(credit.player());
            if (ref == null || ref.getStore() != store) {
                continue;
            }
            credits.add(new ZigPlayerKilledEvent.Credit(ref, credit.player(), credit.killingBlow(), credit.share(),
                    credit.weight()));
            if (credit.killingBlow() && bountyStood) {
                bounty = new ZigPlayerKilledEvent.Bounty(ref, credit.player(), streakBefore,
                        PvpBounty.worth(streakBefore, victimMax, PvpHits.points(store, ref, PvpHits.BOUNTY_BONUS),
                                bountyRules));
            }
            PvpRecord record = PvpRecord.of(store, ref);
            if (record == null) {
                continue;
            }
            if (credit.killingBlow()) {
                record.addKill();
                if (bountyStood) {
                    record.addBounty();
                }
                if (PvpBounty.raised(record.streak(), bountyRules)) {
                    PvpText.bountyRaised(world, bountyRules, store, ref, record.streak());
                } else if (PvpBounty.grew(record.streak(), bountyRules)) {
                    PvpText.bountyGrew(world, bountyRules, store, ref, record.streak());
                }
            } else {
                record.addAssist();
            }
            PlayerRecords.changed(credit.player(), PvpRecord.REGISTRY_ID);
        }
        if (bountyStood) {
            if (bounty != null) {
                PvpText.bountyEnded(world, bountyRules, store, bounty.hunter(), victim, streakBefore);
            } else {
                PvpText.bountyLost(world, bountyRules, store, victim, streakBefore);
            }
        }
        if (credits.isEmpty()) {
            return;
        }
        ZigPlayerKilledEvent.Bounty ended = bounty;
        SEAM.fire("ZigPlayerKilled", ZigPlayerKilledEvent.class,
                () -> new ZigPlayerKilledEvent(store, victim, victimId, victimMax, credits, ended));
    }

    /**
     * Each player whose aura the killer wears when they kill (an effect tagged {@value #AURA_ASSIST_TAG}, put on by
     * another player), with the tag's share, the largest per owner. Never the killer themself.
     */
    @Nonnull
    private static Map<UUID, Double> auraShares(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> wearer, @Nullable UUID wearerId) {
        Map<UUID, Double> out = new LinkedHashMap<>();
        try {
            EffectControllerComponent ctrl = accessor.getComponent(wearer, EffectControllerComponent.getComponentType());
            if (ctrl == null || ctrl.getActiveEffects().isEmpty()) {
                return out;
            }
            for (ActiveEntityEffect active : ctrl.getActiveEffects().values()) {
                Ref<EntityStore> owner = active.getOwner();
                if (owner == null || !owner.isValid()) {
                    continue;
                }
                EntityEffect effect = EntityEffect.getAssetMap().getAsset(active.getEntityEffectIndex());
                AssetExtraInfo.Data data = effect == null ? null : EntityEffect.CODEC.getData(effect);
                double share = auraShare(data == null ? null : data.getRawTags());
                UUID ownerId = share > 0.0 ? idOf(accessor, owner) : null;
                if (ownerId != null && !ownerId.equals(wearerId)) {
                    out.merge(ownerId, share, Math::max);
                }
            }
        } catch (Throwable t) {
            SafeLog.fine("[pvp] a killer's auras could not be read: " + t.getMessage());
        }
        return out;
    }

    /** The auras the last hitter wears, for a death that was no one's but credits them. */
    @Nonnull
    private static Map<UUID, Double> auraOfLastHitter(@Nonnull Store<EntityStore> store, @Nullable UUID lastHitter) {
        Ref<EntityStore> ref = lastHitter == null ? null : PlayerAccess.liveRef(lastHitter);
        return ref == null || ref.getStore() != store ? Map.of() : auraShares(store, ref, lastHitter);
    }

    /**
     * The share an effect's raw tags give its owner in a kill its wearer lands: its {@value #AURA_ASSIST_TAG} value in
     * whole percent, held to 0..100, as a fraction; 0 for none. Pure.
     */
    static double auraShare(@Nullable Map<String, String[]> rawTags) {
        if (rawTags == null) {
            return 0.0;
        }
        double best = 0.0;
        for (Map.Entry<String, String[]> entry : rawTags.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase(AURA_ASSIST_TAG) || entry.getValue() == null) {
                continue;
            }
            for (String value : entry.getValue()) {
                try {
                    double percent = value == null ? 0.0 : Double.parseDouble(value.trim().toLowerCase(Locale.ROOT));
                    if (Double.isFinite(percent)) {
                        best = Math.max(best, Math.min(100.0, percent) / 100.0);
                    }
                } catch (NumberFormatException ignored) {
                    // A value that is no number gives no share.
                }
            }
        }
        return best;
    }

    // ==================== leaving ====================

    /**
     * A player leaves the server: one who leaves within the rules' {@code LogoutSeconds} of another player's hit loses
     * their streak (and its bounty), written on their world thread before the engine removes and saves them; then the
     * ledger forgets them.
     */
    static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef player = event.getPlayerRef();
            UUID id = player == null ? null : player.getUuid();
            if (id == null) {
                return;
            }
            Long struckAt = LAST_STRUCK.get(id);
            Ref<EntityStore> ref = player.getReference();
            if (struckAt != null && ref != null && ref.isValid()) {
                long now = System.currentTimeMillis();
                World world = ref.getStore().getExternalData().getWorld();
                world.execute(() -> fled(ref, id, struckAt, now));
            } else {
                forget(id);
            }
        } catch (Throwable t) {
            SafeLog.fine("[pvp] a departing player could not be read: " + t.getMessage());
        }
    }

    /** On the player's world thread: end a streak left mid-fight, then forget them. */
    private static void fled(@Nonnull Ref<EntityStore> ref, @Nonnull UUID id, long struckAt, long nowMs) {
        try {
            if (!ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            World world = store.getExternalData().getWorld();
            PvpRules.Bounty bountyRules = PvpWorldRules.forWorld(world).bounty();
            PvpRecord record = PvpRecord.of(store, ref);
            if (record == null || record.streak() <= 0 || !PvpBounty.fled(struckAt, nowMs, bountyRules)) {
                return;
            }
            int streak = record.streak();
            record.endStreak();
            PlayerRecords.changed(id, PvpRecord.REGISTRY_ID);
            if (PvpBounty.stands(streak, bountyRules)) {
                PvpText.bountyFled(world, bountyRules, store, ref, streak);
            }
        } catch (Throwable t) {
            SafeLog.fine("[pvp] a streak left mid-fight could not be ended: " + t.getMessage());
        } finally {
            forget(id);
        }
    }

    /** A player left a world: their hits taken go (another world's fight credits nobody here). */
    static void onLeftWorld(@Nonnull RemovedPlayerFromWorldEvent event) {
        try {
            PlayerRef player = event.getHolder().getComponent(PlayerRef.getComponentType());
            if (player != null) {
                forgetHits(player.getUuid());
            }
        } catch (Throwable t) {
            SafeLog.fine("[pvp] a player leaving a world could not be read: " + t.getMessage());
        }
    }

    /** Forget everything held in memory about {@code player}. */
    static void forget(@Nonnull UUID player) {
        forgetHits(player);
        RESPAWNED.remove(player);
        LAST_KILLER.remove(player);
    }

    // ==================== readings ====================

    /** The streak of the player at {@code ref}: credited kills since they last died; 0 for none. World thread. */
    public static int streakOf(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> ref) {
        PvpRecord record = PvpRecord.of(accessor, ref);
        return record == null ? 0 : record.streak();
    }

    /** Does the player at {@code ref} carry a bounty under their world's rules? World thread. */
    public static boolean hasBounty(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> ref) {
        int streak = streakOf(accessor, ref);
        if (streak <= 0) {
            return false;
        }
        try {
            return PvpBounty.stands(streak, PvpWorldRules.forWorld(accessor.getExternalData().getWorld()).bounty());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Did the player {@code target} kill the player {@code holder} within {@code windowMs} of {@code nowMs} (the last
     * player to kill them, remembered half an hour at most)? What a grudge asks.
     */
    public static boolean killedBy(@Nullable UUID holder, @Nullable UUID target, long nowMs, long windowMs) {
        if (holder == null || target == null || windowMs <= 0L) {
            return false;
        }
        Killed killed = LAST_KILLER.get(holder);
        if (killed == null) {
            return false;
        }
        if (nowMs - killed.atMs() > KILLER_MEMORY_MS) {
            LAST_KILLER.remove(holder, killed);
            return false;
        }
        return killed.killer().equals(target) && nowMs - killed.atMs() <= windowMs;
    }

    /** The player's id at {@code ref}, or null for a creature or one that cannot be read. */
    @Nullable
    static UUID idOf(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> ref) {
        try {
            PlayerRef player = ref == null || !ref.isValid() ? null : PlayerAccess.playerRef(accessor, ref);
            return player == null ? null : player.getUuid();
        } catch (Throwable t) {
            return null;
        }
    }
}
