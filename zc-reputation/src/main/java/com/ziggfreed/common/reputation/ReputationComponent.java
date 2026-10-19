package com.ziggfreed.common.reputation;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.counter.PackedTallies;
import com.ziggfreed.common.util.SafeLog;

/**
 * One player's reputation record beside their standing: per reputation, the Beyond payouts waiting to be
 * collected, the highest earned standing the player has reached, whether their standing was imported onto
 * the reputation's track, and whether the engine's copy of it is behind the track.
 *
 * <p><b>One field, and it only grows.</b> The record persists as one {@code Beyond} string
 * ({@link PackedTallies}: {@code <id>/owed:<n>|<id>/highest:<standing>|<id>/imported:1|<id>/unsynced:1}, keys
 * sorted so a save is stable). A
 * reputation id is matched without regard to case and written in the engine's own spelling. A new fact is a
 * new key or a new leaf APPENDED after this one, never a rename. Absent means nothing: no key reads as nothing
 * owed and no standing on record, and a record kept for a reputation that is switched off or no longer pays
 * Beyond is kept, unread, until it pays again.
 *
 * <p><b>The high-water rule</b> ({@link #climb}): the highest is kept as earned STANDING, never as a step, and
 * every step is read when the change lands, from the ladder's top floor and the Every of that moment. A move of
 * earned standing from {@code before} to {@code after} owes {@code steps(after) - max(steps(before),
 * steps(highest))} when that is positive, and the highest becomes the largest of the three standings. So a fall
 * and a climb back owe nothing twice, a later change to Every or to the top floor neither pays steps back nor
 * stalls new pay, and a player with nothing on record starts from where they stand ({@code highest} reads as
 * {@code before}), with no back pay.
 *
 * <p>{@link #register} runs once at library setup, before any world loads (a component type registered later
 * cannot be read off entities saved carrying it), and {@link #install} hangs the connect hook that attaches
 * one to every player. Readers PEEK it ({@link #of}): {@link #TYPE} is null when registration failed. World
 * thread for every read and write.
 */
public class ReputationComponent implements Component<EntityStore> {

    /** The engine registry id, and the save key. */
    public static final String REGISTRY_ID = "ZiggfreedCommon:Reputation";

    /** The most Beyond payouts paid at once: per Collect press, and per change for a Beyond paid on crossing. */
    public static final int MAX_AT_ONCE = 20;

    /** The key suffix of the payouts waiting. */
    static final String OWED = "/owed";

    /** The key suffix of the highest earned standing reached. */
    static final String HIGHEST = "/highest";

    /** The key suffix marking a reputation whose standing was imported onto its track (1 once done). */
    static final String IMPORTED = "/imported";

    /** The key suffix marking a reputation whose engine copy did not take the track's last move (1 until it does). */
    static final String UNSYNCED = "/unsynced";

    @Nullable
    public static ComponentType<EntityStore, ReputationComponent> TYPE;

    public static final BuilderCodec<ReputationComponent> CODEC;

    /** {@code <id>/owed}, {@code <id>/highest}, {@code <id>/imported} and {@code <id>/unsynced}, where set. */
    @Nonnull
    CounterMap beyond;

    static {
        var builder = BuilderCodec.builder(ReputationComponent.class, ReputationComponent::new);
        builder.append(new KeyedCodec<>("Beyond", Codec.STRING),
                (c, v, info) -> c.beyond = deserialize(v),
                (c, info) -> serialize(c.beyond.all())).add();
        CODEC = builder.build();
    }

    public ReputationComponent() {
        this.beyond = new CounterMap();
    }

    /** What one move of earned standing owes in Beyond payouts, and the highest standing on record after it. */
    public record Climb(long owed, long highest) {
    }

    /**
     * The high-water rule, pure: a move of earned standing from {@code before} to {@code after}, with
     * {@code highest} the highest standing on record (null for none, which reads as {@code before}), owes
     * {@code steps(after) - max(steps(before), steps(highest))} when positive, each step read from {@code floor}
     * and {@code every} ({@link ReputationLadder#beyondSteps}), and leaves the highest at the largest of the
     * three standings.
     */
    @Nonnull
    public static Climb climb(long before, long after, @Nullable Long highest, int floor, int every) {
        long reached = highest == null ? before : Math.max(highest, before);
        long base = Math.max(ReputationLadder.beyondSteps(before, floor, every),
                ReputationLadder.beyondSteps(reached, floor, every));
        long owed = Math.max(0L, ReputationLadder.beyondSteps(after, floor, every) - base);
        return new Climb(owed, Math.max(reached, after));
    }

    /** How many of {@code owed} waiting payouts one press pays: all of them, up to {@link #MAX_AT_ONCE}. */
    public static int perPress(long owed) {
        return (int) Math.max(0L, Math.min(owed, MAX_AT_ONCE));
    }

    /** Can {@code reputationId} be kept in the save string (not blank, free of its pair separator)? */
    public static boolean keeps(@Nullable String reputationId) {
        return PackedTallies.usableKey(reputationId);
    }

