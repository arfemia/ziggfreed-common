package com.ziggfreed.common.track.source;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;
import org.joml.Vector3i;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.components.SpawnBeaconReference;
import com.hypixel.hytale.server.npc.components.SpawnMarkerReference;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.health.HealthUtil;
import com.ziggfreed.common.loot.diminish.DiminishLedger;
import com.ziggfreed.common.loot.diminish.DiminishSpec;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentListener;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackReading;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.world.TrackWorldRules;
import com.ziggfreed.common.util.SafeLog;

/**
 * The track sources on every produced moment: a {@link MomentListener} that reads only {@link TrackSources}. A
 * moment no source names costs one lookup; one that a track's table covers pays that table's row the player's
 * level has reached (a row's {@code Level} is a soft gate, {@link TrackSources#reached}: below it the next matching
 * row pays), its source's knobs asked in turn (a spawner's or an item's mob, its live {@code Requires}, its stand
 * time, its diminishing returns), onto the player's default climb through {@link TrackService#add} and its gain
 * pipeline, or into the tick's sum for a frequent kind ({@link SourceBatch}). A moment that happened to another player
 * ({@link VictimPayload#victimIsPlayer}) pays nothing unless the world's rules write {@code Gain.PlayerHits} true
 * (R864).
 *
 * <p>A source's {@code Diminish} counts each moment it pays under the source alone, or, when it names a
 * {@code Key} ({@link DiminishKeyAsset}), under the value that key reads off the moment (its place, target, station
 * or victim), so each keeps a count of its own; counted when it pays ({@link #settle}), so a placement waiting on its
 * stand time ({@link SourceStands}) spends nothing until it stands.
 *
 * <p>A consumer that pays a source file's rows off moment kinds of its own (no producer here fires them) matches the
 * row itself and hands it over: {@link #payMatched} pays it onto the track through the same knobs, and {@link #share}
 * answers the share the guards leave, counted, for a consumer paying through its own award; so a source's guards read
 * one way whoever pays its rows.
 *
 * <p>Called on the world thread inside the producing system's dispatch, like every reaction; what it writes it
 * writes through the track service, which the moment's own player owns.
 */
public final class TrackSourceReactions implements MomentListener {

    /** The one instance; it holds no state but the source counts. */
    public static final TrackSourceReactions INSTANCE = new TrackSourceReactions();

    /** The sources' diminishing-returns counts: kept in memory only, so a restart starts every window over. */
    private static final DiminishLedger COUNTS = new DiminishLedger("track-sources");

    /** The key ids a source named that no key file answers, each warned once. */
    private static final Set<String> UNKNOWN_KEYS = ConcurrentHashMap.newKeySet();

    private TrackSourceReactions() {
    }

    @Override
    public void react(@Nonnull Moment moment) {
        List<TrackSources.TrackTable> tables = TrackSources.forKind(moment.kindId());
        if (tables == null) {
            return;
        }
        UUID player = null;
        for (TrackSources.TrackTable table : tables) {
            try {
                List<TrackSources.Paid> ladder = table.table().matching(moment.kindId(), moment.target(),
                        moment.qualifier());
                if (ladder.isEmpty()) {
                    continue;
                }
                if (player == null) {
                    player = playerOf(moment.store(), moment.ref());
                    if (player == null || refusedAsPlayerHit(moment)) {
                        return;
                    }
                }
                pay(moment, player, ladder);
            } catch (Throwable t) {
                SafeLog.fine("[track] a source on '" + table.trackId() + "' could not pay a " + moment.kindId()
                        + ": " + t.getMessage());
            }
        }
    }

    /** Pay the row of {@code ladder} the player has reached, after its source's knobs. */
    private static void pay(@Nonnull Moment moment, @Nonnull UUID player, @Nonnull List<TrackSources.Paid> ladder) {
        TrackSources.Paid paid = TrackSources.reached(ladder, () -> currentLevel(player, ladder.get(0).trackId()));
        if (paid == null) {
            return;
        }
        SourceRow row = paid.row();
        payMatched(moment, player, paid,
                row.payFor(amountOf(moment), row.perMaxHealth() > 0.0 ? maxHealthOf(moment) : 0.0));
    }

    /**
     * Pay {@code amount} of {@code paid}, a row already matched and priced, onto {@code player}'s default climb of its
     * track, through its source's knobs exactly as a produced moment's row: a spawner's or an item's creature
     * ({@code SkipSpawnerMobs}), the live {@code Requires}, the stand time, then the diminishing returns counted
     * under its key. For a consumer whose own trigger matched one of its source files' rows (a mod paying moment kinds
     * of its own), so its sources' guards read one way; {@code moment} is the moment it reacted to (its place, its
     * victim and its kind are read). World thread, inside the moment's reaction.
     */
    public static void payMatched(@Nonnull Moment moment, @Nonnull UUID player, @Nonnull TrackSources.Paid paid,
            double amount) {
        if (!(amount > 0.0) || refused(moment, paid)) {
            return;
        }
        World world = moment.store().getExternalData().getWorld();
        String countKey = paid.diminish() == null ? paid.label() : countKey(paid, paid.diminish(), moment, world);
        boolean batched = TrackSources.batches(moment.kindId());
        if (paid.standSeconds() > 0 && world != null) {
            SpotPayload spot = moment.payload(SpotPayload.class);
            UUID worldId = worldIdOf(moment);
            if (spot != null && spot.spot() != null && worldId != null) {
                SourceStands.await(world, worldId, player, paid, amount, countKey, batched, spot.spot());
                return;
            }
        }
        settle(world, player, paid, amount, countKey, batched);
    }

