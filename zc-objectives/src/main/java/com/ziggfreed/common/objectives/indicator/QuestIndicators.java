package com.ziggfreed.common.objectives.indicator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.npc.NpcIdentities;
import com.ziggfreed.common.objectives.questlist.CharacterQuestListing;
import com.ziggfreed.common.objectives.questlist.NpcQuestSections.Section;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestTurnInSite;
import com.ziggfreed.common.quest.asset.QuestIndicatorSpec;
import com.ziggfreed.common.quest.asset.QuestSituation;
import com.ziggfreed.common.subject.Subject;

/**
 * The ONE availability answer behind both quest-indicator surfaces: which situations a character
 * is in for a player, in precedence order, each with the knob that says whether and how it shows.
 * The overhead marker and the map marker read the same list, so they cannot disagree.
 *
 * <p>Situations are the NPC quest page's own sections, read through the same
 * {@link CharacterQuestListing} that page builds its list from: a finished quest collected here is
 * {@link QuestSituation#COLLECT}, an errand settled here is {@link QuestSituation#TURN_IN}, a quest
 * on offer here is {@link QuestSituation#AVAILABLE}, a quest being carried whose business is here is
 * {@link QuestSituation#IN_PROGRESS}. Everything else the page lists (parked elsewhere, on cooldown,
 * locked, done) is not a situation a marker announces.
 *
 * <p>The knob for a situation is three scopes merged per leaf, narrowest winning: the server's
 * global word ({@link QuestIndicatorConfig#global}), the quest's own block, and, for the two
 * situations a STEP raises, that step's block. Which step: the hand-in step the character would
 * credit for a turn-in, and the step the player is on for in-progress.
 *
 * <p>The library's own marks read {@link #overheadFor} and {@link #mapMarks}; the two older names are
 * legacy reads that tell the library a consumer draws its own ({@link QuestMarkYield}) and answer as
 * they always did, every quest read as a one-off.
 *
 * <p>Reads only; world thread, since every engine read is.
 */
public final class QuestIndicators {

    /** One situation a character is in for a player, with the knob that says how it shows. */
    public record Reading(@Nonnull QuestSituation situation, @Nonnull Quest quest,
                          @Nonnull QuestIndicatorSpec.Resolved knob) {
    }

    /** One character the map marks for a player, and the winning situation it marks them for. */
    public record MapMark(@Nonnull String npcId, @Nonnull Reading reading) {
    }

    private QuestIndicators() {
    }

    /**
     * Every situation the character answering to {@code answersTo} is in for {@code subject}, in
     * precedence order (collect, turn-in, available, in-progress; then the quest's own listing
     * order), knobs applied but NOT filtered by them: a caller picks the first reading whose knob
     * shows what it draws.
     */
    @Nonnull
    public static List<Reading> situationsAt(@Nonnull QuestEngine engine, @Nonnull Subject subject,
            @Nonnull Set<String> answersTo) {
        return situationsAt(engine, subject, answersTo, true);
    }

    /**
     * {@link #situationsAt(QuestEngine, Subject, Set)}, a repeating quest reading its situation's
     * {@code Repeatable} state only when {@code repeatLooks}. Only the legacy reads pass false: every
     * quest read as a one-off, the answer they always gave.
     */
    @Nonnull
    private static List<Reading> situationsAt(@Nonnull QuestEngine engine, @Nonnull Subject subject,
            @Nonnull Set<String> answersTo, boolean repeatLooks) {
        if (answersTo.isEmpty()) {
            return List.of();
        }
        CharacterQuestListing listing = new CharacterQuestListing(engine, subject, answersTo);
        List<Reading> out = new ArrayList<>();
        for (Quest quest : listing.questsHere()) {
            QuestSituation situation = situationOf(listing.sectionOf(quest));
            if (situation == null) {
                continue;
            }
            out.add(new Reading(situation, quest, knobFor(situation, quest, stepFor(situation, listing, quest),
                    repeatLooks && quest.repeatable())));
        }
        out.sort(Comparator.comparingInt((Reading r) -> r.situation().ordinal())
                .thenComparingInt(r -> r.quest().listOrder())
                .thenComparing(r -> r.quest().id()));
        return out;
    }

