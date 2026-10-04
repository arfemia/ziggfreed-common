package com.ziggfreed.common.loot.trigger;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.Roll;

/**
 * What a bonus row evaluates right now, resolved per moment rather than at fold time: a shared table
 * lives in another store whose load order nobody decides, and resolving late also means a re-tuned
 * table takes effect at once. A table the row names that nothing answers to is skipped with ONE
 * warning ever per table id, however many moments ask, until {@link #reset()} re-arms it.
 */
public final class BonusRolls {

    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final Consumer<String> warn;

    public BonusRolls(@Nonnull Consumer<String> warn) {
        this.warn = warn;
    }

    /** Every table the row names (its rolls only, no Pool), then its own inline rolls. */
    @Nonnull
    public List<Roll> rolls(@Nonnull BonusEntry entry) {
        return LootEngine.resolveRolls(entry.loot(), missing -> warnOnce(entry, missing));
    }

    /** The whole of what the row evaluates: each named table's rolls then its Pool, then the inline rolls. */
    @Nonnull
    public LootEngine.Resolved resolved(@Nonnull BonusEntry entry) {
        return LootEngine.resolve(entry.loot(), missing -> warnOnce(entry, missing));
    }

    /** Re-arm every warning: a refold may have fixed or broken any reference. */
    public void reset() {
        warned.clear();
    }

    private void warnOnce(@Nonnull BonusEntry entry, @Nonnull String missing) {
        if (warned.add(missing.toLowerCase(Locale.ROOT))) {
            warn.accept("Bonus row '" + entry.sourceId() + "' names loot table '" + missing
                    + "', which nothing answers to");
        }
    }
}