    /**
     * The share of a matched row's pay that {@code paid}'s source guards leave for this moment, its diminishing count
     * taken now: 0 when a spawner's or an item's creature ({@code SkipSpawnerMobs}) or the live {@code Requires}
     * refuses it, else the diminishing step this moment pays at (1 with none). For a consumer that pays the row through
     * its own award, such as one moment paying several tracks or a climb of its own; the stand time is not asked here,
     * so a source that writes one pays through {@link #payMatched}. World thread, inside the moment's reaction.
     */
    public static double share(@Nonnull Moment moment, @Nonnull UUID player, @Nonnull TrackSources.Paid paid) {
        if (refused(moment, paid)) {
            return 0.0;
        }
        DiminishSpec spec = paid.diminish();
        if (spec == null) {
            return 1.0;
        }
        World world = moment.store().getExternalData().getWorld();
        return COUNTS.count(player, countKey(paid, spec, moment, world), spec, System.currentTimeMillis());
    }

    /** Do {@code paid}'s source guards refuse this moment: a spawner's or an item's creature, or its live Requires? */
    private static boolean refused(@Nonnull Moment moment, @Nonnull TrackSources.Paid paid) {
        if (paid.skipSpawned() && fromSpawner(moment)) {
            return true;
        }
        return paid.requires() != null && !StoreGates.passes(paid.requires(),
                GateQuery.of(FactorContext.about(moment.store(), moment.ref())));
    }

    /**
     * Pay {@code amount} of {@code paid} to {@code player} now: its diminishing returns counted under
     * {@code countKey} first, then into the tick's sum for a frequent kind ({@code batched}), else straight onto the
     * climb. On the player's world thread ({@code world}, null when the moment named none).
     */
    static void settle(@Nullable World world, @Nonnull UUID player, @Nonnull TrackSources.Paid paid, double amount,
            @Nonnull String countKey, boolean batched) {
        double paying = amount;
        if (paid.diminish() != null) {
            paying *= COUNTS.count(player, countKey, paid.diminish(), System.currentTimeMillis());
            if (!(paying > 0.0)) {
                return;
            }
        }
        if (batched && world != null) {
            SourceBatch.add(world, player, paid.trackId(), paid.label(), paying);
            return;
        }
        TrackService.add(TrackKey.player(player, paid.trackId()), paying, paid.label());
    }

    /**
     * What {@code paid}'s diminishing returns count under for this moment: the source's label alone, or with the value
     * its {@code Key} reads ({@link DiminishKeyAsset#valueOf}); a key no file answers counts under the label, warned
     * once.
     */
    @Nonnull
    private static String countKey(@Nonnull TrackSources.Paid paid, @Nonnull DiminishSpec spec,
            @Nonnull Moment moment, @Nullable World world) {
        String keyId = spec.key();
        if (keyId == null) {
            return paid.label();
        }
        DiminishKeyAsset key = DiminishKeyConfig.getInstance().key(keyId);
        if (key == null) {
            if (UNKNOWN_KEYS.add(keyId.toLowerCase(Locale.ROOT))) {
                SafeLog.warn("[track] the source '" + paid.sourceId() + "' counts its moments by the key '" + keyId
                        + "', which no DiminishKeys file holds, so it counts them all together");
            }
            return paid.label();
        }
        return paid.label() + "@" + key.valueOf(facts(key, moment, world));
    }

    /** What {@code key} reads off {@code moment}: only the parts it switches on are looked up. */
    @Nonnull
    static DiminishKeyAsset.Facts facts(@Nonnull DiminishKeyAsset key, @Nonnull Moment moment,
            @Nullable World world) {
        String victim = key.byVictim() ? victimId(moment) : null;
        SpotPayload spotPayload = key.byStation() || key.byPlace() ? moment.payload(SpotPayload.class) : null;
        String station = key.byStation() && spotPayload != null ? spotPayload.station() : null;
        boolean placed = false;
        int x = 0;
        int y = 0;
        int z = 0;
        if (key.byPlace()) {
            Vector3i spot = spotPayload == null ? null : spotPayload.spot();
            if (spot != null) {
                placed = true;
                x = spot.x;
                y = spot.y;
                z = spot.z;
            } else {
                Vector3d at = positionOf(victimRef(moment));
                if (at == null) {
                    at = positionOf(moment.ref());
                }
                if (at != null) {
                    placed = true;
                    x = (int) Math.floor(at.x);
                    y = (int) Math.floor(at.y);
                    z = (int) Math.floor(at.z);
                }
            }
        }
        return new DiminishKeyAsset.Facts(moment.target(), moment.qualifier(), victim, station,
                world == null ? null : world.getName(), placed, x, y, z);
    }

