package com.ziggfreed.common.objectives.producer;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.LibraryOwner;
import com.ziggfreed.common.npc.TalkCredit;
import com.ziggfreed.common.npc.TalkCredits;
import com.ziggfreed.common.progress.DispatchOptions;
import com.ziggfreed.common.util.SafeLog;

/**
 * Turns a credited conversation into quest and achievement progress: {@code TALK_TO_NPC}, so a step
 * or a criterion asking the player to speak with somebody advances on any server, whoever authored
 * it. The talk-credit engine ({@link TalkCredits}) decides that a conversation HAPPENED - an authored
 * {@code MarkTalked} beat or a {@code ZigTalkCredit} role action, behind its re-trigger window - and
 * hands it to every registered sink; this is the library's own sink.
 *
 * <p><b>The three-call shape is load-bearing.</b> The character's PRIMARY id goes through the full
 * dispatch ({@code ProgressDispatch.fire}), every reaction included, carrying a {@link TalkPayload};
 * every further id the character answers to goes through the
 * {@link DispatchOptions#TARGETED_ONLY targeted} route, which reaches the engines only and only the
 * content that NAMES that id. So one conversation counts ONCE for "talk to anybody" and for a lifetime
 * counter, and once for each specific id the character answers to. Each alias takes the re-trigger
 * window on its own terms ({@link TalkCredits#claim}), which is what stops an alias fired beside its
 * primary from being swallowed by the primary's window.
 *
 * <p><b>A consumer reacts; it never produces this kind beside it.</b> A sink of its own that also
 * dispatched {@code TALK_TO_NPC} to the engines would count every conversation twice. What a
 * consumer adds to a conversation (a statistic, a sound) it adds through a {@code MomentListener} on
 * the moment produced here.
 *
 * <p>World thread: a credit carries the caller's live store and refs straight through.
 */
public final class ZigTalkProducer {

    /** Fired once per credited conversation, and once more per further id it answers to. The built-in kind. */
    public static final String KIND = "TALK_TO_NPC";

    /** The id this sink is registered under in the talk-credit engine. */
    public static final String SINK_ID = LibraryOwner.NAME;

    private static final long AMOUNT = 1L;

    private ZigTalkProducer() {
    }

    /** Join the talk-credit engine. Registration only, from {@code ProgressionDefaults.install}. */
    public static void install() {
        TalkCredits.register(SINK_ID, LibraryOwner.NAME, ZigTalkProducer::credit);
    }

    /** Guarded whole: a producer that throws must not cost the other sinks their credit. */
    private static void credit(@Nonnull TalkCredit credit) {
        try {
            credit(credit, playerIdOf(credit));
        } catch (Throwable t) {
            SafeLog.warn("[progression] talk progress failed for '" + credit.npcId() + "'", t);
        }
    }

    /**
     * The fan-out, with the player's id handed in: the primary through the full dispatch, then each
     * alias whose window this player can take, through the targeted route. With no player to name,
     * no alias window can be claimed, so only the primary counts. Package-visible so the fan-out is
     * pinned over the shared runtime with no server anywhere near it.
     */
    static void credit(@Nonnull TalkCredit credit, @Nullable UUID playerId) {
        ProgressDispatch.fire(credit.store(), credit.playerRef(), null, KIND, credit.npcId(),
                credit.qualifier(), AMOUNT, new TalkPayload(credit));
        if (playerId == null) {
            return;
        }
        for (String alias : credit.aliases()) {
            if (TalkCredits.claim(playerId, alias)) {
                ProgressDispatch.fire(credit.store(), credit.playerRef(), KIND, alias, credit.qualifier(),
                        AMOUNT, DispatchOptions.TARGETED_ONLY);
            }
        }
    }

    @Nullable
    private static UUID playerIdOf(@Nonnull TalkCredit credit) {
        PlayerRef player = credit.player();
        return player == null ? null : player.getUuid();
    }
}
