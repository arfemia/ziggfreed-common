package com.ziggfreed.common.stats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What an entity has on at one instant, by item id: the held stack, the utility-slot (offhand)
 * stack and every armor slot in container order. Read through {@link EquipStatBridge#equippedSnapshot}
 * from the same container reads the bridge's stat walkers use, so a listener deciding by WHICH
 * items are worn (the gear-set engine) sees exactly the slots the bridge applies.
 *
 * <p>An empty slot is a null entry: {@link #armorItemIds} keeps its index alignment with the armor
 * container, so slot two is always at position two. {@link #distinctItemIds} is the lower-cased set
 * of everything on, whichever slot it sits in, for a membership test that ignores authored casing.
 * A pure value: no engine type, so a decision core over it is tested on plain ids.
 */
public record EquippedSnapshot(@Nullable String heldItemId, @Nullable String offhandItemId,
        @Nonnull List<String> armorItemIds, @Nonnull Set<String> distinctItemIds) {

    /** Nothing on at all: the read for a missing container or an invalid ref. */
    public static final EquippedSnapshot EMPTY = of(null, null, List.of());

    public EquippedSnapshot {
        armorItemIds = Collections.unmodifiableList(new ArrayList<>(armorItemIds));
        distinctItemIds = Collections.unmodifiableSet(new LinkedHashSet<>(distinctItemIds));
    }

    /**
     * The snapshot for what is held, what sits in the offhand and what each armor slot holds (null
     * for an empty slot), the distinct set derived from the three.
     */
    @Nonnull
    public static EquippedSnapshot of(@Nullable String heldItemId, @Nullable String offhandItemId,
            @Nonnull List<String> armorItemIds) {
        Set<String> distinct = new LinkedHashSet<>();
        addLower(distinct, heldItemId);
        addLower(distinct, offhandItemId);
        for (String armor : armorItemIds) {
            addLower(distinct, armor);
        }
        return new EquippedSnapshot(blankToNull(heldItemId), blankToNull(offhandItemId), armorItemIds, distinct);
    }

    /** True when nothing is on in any slot. */
    public boolean isEmpty() {
        return distinctItemIds.isEmpty();
    }

    /** True when {@code itemId} is on in ANY slot, matched without regard to case. */
    public boolean has(@Nullable String itemId) {
        String key = lower(itemId);
        return key != null && distinctItemIds.contains(key);
    }

    /** {@code itemId} lower-cased for a membership test, or null for a blank one. */
    @Nullable
    public static String lower(@Nullable String itemId) {
        String trimmed = blankToNull(itemId);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static void addLower(@Nonnull Set<String> into, @Nullable String itemId) {
        String key = lower(itemId);
        if (key != null) {
            into.add(key);
        }
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
