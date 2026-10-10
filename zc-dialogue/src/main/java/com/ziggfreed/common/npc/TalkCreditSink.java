package com.ziggfreed.common.npc;

import javax.annotation.Nonnull;

/**
 * What a mod DOES when a conversation is credited: tick a quest step, tally a statistic, unlock
 * something. Registered on {@link TalkCredits}, one per mod.
 *
 * <p>A sink is a WRITE, which is why credit goes through a registry rather than only through the
 * event bus: writes want attribution (whose sink failed), isolation (one bad mod must not cost
 * another its quest step) and a single decision about whether this moment counts at all. Anything
 * that merely wants to WATCH conversations should listen for {@link NpcTalkedEvent} instead and
 * register nothing. The library's own {@code TALK_TO_NPC} sink ({@link TalkCredits#LIBRARY_SINK_ID})
 * always runs beside yours, so a sink never dispatches that kind itself, or a conversation counts twice.
 *
 * <p>Called on the world thread, inside the caller's own interaction or page click. Throwing is
 * safe - the failure is recorded against your registration and every other sink still runs - but a
 * sink that throws every time is a sink that credits nothing.
 */
@FunctionalInterface
public interface TalkCreditSink {

    /** Credit this conversation. */
    void credit(@Nonnull TalkCredit credit);
}
