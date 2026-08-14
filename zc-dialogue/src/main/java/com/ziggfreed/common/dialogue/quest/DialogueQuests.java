package com.ziggfreed.common.dialogue.quest;

import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.dialogue.DialogueContext;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.quest.QuestStateReader;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * What a conversation is allowed to know and do about quests: the narrow read seam, who the player
 * is, which ids the character in front of them answers to, the conversation a quest hands off to
 * once it settles, and the two things a line may actually change.
 *
 * <p><b>Reading goes through {@link QuestStateReader} and nothing else.</b> That interface is
 * deliberately the smallest surface that can answer "what does this player's quest look like right
 * now", with no accept, no hand-in and no catalogue on it - so a condition that only renders a line
 * cannot quietly start a quest. The two things a conversation genuinely DOES - taking a quest on and
 * handing one in - are separate methods here, and both refuse by default: a mod that wires only the
 * reader gets quest-aware lines with no way to change anything.
 *
 * <p><b>Answer sets are the alias seam.</b> One character can stand for several quest-giver ids (the
 * same guide met at two places, a shared hub identity), and only the consumer knows how its ids fold
 * together. {@link #answersTo} is asked once per evaluation and every id it returns is tried, so a
 * hand-in beat works wherever the quest says to report back. The default is the literal id, which is
 * the right answer for anyone without an alias scheme.
 *
 * <p>Wire one with {@code DialogueEngine.Builder#quests}. Leaving it unset leaves {@link #NONE} in
 * place: every quest-aware line then reads NOT_STARTED and every gate refuses, so a dialogue written
 * against a quest system this server does not run hides those beats instead of promising them.
 */
public interface DialogueQuests {

    /** The quest state this conversation reads. */
    @Nonnull
    QuestStateReader reader();

    /**
     * Who the conversation is about. The default builds one from the talking player, which is what
     * every consumer wants unless it attaches a handle the quest runtime needs (an inventory, a
     * session) - in which case override this and put it on the subject.
     */
    @Nonnull
    default Subject subject(@Nonnull DialogueContext ctx) {
        try {
            return subjectOf(ctx.store(), ctx.ref(), ctx.playerRef(), ctx.player());
        } catch (Throwable t) {
            // A context built without engine handles - a preview render, a test double - can still
            // say who the player is, which is enough for every pure state read.
            return new Subject(ctx.playerRef().getUuid(), ctx.playerRef().getUsername(), ctx.player());
        }
    }

    /**
     * The same player, built from engine handles alone rather than from a conversation.
     *
     * <p>Not every at-NPC surface is a conversation: a UI panel showing what a character offers, a
     * fourth party's page, a hand-in button. Those have the player and the world but no dialogue
     * context, so a consumer that attaches a handle to its subject has to be reachable without one -
     * which is what this is. <b>Override THIS when your quest runtime needs more than a plain
     * player handle</b>, and let {@link #subject(DialogueContext)} keep enriching it from whatever
     * the conversation already fetched; overriding only the context form leaves every non-dialogue
     * surface with a bare subject your runtime may not accept.
     */
    @Nonnull
    default Subject subjectOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                              @Nonnull PlayerRef playerRef, @Nonnull Player player) {
        return new Subject(playerRef.getUuid(), playerRef.getUsername(), player);
    }

    /**
     * Every quest-giver id the character being talked to answers to, most specific first. The
     * default is the id itself; a consumer with aliases returns the whole family.
     */
    @Nonnull
    default Collection<String> answersTo(@Nullable String contextId) {
        return contextId == null || contextId.isBlank() ? List.of() : List.of(contextId);
    }

    /**
     * Take this quest on for the player, AT the character they are talking to. Return true only when
     * it actually started, so a line that pairs an accept with a jump can tell the difference between
     * "started" and "was refused". Refuses by default.
     *
     * <p><b>{@code siteId} is where it was taken on</b>, which is not bookkeeping: a quest whose
     * hand-in is "report back to whoever gave me this" can only resolve that from the place it
     * started, so a conversation passes the character it is with and a surface with no character
     * passes null. It is the conversation's own context id rather than an answered alias, because the
     * character the player is looking at is the one the story means.
     */
    default boolean accept(@Nonnull Subject subject, @Nonnull String questId, @Nullable String siteId) {
        return false;
    }

    /**
     * Hand this quest in at {@code atId} (already resolved through {@link #answersTo}). Return true
     * when the hand-in went through. Refuses by default.
     */
    default boolean turnIn(@Nonnull Subject subject, @Nonnull String questId, @Nullable String atId) {
        return false;
    }

    /**
     * The conversation this quest hands off to when it settles, or null when it names none.
     *
     * <p>WHICH conversation is authored data the consumer's catalogue already carries, so only the
     * consumer can read it. WHEN it plays is not a consumer decision at all and lives in
     * {@link QuestCompletionRouting}, which is what keeps a quest log, a book and a giver's own panel
     * from each improvising a different answer.
     *
     * <p>Returns null by default, so a mod that wires only the reader gets quest-aware lines and no
     * hand-off.
     */
    @Nullable
    default String completionDialogueOf(@Nonnull String questId) {
        return null;
    }

    /**
     * The stand-in for "no quest system here": every read is the empty answer and every write
     * refuses, so quest-aware content stays hidden rather than half-working.
     */
    DialogueQuests NONE = new DialogueQuests() {

        private final QuestStateReader empty = new QuestStateReader() {

            @Nonnull
            @Override
            public QuestStatus status(@Nonnull Subject subject, @Nonnull String questId) {
                return QuestStatus.NOT_STARTED;
            }

            @Nullable
            @Override
            public ObjectiveProgressState objectiveProgress(@Nonnull Subject subject,
                                                            @Nonnull String questId,
                                                            @Nonnull String objectiveId) {
                return null;
            }

            @Nonnull
            @Override
            public List<String> activeAndUnclaimedIds(@Nonnull Subject subject) {
                return List.of();
            }

            @Override
            public boolean canDeliverTurnInAt(@Nonnull Subject subject, @Nonnull String questId,
                                              @Nullable String atId) {
                return false;
            }

            /**
             * The one read whose interface default is permissive - it is a REFUSAL gate, and "no site
             * rule to break" is genuinely yes. A stand-in for "there is no quest system here" must
             * still answer NO: everything else about a quest reads as nothing, so a site check that
             * alone said yes would offer a hand-in for a quest this reader also says was never
             * started. Spelled out rather than inherited, because inheriting it is the bug.
             */
            @Override
            public boolean canCompleteAt(@Nonnull Subject subject, @Nonnull String questId,
                                         @Nullable String atId) {
                return false;
            }

            @Override
            public boolean hasDeliverableTurnInAt(@Nonnull Subject subject, @Nullable String atId) {
                return false;
            }
        };

        @Nonnull
        @Override
        public QuestStateReader reader() {
            return empty;
        }
    };
}
