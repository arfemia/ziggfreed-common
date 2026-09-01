package com.ziggfreed.common.progress;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.registry.RegistryLedger;

/**
 * The open objective VOCABULARY: which objective kinds exist, and what the engine must know about
 * each. A consumer builds one, adds whatever kinds its own producers fire, and hands it to the
 * lifecycle engine that will dispatch against it; content then authors those ids with no code.
 *
 * <p><b>Instantiable per consumer, never a shared mutable global</b> - the same paradigm the
 * dialogue engine keeps. A registry is fully populated at setup and only then handed to the engine
 * that reads it, so there is no registration race and one consumer's vocabulary never leaks into
 * another's.
 *
 * <p><b>Twenty-three engine-generic kinds are PRE-SEEDED</b> (see {@link #seedBuiltIns}) - the ones
 * whose meaning does not depend on any particular game's systems: breaking a block, killing an
 * entity, talking to somebody, handing something in, standing at some measured value. All twenty-three
 * seed as producible, all but {@link #STAT_THRESHOLD} accumulate, and two of them
 * ({@code TALK_TO_NPC}, {@code REACH_LOCATION}) seed as place-targeted; a consumer that has no
 * producer for one can re-register it unproducible so its validator says so. Domain kinds (anything
 * tied to a consumer's own progression, economy, or classes) are the consumer's to add - registering
 * one is a single call and overrides a built-in of the same id.
 *
 * <p><b>A consumer registers only what it ADDS.</b> {@link #isBuiltIn} is there so a consumer
 * walking its own vocabulary can skip the ids this class already states: re-registering one restates
 * this class's flags in a second place, which holds until this class learns to seed a flag the
 * consumer has never heard of and silently resets it.
 *
 * <p><b>Two of them describe a finished INSTANCE ROUND</b> - a minigame match, a co-op session, a
 * dungeon clear - and share one contract. {@code Target} is {@code <modId>:<modeId>} (e.g.
 * {@code kweebec:chase}), so the PREFIX {@code kweebec:} matches any mode of that mod;
 * {@code Qualifier} is the preset id; {@code Amount} is 1 per round.
 * {@code INSTANCE_ROUND_ENDED} fires once per PARTICIPANT on every completion, win or lose,
 * and {@code INSTANCE_ROUND_WON} fires once per WINNER and only on a win - so "play ten
 * rounds" and "win ten rounds" are two different objectives rather than one with a flag.
 *
 * <p>Registration bookkeeping (who owns an id, how often it has misbehaved) lives in the shared
 * {@link RegistryLedger}; ids are matched case-insensitively and registration is idempotent per id
 * with last-write-wins.
 */
public final class ObjectiveKindRegistry {

    /**
     * {@code STAT_THRESHOLD} - the one pre-seeded VALUE-BASED kind: reach some standing value rather
     * than do something a number of times.
     *
     * <p>Its contract, and the three leaves an author writes:
     * <ul>
     *   <li>{@code Target} is a native STAT CHANNEL id (the {@code hytale:stat} vocabulary - one
     *   registered entity stat type), and it is REQUIRED: without a channel to read there is nothing
     *   to compare against, which is why both content validators report a blank one.
     *   <li>{@code Amount} is the threshold the channel must reach.
     *   <li>progress is the HIGH-WATER of the channel's effective (folded) value, so a value that
     *   later drops back never takes recorded progress with it.
     * </ul>
     *
     * <p>Both lifecycle engines can re-read the channel themselves through
     * {@link StatThresholdProbe}, wired with the optional factor seam on their builders; with that
     * seam unwired the kind is purely consumer-fired, like every other kind here. See the probe for
     * exactly when each engine re-reads.
     */
    public static final String STAT_THRESHOLD = "STAT_THRESHOLD";

    /**
     * The engine-generic vocabulary every registry starts with. Each names a moment any game has:
     * nothing here assumes a progression system, an economy, or a particular content pack.
     */
    private static final List<String> BUILT_IN_ACCUMULATING = List.of(
            "BREAK_BLOCK", "PLACE_BLOCK", "CRAFT_ITEM", "KILL_ENTITY", "DEAL_DAMAGE",
            "PICKUP_ITEM", "TALK_TO_NPC", "CATCH_FISH", "TURN_IN", "COMPLETE_QUEST",
            "TAKE_FALL_DAMAGE", "PLAYER_DEATH", "SPRINT_DISTANCE", "SWIM_DISTANCE",
            "BREED_ANIMAL", "FEED_ANIMAL", "HARVEST_ANIMAL", "COMPANION_COMBAT",
            "REACH_LOCATION", "CONSUME_ITEM",
            "INSTANCE_ROUND_WON", "INSTANCE_ROUND_ENDED");

