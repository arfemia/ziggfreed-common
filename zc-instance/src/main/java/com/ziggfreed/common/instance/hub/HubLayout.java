package com.ziggfreed.common.instance.hub;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.instance.hub.HubLots.Lot;
import com.ziggfreed.common.instance.hub.HubLots.Size;

/**
 * Which district stands on which lot: the one rule every hub copy is laid out by, and the one the boot audit reads its
 * lot findings from. Pure, no engine type, so it runs in a plain unit JVM.
 *
 * <p><b>The rule.</b>
 * <ol>
 *   <li>Each district naming a lot ({@code Lot.Id}) claims it, an owner's move first (a district whose lot the owner
 *       file changed), then by id without regard to case. A claim on a lot the map does not have, on a lot smaller than
 *       the district, or on a lot already claimed stands nowhere and is a {@link Problem}; the district already there
 *       stays, so adding a district never moves another.</li>
 *   <li>Every lot no district claimed takes the first district by id that fills empty lots ({@code Lot.FillsEmpty})
 *       of its size (or of any size, when it names none): the scaffolding over lots still to be built.</li>
 *   <li>A district naming neither stands nowhere and is a {@link Problem}.</li>
 * </ol>
 */
public final class HubLayout {

    /** A district's claim on a lot that names none the map has. */
    public static final String LOT_UNKNOWN = "LOT_UNKNOWN";
    /** A district too large for the lot it names. */
    public static final String LOT_TOO_SMALL = "LOT_TOO_SMALL";
    /** A district naming a lot another district holds. */
    public static final String LOT_CLASH = "LOT_CLASH";
    /** A district naming no lot and filling no empty one. */
    public static final String NO_LOT = "NO_LOT";

    /**
     * One district as the layout reads it.
     *
     * @param districtId  its file's id
     * @param lotId       the lot it claims, or null for none
     * @param size        its size, or null to fit whatever lot it names (and, filling, any lot)
     * @param fillsEmpty  whether it stands on every empty lot of its size, read only when it names no lot
     * @param ownerPlaced whether the server owner's file chose its lot, which puts its claim first
     */
    public record Claim(@Nonnull String districtId, @Nullable String lotId, @Nullable Size size, boolean fillsEmpty,
                        boolean ownerPlaced) {
    }

    /**
     * A district on a lot.
     *
     * @param lot         the lot
     * @param districtId  the district's id, as its file spells it
     * @param placeholder whether it stands there only because the lot was empty
     */
    public record Placed(@Nonnull Lot lot, @Nonnull String districtId, boolean placeholder) {
    }

    /**
     * A district the rule left off the map, and why.
     *
     * @param code       one of {@link #LOT_UNKNOWN}, {@link #LOT_TOO_SMALL}, {@link #LOT_CLASH}, {@link #NO_LOT}
     * @param districtId the district
     * @param lotId      the lot it named, or null
     * @param holder     for a clash, the district holding the lot; else null
     */
    public record Problem(@Nonnull String code, @Nonnull String districtId, @Nullable String lotId,
                          @Nullable String holder) {
    }

    /**
     * The laid-out hub.
     *
     * @param placed   each lot's district, in the map's lot order; a lot no district stands on is absent
     * @param problems every district left off, in claim order
     */
    public record Result(@Nonnull List<Placed> placed, @Nonnull List<Problem> problems) {

        /** The lot {@code districtId} stands on as a claimed (never a placeholder) district, or null. */
        @Nullable
        public Lot lotOf(@Nullable String districtId) {
            if (districtId == null) {
                return null;
            }
            for (Placed p : placed) {
                if (!p.placeholder() && p.districtId().equalsIgnoreCase(districtId.trim())) {
                    return p.lot();
                }
            }
            return null;
        }
    }

    private static final Comparator<Claim> CLAIM_ORDER = Comparator.comparing((Claim c) -> !c.ownerPlaced())
            .thenComparing(c -> c.districtId().toLowerCase(Locale.ROOT));

    private HubLayout() {
    }

    /** Lay {@code claims} out on {@code lots}. Deterministic: the same input always gives the same map. */
    @Nonnull
    public static Result assign(@Nonnull List<Lot> lots, @Nonnull Collection<Claim> claims) {
        Map<String, Lot> byId = new LinkedHashMap<>();
        for (Lot lot : lots) {
            byId.put(key(lot.id()), lot);
        }
        List<Claim> ordered = new ArrayList<>(claims);
        ordered.sort(CLAIM_ORDER);

        Map<String, Placed> held = new LinkedHashMap<>();
        List<Problem> problems = new ArrayList<>();
        List<Claim> fillers = new ArrayList<>();
        for (Claim claim : ordered) {
            String lotId = claim.lotId() == null || claim.lotId().isBlank() ? null : claim.lotId().trim();
            if (lotId == null) {
                if (claim.fillsEmpty()) {
                    fillers.add(claim);
                } else {
                    problems.add(new Problem(NO_LOT, claim.districtId(), null, null));
                }
                continue;
            }
            Lot lot = byId.get(key(lotId));
            if (lot == null) {
                problems.add(new Problem(LOT_UNKNOWN, claim.districtId(), lotId, null));
                continue;
            }
            if (claim.size() != null && !claim.size().fits(lot.size())) {
                problems.add(new Problem(LOT_TOO_SMALL, claim.districtId(), lot.id(), null));
                continue;
            }
            Placed there = held.get(key(lot.id()));
            if (there != null) {
                problems.add(new Problem(LOT_CLASH, claim.districtId(), lot.id(), there.districtId()));
                continue;
            }
            held.put(key(lot.id()), new Placed(lot, claim.districtId(), false));
        }

        fillers.sort(Comparator.comparing(c -> c.districtId().toLowerCase(Locale.ROOT)));
        List<Placed> placed = new ArrayList<>();
        for (Lot lot : lots) {
            Placed there = held.get(key(lot.id()));
            if (there == null) {
                Claim filler = fillerFor(lot, fillers);
                there = filler == null ? null : new Placed(lot, filler.districtId(), true);
            }
            if (there != null) {
                placed.add(there);
            }
        }
        return new Result(List.copyOf(placed), List.copyOf(problems));
    }

    /** The first filler that fits {@code lot}: its size, or no size at all. */
    @Nullable
    private static Claim fillerFor(@Nonnull Lot lot, @Nonnull List<Claim> fillers) {
        for (Claim filler : fillers) {
            if (filler.size() == null || filler.size() == lot.size()) {
                return filler;
            }
        }
        return null;
    }

    @Nonnull
    private static String key(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
