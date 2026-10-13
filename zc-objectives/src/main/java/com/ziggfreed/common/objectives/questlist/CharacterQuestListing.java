package com.ziggfreed.common.objectives.questlist;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.board.BoardEngine;
import com.ziggfreed.common.board.BountyRef;
import com.ziggfreed.common.commerce.fold.BoardAssetSpec;
import com.ziggfreed.common.commerce.fold.CommerceCatalogs;
import com.ziggfreed.common.commerce.fold.CommerceEngines;
import com.ziggfreed.common.commerce.fold.CommerceFold;
import com.ziggfreed.common.objectives.questlist.NpcQuestSections.Section;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.gate.GateRefusal;
import com.ziggfreed.common.quest.NpcOffer;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.rotation.PoolSlot;
import com.ziggfreed.common.rotation.WeightedSlotDraw;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.WhereValidator.LoadedWorld;

/**
 * What ONE character is to ONE player's quests, read off the engine: which quests belong on the
 * character's list, which section each sits in, and the place-aware facts behind both. The page a
 * player reads at a character and the indicator floating over that character's head both read
 * this, so the two can never disagree about what a character has for a player.
 *
 * <p>Built per read from the engine, the player's subject, every id the character answers to (its own
 * plus its aliases, plus every board that names it in {@code Hosts}, {@link HostedBoards}) and, where a
 * player is looking, the world they stand in. It holds no state beyond those and the postings it reads
 * once, and is cheap to construct, so a caller makes one per question rather than caching it.
 *
 * <h2>The HERE list is five questions, asked of three authorities</h2>
 *
 * <p>Which quests a character HANDS OUT is an authoring-layer association the runtime cannot
 * read, which is exactly what the offer table exists to answer. What a board the character hosts has
 * POSTED is the board engine's: each answer-set id that is a board open where the player stands lists
 * its current postings ({@link #postingOf}), a contract two of its boards post listing once, under the
 * first board by {@code Order}. The other three are pure quest state, so the engine answers them
 * itself over the whole answer set: which quests point BACK here, which were TAKEN here - the accept
 * site the engine records on every accept, which is what makes "given here" engine data rather than
 * something a consumer has to register - and which are FINISHED and may be COLLECTED here, so a quest
 * credited by a beat at a character that neither gave it nor is named as its hand-in still reaches the
 * list it is collected from. How the last four combine is {@link NpcQuestSections#belongsHere}, pure and
 * asserted.
 *
 * <h2>A posting reads by its board's rules</h2>
 *
 * <p>A posting not being carried sits where the BOARD puts it, never the quest engine: Comes back while
 * the player has done it this rotation (with the board's own clock, {@link #millisUntilNewWork}),
 * Available when the board's slot-aware accept check passes, Locked when it does not
 * ({@link #acceptCheck}, {@link #gateRefusals}). Once taken, it reads as any carried quest does.
 */
public final class CharacterQuestListing {

    /** One outstanding hand-in, and the id this character answered under when it was found. */
    public record TurnIn(@Nonnull ObjectiveDef step, @Nullable String atId) {
    }

    /**
     * One contract a board this character hosts has posted for the player right now: the board, the contract as
     * the board engine sees it, and the slot that posted it (its own gate applies to the accept), null on a board
     * that draws without slots.
     */
    public record Posting(@Nonnull BoardAssetSpec board, @Nonnull BountyRef contract, @Nullable PoolSlot slot) {
    }

    @Nonnull private final QuestEngine engine;
    @Nonnull private final Subject subject;
    @Nonnull private final Set<String> answersTo;
    @Nullable private final LoadedWorld viewer;
    private final long nowMs;

    /** The postings, read once on first ask: contract id lower-cased to its posting. */
    @Nullable private Map<String, Posting> postings;

    public CharacterQuestListing(@Nonnull QuestEngine engine, @Nonnull Subject subject,
            @Nonnull Set<String> answersTo) {
        this(engine, subject, answersTo, null);
    }

