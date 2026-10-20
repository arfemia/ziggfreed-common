package com.ziggfreed.common.track.source;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.components.SpawnBeaconReference;
import com.hypixel.hytale.server.npc.components.SpawnMarkerReference;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.health.HealthUtil;
import com.ziggfreed.common.loot.diminish.DiminishLedger;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentListener;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackReading;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.util.SafeLog;

/**
 * The track sources on every produced moment: a {@link MomentListener} that reads only {@link TrackSources}. A
 * moment no source names costs one lookup; one that a track's table covers pays that table's row the player's
 * level has reached (a row's {@code Level} is a soft gate: below it the next matching row pays), its source's knobs
 * asked in turn (a spawner's mob, its live {@code Requires}, its diminishing returns), onto the player's default
 * climb through {@link TrackService#add} and its gain pipeline, or into the tick's sum for a frequent kind
 * ({@link SourceBatch}).
 *
 * <p>Called on the world thread inside the producing system's dispatch, like every reaction; what it writes it
 * writes through the track service, which the moment's own player owns.
 */
public final class TrackSourceReactions implements MomentListener {

    /** The one instance; it holds no state but the source counts. */
    public static final TrackSourceReactions INSTANCE = new TrackSourceReactions();

    /** The sources' diminishing-returns counts: kept in memory only, so a restart starts every window over. */
    private static final DiminishLedger COUNTS = new DiminishLedger("track-sources");

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
                    if (player == null) {
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
        TrackSources.Paid paid = reached(ladder, player);
        if (paid == null) {
            return;
        }
        SourceRow row = paid.row();
        double amount = row.payFor(amountOf(moment), row.perMaxHealth() > 0.0 ? maxHealthOf(moment) : 0.0);
        if (!(amount > 0.0)) {
            return;
        }
        if (paid.skipSpawned() && fromSpawner(moment)) {
            return;
        }
        if (paid.requires() != null && !StoreGates.passes(paid.requires(),
                GateQuery.of(FactorContext.about(moment.store(), moment.ref())))) {
            return;
        }
        if (paid.diminish() != null) {
            amount *= COUNTS.count(player, paid.label(), paid.diminish(), System.currentTimeMillis());
            if (!(amount > 0.0)) {
                return;
            }
        }
        if (TrackSources.batches(moment.kindId())) {
            World world = moment.store().getExternalData().getWorld();
            if (world != null) {
                SourceBatch.add(world, player, paid.trackId(), paid.label(), amount);
                return;
            }
        }
        TrackService.add(TrackKey.player(player, paid.trackId()), amount, paid.label());
    }

    /**
     * The first row of {@code ladder} (most specific first) whose {@code Level} the player's current climb has
     * reached, or null when none has: the track is read only when a row waits for a level.
     */
    @Nullable
    private static TrackSources.Paid reached(@Nonnull List<TrackSources.Paid> ladder, @Nonnull UUID player) {
        int level = -1;
        for (TrackSources.Paid paid : ladder) {
            int needs = paid.row().level();
            if (needs <= 1) {
                return paid;
            }
            if (level < 0) {
                TrackReading reading = TrackService.read(TrackKey.player(player, paid.trackId()));
                level = reading == null ? 1 : reading.level();
            }
            if (level >= needs) {
                return paid;
            }
        }
        return null;
    }

    /** The moment's amount: its payload's exact one, else its whole one. */
    private static double amountOf(@Nonnull Moment moment) {
        ExactAmountPayload exact = moment.payload(ExactAmountPayload.class);
        double amount = exact != null ? exact.exactAmount() : (double) moment.amount();
        return Double.isFinite(amount) ? Math.max(0.0, amount) : 0.0;
    }

    /** The maximum health of the entity the moment happened to, or 0 when it names none or it cannot be read. */
    private static double maxHealthOf(@Nonnull Moment moment) {
        VictimPayload payload = moment.payload(VictimPayload.class);
        Ref<EntityStore> victim = payload == null ? null : payload.victimRef();
        if (victim == null || !victim.isValid()) {
            return 0.0;
        }
        float max = HealthUtil.max(victim.getStore(), victim);
        return Float.isFinite(max) && max > 0f ? max : 0.0;
    }

    /** Did a spawn marker or a spawn beacon stand up the entity the moment happened to? */
    private static boolean fromSpawner(@Nonnull Moment moment) {
        VictimPayload payload = moment.payload(VictimPayload.class);
        Ref<EntityStore> victim = payload == null ? null : payload.victimRef();
        if (victim == null || !victim.isValid()) {
            return false;
        }
        try {
            Store<EntityStore> store = victim.getStore();
            return store.getComponent(victim, SpawnMarkerReference.getComponentType()) != null
                    || store.getComponent(victim, SpawnBeaconReference.getComponentType()) != null;
        } catch (Throwable t) {
            // The spawning plugin is not running here, so nothing was stood up by one.
            return false;
        }
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