    /** The Beyond payouts waiting for {@code reputationId} (any case); 0 when none. */
    public long owed(@Nonnull String reputationId) {
        return beyond.get(reputationId.trim() + OWED);
    }

    /**
     * The highest earned standing on record with {@code reputationId} (any case), or null when none is. A
     * highest of 0 or less is never kept (nothing past a top floor sits there), so it reads as none.
     */
    @Nullable
    public Long highest(@Nonnull String reputationId) {
        String wanted = reputationId.trim() + HIGHEST;
        for (String key : beyond.keys()) {
            if (key.equalsIgnoreCase(wanted)) {
                return beyond.get(key);
            }
        }
        return null;
    }

    /**
     * Record a move of earned standing from {@code before} to {@code after} with {@code reputationId} (the
     * engine's spelling, which the record then keeps), its steps read from the top {@code floor} and
     * {@code every} of this moment: the payouts it newly owes are added to those waiting and the highest
     * standing is raised. Returns how many it newly owes.
     */
    public long climbed(@Nonnull String reputationId, long before, long after, int floor, int every) {
        String id = reputationId.trim();
        Climb climb = climb(before, after, highest(id), floor, every);
        beyond.highWater(id + HIGHEST, climb.highest());
        if (climb.owed() > 0) {
            beyond.add(id + OWED, climb.owed());
        }
        return climb.owed();
    }

    /** {@code steps} of {@code reputationId}'s waiting payouts were paid: take them off, never below none. */
    public void paid(@Nonnull String reputationId, long steps) {
        String id = reputationId.trim();
        beyond.set(id + OWED, Math.max(0L, owed(id) - Math.max(0L, steps)));
    }

    /**
     * Has {@code reputationId}'s standing (any case) been imported onto its track for this player? Once it has,
     * the track holds the standing and the engine's own value is a copy, so the import never runs again.
     */
    public boolean imported(@Nonnull String reputationId) {
        return beyond.get(reputationId.trim() + IMPORTED) > 0;
    }

    /** Mark {@code reputationId}'s standing imported onto its track (the engine's spelling, which it keeps). */
    public void markImported(@Nonnull String reputationId) {
        beyond.set(reputationId.trim() + IMPORTED, 1L);
    }

    /**
     * Is the engine's copy of the player's standing with {@code reputationId} (any case) behind its track: a move
     * made where the engine keeps reputation per world, or a write the engine refused? While it is, the copy is
     * written again at the next chance and never folded back into the track, across a restart too.
     */
    public boolean unsynced(@Nonnull String reputationId) {
        return beyond.get(reputationId.trim() + UNSYNCED) > 0;
    }

    /** Mark the copy of {@code reputationId} behind its track, or (false) caught up, which drops the key. */
    public void unsynced(@Nonnull String reputationId, boolean behind) {
        beyond.set(reputationId.trim() + UNSYNCED, behind ? 1L : 0L);
    }

    /** The save string: {@code key:value|key:value}, keys sorted; an empty record is an empty string. */
    @Nonnull
    static String serialize(@Nonnull Map<String, Long> tallies) {
        return PackedTallies.serialize(tallies);
    }

    /** The record a saved string holds; a damaged pair costs only itself. */
    @Nonnull
    static CounterMap deserialize(@Nullable String packed) {
        return PackedTallies.deserialize(packed);
    }

    /** Register the type with the entity-store registry, once, at library setup. Never throws. */
    @Nullable
    public static ComponentType<EntityStore, ReputationComponent> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            TYPE = registry.registerComponent(ReputationComponent.class, REGISTRY_ID, CODEC);
            return TYPE;
        } catch (Throwable t) {
            SafeLog.warn("[reputation] could not register the reputation record", t);
            return null;
        }
    }

    /**
     * The record the player at {@code ref} carries, peeked: null when the type never registered, the player
     * carries none, or the read fails. World thread.
     */
    @Nullable
    public static ReputationComponent of(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            ComponentType<EntityStore, ReputationComponent> type = TYPE;
            return type == null ? null : store.getComponent(ref, type);
        } catch (Throwable t) {
            SafeLog.warn("[reputation] could not read a player's reputation record: " + t.getMessage());
            return null;
        }
    }

    /** Hang the connect hook that attaches a record to every player. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().register(PlayerConnectEvent.class, ReputationComponent::onPlayerConnect);
    }

    private static void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        try {
            if (TYPE == null) {
                return;
            }
            event.getHolder().ensureAndGetComponent(TYPE);
        } catch (Throwable t) {
            SafeLog.warn("[reputation] could not attach the reputation record", t);
        }
    }

    @Override
    @SuppressWarnings("CloneDeclaresCloneNotSupported")
    public ReputationComponent clone() {
        ReputationComponent copy = new ReputationComponent();
        copy.beyond = this.beyond.copy();
        return copy;
    }
}
