package com.ziggfreed.common.objectives.hud;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.questlist.ProgressionTexts;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.UiText;
import com.ziggfreed.common.ui.ZigRichButton;

/**
 * The one renderer for a tracked-quests SIDE PANEL on a page: one {@code Pages/ZigTrackedQuestRow.ui}
 * row per pinned quest inside the host page's {@code #TrackedQuestsList}, plus its
 * {@code #TrackedQuestsEmpty} note. It is not the HUD ({@link TrackedQuestHud} is); it is what a
 * quest log or an overview page embeds so the two panels can never drift apart, and it reads the
 * same runtime the HUD reads.
 *
 * <p><b>Host page contract.</b> The page's document carries a {@code Group #TrackedQuestsList}
 * the rows are appended into and a {@code Label #TrackedQuestsEmpty} for the note; the header
 * label, if any, stays with the page (its id differs across pages). Every one of the engine's
 * {@code maxTracked()} row slots is always addressed - appended on a full build, hidden when
 * surplus - so a later {@code sendUpdate} can repaint the panel by index without re-appending.
 *
 * <p>Titles come from the registered text sources; progress from the engine's own tally. World
 * thread, like every page paint.
 */
public final class TrackedQuestPanelRenderer {

    public static final String ROW_TEMPLATE = "Pages/ZigTrackedQuestRow.ui";

    /** The host page's list container the rows go into. */
    public static final String LIST_SELECTOR = "#TrackedQuestsList";

    /** The host page's note shown when nothing is pinned. */
    public static final String EMPTY_SELECTOR = "#TrackedQuestsEmpty";

    private static final String PREFIX = "ziggfreedcommon.";
    private static final String DOMAIN = "progression.";

    private TrackedQuestPanelRenderer() {
    }

    /**
     * Paint the tracked panel for {@code subject} (null when there is nobody to read: every row
     * hides and the note shows). {@code appendRows} appends the row templates first (a full page
     * build) or only repaints the pre-allocated slots (a {@code sendUpdate}). When {@code events}
     * and {@code clickAction} are both given, each row's name button fires
     * {@code EventData(Action: clickAction, QuestId: <id>)}.
     */
    public static void render(@Nonnull UICommandBuilder cmd, @Nullable UIEventBuilder events,
            @Nullable Subject subject, boolean appendRows, @Nullable String clickAction) {
        QuestEngine engine = ProgressionRuntime.quests();
        List<Quest> visible = subject != null ? engine.trackedActive(subject) : List.of();
        int slots = engine.maxTracked();
        for (int i = 0; i < slots; i++) {
            if (appendRows) {
                cmd.append(LIST_SELECTOR, ROW_TEMPLATE);
            }
            String slot = LIST_SELECTOR + "[" + i + "]";
            if (i < visible.size()) {
                Quest quest = visible.get(i);
                cmd.set(slot + ".Visible", true);
                // A Button + inner Label: the title is a Message and only substitutes on .TextSpans.
                ZigRichButton.text(cmd, slot + " #TrackedQuestNameBtn", ProgressionTexts.titleOrUntitled(quest.id()));
                QuestEngine.ObjectiveTally tally = engine.tally(subject, quest);
                double pct = tally.total() == 0 ? 0.0
                        : Math.min(1.0, (double) tally.completed() / tally.total());
                cmd.set(slot + " #TrackedQuestProgress.Value", pct);
                // "X/Y" is pure data (two numbers), not localized text.
                UiText.setText(cmd, slot + " #TrackedQuestProgressText.Text", tally.completed() + "/" + tally.total());
                if (events != null && clickAction != null) {
                    events.addEventBinding(CustomUIEventBindingType.Activating,
                            slot + " #TrackedQuestNameBtn",
                            EventData.of("Action", clickAction).append("QuestId", quest.id()), false);
                }
            } else {
                cmd.set(slot + ".Visible", false);
            }
        }
        boolean hasAny = !visible.isEmpty();
        cmd.set(EMPTY_SELECTOR + ".Visible", !hasAny);
        if (!hasAny) {
            // .TextSpans, never .Text: a Message on a Label's String sink crashes the client.
            cmd.set(EMPTY_SELECTOR + ".TextSpans", Msg.tr(PREFIX, DOMAIN + "tracked.empty"));
        }
    }
}
