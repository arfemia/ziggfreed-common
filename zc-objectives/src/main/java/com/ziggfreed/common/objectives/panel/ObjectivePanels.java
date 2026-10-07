package com.ziggfreed.common.objectives.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.book.ObjectiveBookDeps;
import com.ziggfreed.common.objectives.book.ObjectiveBookPages;
import com.ziggfreed.common.objectives.book.achievement.AchievementReader;
import com.ziggfreed.common.objectives.journal.QuestPresentation;
import com.ziggfreed.common.objectives.journal.QuestReader;
import com.ziggfreed.common.occurrence.Occurrences;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.LedgerBindings;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.RowSize;
import com.ziggfreed.common.util.SafeLog;

/**
 * A player's pinned achievements and tracked quests as compact rows, for a page outside the book (the MMO's Skills
 * page): the same rows the book's own strips show ({@code AchievementReader.compactRow}, {@code QuestReader.compactRow}),
 * painted through the kit's ledger into {@code Pages/ZigLedgerRowCompact.ui}. A row's click is the caller's: it is
 * handed the row's kind ({@link #KIND_ACHIEVEMENT} or {@link #KIND_QUEST}) and id, and usually opens the book on it
 * ({@code ObjectiveBookPages.open(tab, id, ...)}).
 *
 * <p>What lists: the pins the book's Overview lists under Pinned, oldest pin first, then the tracked quests still
 * being carried ({@code QuestEngine.trackedActive}); each side capped at {@code maxRows}, an empty side left out.
 * Each side is a section of the host with its own head (label and count, no fold).
 *
 * <p>Guarded: it never throws. When the progression runtime cannot be read for this player (no engines, no
 * subject, a seam that throws) it sends nothing at all; when it reads but nothing is pinned or tracked it clears the
 * host and returns 0, so the caller shows its own empty line.
 */
public final class ObjectivePanels {

    /** The kind handed to the binding for a pinned achievement's row. */
    public static final String KIND_ACHIEVEMENT = "achievement";

    /** The kind handed to the binding for a tracked quest's row. */
    public static final String KIND_QUEST = "quest";

    /** The pinned achievements' section id. */
    static final String PINNED = "panel.pinned";

    /** The tracked quests' section id. */
    static final String TRACKED = "panel.tracked";

    private static final String PREFIX = "ziggfreedcommon.";

    private ObjectivePanels() {
    }

    /**
     * Paint {@code viewer}'s pinned achievements and tracked quests into {@code host} (cleared first), each side
     * capped at {@code maxRows}, every row bound to {@code binding.apply(kind, id)} (a null answer leaves the row
     * unbound). Call it in a full build, or in a partial update that repaints the whole host.
     *
     * @return how many rows were painted; 0 when nothing is pinned or tracked, or nothing could be read
     */
    public static int paintPinnedAndTracked(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull String host, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nullable PlayerRef viewer, int maxRows, @Nonnull BiFunction<String, String, EventData> binding) {
        List<LedgerSection> sections;
        try {
            sections = read(store, ref, viewer, maxRows);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the pinned and tracked panel could not be read: " + t.getMessage());
            return 0;
        }
        if (sections == null) {
            return 0;
        }
        try {
            return paint(cmd, events, host, sections, viewer, binding);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the pinned and tracked panel could not be painted: " + t.getMessage());
            return 0;
        }
    }

    /** The two sides read for one player, or null when neither engine can be read for them. */
    @Nullable
    private static List<LedgerSection> read(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nullable PlayerRef viewer, int maxRows) {
        if (store == null || ref == null) {
            return null;
        }
        ObjectiveBookDeps deps = ObjectiveBookPages.resolvedDeps();
        long now = System.currentTimeMillis();
        UUID viewerId = viewer == null ? null : viewer.getUuid();
        Subject achievementSubject = ProgressionRuntime.subjects().achievementSubject(store, ref);
        Subject questSubject = ProgressionRuntime.subjects().questSubject(store, ref);
        if (achievementSubject == null && questSubject == null) {
            return null;
        }
        AchievementReader achievements = achievementSubject == null ? null
                : AchievementReader.of(ProgressionRuntime.achievements(), achievementSubject, deps, viewerId,
                        Occurrences.source(), deps.seen(), now);
        QuestReader quests = questSubject == null ? null
                : QuestReader.of(ProgressionRuntime.quests(), questSubject, QuestPresentation.of(deps),
                        questSubject.id(), now);
        return sections(achievements, quests, maxRows);
    }

    /**
     * The panel's sections: Pinned (the Overview's pinned strip, capped), then Tracked (the carried tracked quests,
     * capped); a side with no row, or no reader, is left out.
     */
    @Nonnull
    static List<LedgerSection> sections(@Nullable AchievementReader achievements, @Nullable QuestReader quests,
            int maxRows) {
        int cap = Math.max(0, Math.min(maxRows, LedgerSection.DEFAULT_CAP));
        List<LedgerSection> out = new ArrayList<>(2);
        if (cap == 0) {
            return out;
        }
        if (achievements != null) {
            List<LedgerRow> pinned = achievements.overview(0, 0).pinned();
            add(out, PINNED, Msg.tr(PREFIX, "progression.book.achievements.overview.pinned"), pinned, cap);
        }
        if (quests != null) {
            List<LedgerRow> tracked = new ArrayList<>();
            for (Quest quest : quests.engine().trackedActive(quests.subject())) {
                tracked.add(quests.compactRow(quest));
                if (tracked.size() >= cap) {
                    break;
                }
            }
            add(out, TRACKED, Msg.tr(PREFIX, "progression.book.quests.tracked_header"), tracked, cap);
        }
        return out;
    }

    private static void add(@Nonnull List<LedgerSection> out, @Nonnull String id, @Nonnull Message label,
            @Nonnull List<LedgerRow> rows, int cap) {
        if (rows.isEmpty()) {
            return;
        }
        List<LedgerRow> shown = rows.size() > cap ? List.copyOf(rows.subList(0, cap)) : List.copyOf(rows);
        out.add(new LedgerSection(id, label, shown, true, cap));
    }

    /** Paint {@code sections} into {@code host} through the kit, compact rows, every row bound by its kind and id. */
    static int paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String host,
            @Nonnull List<LedgerSection> sections, @Nullable PlayerRef viewer,
            @Nonnull BiFunction<String, String, EventData> binding) {
        LedgerBindings bindings = new LedgerBindings() {
            @Nullable
            @Override
            public EventData row(LedgerSection s, LedgerRow r) {
                try {
                    return binding.apply(PINNED.equals(s.id()) ? KIND_ACHIEVEMENT : KIND_QUEST, r.id());
                } catch (Throwable t) {
                    SafeLog.warn("[progression] the pinned and tracked panel's binding failed: " + t.getMessage());
                    return null;
                }
            }

            @Nullable
            @Override
            public EventData section(LedgerSection s) {
                return null;
            }

            @Nullable
            @Override
            public EventData showMore(LedgerSection s) {
                return null;
            }
        };
        LedgerPainter.paint(cmd, events, host, LedgerModel.of(sections), Set.of(), null, bindings, RowSize.COMPACT,
                viewer);
        int rows = 0;
        for (LedgerSection section : sections) {
            rows += Math.min(section.rows().size(), section.cap());
        }
        return rows;
    }
}