    /** {@link #situationsAt(QuestEngine, Subject, Set)} over the shared runtime's engine. */
    @Nonnull
    public static List<Reading> situationsAt(@Nonnull Subject subject, @Nonnull Set<String> answersTo) {
        return situationsAt(ProgressionRuntime.quests(), subject, answersTo);
    }

    /**
     * The one situation whose marker floats over this character for {@code subject}: the first, in
     * precedence order, whose knob shows overhead. Null for nothing.
     */
    @Nullable
    public static Reading overheadFor(@Nonnull QuestEngine engine, @Nonnull Subject subject,
            @Nonnull Set<String> answersTo) {
        return overheadFor(engine, subject, answersTo, true);
    }

    /** {@link #overheadFor(QuestEngine, Subject, Set)}, repeat looks as {@link #situationsAt} reads them. */
    @Nullable
    private static Reading overheadFor(@Nonnull QuestEngine engine, @Nonnull Subject subject,
            @Nonnull Set<String> answersTo, boolean repeatLooks) {
        for (Reading reading : situationsAt(engine, subject, answersTo, repeatLooks)) {
            if (reading.knob().showsOverhead()) {
                return reading;
            }
        }
        return null;
    }

    /**
     * The overhead reading, for a consumer that draws its own marks. It answers as it always did:
     * every quest reads as a one-off, so a repeating quest shows its situation's own state, never the
     * {@code Repeatable} one the library's own marks show.
     *
     * @deprecated the library draws every quest mark itself; read {@link #overheadFor}. A consumer still
     * calling this is taken to draw its own marks, and the library's stand down for the rest of the boot
     * ({@link QuestMarkYield}).
     */
    @Deprecated
    @Nullable
    public static Reading overheadAt(@Nonnull QuestEngine engine, @Nonnull Subject subject,
            @Nonnull Set<String> answersTo) {
        QuestMarkYield.noteConsumerDraws("QuestIndicators.overheadAt");
        return overheadFor(engine, subject, answersTo, false);
    }

    /**
     * The overhead reading over the shared runtime's engine, for a consumer that draws its own marks.
     * It answers as it always did: every quest reads as a one-off.
     *
     * @deprecated the library draws every quest mark itself; read {@link #overheadFor}. A consumer still
     * calling this is taken to draw its own marks, and the library's stand down for the rest of the boot
     * ({@link QuestMarkYield}).
     */
    @Deprecated
    @Nullable
    public static Reading overheadAt(@Nonnull Subject subject, @Nonnull Set<String> answersTo) {
        QuestMarkYield.noteConsumerDraws("QuestIndicators.overheadAt");
        return overheadFor(ProgressionRuntime.quests(), subject, answersTo, false);
    }

    /**
     * The map marks, for a consumer that draws its own. It answers as it always did: every quest reads
     * as a one-off, so a repeating quest shows its situation's own state, never the {@code Repeatable}
     * one the library's own marks show.
     *
     * @deprecated the library draws every quest mark itself; read {@link #mapMarks}. A consumer still
     * calling this is taken to draw its own marks, and the library's stand down for the rest of the boot
     * ({@link QuestMarkYield}).
     */
    @Deprecated
    @Nonnull
    public static List<MapMark> mapMarksFor(@Nonnull QuestEngine engine, @Nonnull Subject subject) {
        QuestMarkYield.noteConsumerDraws("QuestIndicators.mapMarksFor");
        return mapMarks(engine, subject, false);
    }

    /**
     * The map marks over the shared runtime's engine, for a consumer that draws its own. It answers as
     * it always did: every quest reads as a one-off.
     *
     * @deprecated the library draws every quest mark itself; read {@link #mapMarks}. A consumer still
     * calling this is taken to draw its own marks, and the library's stand down for the rest of the boot
     * ({@link QuestMarkYield}).
     */
    @Deprecated
    @Nonnull
    public static List<MapMark> mapMarksFor(@Nonnull Subject subject) {
        QuestMarkYield.noteConsumerDraws("QuestIndicators.mapMarksFor");
        return mapMarks(ProgressionRuntime.quests(), subject, false);
    }

    /**
     * Every character the map marks for {@code subject}: each character any catalogued quest names
     * as its giver, its hand-in place or its collection site, evaluated at that character's whole
     * answer set, keeping the first situation whose knob marks the map. One mark per character,
     * by its primary id.
     */
    @Nonnull
    public static List<MapMark> mapMarks(@Nonnull QuestEngine engine, @Nonnull Subject subject) {
        return mapMarks(engine, subject, true);
    }