    /**
     * A listing for a player standing in {@code viewer}'s world: a hosted board lists its postings only where it
     * is open to them ({@code Where} included). With no viewer, a board lists wherever it is open on this server.
     */
    public CharacterQuestListing(@Nonnull QuestEngine engine, @Nonnull Subject subject,
            @Nonnull Set<String> answersTo, @Nullable LoadedWorld viewer) {
        this.engine = engine;
        this.subject = subject;
        this.answersTo = Set.copyOf(answersTo);
        this.viewer = viewer;
        this.nowMs = System.currentTimeMillis();
    }

    /** Every id the character answers to, as this listing was built with. */
    @Nonnull
    public Set<String> answersTo() {
        return answersTo;
    }

    /**
     * The clock reading this listing was built at, which every posting answer uses: an accept made from this listing
     * passes it on, so it takes a contract against the same period the posting was drawn in.
     */
    public long nowMs() {
        return nowMs;
    }

    /**
     * What belongs on this character's list: what it hands out, what its boards have posted, plus anything the
     * player is carrying or has finished but not collected whose business is here.
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
        for (Posting posting : postings().values()) {
            Quest quest = engine.quest(posting.contract().bountyId());
            if (quest != null) {
                out.putIfAbsent(quest.id(), quest);
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

    // ==================== postings ====================

    /**
     * The posting {@code questId} is on this character's list as, or null when no board the character hosts has
     * it posted for the player right now.
     */
    @Nullable
    public Posting postingOf(@Nullable String questId) {
        return questId == null ? null : postings().get(questId.trim().toLowerCase(Locale.ROOT));
    }

    /** The boards among this character's ids that are open where the player stands, in the order boards list. */
    @Nonnull
    public List<BoardAssetSpec> boards() {
        return HostedBoards.openAmong(answersTo, viewer);
    }

    /** Whether the board's accept would take {@code posting} for the player now, and what refuses it if not. */
    @Nonnull
    public BoardEngine.BoardCheck acceptCheck(@Nonnull Posting posting) {
        return CommerceEngines.boards().canAccept(subject, posting.board(), posting.contract(), posting.slot(),
                nowMs);
    }

    /** Every unmet gate between the player and taking {@code posting}: the board's, the band's, its own, the slot's. */
    @Nonnull
    public List<GateRefusal> gateRefusals(@Nonnull Posting posting) {
        return CommerceEngines.boards().acceptGateRefusals(subject, posting.board(), posting.contract(),
                posting.slot());
    }

    /** Whether the player has done {@code posting} already this rotation of its board. */
    public boolean doneThisRotation(@Nonnull Posting posting) {
        return CommerceEngines.boards().completedThisPeriod(subject, posting.board(),
                posting.contract().bountyId(), nowMs);
    }

    /**
     * Whether {@code posting}'s board ever turns over: one with no {@code Rotation} keeps the same postings for good,
     * so a contract done on it has no clock to wait on (it still reads under Comes back).
     */
    public boolean postsNewWork(@Nonnull Posting posting) {
        return posting.board().rotation().periodLengthMs() != CommerceFold.NEVER.periodLengthMs();
    }

    /** How long until {@code posting}'s board turns over and posts new work; ask {@link #postsNewWork} first. */
    public long millisUntilNewWork(@Nonnull Posting posting) {
        return posting.board().rotation().millisUntilNext(nowMs);
    }

    @Nonnull
    private Map<String, Posting> postings() {
        Map<String, Posting> read = postings;
        if (read != null) {
            return read;
        }
        Map<String, Posting> out = new LinkedHashMap<>();
        if (!answersTo.isEmpty()) {
            try {
                out = readPostings(boards());
            } catch (Throwable t) {
                SafeLog.warn("[questlist] a character's boards could not be read, so their postings are left off", t);
            }
        }
        postings = out;
        return out;
    }

