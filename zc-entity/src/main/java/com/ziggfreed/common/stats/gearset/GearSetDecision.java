package com.ziggfreed.common.stats.gearset;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.stats.EquippedSnapshot;

/**
 * The PURE decision under the gear-set engine: given what a player has on and one set, how many of
 * the set's members sit in each kind of slot, and which tiers those counts satisfy. No engine type
 * anywhere, so every rule here is pinned on plain item ids.
 *
 * <p>The four counts are independent readings of the same snapshot, each answering ONE of a tier's
 * minimums, since a tier's condition is a conjunction of independent minimums, never a bare count:
 * <ul>
 *   <li>{@code pieces}: distinct members on anywhere (worn, held, or in the utility slot); a copy
 *       held while the same piece is worn counts once;</li>
 *   <li>{@code armor}: distinct members in armor slots only;</li>
 *   <li>{@code held}: 1 when the active-hand item is a member AND is not also worn in an armor slot
 *       (compared by item id without regard to case, the way membership is), so a spare copy of a
 *       worn piece in hand never stands in for the set's weapon;</li>
 *   <li>{@code utility}: 1 when the utility-slot item is a member.</li>
 * </ul>
 * A tier holds when EVERY minimum it authors is met; an unauthored minimum is not a condition, and a
 * tier authoring none never holds (the validator names it).
 */
public final class GearSetDecision {

    /** How many of one set's members sit in each kind of slot. */
    public record SlotCounts(int pieces, int armor, int held, int utility) {
    }

    private GearSetDecision() {
    }

    /** The counts for {@code set} over {@code snapshot}. */
    @Nonnull
    public static SlotCounts count(@Nonnull GearSetAsset set, @Nonnull EquippedSnapshot snapshot) {
        Set<String> members = set.memberIds();
        Set<String> anywhere = new HashSet<>();
        Set<String> inArmor = new HashSet<>();

        for (String worn : snapshot.armorItemIds()) {
            String key = EquippedSnapshot.lower(worn);
            if (key != null && members.contains(key)) {
                inArmor.add(key);
                anywhere.add(key);
            }
        }
        String held = EquippedSnapshot.lower(snapshot.heldItemId());
        boolean heldMember = held != null && members.contains(held);
        if (heldMember) {
            anywhere.add(held);
        }
        int heldCount = heldMember && !inArmor.contains(held) ? 1 : 0;
        String offhand = EquippedSnapshot.lower(snapshot.offhandItemId());
        int utilityCount = offhand != null && members.contains(offhand) ? 1 : 0;
        if (utilityCount == 1) {
            anywhere.add(offhand);
        }
        return new SlotCounts(anywhere.size(), inArmor.size(), heldCount, utilityCount);
    }

    /** Whether every minimum {@code tier} authors is met by {@code counts}; a tier with none never holds. */
    public static boolean holds(@Nonnull GearSetAsset.Tier tier, @Nonnull SlotCounts counts) {
        if (!tier.hasCondition()) {
            return false;
        }
        return met(tier.getPieces(), counts.pieces())
                && met(tier.getArmor(), counts.armor())
                && met(tier.getHeld(), counts.held())
                && met(tier.getUtility(), counts.utility());
    }

    /** The positions, in {@code Bonuses} order, of every tier of {@code set} that {@code counts} satisfies. */
    @Nonnull
    public static List<Integer> activeTiers(@Nonnull GearSetAsset set, @Nonnull SlotCounts counts) {
        List<GearSetAsset.Tier> tiers = set.tiers();
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < tiers.size(); i++) {
            if (holds(tiers.get(i), counts)) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * How many pieces a FULL set takes, the number a notice counts against: the largest, over the
     * tiers, of {@code max(Pieces, Armor + Held + Utility)}, an unauthored minimum reading 0. Never
     * the member list, which may hold several alternatives for one slot (two helms and two shields
     * make a two-piece set; three hatchets fill one hand). 0 for a set with no tier.
     */
    public static int fullCount(@Nonnull GearSetAsset set) {
        int full = 0;
        for (GearSetAsset.Tier tier : set.tiers()) {
            full = Math.max(full, size(tier));
        }
        return full;
    }

    /**
     * How far {@code counts} stand toward the full set, never the raw {@code pieces}: the best, over
     * the tiers, of how far they stand toward that tier ({@link #toward}), so a member in the wrong slot
     * moves nothing and a tier that holds never reads below its own size. So a worn helm and a shield
     * meant for the off hand, held in the main hand, stand at 1 of 2; a helm and the hatchet in the off
     * hand stand at 2 of the Woodcutter's Kit's 5, its two-piece tier on. A count at the full set means
     * a tier of that size holds. A set authoring no count answers {@code pieces}.
     */
    public static int progress(@Nonnull GearSetAsset set, @Nonnull SlotCounts counts) {
        int best = -1;
        for (GearSetAsset.Tier tier : set.tiers()) {
            if (size(tier) > 0) {
                best = Math.max(best, toward(tier, counts));
            }
        }
        return best < 0 ? counts.pieces() : best;
    }

    /**
     * How far {@code counts} stand toward {@code tier}: its {@link #size} less what it still lacks, the
     * larger of its unmet slot minimums ({@code Armor}, {@code Held}, {@code Utility}, summed) and its
     * unmet {@code Pieces}, at least 0. It equals the size exactly when the tier holds.
     */
    private static int toward(@Nonnull GearSetAsset.Tier tier, @Nonnull SlotCounts counts) {
        int slotsLacking = shortfall(tier.getArmor(), counts.armor())
                + shortfall(tier.getHeld(), counts.held())
                + shortfall(tier.getUtility(), counts.utility());
        int piecesLacking = shortfall(tier.getPieces(), counts.pieces());
        return Math.max(0, size(tier) - Math.max(slotsLacking, piecesLacking));
    }

    /**
     * The count a notice shows: {@code pieces}, at most {@code full}, so a spare member past the
     * full set (a second shield in hand) never reads "3/2". A {@code full} of 0 (a set authoring no
     * count) leaves {@code pieces} as it is. Pass {@link #progress}, never the raw {@code pieces}.
     */
    public static int shownPieces(int pieces, int full) {
        return full > 0 ? Math.min(pieces, full) : pieces;
    }

    /**
     * The tier the player has still to earn: the first of {@code set}'s tiers, in {@code Bonuses}
     * order, that authors a condition {@code counts} does not satisfy; null when every such tier
     * holds. A tier authoring no condition never holds, so it is never anybody's next.
     */
    @Nullable
    public static GearSetAsset.Tier nextTier(@Nonnull GearSetAsset set, @Nonnull SlotCounts counts) {
        for (GearSetAsset.Tier tier : set.tiers()) {
            if (tier.hasCondition() && !holds(tier, counts)) {
                return tier;
            }
        }
        return null;
    }

    private static boolean met(Integer minimum, int count) {
        return minimum == null || count >= minimum;
    }

    /** How many pieces {@code tier} takes: {@code max(Pieces, Armor + Held + Utility)}, unauthored reading 0. */
    private static int size(@Nonnull GearSetAsset.Tier tier) {
        return Math.max(orZero(tier.getPieces()), slots(tier));
    }

    private static int slots(@Nonnull GearSetAsset.Tier tier) {
        return orZero(tier.getArmor()) + orZero(tier.getHeld()) + orZero(tier.getUtility());
    }

    private static int shortfall(@Nullable Integer minimum, int count) {
        return Math.max(0, orZero(minimum) - count);
    }

    private static int orZero(@Nullable Integer value) {
        return value == null ? 0 : value;
    }
}
