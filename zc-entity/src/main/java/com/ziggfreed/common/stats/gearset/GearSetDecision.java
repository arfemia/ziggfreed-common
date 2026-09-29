package com.ziggfreed.common.stats.gearset;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

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

    private static boolean met(Integer minimum, int count) {
        return minimum == null || count >= minimum;
    }
}
