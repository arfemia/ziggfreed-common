package com.ziggfreed.common.loot.trigger;

import java.util.LinkedHashSet;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.reward.ChestSite;
import com.ziggfreed.common.loot.reward.MomentItems;

/**
 * The three moments a bonus row can hang off: a block a player broke, a mob a player killed, an
 * item a player harvested by hand.
 *
 * <p>Each constant says how it is spelled in a row and which pass-scoped collectors its pass layers
 * on the subject it grants through. The audit reads that declaration; the pass itself layers a
 * {@link MomentItems} only when its site hands it the harvested stack, so a new carried collector
 * changes {@link BonusPasses} too. The library's own rows carry more on some moments
 * ({@link #libraryCarries}): zc-objectives' {@code BonusRowReactions} layers a {@link ChestSite} on its kill and
 * break passes and stands the chest it collects, which a consumer's own table never does. A new moment appends
 * after the last constant, with its landing in {@link BonusPasses}, so fold and listing order never move.
 */
public enum BonusMoment {

    /** A player broke a block they did not place; a row's {@code Match} reads the block id. */
    BREAK_BLOCK("BreakBlock", Set.of(), Set.of(ChestSite.class)),

    /** A kill credited to a player; a row's {@code Match} reads the mob's id. */
    KILL_MOB("KillMob", Set.of(), Set.of(ChestSite.class)),

    /**
     * A player harvested an item by hand; a row's {@code Match} reads the item id, and the pass
     * runs after the harvest itself lands. Its pass carries a {@link MomentItems}, so a
     * {@code Moment_Item} reward hands over another of the harvested stack.
     */
    PICKUP_ITEM("PickupItem", Set.of(MomentItems.class), Set.of());

    private final String token;
    private final Set<Class<?>> carries;
    private final Set<Class<?>> libraryCarries;

    BonusMoment(@Nonnull String token, @Nonnull Set<Class<?>> carries, @Nonnull Set<Class<?>> libraryAdds) {
        this.token = token;
        this.carries = carries;
        Set<Class<?>> library = new LinkedHashSet<>(carries);
        library.addAll(libraryAdds);
        this.libraryCarries = Set.copyOf(library);
    }

    /** How this moment is spelled in a row. */
    @Nonnull
    public String token() {
        return token;
    }

    /**
     * The collector types this moment's pass layers on its subject; empty for a pass that carries
     * none. The audit judges a row's inline rolls against it ({@link BonusRowAudit}); the pass
     * builds its collector from the stack its site hands it, never from this set.
     */
    @Nonnull
    public Set<Class<?>> carries() {
        return carries;
    }

    /**
     * The collector types the library's OWN rows' pass layers for this moment ({@code BonusRowReactions}): every
     * one {@link #carries} names, plus a {@link ChestSite} on a kill and a break, whose chest that pass stands. The
     * audit of the library's rows judges their inline rolls against this set ({@link BonusRowAudit#auditAll}); a
     * consumer's table, whose pass is {@link BonusPasses#run} alone, keeps {@link #carries}.
     */
    @Nonnull
    public Set<Class<?>> libraryCarries() {
        return libraryCarries;
    }

    /** The moment {@code raw} names, or null when it names none. Case and underscores forgiven. */
    @Nullable
    public static BonusMoment parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String squashed = raw.trim().replace("_", "");
        if (squashed.isEmpty()) {
            return null;
        }
        for (BonusMoment moment : values()) {
            if (moment.token.equalsIgnoreCase(squashed)) {
                return moment;
            }
        }
        return null;
    }

    /** Every spelling, comma separated, for a message that has to list them. */
    @Nonnull
    public static String tokens() {
        StringBuilder sb = new StringBuilder();
        for (BonusMoment moment : values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(moment.token);
        }
        return sb.toString();
    }
}