    /** Each board's postings for the player now (its shared draw with their rerolls laid over it), deduped. */
    @Nonnull
    private Map<String, Posting> readPostings(@Nonnull List<BoardAssetSpec> boards) {
        if (boards.isEmpty()) {
            return new LinkedHashMap<>();
        }
        BoardEngine board = CommerceEngines.boards();
        Collection<BountyRef> pool = CommerceCatalogs.boards().pool();
        Map<BoardAssetSpec, List<Posting>> byBoard = new LinkedHashMap<>();
        for (BoardAssetSpec spec : boards) {
            List<BountyRef> shown = board.activeSetFor(subject, spec, pool, nowMs);
            WeightedSlotDraw.DrawResult<BountyRef> draw = board.drawFor(spec, pool, nowMs);
            List<Posting> posted = new ArrayList<>(shown.size());
            for (int position = 0; position < shown.size(); position++) {
                posted.add(new Posting(spec, shown.get(position),
                        position < draw.size() ? draw.slotAt(position) : null));
            }
            byBoard.put(spec, posted);
        }
        Map<String, BoardAssetSpec> first = HostedBoards.firstPoster(boards,
                spec -> contractIds(byBoard.get(spec)));
        Map<String, Posting> out = new LinkedHashMap<>();
        for (Map.Entry<String, BoardAssetSpec> entry : first.entrySet()) {
            for (Posting posting : byBoard.get(entry.getValue())) {
                if (posting.contract().bountyId().equalsIgnoreCase(entry.getKey())) {
                    out.put(entry.getKey(), posting);
                    break;
                }
            }
        }
        return out;
    }

    @Nonnull
    private static List<String> contractIds(@Nullable List<Posting> postings) {
        List<String> ids = new ArrayList<>();
        if (postings != null) {
            for (Posting posting : postings) {
                ids.add(posting.contract().bountyId());
            }
        }
        return ids;
    }

    // ==================== place-aware facts ====================

    /**
     * Is this character where the quest's outstanding step resolves, under any id it answers to? A hand-in
     * that names no place counts nowhere here ({@link QuestEngine#nextStepNamesPlace}): it can be made
     * anywhere, so it is no one character's business, and reading it as here would list the quest on every
     * character's page at once.
     */
    public boolean readyHere(@Nonnull Quest quest) {
        for (String id : answersTo) {
            if (engine.nextStepNamesPlace(subject, quest, id)) {
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
     * engine compares the same id everywhere else. A contract taken at a board this character hosts
     * was taken here too.
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
     * at its own site pays out there and then while the same hand-in from nowhere parks it. The id the
     * quest was taken under is asked first, so a contract taken at a board this character hosts hands in
     * at that board.
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
        for (String id : siteFirst(quest)) {
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
     * claim itself must be made at, the id it was taken under asked first. Null when none of them
     * answers, and null when there is nobody in front of the player at all, which is exactly what a
     * claim from nowhere passes.
     */
    @Nullable
    public String collectionSite(@Nonnull Quest quest) {
        for (String id : siteFirst(quest)) {
            if (engine.canCompleteAt(subject, quest, id)) {
                return id;
            }
        }
        return null;
    }

    /** The answer set with the id the quest was taken under (when it is one of them) first. */
    @Nonnull
    private List<String> siteFirst(@Nonnull Quest quest) {
        List<String> out = new ArrayList<>(answersTo.size());
        String site = answersTo.size() > 1 ? engine.acceptSiteOf(subject, quest.id()) : null;
        if (site != null && !site.isBlank()) {
            for (String id : answersTo) {
                if (site.equalsIgnoreCase(id)) {
                    out.add(id);
                }
            }
        }
        for (String id : answersTo) {
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * Which section {@code quest} sits in on this character's list. A posting the player is not carrying sits
     * where its board puts it: Comes back once done this rotation, else Available or Locked by the board's own
     * slot-aware accept check.
     */
    @Nonnull
    public Section sectionOf(@Nonnull Quest quest) {
        QuestStatus status = engine.status(subject, quest);
        Posting posting = status == QuestStatus.ACTIVE || status == QuestStatus.COMPLETED_UNCLAIMED
                ? null : postingOf(quest.id());
        if (posting != null) {
            if (doneThisRotation(posting)) {
                return Section.COOLDOWN;
            }
            return acceptCheck(posting).ok() ? Section.AVAILABLE : Section.LOCKED;
        }
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
