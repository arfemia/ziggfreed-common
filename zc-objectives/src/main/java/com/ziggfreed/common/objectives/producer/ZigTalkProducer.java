package com.ziggfreed.common.objectives.producer;

import java.util.UUID;
import java.util.function.BiPredicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.npc.TalkCredit;
import com.ziggfreed.common.npc.TalkCredits;
import com.ziggfreed.common.progress.DispatchOptions;

/**
 * Turns a credited conversation into quest and achievement progress: one {@code TALK_TO_NPC} for the
 * character's primary id, plus one per further id it answers to, Qualifier the beat's own, Amount 1.
 *
 * <p>A conversation is not a native event: it is credited by an authored beat ({@code MarkTalked}, or
 * the {@code ZigTalkCredit} role action) through zc-dialogue's {@link TalkCredits}, which decides
 * whether the moment counts at all (the re-trigger window) and resolves the alias set. This producer
 * is the library's own SINK there, registered under {@link TalkCredits#LIBRARY_SINK_ID}, and it
 * always runs, beside any sink a consumer registers. A consumer reacts to the moment produced here
 * through a {@code MomentListener} and never dispatches {@code TALK_TO_NPC} from a sink of its own,
 * or one conversation counts twice.
 *
 * <p><b>The three-call shape is load-bearing.</b> The primary goes through the producer form of
 * {@link ProgressDispatch#fire}: every reaction sees it with a {@link TalkPayload}, and both engines
 * count it in full, a match-all "talk to anyone" step included. Each alias goes through the
 * {@link DispatchOptions#TARGETED_ONLY} engine route instead, after taking its own re-trigger window
 * ({@link TalkCredits#claim}): only a step that NAMES that id may move, and no reaction sees it again.
 * Collapsing the two into one loop would count a match-all step, and any reaction, once per alias.
 *
 * <p>World thread: the credit carries the caller's live store and player ref straight through.
 */
public final class ZigTalkProducer {

    /** Fired once per credited conversation for the primary, and once per claimed alias. */
    public static final String KIND = "TALK_TO_NPC";

    private static final long AMOUNT = 1L;

    private ZigTalkProducer() {
    }

    /**
     * Register the library's talk-credit sink. Registration only, from {@code ProgressionDefaults.install}
     * beside the other producers. The sink registry is process-wide, so {@code plugin} is taken for the
     * same install shape as its neighbours and nothing is registered on it.
     */
    public static void install(@Nonnull PluginBase plugin) {
        TalkCredits.registerLibrarySink(ZigTalkProducer::credit);
    }

    /** Where one conversation's fires go. A seam purely so the fan-out needs no server to test. */
    interface Sink {

        /** The primary: every reaction, then both engines in full. */
        void primary(@Nonnull TalkCredit credit, @Nonnull String kindId, @Nonnull String target,
                @Nullable String qualifier, long amount, @Nonnull TalkPayload payload);

        /** One alias: the engines only, with {@code options}, and no reaction. */
        void alias(@Nonnull TalkCredit credit, @Nonnull String kindId, @Nonnull String target,
                @Nullable String qualifier, long amount, @Nonnull DispatchOptions options);
    }

    /** The live engine half: {@link ProgressDispatch}'s two routes over the credit's own handles. */
    private static final Sink DISPATCH = new Sink() {

        @Override
        public void primary(@Nonnull TalkCredit credit, @Nonnull String kindId, @Nonnull String target,
                @Nullable String qualifier, long amount, @Nonnull TalkPayload payload) {
            ProgressDispatch.fire(credit.store(), credit.playerRef(), null, kindId, target, qualifier, amount,
                    payload);
        }

        @Override
        public void alias(@Nonnull TalkCredit credit, @Nonnull String kindId, @Nonnull String target,
                @Nullable String qualifier, long amount, @Nonnull DispatchOptions options) {
            ProgressDispatch.fire(credit.store(), credit.playerRef(), kindId, target, qualifier, amount, options);
        }
    };

    /**
     * The sink {@link TalkCredits} calls. It runs inside that engine's per-sink guard, which records a
     * throw against this registration and keeps the conversation's event firing. Each alias claims its
     * window under the beat's qualifier, as the primary did, so an unqualified credit never swallows a
     * qualified line's alias inside the window.
     */
    static void credit(@Nonnull TalkCredit credit) {
        fanOut(credit, playerIdOf(credit), (player, alias) -> TalkCredits.claim(player, alias, credit.qualifier()),
                DISPATCH);
    }

    /**
     * The primary, then every alias whose own window {@code claim} grants. {@link TalkCredits} has
     * already taken the primary's window before any sink runs. With no player to claim for, only the
     * primary goes: an alias never fires unclaimed.
     *
     * @return how many moments went out
     */
    static int fanOut(@Nonnull TalkCredit credit, @Nullable UUID playerId,
            @Nonnull BiPredicate<UUID, String> claim, @Nonnull Sink sink) {
        String target = credit.npcId();
        if (target.isBlank()) {
            return 0;
        }
        sink.primary(credit, KIND, target, credit.qualifier(), AMOUNT, new TalkPayload(credit));
        int fired = 1;
        if (playerId == null) {
            return fired;
        }
        for (String alias : credit.aliases()) {
            if (!claim.test(playerId, alias)) {
                continue;
            }
            sink.alias(credit, KIND, alias, credit.qualifier(), AMOUNT, DispatchOptions.TARGETED_ONLY);
            fired++;
        }
        return fired;
    }

    /** The credited player's uuid, or null when the credit's ref no longer resolves a player. */
    @Nullable
    private static UUID playerIdOf(@Nonnull TalkCredit credit) {
        try {
            PlayerRef player = credit.player();
            return player == null ? null : player.getUuid();
        } catch (Throwable t) {
            return null;
        }
    }
}