    /**
     * The pre-seeded kinds whose producers fire a CURRENT value rather than an increment, so a
     * dispatch raises a high-water mark instead of adding to a tally.
     */
    private static final List<String> BUILT_IN_VALUE_BASED = List.of(STAT_THRESHOLD);

    /**
     * The pre-seeded kinds whose TARGET names somewhere to go rather than something an event
     * carries, so a listing can say "this step resolves here" for a step with no hand-in of its own.
     *
     * <p>Orthogonal to the two arithmetic lists above, which stay the partition: a kind appears in
     * exactly one of those and independently may or may not appear here. {@code TURN_IN} is
     * deliberately absent - what its target names is the thing being delivered, and where it may be
     * delivered is its own hand-in lock.
     */
    private static final Set<String> BUILT_IN_PLACE_TARGETED = Set.of("TALK_TO_NPC", "REACH_LOCATION");

    /**
     * The pre-seeded kinds whose TARGET names something a player can hold, so a surface listing one
     * of their steps may draw the target's own picture beside it.
     *
     * <p>{@code TURN_IN} belongs here because what its target names is the thing being delivered.
     * A hand-in that delivers nothing carries no target at all, so it is pictured by whatever a
     * consumer says about it, or not at all.
     */
    private static final Set<String> BUILT_IN_ITEM_TARGETED = Set.of(
            "BREAK_BLOCK", "PLACE_BLOCK", "CRAFT_ITEM", "PICKUP_ITEM", "CATCH_FISH",
            "TURN_IN", "CONSUME_ITEM");

    /**
     * The pre-seeded kinds whose TARGET names a creature, drawn from that creature's own generated
     * portrait rather than from an item.
     *
     * <p>Independent of the two sets above: {@code TALK_TO_NPC} names both somewhere to go and the
     * character standing there, and is in both.
     */
    private static final Set<String> BUILT_IN_ENTITY_TARGETED = Set.of(
            "KILL_ENTITY", "DEAL_DAMAGE", "TALK_TO_NPC", "BREED_ANIMAL", "FEED_ANIMAL",
            "HARVEST_ANIMAL", "COMPANION_COMBAT");

    /**
     * The pre-seeded kinds whose TARGET names another piece of CONTENT, drawn with that content's
     * own icon - the picture it is already listed under everywhere else.
     */
    private static final Set<String> BUILT_IN_CONTENT_TARGETED = Set.of("COMPLETE_QUEST");

    /** The owner name the pre-seeded kinds are attributed to in the ledger. */
    public static final String BUILT_IN_OWNER = "built-in";

    @Nonnull
    private final RegistryLedger<ObjectiveKind> ledger;

    /** A registry pre-seeded with the built-in vocabulary, logging under a generic prefix. */
    public ObjectiveKindRegistry() {
        this(null);
    }

    /**
     * A registry pre-seeded with the built-in vocabulary whose ledger log lines are prefixed
     * {@code [label]}, so an owner reading an overwrite warning can tell which vocabulary it was.
     */
    public ObjectiveKindRegistry(@Nullable String label) {
        this.ledger = new RegistryLedger<>(label == null || label.isBlank() ? "objective-kind" : label);
        seedBuiltIns();
    }

    /** Register the twenty-three engine-generic kinds, all producible, each with its own arithmetic. */
    private void seedBuiltIns() {
        for (String id : BUILT_IN_ACCUMULATING) {
            ledger.put(id, BUILT_IN_OWNER, new ObjectiveKind(id, false, true,
                    BUILT_IN_PLACE_TARGETED.contains(id),
                    BUILT_IN_ITEM_TARGETED.contains(id),
                    BUILT_IN_ENTITY_TARGETED.contains(id),
                    false,
                    BUILT_IN_CONTENT_TARGETED.contains(id),
                    false));
        }
        for (String id : BUILT_IN_VALUE_BASED) {
            ledger.put(id, BUILT_IN_OWNER, ObjectiveKind.valueBased(id));
        }
    }

