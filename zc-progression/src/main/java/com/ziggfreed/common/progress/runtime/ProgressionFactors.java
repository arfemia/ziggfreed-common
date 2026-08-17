package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.factor.FactorProvider;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.quest.QuestProgressStore;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * What a player's PROGRESSION looks like, as ordinary factor readings: has this quest been
 * finished, how many times, has this achievement been earned, and how many achievement points does
 * this player hold.
 *
 * <p><b>Why they exist.</b> A requirement is a factor everywhere else in this library - a level, a
 * tool's tier, another mod's rarity reading - and progression was the one thing a gate could only
 * ask about through a bespoke leaf. With these four ids any content anywhere can gate on finished
 * content with no Java and no dependency on the engine that owns it: a storefront, a board, an NPC
 * placement, a dialogue option and a loot roll all read them through the same
 * {@link FactorRegistry} they read everything else through.
 *
 * <p><b>They answer for THE shared runtime</b> ({@link ProgressionRuntime}), because that is the one
 * progression a server has however many mods contribute to it. {@link #contribute()} claims all four
 * ids process-wide through {@link FactorContributions}, so every vocabulary on the server resolves
 * them without anybody wiring anything; {@link #registerInto} is the same four ids pointed at
 * somebody else's engine, for a consumer running a private one (a round that dies with the match)
 * or for a test.
 *
 * <table>
 *   <caption>The progression factor ids</caption>
 *   <tr><th>Id</th><th>Param</th><th>Value</th></tr>
 *   <tr><td>{@code ziggfreedcommon:quest_completed}</td><td>a quest id</td>
 *       <td>1 when the quest's stored status is {@code COMPLETED} right now - finished AND collected
 *       - else 0. A CURRENT reading, so a parked reward reads 0, and so does a repeatable a re-arm
 *       has sent round again</td></tr>
 *   <tr><td>{@code ziggfreedcommon:quest_completions}</td><td>a quest id</td>
 *       <td>how many times they have finished it AND collected what it paid, ever - the durable
 *       tally beside that flag, and a REPEATABLE reading, since only a repeatable keeps a completion
 *       record and a one-shot stays 0</td></tr>
 *   <tr><td>{@code ziggfreedcommon:achievement_earned}</td><td>an achievement id</td>
 *       <td>1 when earned (collected or not), else 0</td></tr>
 *   <tr><td>{@code ziggfreedcommon:achievement_points}</td><td>ignored</td>
 *       <td>the player's earned points total</td></tr>
 * </table>
 *
 * <p><b>Every one of them is fail-closed, and an id nothing knows is the case that matters.</b> A
 * quest id no catalogue on the server carries reads {@code null}, never {@code 0} - a typo must not
 * read as "they have not done it" and open a bounds-less gate, which is exactly what a {@code 0}
 * would do. The ladder is: the RECORD first (a player who finished it answers even if the content
 * has since been retired), then the CATALOGUE (known, not done: 0), then nothing.
 *
 * <p><b>A factor read never BUILDS the runtime.</b> Reading either engine off
 * {@link ProgressionRuntime} would seal it, and a gate evaluated early - a placement sweep, a
 * content audit - would then seal it before every consumer had registered its parts. So these
 * readings answer {@code null} until the runtime is built, which shuts a gate for a moment rather
 * than moving where a player's data lives for the rest of the boot.
 *
 * <p><b>The reads are narrow by construction.</b> {@link Reads} names six questions, all of them
 * answers about a player, and nothing here can accept a quest, pay one out or touch a store - the
 * same discipline {@code QuestStateReader} exists to keep for a conversation.
 *
 * <p><b>A finished quest means a CLAIMED one, both ways it can be written.</b> A {@code Requires}
 * block's {@code Quests} prerequisite and the {@code ziggfreedcommon:quest_completed} factor are
 * both satisfied only by the stored status {@code COMPLETED}. A quest sitting in
 * {@code COMPLETED_UNCLAIMED} - objectives done, reward not collected, which is where a quest
 * authored {@code AutoClaim: false} lives until the player takes their payout - satisfies NEITHER.
 * That is the stricter of the two readings, chosen so an author writing either spelling gets the
 * same answer about the same player at the same instant.
 */
public final class ProgressionFactors {

    /** Who these registrations are attributed to in the registry ledger. */
    public static final String OWNER = "ziggfreedcommon";

    /**
     * {@code ziggfreedcommon:quest_completed} - 1 when the quest named by Param is finished AND its
     * reward collected (stored status {@code COMPLETED}).
     */
    public static final String QUEST_COMPLETED = "ziggfreedcommon:quest_completed";

    /**
     * {@code ziggfreedcommon:quest_completions} - lifetime COLLECTED completions of the quest named
     * by Param. A parked reward has not been completed yet, the same rule {@link #QUEST_COMPLETED}
     * reads.
     *
     * <p>Only a REPEATABLE keeps a completion record, so a one-shot reads 0 here however many times
     * it was finished and collected; {@link #QUEST_COMPLETED} is what a one-shot is gated on.
     */
    public static final String QUEST_COMPLETIONS = "ziggfreedcommon:quest_completions";

    /** {@code ziggfreedcommon:achievement_earned} - 1 when the achievement named by Param is earned. */
    public static final String ACHIEVEMENT_EARNED = "ziggfreedcommon:achievement_earned";

    /** {@code ziggfreedcommon:achievement_points} - the subject's earned points total; Param ignored. */
    public static final String ACHIEVEMENT_POINTS = "ziggfreedcommon:achievement_points";

    private static final Double YES = 1.0;
    private static final Double NO = 0.0;

    private ProgressionFactors() {
    }

    /**
     * WHO a progression question is about, resolved from the question itself.
     *
     * <p>It is a seam rather than a fixed rule because a subject has to match the STORE that will
     * be asked about it: a subject built by somebody else reads neutral through another mod's store
     * and reports a player who has finished everything as having finished nothing. {@link #RUNTIME}
     * therefore asks the shared runtime's own registered source, which is the only answer that is
     * right on every server.
     */
    public interface Subjects {

        /** The subject the QUEST store understands, or null when the question has no player in it. */
        @Nullable
        Subject questSubject(@Nonnull FactorContext ctx);

        /** The subject the ACHIEVEMENT store understands, or null. Often the same object. */
        @Nullable
        Subject achievementSubject(@Nonnull FactorContext ctx);

        /**
         * The shared runtime's own answer: the live entity on the context becomes the subject the
         * ACTIVE stores speak in. Null before the runtime is built, off a context with no live
         * entity, and for an entity that is not a player.
         */
        Subjects RUNTIME = new Subjects() {

            @Override
            @Nullable
            public Subject questSubject(@Nonnull FactorContext ctx) {
                LivePlayer player = livePlayer(ctx);
                return player == null ? null : ProgressionRuntime.subjects()
                        .questSubject(player.store(), player.ref(), player.playerRef());
            }

            @Override
            @Nullable
            public Subject achievementSubject(@Nonnull FactorContext ctx) {
                LivePlayer player = livePlayer(ctx);
                return player == null ? null : ProgressionRuntime.subjects()
                        .achievementSubject(player.store(), player.ref(), player.playerRef());
            }
        };
    }

    /**
     * The six READS these factors are allowed to make - every one a question about a player, none
     * of them able to change anything.
     *
     * <p>The two {@code known} questions are what keeps a mistyped id from reading as a definite
     * "not done": they ask whether anything on this server carries that id at all, so the providers
     * can answer nothing instead of zero.
     */
    public interface Reads {

        /** Does any catalogued quest carry this id? */
        boolean questKnown(@Nonnull String questId);

        /**
         * Has this subject finished this quest AND collected what it paid - the stored status
         * {@code COMPLETED}, on cooldown or not? A quest whose objectives are done but whose reward
         * is still waiting to be taken answers {@code false}: a prerequisite is met once the quest
         * is behind the player, not the moment its last objective ticks over.
         */
        boolean questFinished(@Nonnull Subject subject, @Nonnull String questId);

        /**
         * How many times this subject has finished it AND collected what it paid, ever, or null when
         * the store cannot remember completions at all (which is a different thing from never having
         * finished it). A quest whose objectives are done but whose reward is still waiting to be
         * taken has not been completed yet by this count, exactly as {@link #questFinished} reads it.
         */
        @Nullable
        Integer questCompletions(@Nonnull Subject subject, @Nonnull String questId);

        /** Does any catalogued achievement carry this id? */
        boolean achievementKnown(@Nonnull String achievementId);

        /** Has this subject earned it, whether or not they have collected what it pays? */
        boolean achievementEarned(@Nonnull Subject subject, @Nonnull String achievementId);

        /** This subject's earned points total across every achievement whose points count. */
        int achievementPoints(@Nonnull Subject subject);

        /**
         * The shared runtime's own answers. Every one of them reports "no" / "nothing" until the
         * runtime is BUILT, so asking never builds it - see the class javadoc for why that matters
         * more than the moment of shut gates it costs.
         */
        Reads RUNTIME = new Reads() {

            @Override
            public boolean questKnown(@Nonnull String questId) {
                return ProgressionRuntime.isBuilt()
                        && ProgressionRuntime.quests().quest(questId) != null;
            }

            @Override
            public boolean questFinished(@Nonnull Subject subject, @Nonnull String questId) {
                if (!ProgressionRuntime.isBuilt()) {
                    return false;
                }
                return ProgressionRuntime.quests().store().status(subject, questId)
                        == QuestStatus.COMPLETED;
            }

            @Override
            @Nullable
            public Integer questCompletions(@Nonnull Subject subject, @Nonnull String questId) {
                if (!ProgressionRuntime.isBuilt()) {
                    return null;
                }
                QuestProgressStore store = ProgressionRuntime.quests().store();
                if (!store.recordsCompletions()) {
                    return null;
                }
                return Integer.valueOf(store.completions(subject, questId).claimedCount());
            }

            @Override
            public boolean achievementKnown(@Nonnull String achievementId) {
                return ProgressionRuntime.isBuilt()
                        && ProgressionRuntime.achievements().achievement(achievementId) != null;
            }

            @Override
            public boolean achievementEarned(@Nonnull Subject subject,
                                             @Nonnull String achievementId) {
                return ProgressionRuntime.isBuilt()
                        && ProgressionRuntime.achievements().isUnlocked(subject, achievementId);
            }

            @Override
            public int achievementPoints(@Nonnull Subject subject) {
                return ProgressionRuntime.isBuilt()
                        ? ProgressionRuntime.achievements().points(subject) : 0;
            }
        };
    }

    // ==================== registration ====================

    /**
     * Claim all four ids process-wide, answered from THE shared runtime. One call from the wiring
     * root's {@code setup()}; from then on every {@link FactorRegistry} on the server resolves them,
     * including registries built before this ran.
     */
    public static void contribute() {
        contribute(Subjects.RUNTIME, Reads.RUNTIME);
    }

    /** {@link #contribute()} over somebody else's subject resolution and reads. */
    public static void contribute(@Nonnull Subjects subjects, @Nonnull Reads reads) {
        FactorContributions.register(QUEST_COMPLETED, OWNER, questCompleted(subjects, reads));
        FactorContributions.register(QUEST_COMPLETIONS, OWNER, questCompletions(subjects, reads));
        FactorContributions.register(ACHIEVEMENT_EARNED, OWNER, achievementEarned(subjects, reads));
        FactorContributions.register(ACHIEVEMENT_POINTS, OWNER, achievementPoints(subjects, reads));
    }

    /**
     * Register all four ids into ONE vocabulary, answered by {@code subjects} + {@code reads}. This
     * is the private-engine form: a consumer's own registration always beats the process-wide claim
     * {@link #contribute()} makes, so the same content reads that consumer's engine inside its own
     * evaluation site and the shared one everywhere else.
     */
    public static void registerInto(@Nonnull FactorRegistry registry, @Nullable String owner,
                                    @Nonnull Subjects subjects, @Nonnull Reads reads) {
        String attributed = owner == null || owner.isBlank() ? OWNER : owner;
        registry.register(QUEST_COMPLETED, attributed, questCompleted(subjects, reads));
        registry.register(QUEST_COMPLETIONS, attributed, questCompletions(subjects, reads));
        registry.register(ACHIEVEMENT_EARNED, attributed, achievementEarned(subjects, reads));
        registry.register(ACHIEVEMENT_POINTS, attributed, achievementPoints(subjects, reads));
    }

    // ==================== providers ====================

    /**
     * {@code 1} when this player has ever finished AND collected the quest named by {@code Param},
     * {@code 0} when they have not, {@code null} when nothing on this server knows that quest id (or
     * there is no player in the question).
     *
     * <p>Finished means the STORED status {@code COMPLETED}, so a repeatable sitting on its cooldown
     * still reads {@code 1} - "have you ever done this" is what a requirement asks - while a quest
     * whose objectives are done and whose reward is still uncollected reads {@code 0}. That is the
     * same answer the {@code Requires} block's {@code Quests} prerequisite gives for the same quest,
     * which is the point: an author who writes the leaf and an author who writes the condition are
     * asking for one thing.
     *
     * <p>The record is consulted BEFORE the catalogue on purpose: a player who finished a quest that
     * has since been retired from the content still finished it, and hiding that behind a catalogue
     * lookup would quietly re-lock everything gated on it.
     */
    @Nonnull
    public static FactorProvider questCompleted(@Nonnull Subjects subjects, @Nonnull Reads reads) {
        return ctx -> {
            String questId = trimmed(ctx.param());
            if (questId == null) {
                return null;
            }
            Subject subject = subjects.questSubject(ctx);
            if (subject == null) {
                return null;
            }
            if (reads.questFinished(subject, questId)) {
                return YES;
            }
            return reads.questKnown(questId) ? NO : null;
        };
    }

    /**
     * How many times this player has COLLECTED the quest named by {@code Param}, ever - the reading a
     * repeatable is gated on ("come back when you have run this board ten times").
     *
     * <p>It counts claims rather than finishes, so a run whose objectives are done but whose reward
     * is still parked has not been done yet under this reading either - which is what stops it
     * disagreeing with {@link #questCompleted(Subjects, Reads)} about a parked reward. The agreement
     * is about that window and nothing more. A ONE-SHOT keeps no completion record at all, so it
     * reads 0 here however many times the flag reads 1; and a repeatable that has come back around
     * reads 0 on the flag, whose status was re-armed, while this count keeps every run it ever paid
     * out. Two different questions, deliberately: has it been done RIGHT NOW, and how many times
     * ever.
     *
     * <p>Null when the runtime's store cannot remember completions at all: a store with no record to
     * keep would report every player as zero, and a "fewer than N" bound would then pass for
     * somebody who had done it a hundred times. Cannot tell is not the same as none, so it says so.
     */
    @Nonnull
    public static FactorProvider questCompletions(@Nonnull Subjects subjects, @Nonnull Reads reads) {
        return ctx -> {
            String questId = trimmed(ctx.param());
            if (questId == null) {
                return null;
            }
            Subject subject = subjects.questSubject(ctx);
            if (subject == null) {
                return null;
            }
            Integer count = reads.questCompletions(subject, questId);
            if (count == null) {
                return null;
            }
            if (count.intValue() > 0) {
                return Double.valueOf(count.intValue());
            }
            return reads.questKnown(questId) ? NO : null;
        };
    }

    /**
     * {@code 1} when this player has earned the achievement named by {@code Param} - collected or
     * not, since earning it is the thing that happened - {@code 0} when they have not, {@code null}
     * when no catalogue carries that id or there is no player in the question.
     */
    @Nonnull
    public static FactorProvider achievementEarned(@Nonnull Subjects subjects,
                                                   @Nonnull Reads reads) {
        return ctx -> {
            String achievementId = trimmed(ctx.param());
            if (achievementId == null) {
                return null;
            }
            Subject subject = subjects.achievementSubject(ctx);
            if (subject == null) {
                return null;
            }
            if (reads.achievementEarned(subject, achievementId)) {
                return YES;
            }
            return reads.achievementKnown(achievementId) ? NO : null;
        };
    }

    /**
     * This player's earned achievement POINTS, the reading a points milestone is written against
     * ({@code {"Factor": "ziggfreedcommon:achievement_points", "Min": 50}}).
     *
     * <p><b>{@code Param} is ignored</b> - a points total is one number for the player, with nothing
     * inside it to address. {@code 0} is a real answer here (a player who has earned nothing), which
     * is why a gate wanting "any points at all" has to write {@code Min: 1} rather than relying on
     * the bounds-less form.
     */
    @Nonnull
    public static FactorProvider achievementPoints(@Nonnull Subjects subjects,
                                                   @Nonnull Reads reads) {
        return ctx -> {
            Subject subject = subjects.achievementSubject(ctx);
            return subject == null ? null : Double.valueOf(reads.achievementPoints(subject));
        };
    }

    // ==================== helpers ====================

    /** The three live handles a runtime subject is built from, carried together so none can go missing. */
    private record LivePlayer(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                              @Nonnull PlayerRef playerRef) {
    }

    /**
     * The player behind the question, or null when the context has no live entity, the runtime is
     * not built yet, or the entity is not a player. Read on the world thread and never retained,
     * like every other engine read a provider makes.
     */
    @Nullable
    private static LivePlayer livePlayer(@Nonnull FactorContext ctx) {
        Store<EntityStore> store = ctx.store();
        Ref<EntityStore> ref = ctx.subject();
        if (!ProgressionRuntime.isBuilt() || !ctx.hasLiveSubject() || store == null || ref == null) {
            return null;
        }
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        return playerRef == null ? null : new LivePlayer(store, ref, playerRef);
    }

    @Nullable
    private static String trimmed(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
