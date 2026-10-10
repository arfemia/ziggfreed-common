package com.ziggfreed.common.counter;

import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.registry.RegistryLedger;

/**
 * Every {@link NativeReader}, keyed by the id of the tally it feeds, matched without regard to case. It sits
 * here, below every module, so the module that may ask the engine a question registers its reader at its
 * own setup and the module that ranks tallies reads them all, with no edge between the two.
 *
 * <p>An open registry: one reader per id, a second registration replacing the first with one warning, and
 * nothing registered means nothing read. A live server registers once at setup and never unregisters.
 */
public final class NativeReaders {

    private static final RegistryLedger<NativeReader> READERS = new RegistryLedger<>("native readers");

    private NativeReaders() {
    }

    /** Register {@code reader} for the tally {@code id}, attributed to {@code owner}. A blank id or a null reader is ignored. */
    public static void register(@Nullable String owner, @Nullable String id, @Nullable NativeReader reader) {
        READERS.put(id, owner, reader);
    }

    /** The reader registered for {@code id}, or null when none is. */
    @Nullable
    public static NativeReader get(@Nullable String id) {
        return READERS.get(id);
    }

    /** Every id a reader is registered for, lower-cased and sorted. */
    @Nonnull
    public static Set<String> ids() {
        return READERS.ids();
    }

    /** Note that {@code id}'s reader just threw, so the ledger can say who owns a failing reader. */
    public static void recordFailure(@Nullable String id, @Nullable String message) {
        READERS.recordFailure(id, message);
    }
}