    /** {@link #mapMarks(QuestEngine, Subject)}, repeat looks as {@link #situationsAt} reads them. */
    @Nonnull
    private static List<MapMark> mapMarks(@Nonnull QuestEngine engine, @Nonnull Subject subject,
            boolean repeatLooks) {
        Map<String, MapMark> out = new LinkedHashMap<>();
        for (String npcId : candidateCharacters(engine, subject)) {
            String key = npcId.toLowerCase(Locale.ROOT);
            if (out.containsKey(key)) {
                continue;
            }
            for (Reading reading : situationsAt(engine, subject, NpcIdentities.answerSetForPrimary(npcId),
                    repeatLooks)) {
                if (reading.knob().showsMap()) {
                    out.put(key, new MapMark(npcId, reading));
                    break;
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * The knob for {@code situation} on {@code quest}: the global word, the quest's block over it,
     * and {@code stepId}'s block over that, each leaf by leaf, with the library's defaults filled in
     * last. A null {@code stepId} reads the quest scope alone. A repeating quest reads its
     * situation's {@code Repeatable} state.
     */
    @Nonnull
    public static QuestIndicatorSpec.Resolved knobFor(@Nonnull QuestSituation situation, @Nonnull Quest quest,
            @Nullable String stepId) {
        return knobFor(situation, quest, stepId, quest.repeatable());
    }

    /** {@link #knobFor(QuestSituation, Quest, String)}, the {@code Repeatable} state read only when {@code repeats}. */
    @Nonnull
    private static QuestIndicatorSpec.Resolved knobFor(@Nonnull QuestSituation situation, @Nonnull Quest quest,
            @Nullable String stepId, boolean repeats) {
        QuestIndicatorSpec merged = QuestIndicatorSpec.merge(QuestIndicatorConfig.getInstance().global(),
                quest.indicator());
        merged = QuestIndicatorSpec.merge(merged, quest.stepIndicator(stepId));
        return merged.resolve(situation, repeats);
    }

    /** The situation a page section announces, or null for a section no marker speaks for. */
    @Nullable
    public static QuestSituation situationOf(@Nonnull Section section) {
        return switch (section) {
            case READY -> QuestSituation.COLLECT;
            case TURN_IN -> QuestSituation.TURN_IN;
            case AVAILABLE -> QuestSituation.AVAILABLE;
            case ACTIVE -> QuestSituation.IN_PROGRESS;
            case PARKED, COOLDOWN, LOCKED, DONE -> null;
        };
    }

    /** The step whose own block narrows {@code situation}, or null for a quest-level situation. */
    @Nullable
    private static String stepFor(@Nonnull QuestSituation situation, @Nonnull CharacterQuestListing listing,
            @Nonnull Quest quest) {
        return switch (situation) {
            case TURN_IN -> {
                CharacterQuestListing.TurnIn turnIn = listing.turnInHere(quest);
                ObjectiveDef step = turnIn != null ? turnIn.step() : listing.outstandingStep(quest);
                yield step == null ? null : step.id();
            }
            case IN_PROGRESS -> {
                ObjectiveDef step = listing.outstandingStep(quest);
                yield step == null ? null : step.id();
            }
            case COLLECT, AVAILABLE -> null;
        };
    }

    /**
     * Every character a quest on this server could put in a situation for {@code subject}: each
     * catalogued quest's giver, each carried quest's hand-in places, and each quest's named
     * collection site. Deduped without regard to case, first spelling kept.
     */
    @Nonnull
    private static List<String> candidateCharacters(@Nonnull QuestEngine engine, @Nonnull Subject subject) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Quest quest : engine.quests()) {
            add(out, quest.npcViewId());
            QuestTurnInSite site = quest.turnInAt();
            if (site != null && !site.isAcceptSite()) {
                add(out, site.id());
            }
        }
        for (Quest quest : engine.activeAndUnclaimed(subject)) {
            for (ObjectiveDef objective : quest.objectives()) {
                add(out, objective.turnInLockId());
            }
        }
        return new ArrayList<>(out.values());
    }

    private static void add(@Nonnull Map<String, String> out, @Nullable String npcId) {
        if (npcId != null && !npcId.isBlank()) {
            out.putIfAbsent(npcId.trim().toLowerCase(Locale.ROOT), npcId.trim());
        }
    }
}
