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

    /**
     * The whole quest system is switched off for this player, per the owner's registered system
     * gate. The engine's own refusal, so an accept asked of a switched-off system is told no with a
     * reason rather than quietly taken - and it stands alone: no other reason is gathered beside
     * it, since a prerequisite the player could go and meet is no route into a system that is off.
     */
    String REASON_SYSTEM_DISABLED = "system_disabled";

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
     * Both accept-time questions at once: is the player past what the quest asks for first, and does
     * the gate let them take it? The engine asks this ONE method when somebody is trying to accept,
     * and the default answers it exactly as asking the two separately would.
     *
     * <p><b>Override it when a gate answers both from the same reading.</b> A gate evaluating an
     * authored requirement block answers both questions off one pass, so asking it twice per accept
     * doubles the work for one decision, and accept is asked per quest on listing and render paths.
     * Overriding lets it read once and still add every token it would have added.
     *
     * <p>An override owes the same tokens: {@link #REASON_PREREQUISITES} when a quest that requires
     * them is not past them, plus whatever {@link #accepts} would have added, and it must not add a
     * token twice.
     */
    default boolean opensFor(@Nonnull Subject subject, @Nonnull Quest quest,
                             @Nonnull List<String> reasons) {
        boolean open = true;
        if (quest.visibility().requirePrerequisites() && !prerequisitesMet(subject, quest)) {
            if (!reasons.contains(REASON_PREREQUISITES)) {
                reasons.add(REASON_PREREQUISITES);
            }
            open = false;
        }
        if (!accepts(subject, quest, reasons)) {
            open = false;
        }
        return open;
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
