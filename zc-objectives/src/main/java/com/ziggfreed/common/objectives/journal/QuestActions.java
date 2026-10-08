package com.ziggfreed.common.objectives.journal;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.book.BookVerbs;
import com.ziggfreed.common.objectives.questlist.CharacterQuestListing;
import com.ziggfreed.common.objectives.questlist.NpcQuestSections;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;

/**
 * A quest page's action bar, read off the quest's live state: Primary is Accept, Hand in or a gold Collect; Danger
 * is Abandon; Secondary is Track or Untrack on a carried quest when a page wants it in the bar (the book and the NPC
 * page show it as the header toggle instead). The three buttons are bound once, with no quest in the binding, and a
 * press is {@link #dispatch}ed on what the quest is when it lands, so a page repainted by a partial never re-binds.
 *
 * <p><b>In the book</b> ({@code here} null): Accept only for a quest the log may take now that is taken neither at its
 * giver ({@link BookVerbs#takenAtGiver}; a quest that arms itself never is) nor at a board; Hand in when a step can be
 * handed in somewhere unlocked ({@code firstActiveTurnIn(subject, quest, null)}; a hand-in locked to a character
 * never completes from the book); Collect for a finished quest unless it is collected only at its site; Abandon on
 * a carried quest, a board's too.
 *
 * <p><b>At a character</b> ({@code here} non-null, the NPC quest page), the place decides: Accept for anything
 * Available there (a giver-bound quest included, since this is where it is taken); Hand in when a step resolves
 * there ({@link CharacterQuestListing#turnInHere}), read as finishing the step when it delivers nothing; Collect only
 * where the quest may be collected (nothing for one parked for another character); Abandon on a carried quest.
 *
 * <p>Both places ask one rule for Collect ({@link #collectableHere}), the same one the reader's Elsewhere state word
 * asks, so a row never reads Collect where its page has no Collect button.
 */
public final class QuestActions {

    /** The verbs a press dispatches to; the same ids the book's bindings carry ({@code BookActions}). */
    public static final String ACCEPT = "accept";
    public static final String HAND_IN = "turn_in";
    public static final String COLLECT = "collect";
    public static final String ABANDON = "abandon";
    public static final String TRACK = "toggletrack";

    private QuestActions() {
    }

    /** The book's action bar for {@code q}. */
    @Nonnull
    public static List<DetailAction> of(@Nonnull Quest q, @Nonnull QuestReader r) {
        return of(q, r, null);
    }

    /** The action bar for {@code q}; with {@code here}, at that character. */
    @Nonnull
    public static List<DetailAction> of(@Nonnull Quest q, @Nonnull QuestReader r,
            @Nullable CharacterQuestListing here) {
        return of(q, r, here, false);
    }

    /** {@link #of(Quest, QuestReader, CharacterQuestListing)}, with Track or Untrack in Secondary when asked. */
    @Nonnull
    public static List<DetailAction> of(@Nonnull Quest q, @Nonnull QuestReader r,
            @Nullable CharacterQuestListing here, boolean trackInBar) {
        List<DetailAction> actions = new ArrayList<>();
        DetailAction primary = primary(q, r, here);
        if (primary != null) {
            actions.add(primary);
        }
        QuestStatus status = r.status(q);
        if (status == QuestStatus.ACTIVE) {
            if (trackInBar) {
                boolean on = r.tracked(q);
                actions.add(action(ActionSlot.SECONDARY, QuestReader.text(on ? "action.untrack" : "action.track"),
                        ActionLook.NORMAL, TRACK));
            }
            actions.add(action(ActionSlot.DANGER, QuestReader.text("action.abandon"), ActionLook.DANGER, ABANDON));
        }
        return actions;
    }

    /** The verb a press of {@code slot} means for the book, on {@code live}'s state now; null for none. */
    @Nullable
    public static String dispatch(@Nonnull ActionSlot slot, @Nonnull Quest live, @Nonnull QuestReader r) {
        return dispatch(slot, live, r, null);
    }

    /**
     * The verb a press of {@code slot} means on {@code live}'s state now; with {@code here}, at that character. Null
     * when the slot holds nothing for the quest as it now is (a stale press after the state moved on). Secondary
     * answers {@link #TRACK} on a carried quest whether or not a page shows it in the bar.
     */
    @Nullable
    public static String dispatch(@Nonnull ActionSlot slot, @Nonnull Quest live, @Nonnull QuestReader r,
            @Nullable CharacterQuestListing here) {
        for (DetailAction action : of(live, r, here, true)) {
            if (action.slot() == slot) {
                return action.actionId();
            }
        }
        return null;
    }

    /** Accept, Hand in or Collect, by state and place; null when there is nothing to press. */
    @Nullable
    private static DetailAction primary(@Nonnull Quest q, @Nonnull QuestReader r,
            @Nullable CharacterQuestListing here) {
        boolean managed = r.presentation().managed(q);
        return switch (r.status(q)) {
            case NOT_STARTED -> {
                boolean offered = here != null
                        ? here.sectionOf(q) == NpcQuestSections.Section.AVAILABLE
                        : r.acceptable(q) && !BookVerbs.takenAtGiver(q);
                yield offered && !managed
                        ? action(ActionSlot.PRIMARY, QuestReader.text("action.accept"), ActionLook.NORMAL, ACCEPT)
                        : null;
            }
            case ACTIVE -> {
                if (managed) {
                    yield null;
                }
                if (here != null) {
                    CharacterQuestListing.TurnIn turnIn = here.turnInHere(q);
                    if (turnIn == null) {
                        yield null;
                    }
                    String target = turnIn.step().target();
                    // A report-back delivers nothing, so it reads as finishing the step.
                    Message label = target == null || target.isEmpty()
                            ? Msg.tr(QuestReader.PREFIX, "progression.npcquests.action.complete")
                            : QuestReader.text("action.hand_in");
                    yield action(ActionSlot.PRIMARY, label, ActionLook.NORMAL, HAND_IN);
                }
                yield r.engine().firstActiveTurnIn(r.subject(), q, null) != null
                        ? action(ActionSlot.PRIMARY, QuestReader.text("action.hand_in"), ActionLook.NORMAL, HAND_IN)
                        : null;
            }
            case COMPLETED_UNCLAIMED -> collectableHere(q, here)
                    ? action(ActionSlot.PRIMARY, QuestReader.text("action.collect"), ActionLook.COLLECT, COLLECT)
                    : null;
            case ON_COOLDOWN, COMPLETED -> null;
        };
    }

    /**
     * Whether a finished quest can be collected where the player stands: at {@code here}, under an id the character
     * answers to ({@link CharacterQuestListing#collectionSite}); in the book, only when it names no site, since the
     * engine refuses a site-bound payout from nowhere. Where this is false the row reads Elsewhere
     * ({@link QuestReader#collectsElsewhere}) and there is no Collect.
     */
    public static boolean collectableHere(@Nonnull Quest q, @Nullable CharacterQuestListing here) {
        return here != null ? here.collectionSite(q) != null : q.turnInAt() == null;
    }

    @Nonnull
    private static DetailAction action(@Nonnull ActionSlot slot, @Nonnull Message label, @Nonnull ActionLook look,
            @Nonnull String verb) {
        return new DetailAction(slot, label, look, verb, null, true, null);
    }
}
