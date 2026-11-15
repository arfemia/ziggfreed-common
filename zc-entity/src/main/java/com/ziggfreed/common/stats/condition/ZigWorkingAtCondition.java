package com.ziggfreed.common.stats.condition;

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * An entity condition: is the entity working at a station now, and with {@code Stations}, at one of these (by the
 * station's own id, as its mod's files name it, matched without regard to case)? Unauthored or empty, any station
 * holds. Each other knob asks one more fact of the work, unauthored asking nothing: {@code CyclesMin}, the session has
 * reached that cycle (the first is 1); {@code ItemLevelMin}, {@code QualityMin}, {@code DurabilityBelow} and
 * {@code Stamped}, the piece being worked (its item level, its quality tier value, its durability percent, whether it
 * carries stamped enhancement points), each read as the {@code hytale:item_*} factors and
 * {@code ziggfreedcommon:item_stamp_points} read the same stack, once per piece ({@link StationWork#pieceOf}). A piece
 * knob fails with no piece. The library knows no station: the answer comes through {@link StationWork}, which a station
 * mod fills at setup, so on a server without one the condition never holds and a file naming it still loads (R948).
 * Registered into the engine's entity condition map as {@value #TYPE_NAME} (R700), it is the one "while working at a
 * station" a bound grant, a conditional stat or an effect writes (R935). Not hit-aware: a hit is never dealt at a
 * station's work.
 *
 * <pre>{@code
 * { "Id": "ZigWorkingAt", "Stations": [ "Sawmill" ] }
 * { "Id": "ZigWorkingAt", "Stations": [ "Sawmill" ], "CyclesMin": 20 }
 * { "Id": "ZigWorkingAt", "Stations": [ "Disenchanting_Table", "Disenchanting_Table_Greater" ], "ItemLevelMin": 40 }
 * }</pre>
 */
public final class ZigWorkingAtCondition extends Condition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigWorkingAt";

    @Nullable
    private String[] stations;
    @Nullable
    private Integer cyclesMin;
    @Nullable
    private Integer itemLevelMin;
    @Nullable
    private Integer qualityMin;
    @Nullable
    private Double durabilityBelow;
    @Nullable
    private Boolean stamped;

    /** {@link #stations} lower-cased once, on the first read; empty for any station. */
    @Nullable
    private volatile Set<String> lowered;

    private static final class Holder {
        static final BuilderCodec<ZigWorkingAtCondition> CODEC = BuilderCodec.builder(
                        ZigWorkingAtCondition.class, ZigWorkingAtCondition::new, Condition.BASE_CODEC)
                .documentation("Holds while the entity is working at a station, as the server's station mod says, and "
                        + "the work is as each knob writes. Never holds on a server without one. With Inverse, while "
                        + "it is not.")
                .append(new KeyedCodec<>("Stations", Codec.STRING_ARRAY, false), (c, v) -> c.stations = v,
                        c -> c.stations)
                .documentation("Only while the station being worked is one of these, by the station's own id as its "
                        + "mod names it (Sawmill, Disenchanting_Table), matched without regard to case. Any station "
                        + "when unauthored.")
                .add()
                .append(new KeyedCodec<>("CyclesMin", Codec.INTEGER, false), (c, v) -> c.cyclesMin = v,
                        c -> c.cyclesMin)
                .documentation("Only from this cycle of the session on, the first being 1 (20: once a session has "
                        + "run 19 cycles). Any cycle when unauthored.")
                .add()
                .append(new KeyedCodec<>("ItemLevelMin", Codec.INTEGER, false), (c, v) -> c.itemLevelMin = v,
                        c -> c.itemLevelMin)
                .documentation("Only while the piece being worked is of at least this item level. Any piece when "
                        + "unauthored.")
                .add()
                .append(new KeyedCodec<>("QualityMin", Codec.INTEGER, false), (c, v) -> c.qualityMin = v,
                        c -> c.qualityMin)
                .documentation("Only while the piece being worked has at least this quality tier value (a quality "
                        + "stamped on it, else its item's: 2 is Uncommon). Any piece when unauthored.")
                .add()
                .append(new KeyedCodec<>("DurabilityBelow", Codec.DOUBLE, false), (c, v) -> c.durabilityBelow = v,
                        c -> c.durabilityBelow)
                .documentation("Only while the piece being worked has less than this percent of its durability left "
                        + "(50: under half). A piece that tracks no durability reads 100. Any piece when unauthored.")
                .add()
                .append(new KeyedCodec<>("Stamped", Codec.BOOLEAN, false), (c, v) -> c.stamped = v, c -> c.stamped)
                .documentation("True: only while the piece being worked carries stamped enhancement points. False: "
                        + "only while it carries none. Either when unauthored.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigWorkingAtCondition> codec() {
        return Holder.CODEC;
    }

    /**
     * Make the engine's entity conditions know this kind when nothing has registered it yet: a consumer's file read
     * before setup (a unit-test JVM, which never runs setup) still decodes. On a server the library's setup has
     * registered it first, so this does nothing. Idempotent.
     */
    public static void ensureKnown() {
        if (Condition.CODEC.getCodecFor(TYPE_NAME) == null) {
            Condition.CODEC.register(TYPE_NAME, ZigWorkingAtCondition.class, codec());
        }
    }

    /** A condition holding while the entity works at one of {@code stations}, or at any with none (a consumer, a test). */
    @Nonnull
    public static ZigWorkingAtCondition at(@Nonnull String... stations) {
        ZigWorkingAtCondition condition = new ZigWorkingAtCondition();
        condition.stations = stations.clone();
        return condition;
    }

    /**
     * Does this condition name {@code stationId} (any station when it lists none)? Matched without regard to case;
     * false for a blank id. Neither {@code Inverse} nor the other knobs apply: see {@link #countsAt}.
     */
    public boolean names(@Nullable String stationId) {
        if (stationId == null || stationId.isBlank()) {
            return false;
        }
        Set<String> listed = listed();
        return listed.isEmpty() || listed.contains(stationId.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Would a grant bound under this condition count at {@code stationId} for a roll made away from the work: it names
     * the station and is not inverted, its session and piece knobs taken as holding (R958: a station's talents count
     * whatever the moment). What {@link StationStatReading} asks of a modifier's conditions.
     */
    public boolean countsAt(@Nullable String stationId) {
        return !inverse && names(stationId);
    }

    /**
     * Does this condition bind a grant to working at some station other than {@code stationId}: not inverted, and it
     * names stations that leave this one out? What a reading at one station takes back out of the stat while another
     * station's share is on ({@link StationStatReading}).
     */
    public boolean bindsElsewhereThan(@Nullable String stationId) {
        return !inverse && !names(stationId);
    }

    /** Does {@code work}, the entity's work now, meet this condition before {@code Inverse}? Null work never does. */
    boolean holdsFor(@Nullable StationWork.Work work, @Nonnull Ref<EntityStore> ref) {
        if (work == null || !names(work.stationId())) {
            return false;
        }
        if (cyclesMin != null && work.cycle() < cyclesMin) {
            return false;
        }
        if (!asksAboutPiece()) {
            return true;
        }
        return pieceHolds(StationWork.pieceOf(ref, work.piece()));
    }

    /** Do the piece knobs hold on {@code piece}? A knob asking about a reading the piece lacks fails. */
    boolean pieceHolds(@Nonnull StationWork.Piece piece) {
        if (itemLevelMin != null && (piece.level() == null || piece.level() < itemLevelMin)) {
            return false;
        }
        if (qualityMin != null && (piece.quality() == null || piece.quality() < qualityMin)) {
            return false;
        }
        if (durabilityBelow != null
                && (piece.durabilityPercent() == null || piece.durabilityPercent() >= durabilityBelow)) {
            return false;
        }
        if (stamped != null) {
            if (piece.stampPoints() == null || stamped != (piece.stampPoints() >= 1.0)) {
                return false;
            }
        }
        return true;
    }

    /** Does a knob ask about the piece being worked? */
    private boolean asksAboutPiece() {
        return itemLevelMin != null || qualityMin != null || durabilityBelow != null || stamped != null;
    }

    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        return holdsFor(StationWork.workAt(componentAccessor, ref), ref);
    }

    @Nonnull
    private Set<String> listed() {
        Set<String> cached = lowered;
        if (cached != null) {
            return cached;
        }
        Set<String> out = new HashSet<>();
        if (stations != null) {
            for (String station : stations) {
                if (station != null && !station.isBlank()) {
                    out.add(station.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        lowered = Set.copyOf(out);
        return lowered;
    }
}
