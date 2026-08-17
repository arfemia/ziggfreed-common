package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;

import com.ziggfreed.common.subject.Subject;

/**
 * Is one of the runtime's two systems switched ON for this player right now?
 *
 * <p>An owner usually has an admin switch for each system it ships - "quests off on this server",
 * "achievements off until launch" - normally with a staff bypass beside it, and that switch was
 * historically read inside the owner's own event systems. Once a moment is produced by a SHARED
 * producer instead, the switch has to be readable from the shared dispatch or a server with quests
 * turned off would watch them advance anyway.
 *
 * <p><b>This is a SYSTEM gate, not a producer claim.</b> Nothing here stands a producer down: every
 * producer runs, every produced moment reaches the shared dispatch, and what a refusal costs is
 * exactly the half it names - unwritten to THAT engine for THAT player, with the other half
 * untouched. There is one quest engine and one achievement engine per server, so today that is the
 * whole of the refused half; the point of saying it this way is that a second engine would not
 * change the rule.
 *
 * <p><b>Contributions STACK, and they AND.</b> Every registered gate is asked and any refusal wins,
 * so registration order cannot change the answer and no mod can re-open a system another mod shut.
 * With nothing registered the answer is OPEN, which is what keeps a server that registered no
 * switches running every moment.
 *
 * <p><b>A gate that THROWS counts as OPEN, and says so once.</b> The switch it reads is a config
 * value and an unreadable one must never read as "off": failing closed would turn a whole system
 * off for every player on the server on the strength of a bug, silently, while failing open costs
 * at most that one refusal. It is also what the owner's own read did before the move - the switch
 * threw rather than answering, so nothing ever read a failure as a refusal.
 *
 * <p>Answered about a {@link Subject}, which is the one identity vocabulary every engine here
 * speaks, so a gate is pure logic and can be unit tested with no server anywhere near it. An
 * implementation that needs a richer handle reads it back with {@link Subject#handleAs}.
 */
@FunctionalInterface
public interface ProgressionSystemGate {

    /** Refuses nothing: the answer a runtime nobody registered a gate into gives. */
    ProgressionSystemGate OPEN = (system, subject) -> true;

    /**
     * True when {@code system} is switched on for {@code subject}, false when the owner has it off.
     *
     * <p>Asked on the world thread, on the hot path of every produced moment, so keep it to a
     * config read and a permission check.
     */
    boolean enabled(@Nonnull ProgressionSystem system, @Nonnull Subject subject);
}