    /**
     * Is {@code kindId} one of the twenty-three this class seeds? A consumer registering its own
     * vocabulary asks this to add only what it ADDS, leaving the built-ins stated once, here, with
     * every flag they carry - including any this class learns to seed later.
     */
    public static boolean isBuiltIn(@Nullable String kindId) {
        if (kindId == null || kindId.isBlank()) {
            return false;
        }
        String id = kindId.trim().toUpperCase(Locale.ROOT);
        return BUILT_IN_ACCUMULATING.contains(id) || BUILT_IN_VALUE_BASED.contains(id);
    }

    /** Register (or replace) an accumulating, producible kind, unattributed. */
    public void register(@Nullable String kindId) {
        register(kindId, null, false, true);
    }

    /**
     * Register (or replace) {@code kindId} with the two arithmetic knobs stated, attributed to
     * {@code owner} ({@link RegistryLedger#UNATTRIBUTED} when null/blank). A blank id is ignored.
     * See {@link ObjectiveKind} for what each flag decides.
     *
     * <p>The kind registered this way targets a THING. A kind whose target names a place is built
     * with {@link ObjectiveKind#placeTargeted} and registered through
     * {@link #register(String, ObjectiveKind)}, so a flag a caller has no opinion about never has to
     * be spelled out as a bare positional boolean.
     */
    public void register(@Nullable String kindId, @Nullable String owner,
                         boolean valueBased, boolean producible) {
        if (kindId == null || kindId.isBlank()) {
            return;
        }
        ledger.put(kindId, owner, new ObjectiveKind(kindId, valueBased, producible));
    }

    /** Register (or replace) a fully-built kind, attributed to {@code owner}. */
    public void register(@Nullable String owner, @Nullable ObjectiveKind kind) {
        if (kind == null) {
            return;
        }
        ledger.put(kind.id(), owner, kind);
    }

    /**
     * Register (or replace) a fully-built kind WITHOUT the ledger's replacement notice, for a caller
     * whose whole job is to lay one description over another - an authored file merged over the
     * Java registration it refines. Every other caller should use {@link #register(String,
     * ObjectiveKind)} and let a genuine collision be heard.
     */
    public void registerQuietly(@Nullable String owner, @Nullable ObjectiveKind kind) {
        if (kind == null) {
            return;
        }
        ledger.putQuietly(kind.id(), owner, kind);
    }

    /** The kind registered under {@code kindId} (case-insensitive), or null when nothing is. */
    @Nullable
    public ObjectiveKind kind(@Nullable String kindId) {
        return ledger.get(kindId);
    }

    /** Is {@code kindId} part of this vocabulary? */
    public boolean isRegistered(@Nullable String kindId) {
        return ledger.isRegistered(kindId);
    }

    /**
     * May authored content use {@code kindId}? False for an unknown id AND for a registered but
     * unproducible one - both would ship an objective that can never progress.
     */
    public boolean isProducible(@Nullable String kindId) {
        ObjectiveKind kind = ledger.get(kindId);
        return kind != null && kind.producible();
    }

    /** Does {@code kindId} track a high-water value rather than accumulate? False when unknown. */
    public boolean isValueBased(@Nullable String kindId) {
        ObjectiveKind kind = ledger.get(kindId);
        return kind != null && kind.valueBased();
    }

    /**
     * Does {@code kindId}'s target name a place a player can stand at? False when unknown, which is
     * what keeps "is this step pointing here" a positive question: a kind nothing registered points
     * nowhere rather than everywhere.
     */
    public boolean isPlaceTargeted(@Nullable String kindId) {
        ObjectiveKind kind = ledger.get(kindId);
        return kind != null && kind.targetsPlace();
    }

    /** Every registered id, sorted (diagnostics, an authoring hint, a validator message). */
    @Nonnull
    public List<String> ids() {
        return List.copyOf(ledger.ids());
    }

    /** Every registered id's owner plus failure history, keyed by id (an admin registry listing). */
    @Nonnull
    public Map<String, RegistryLedger.RegistrationInfo> info() {
        return ledger.info();
    }

    /** Drop every registration INCLUDING the built-ins, then re-seed the built-ins. */
    public void clear() {
        ledger.clear();
        seedBuiltIns();
    }
}
