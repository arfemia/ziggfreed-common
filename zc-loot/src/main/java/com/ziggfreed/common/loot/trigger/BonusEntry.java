package com.ziggfreed.common.loot.trigger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.loot.LootRef;

/**
 * One resolved bonus row as the table, the odds, the rolls and the audit read it. A table's own row
 * type implements this beside whatever else its layer carries (a note, a moment of its own).
 */
public interface BonusEntry {

    /** The moment this row answers for. */
    @Nonnull
    BonusMoment moment();

    /** The id the row is filed under: what a finding, a warning and a source label name. */
    @Nonnull
    String sourceId();

    /** What the row hands over; an empty ref is a deliberate hole. */
    @Nonnull
    LootRef loot();

    /** The odds the row states for itself as a PERCENT, or null to leave them to its moment's default. */
    @Nullable
    FactorFormula chance();

    /**
     * The group the row competes in, lower-cased: rows of one group give one answer for a name, and each group
     * answers on its own ({@link BonusTable#answers}). Blank for the shared group, which a row joins unless it names
     * one, and which is all a table whose rows name none ever has.
     */
    @Nonnull
    default String group() {
        return "";
    }

    /**
     * Whether the moment's yield adds to the {@code Chance} the row writes, so a written chance with no term of its
     * own can still rise off zero. False unless the table's pass adds one (the library's own rows do).
     */
    default boolean addsYield() {
        return false;
    }

    /**
     * True when the row can never hand anything over: a hole that stops a broader pattern covering
     * the same name.
     */
    default boolean handsNothingOver() {
        return loot().isEmpty();
    }
}
