package com.ziggfreed.common.objectives.dialogue;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.dialogue.schema.DialogueHeaders;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.runtime.ProgressionTexts;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.npc.NpcIdentities;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * The {@code ActiveObjective} header source: under the speaker's name, what the player is currently
 * meant to be doing on a quest THIS character gave them, named by its quest so a player with two of
 * this character's quests can tell which one it is: "Pumpkin Patch: Bring 3 Hallowed Pumpkins (0/3)".
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

    /** "{quest}: {step}", for a step with nothing to count. */
    static final String LINE_KEY = "ziggfreedcommon.dialogue.active_step";

    /** "{quest}: {step} ({current}/{required})", the two counts typed numbers. */
    static final String COUNTED_KEY = "ziggfreedcommon.dialogue.active_step_count";

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
            // Alias-aware, the way every other surface asks: a character answers to its primary id
            // AND to whatever ids its placement declares, so a quest whose giver is written as one
            // of those aliases still belongs to the character standing here. A bare id comparison
            // silently shows no step for exactly the quests an alias was introduced to serve.
            if (!NpcIdentities.primaryAnswersTo(contextNpcId, quest.npcViewId())) {
                continue;
            }
            Message line = firstUnfinished(engine, subject, quest,
                    ProgressionTexts.titleOrUntitled(quest.id()));
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
            @Nonnull Quest quest, @Nonnull Message title) {
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
            return line(title, text, current, required);
        }
        return null;
    }

    /**
     * The note itself: the quest's name, its step, and the step's tally when there is more than one
     * thing to do. The tally's numbers are typed params, so the player's own client writes them, and
     * the lang value decides how the parts sit together.
     */
    @Nonnull
    static Message line(@Nonnull Message title, @Nonnull Message step, int current, int required) {
        return required > 1
                ? Msg.key(COUNTED_KEY, title, step, current, required)
                : Msg.key(LINE_KEY, title, step);
    }
}
