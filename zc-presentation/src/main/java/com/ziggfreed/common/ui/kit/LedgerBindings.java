package com.ziggfreed.common.ui.kit;

import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.builder.EventData;

/**
 * The event data a page wants on each clickable part of a ledger list. A null answer leaves that part unbound (a
 * row a page does not select, a section that does not fold).
 */
public interface LedgerBindings {

    /** A row's click. */
    @Nullable
    EventData row(LedgerSection s, LedgerRow r);

    /** A section head's click (open or close). */
    @Nullable
    EventData section(LedgerSection s);

    /** A section's "Show N more" row. */
    @Nullable
    EventData showMore(LedgerSection s);

    /**
     * A row's own action button ({@link LedgerRow#action}), pressed: none by default, so a list whose rows carry no
     * action answers nothing new. Asked only for a row with an action that can be pressed.
     */
    @Nullable
    default EventData action(LedgerSection s, LedgerRow r) {
        return null;
    }
}
