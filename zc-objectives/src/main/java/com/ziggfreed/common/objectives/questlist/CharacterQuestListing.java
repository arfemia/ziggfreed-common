package com.ziggfreed.common.objectives.questlist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.objectives.questlist.NpcQuestSections.Section;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.quest.NpcOffer;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * What ONE character is to ONE player's quests, read off the engine: which quests belong on the
 * character's list, which section each sits in, and the place-aware facts behind both. The page a
 * player reads at a character and the indicator floating over that character's head both read
 * this, so the two can never disagree about what a character has for a player.
 *
 * <p>Built per read from three things: the engine, the player's subject, and every id the
 * character answers to (its own plus its aliases). It holds no state beyond those three and is
 * cheap to construct, so a caller makes one per question rather than caching it.
 *
 * <h2>The HERE list is four questions, asked of two authorities</h2>
 *
 * <p>Which quests a character HANDS OUT is an authoring-layer association the runtime cannot
 * read, which is exactly what the offer table exists to answer. The other three are pure quest
 * state, so the engine answers them itself over the whole answer set: which quests point BACK
 * here, which were TAKEN here - the accept site the engine records on every accept, which is
 * what makes "given here" engine data rather than something a consumer has to register - and
 * which are FINISHED and may be COLLECTED here, so a quest credited by a beat at a character
 * that neither gave it nor is named as its hand-in still reaches the list it is collected from.
 * How the four combine is {@link NpcQuestSections#belongsHere}, pure and asserted.
 */
public final class CharacterQuestListing {

    /** One outstanding hand-in, and the id this character answered under when it was found. */
    public record TurnIn(@Nonnull ObjectiveDef step, @Nullable String atId) {
    }

    @Nonnull private final QuestEngine engine;
    @Nonnull private final Subject subject;
    @Nonnull private final Set<String> answersTo;

    public CharacterQuestListing(@Nonnull QuestEngine engine, @Nonnull Subject subject,
            @Nonnull Set<String> answersTo) {
        this.engine = engine;
        this.subject = subject;
        this.answersTo = Set.copyOf(answersTo);
    }

    /** Every id the character answers to, as this listing was built with. */
    @Nonnull
    public Set<String> answersTo() {
        return answersTo;
    }

    /**
     * What belongs on this character's list: what it hands out, plus anything the player is
     * carrying or has finished but not collected whose business is here.
     */
    @Nonnull
    public List<Quest> questsHere() {
        Map<String, Quest> out = new LinkedHashMap<>();
        if (!answersTo.isEmpty()) {
            for (NpcOffer offer : NpcOfferProviders.offersAt(subject, answersTo)) {
                Quest quest = engine.quest(offer.id());
                if (quest != null) {
                    out.putIfAbsent(quest.id(), quest);
                }
            }
        }
        // Carried and finished-but-uncollected alike: a quest parked for collection at the character
        // it was taken from has to be reachable here, or nobody could ever collect it.
        for (Quest quest : engine.activeAndUnclaimed(subject)) {
            if (out.containsKey(quest.id())) {
                continue;
            }
            if (NpcQuestSections.belongsHere(engine.status(subject, quest), readyHere(quest), takenHere(quest),
                    collectionSite(quest) != null)) {
                out.put(quest.id(), quest);
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Is this character where the quest's outstanding step resolves, under any id it answers to? */
    public boolean readyHere(@Nonnull Quest quest) {
        for (String id : answersTo) {
            if (engine.readyToTurnInAt(subject, quest, id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Would handing over what the player is carrying, to THIS character, finish the quest? Asked of
     * every id the character answers to, because a quest bound to one of them is this character's
     * errand however they were addressed.
     */
    public boolean settlesHere(@Nonnull Quest quest) {
        for (String id : answersTo) {
            if (engine.settlesTurnInAt(subject, quest, id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Did the player TAKE this quest here? Read straight off the accept site the engine recorded, so
     * a quest a character handed out is still that character's business while it is being carried,
     * whatever the offer table currently offers. Compared case-insensitively, matching how the
     * engine compares the same id everywhere else.
     */
    public boolean takenHere(@Nonnull Quest quest) {
        String site = engine.acceptSiteOf(subject, quest.id());
        if (site == null || site.isBlank()) {
            return false;
        }
        for (String id : answersTo) {
            if (site.equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first outstanding hand-in step that can be handed in AT this character, WITH the id it
     * answered under - the id the hand-in itself must then be performed at, since a quest collected
     * at its own site pays out there and then while the same hand-in from nowhere parks it.
     *
     * <p>With nobody in front of the player (an empty answer set), the search is the "somewhere
     * unlocked" form and the id is null, which is the same thing the objective book asks.
     */
    @Nullable
    public TurnIn turnInHere(@Nonnull Quest quest) {
        if (answersTo.isEmpty()) {
            ObjectiveDef anywhere = engine.firstActiveTurnIn(subject, quest, null);
            return anywhere == null ? null : new TurnIn(anywhere, null);
        }
        for (String id : answersTo) {
            ObjectiveDef step = engine.firstActiveTurnIn(subject, quest, id);
            if (step != null) {
                return new TurnIn(step, id);
            }
        }
        return null;
    }

    /**
     * Whether a FINISHED quest may be collected at this character, asked once per id it answers to.
     *
     * <p>The engine compares ONE id, deliberately, so a character answering to several is this
     * loop - which is what keeps an identity registry out of the progression module. A quest
     * naming no collection site passes on the first ask, which is the great majority of content.
     */
    public boolean canCollectHere(@Nonnull Quest quest) {
        if (answersTo.isEmpty()) {
            return engine.canCompleteAt(subject, quest, null);
        }
        return collectionSite(quest) != null;
    }

    /**
     * The id, out of this character's answer set, this quest may be collected under - the one the
     * claim itself must be made at. Null when none of them answers, and null when there is nobody in
     * front of the player at all, which is exactly what a claim from nowhere passes.
     */
    @Nullable
    public String collectionSite(@Nonnull Quest quest) {
        for (String id : answersTo) {
            if (engine.canCompleteAt(subject, quest, id)) {
                return id;
            }
        }
        return null;
    }

    /** Which section {@code quest} sits in on this character's list. */
    @Nonnull
    public Section sectionOf(@Nonnull Quest quest) {
        QuestStatus status = engine.status(subject, quest);
        boolean acceptable = status == QuestStatus.NOT_STARTED && engine.canAccept(subject, quest).allowed();
        return NpcQuestSections.classify(status, acceptable, settlesHere(quest), canCollectHere(quest));
    }

    /**
     * The step a carried quest is ON: the first objective of its current step that is not yet
     * done, or null when nothing is open. This is the step whose own word narrows an in-progress
     * indicator.
     */
    @Nullable
    public ObjectiveDef outstandingStep(@Nonnull Quest quest) {
        Map<String, ObjectiveProgressState> progress = engine.progressOf(subject, quest.id());
        for (ObjectiveDef objective : engine.activeStepObjectives(subject, quest)) {
            ObjectiveProgressState state = progress.get(objective.id());
            if (state == null || !state.isCompleted()) {
                return objective;
            }
        }
        return null;
    }
}