    /** The current climb's level on {@code trackId}, 1 when it cannot be read. */
    private static int currentLevel(@Nonnull UUID player, @Nonnull String trackId) {
        TrackReading reading = TrackService.read(TrackKey.player(player, trackId));
        return reading == null ? 1 : reading.level();
    }

    /** The moment's amount: its payload's exact one, else its whole one. */
    private static double amountOf(@Nonnull Moment moment) {
        ExactAmountPayload exact = moment.payload(ExactAmountPayload.class);
        double amount = exact != null ? exact.exactAmount() : (double) moment.amount();
        return Double.isFinite(amount) ? Math.max(0.0, amount) : 0.0;
    }

    /** The maximum health of the entity the moment happened to, or 0 when it names none or it cannot be read. */
    private static double maxHealthOf(@Nonnull Moment moment) {
        Ref<EntityStore> victim = victimRef(moment);
        if (victim == null) {
            return 0.0;
        }
        float max = HealthUtil.max(victim.getStore(), victim);
        return Float.isFinite(max) && max > 0f ? max : 0.0;
    }

    /** The live entity the moment happened to, or null. */
    @Nullable
    private static Ref<EntityStore> victimRef(@Nonnull Moment moment) {
        VictimPayload payload = moment.payload(VictimPayload.class);
        Ref<EntityStore> victim = payload == null ? null : payload.victimRef();
        return victim == null || !victim.isValid() ? null : victim;
    }

    /** The id of the one entity the moment happened to, or null when it names none or it cannot be read. */
    @Nullable
    private static String victimId(@Nonnull Moment moment) {
        return entityId(victimRef(moment));
    }

    /** The id of {@code victim}, or null for none or one that cannot be read. */
    @Nullable
    private static String entityId(@Nullable Ref<EntityStore> victim) {
        if (victim == null || !victim.isValid()) {
            return null;
        }
        try {
            UUIDComponent id = victim.getStore().getComponent(victim, UUIDComponent.getComponentType());
            return id == null || id.getUuid() == null ? null : id.getUuid().toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Where {@code ref} stands, or null for none or one that cannot be read. */
    @Nullable
    private static Vector3d positionOf(@Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        try {
            TransformComponent transform = ref.getStore().getComponent(ref, TransformComponent.getComponentType());
            return transform == null ? null : transform.getPosition();
        } catch (Throwable t) {
            return null;
        }
    }

    /** The world the moment's player stands in, by id, or null when it cannot be read. */
    @Nullable
    private static UUID worldIdOf(@Nonnull Moment moment) {
        try {
            PlayerRef player = moment.store().getComponent(moment.ref(), PlayerRef.getComponentType());
            return player == null ? null : player.getWorldUuid();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Did the moment happen to another player in a world whose rules leave {@code Gain.PlayerHits} off (R864)? Asked
     * once a moment, before either the direct pay or the tick's sum, so no source pays a hit on a player there. A
     * moment of PvP's own stands outside the rule ({@link VictimPayload#underPlayerHitRule}).
     */
    private static boolean refusedAsPlayerHit(@Nonnull Moment moment) {
        VictimPayload payload = moment.payload(VictimPayload.class);
        if (payload == null || !payload.underPlayerHitRule()) {
            return false;
        }
        try {
            return !TrackWorldRules.paysPlayerHits(moment.store().getExternalData().getWorld());
        } catch (Throwable t) {
            // The world cannot be read, so its rules cannot turn the hit on.
            return true;
        }
    }

    /** Did a spawn marker, a spawn beacon or an item (a spawn egg) stand up the entity the moment happened to? */
    private static boolean fromSpawner(@Nonnull Moment moment) {
        return stoodUpBySpawner(victimRef(moment));
    }

    /**
     * Did a spawn marker, a spawn beacon or an item (a spawn egg, {@link ItemSpawnedMobs}) stand {@code victim} up:
     * the creature a source's {@code SkipSpawnerMobs} pays nothing for. For a consumer pricing a hit of its own off a
     * source's switch; false for none or one that cannot be read. World thread.
     */
    public static boolean stoodUpBySpawner(@Nullable Ref<EntityStore> victim) {
        if (victim == null || !victim.isValid()) {
            return false;
        }
        try {
            Store<EntityStore> store = victim.getStore();
            if (store.getComponent(victim, SpawnMarkerReference.getComponentType()) != null
                    || store.getComponent(victim, SpawnBeaconReference.getComponentType()) != null) {
                return true;
            }
        } catch (Throwable t) {
            // The spawning plugin is not running here, so nothing was stood up by one.
        }
        String id = entityId(victim);
        return id != null && ItemSpawnedMobs.isMarked(UUID.fromString(id));
    }

    @Nullable
    private static UUID playerOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            return player == null ? null : player.getUuid();
        } catch (Throwable t) {
            return null;
        }
    }
}
