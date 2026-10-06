package com.ziggfreed.common.ui.kit;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A module's contribution to a shared ledger surface ({@link LedgerContributions#STATISTICS}): the sections it adds
 * to the list and the page for one of its rows. Pure reads for one viewer; a source that has nothing for this
 * viewer returns no sections, and a surface with no section from any source hides.
 */
public interface LedgerSource {

    /** A stable id, unique on its surface; contributing the same id again replaces the source. */
    @Nonnull
    String id();

    /** Where its sections sit among the other sources' (lower first; ties by id). */
    int order();

    /** Its sections for this viewer; row ids are unique within this source. */
    @Nonnull
    List<LedgerSection> sections(@Nonnull LedgerContext ctx);

    /** The page for one of its rows, or null when the row is gone. */
    @Nullable
    DetailView page(@Nonnull String rowId, @Nonnull LedgerContext ctx);
}
