package com.ziggfreed.common.objectives.dialogue;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.dialogue.DialogueHeaders;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.questlist.ProgressionTexts;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * The {@code ActiveObjective} header source: under the speaker's name, what the player is currently
 * meant to be doing on a quest THIS character gave them.
 *
 * <p>A conversation asks for it by name and gets it; nothing about it is any one mod's, because
 * quests are the library's. Which character hands a quest out rides on the quest itself
 * ({@link Quest#npcViewId()}), and the line reads the same values the quest tracker paints
 * ({@link ProgressionTexts}), so the reminder in the conversation and the row on the tracker cannot
 * drift apart or be worded differently.
 *
 * <pre>{@code
 * "Header": ["ActiveObjective"]
 * }</pre>
 *
 * <p>It answers null - so the conversation shows no note at all - when the player has nothing active
 * from this character, when every step of what they do have is already done, or when the screen was
 * opened with no character named.
 */
public final class ActiveObjectiveHeader {

    /** The name a conversation writes in its {@code Header} list. */
    public static final String NAME = "ActiveObjective";

    private ActiveObjectiveHeader() {
    }

    /** Contribute this source to the shared header vocabulary. Call once from library setup. */
    public static void register(@Nullable String owner) {
        DialogueHeaders.register(NAME, owner, ActiveObjectiveHeader::lineFor);
    }

    @Nullable
    private static Message lineFor(@Nullable String contextNpcId, @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store) {
        if (contextNpcId == null || contextNpcId.isBlank()) {
            return null;
        }
        Subject subject = ProgressionRuntime.subjects().questSubject(store, ref);
        if (subject == null) {
            return null;
        }
        QuestEngine engine = ProgressionRuntime.quests();
        for (Quest quest : engine.activeAndUnclaimed(subject)) {
            if (!contextNpcId.equalsIgnoreCase(quest.npcViewId())) {
                continue;
            }
            Message line = firstUnfinished(engine, subject, quest);
            if (line != null) {
                return line;
            }
        }
        return null;
    }

    /**
     * The quest's first step that is not finished yet, worded the way the tracker words it and
     * carrying its own count. Only the CURRENT step is looked at, so a later step is not announced
     * before the player can act on it.
     */
    @Nullable
    private static Message firstUnfinished(@Nonnull QuestEngine engine, @Nonnull Subject subject,
            @Nonnull Quest quest) {
        List<ObjectiveDef> step = engine.activeStepObjectives(subject, quest);
        Map<String, ObjectiveProgressState> progress = engine.progressOf(subject, quest.id());
        for (ObjectiveDef objective : step) {
            ObjectiveProgressState state = progress.get(objective.id());
            if (state != null && state.isCompleted()) {
                continue;
            }
            Message text = ProgressionTexts.objectiveOrUntitled(quest.id(), objective.id());
            int required = state != null ? state.required() : objective.amountAsInt();
            int current = state != null ? state.current() : 0;
            return Msg.key("ziggfreedcommon.dialogue.active_objective",
                    required > 1 ? withCount(text, current, required) : text);
        }
        return null;
    }

    /** The step plus its {@code current/required} tally, as one message the caller can nest. */
    @Nonnull
    private static Message withCount(@Nonnull Message text, int current, int required) {
        return Msg.cat(text, Msg.raw(" (" + current + "/" + required + ")"));
    }
}
