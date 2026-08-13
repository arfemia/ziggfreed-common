package com.ziggfreed.common.quest;

import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.subject.Subject;

/**
 * The consumer's say in whether a player may take, see, or be paid for a quest. The engine owns the
 * mechanical rules it can check by itself (is it available, is it already running, is the log full,
 * is the cooldown up) and asks this for everything that depends on the consumer's own systems.
 *
 * <p>Every method has a permissive default, so a consumer implements only what it actually gates and
 * {@link #OPEN} is a working no-gates implementation.
 *
 * <p><b>Reasons are opaque tokens</b>, not sentences. The engine emits the constants below for its
 * own refusals and appends whatever a gate adds; turning a token into text a player reads is the
 * consumer's job, which is what keeps this engine free of any display language.
 */
public interface QuestGates {

    /** The quest is switched off. */
    String REASON_UNAVAILABLE = "unavailable";

    /** The quest is already running, finished, or waiting to be claimed. */
    String REASON_ALREADY_STARTED = "already_started";

    /** A finished repeatable whose rolling cooldown has not elapsed. */
    String REASON_ON_COOLDOWN = "on_cooldown";

    /** A repeatable already finished as often as its calendar window allows; it returns next window. */
    String REASON_PERIOD_SPENT = "period_spent";

    /** A repeatable finished as often as it ever can be; nothing brings it back. */
    String REASON_MAX_COMPLETIONS = "max_completions";

    /** The player is already carrying as many quests as the engine allows. */
    String REASON_LOG_FULL = "log_full";

    /** The consumer's prerequisite check said no. */
    String REASON_PREREQUISITES = "prerequisites";

    /** No gates at all: everything passes, nothing is pre-satisfied. */
    QuestGates OPEN = new QuestGates() {
    };

    /**
     * May this player accept this quest? Append a token per refusal to {@code reasons}; the engine
     * keeps whatever is added alongside its own. Returning false with an empty list is allowed but
     * leaves the caller nothing to explain.
     */
    default boolean accepts(@Nonnull Subject subject, @Nonnull Quest quest,
                            @Nonnull List<String> reasons) {
        return true;
    }

    /**
     * Has this player earned the right to SEE a quest whose
     * {@link Quest.Visibility#requirePrerequisites()} is set? Also consulted on accept, so a quest
     * can never be taken through a back door it is not yet visible through.
     */
    default boolean prerequisitesMet(@Nonnull Subject subject, @Nonnull Quest quest) {
        return true;
    }

    /**
     * Can this player physically receive the quest's rewards right now (typically: is there room)?
     * Answering false makes a finished quest park for manual claim instead of paying out into
     * nowhere, and the player can collect once they have made space.
     */
    default boolean canReceiveRewards(@Nonnull Subject subject, @Nonnull Quest quest) {
        return true;
    }

    /**
     * Progress this player ALREADY has toward an objective at the instant they accept, applied as a
     * high-water value so an objective they have plainly satisfied does not ask them to do it again.
     * Return {@code 0} (the default) for "start from nothing", which is right for anything counted
     * by doing it. Only objectives measuring a standing value have a sensible answer here.
     */
    default long preSatisfiedAmount(@Nonnull Subject subject, @Nonnull Quest quest,
                                    @Nonnull ObjectiveDef objective) {
        return 0L;
    }
}
