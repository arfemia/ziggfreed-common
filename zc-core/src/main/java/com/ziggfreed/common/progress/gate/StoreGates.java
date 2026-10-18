package com.ziggfreed.common.progress.gate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * The {@code Requires} of a store file below the quest stores (a loot table, a bonus row, a gear set, a
 * reward row): read whole, every condition (R606). Its plain mod-presence condition is decided once, at the
 * fold (zc-core {@code ModGates}: a file whose mod is missing never loads); everything else in the block is
 * its LIVE part ({@link #live}), asked of the player at the moment the file would pay or apply
 * ({@link #passes}).
 *
 * <p>Earlier versions read these stores' blocks for mod presence alone and ignored the rest without a word. A
 * fold names, once at INFO, every file whose block carries more ({@link #reportWidened}), so an owner can see
 * which gates now apply.
 */
public final class StoreGates {

    /** The sentence the Asset Editor shows beside such a store's file-level {@code Requires}. */
    public static final String DOCUMENTATION = "What a player must meet for this file to pay or apply, in the "
            + "Requires shape quests use (Factors, Permission, Quests, Custom, AllOf, AnyOf, Not), every "
            + "condition read. A plain { \"Factor\": \"hytale:mod_installed\", \"Param\": \"Group:Name\", \"Min\": 1 } "
            + "keeps the file out entirely where that mod is not installed; the rest is asked of the player each "
            + "time. Write Min 1 on a presence check: a condition with no bound is met by a missing mod too.";

    /** The sentence the Asset Editor shows beside a reward row's own {@code Requires}. */
    public static final String ROW_DOCUMENTATION = "What a player must meet for this reward row to pay, in the "
            + "Requires shape quests use, every condition read. A plain { \"Factor\": \"hytale:mod_installed\", "
            + "\"Param\": \"Group:Name\", \"Min\": 1 } leaves the row out where that mod is not installed (it pays "
            + "nothing, shows nowhere and is not reported); the rest is asked of the player when the row pays, and a "
            + "row they do not meet pays them nothing while the rest of the list pays as written.";

    /** The last list each store reported, so a refold with nothing new says nothing again. */
    private static final Map<String, List<String>> REPORTED = new ConcurrentHashMap<>();

    private StoreGates() {
    }

    /**
     * What of {@code requires} is asked per player: the block without its top-level plain mod-presence
     * conditions (the fold decided those), or null when nothing is left. The answer is a new block only when
     * something was lifted, so a caller keeps it per file rather than lifting on every ask.
     */
    @Nullable
    public static GateSpec live(@Nullable GateSpec requires) {
        if (requires == null || requires.isEmpty()) {
            return null;
        }
        return FeatureLift.liftModPresence(requires).requires();
    }

    /** Does {@code live} pass for {@code query}? Null (nothing to ask) passes. Through the server's one evaluator. */
    public static boolean passes(@Nullable GateSpec live, @Nonnull GateQuery query) {
        return live == null || GateRuntime.evaluator().check(live, query).passed();
    }

    /**
     * Name, once at INFO, the files of {@code store} whose {@code Requires} carries conditions beyond mod
     * presence, which apply from this version on. Nothing for an empty list, or for the same list this store
     * reported last.
     */
    public static void reportWidened(@Nonnull String store, @Nonnull Collection<String> ids) {
        List<String> sorted = new ArrayList<>(new TreeSet<>(ids));
        String key = store.toLowerCase(Locale.ROOT);
        List<String> last = REPORTED.put(key, List.copyOf(sorted));
        if (sorted.isEmpty() || sorted.equals(last)) {
            return;
        }
        SafeLog.info("[zc] " + store + ": " + sorted.size() + " file(s) gate on more than mod presence in Requires;"
                + " earlier versions read only the mod condition, and the rest now applies to each player: "
                + String.join(", ", sorted));
    }

    /** Forget what each store reported. Tests only. */
    public static void resetForTests() {
        REPORTED.clear();
    }
}
