package com.ziggfreed.common.objectives.hud;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.progress.runtime.ProgressionTexts;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * One paint of the tracker, worked out from the engine and nothing else: which quests are on it,
 * what each is called, which of its CURRENT step's objectives to list, and where each stands.
 *
 * <p>Kept apart from the commands that draw it so the decisions - hidden when nothing is pinned,
 * only the current step's rows, no count on a report-back hand-in, complete flips the glyph - are
 * plain values a test can read over an in-memory engine, while the drawing stays the mechanical
 * mapping of those values onto the document's fixed slots.
 *
 * <p>The slot counts are the document's: {@value #MAX_QUESTS} quest blocks of {@value #MAX_ROWS}
 * rows each. Anything past them is not shown; the engine's own tracked cap is respected first, and
 * a step's later objectives simply wait for the rows they need to free up.
 */
public record TrackedQuestSnapshot(boolean panelVisible, @Nonnull List<Block> blocks) {

    /** Quest blocks the tracker document ships ({@code #ZigQuest0..4}). */
    public static final int MAX_QUESTS = 5;

    /** Objective rows per quest block the tracker document ships ({@code #ObjRow0..3}). */
    public static final int MAX_ROWS = 4;

    /** Nothing on screen: the panel is hidden and there are no blocks to paint. */
    public static final TrackedQuestSnapshot HIDDEN = new TrackedQuestSnapshot(false, List.of());

    /** One tracked quest: its heading and the rows under it. */
    public record Block(@Nonnull String questId, @Nonnull Message title, @Nonnull List<Row> rows) {
    }

    /**
     * One objective line: what it reads as, the {@code current/required} count as the plain string
     * the count label shows (EMPTY for a report-back hand-in, which reads cleaner with none), and
     * whether it is done, which is what flips the glyph and the colours.
     */
    public record Row(@Nonnull Message text, @Nonnull String count, boolean complete) {
    }

    public TrackedQuestSnapshot {
        blocks = List.copyOf(blocks);
    }

    /** The ids of the quests this paint shows, in order. */
    @Nonnull
    public Set<String> questIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Block block : blocks) {
            ids.add(block.questId());
        }
        return ids;
    }

    /**
     * What {@code subject}'s tracker shows right now. A null subject (nobody to read) or a tracker
     * the deps say is switched off or unwanted is {@link #HIDDEN}; so is an empty pin list.
     */
    @Nonnull
    public static TrackedQuestSnapshot of(@Nonnull QuestEngine engine, @Nullable Subject subject,
            @Nonnull TrackedQuestHudDeps deps) {
        if (subject == null || !deps.isEnabled() || !deps.wantsHud(subject)) {
            return HIDDEN;
        }
        List<Quest> tracked = engine.trackedActive(subject);
        if (tracked.isEmpty()) {
            return HIDDEN;
        }
        List<Block> blocks = new ArrayList<>();
        for (Quest quest : tracked) {
            if (blocks.size() >= MAX_QUESTS) {
                break;
            }
            blocks.add(blockOf(engine, subject, quest));
        }
        return new TrackedQuestSnapshot(true, blocks);
    }

    @Nonnull
    private static Block blockOf(@Nonnull QuestEngine engine, @Nonnull Subject subject, @Nonnull Quest quest) {
        // Only the CURRENT step's objectives, so a multi-step quest's later steps (the closing
        // "return to the giver" hand-in above all) are not truncated by the row cap; the list
        // advances by itself as each step completes.
        List<ObjectiveDef> step = engine.activeStepObjectives(subject, quest);
        Map<String, ObjectiveProgressState> progress = engine.progressOf(subject, quest.id());
        List<Row> rows = new ArrayList<>();
        for (ObjectiveDef objective : step) {
            if (rows.size() >= MAX_ROWS) {
                break;
            }
            ObjectiveProgressState state = progress.get(objective.id());
            boolean complete = state != null && state.isCompleted();
            int required = state != null ? state.required() : objective.amountAsInt();
            int current = complete ? required : (state != null ? state.current() : 0);
            String count = isReportBack(objective) ? "" : current + "/" + required;
            rows.add(new Row(ProgressionTexts.objectiveOrUntitled(quest.id(), objective.id()), count, complete));
        }
        return new Block(quest.id(), ProgressionTexts.titleOrUntitled(quest.id()), rows);
    }

    /**
     * A hand-in that delivers nothing - "go back and speak to them" - carries no count worth
     * showing: it is done or it is not. Decided by the reserved kind id plus an unstated target,
     * which is exactly how the engine tells a report-back from an item hand-in.
     */
    private static boolean isReportBack(@Nonnull ObjectiveDef objective) {
        return "TURN_IN".equalsIgnoreCase(objective.kind().trim()) && objective.target().isBlank();
    }
}
