package com.ziggfreed.common.progress;

/**
 * The two independent switches on a progress dispatch. Both default off, and the three constants
 * below name the combinations that come up in practice.
 *
 * <p><b>{@code tapObservers}</b> decides whether the dispatching engine's observer tap sees this
 * event. On for the ONE authoritative fire of a player action; off for any follow-up fire of the
 * same action, so a lifetime counter cannot count one swing twice.
 *
 * <p><b>{@code targetedOnly}</b> restricts matching to objectives that NAME a target, skipping the
 * match-all ones (blank target). This is what makes it safe to re-dispatch a single action under
 * every id the thing it happened to answers to: a match-all objective already counted the action on
 * the primary fire, and without this switch it would count again for each alias. An objective that
 * names a target cannot double-count, because only one of the alias ids can match it.
 */
public record DispatchOptions(boolean tapObservers, boolean targetedOnly) {

    /** The authoritative fire of an action: objectives advance and the tap sees it. */
    public static final DispatchOptions FULL = new DispatchOptions(true, false);

    /** Objectives only. Use when the consumer already counted this event some other way. */
    public static final DispatchOptions OBJECTIVES_ONLY = new DispatchOptions(false, false);

    /**
     * The follow-up fire under an additional id: only objectives that NAME a target may match, and
     * the tap stays out of it. See the class javadoc for why both switches belong together here.
     */
    public static final DispatchOptions TARGETED_ONLY = new DispatchOptions(false, true);
}
