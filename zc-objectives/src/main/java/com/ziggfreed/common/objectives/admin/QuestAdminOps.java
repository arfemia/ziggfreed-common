package com.ziggfreed.common.objectives.admin;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.dialogue.DialogueMemories;
import com.ziggfreed.common.objectives.hud.TrackedQuestHuds;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * What an administrator's "start this player over" actually does, in one place, so every command
 * that offers it does the same thing.
 *
 * <p>A reset here is a WIPE, not the in-play re-arm. Abandoning, a repeatable coming round and a
 * lapsed contract re-offered all keep the record of how often the player has finished the quest,
 * because a lifetime cap either of them wiped could never be reached; an administrator's "start
 * over" drops it. Either way the re-arm is reported, so a memory a conversation declared to live
 * only as long as the quest is forgotten with it.
 *
 * <p>A wipe puts the player back at day one for whatever it wiped, so both entry points finish by
 * running the same maintenance a fresh login runs: re-arm every auto-accept quest the wipe
 * re-opened, and repaint the tracker, which otherwise keeps showing the wiped quests because an
 * administrative wipe is not one of the quest events it repaints on. Without that, "reset and run
 * the opening again" leaves the player with no starter quest until they relog, and a tracker lying
 * about what they carry.
 */
public final class QuestAdminOps {

    private QuestAdminOps() {
    }

    /**
     * Wipe every quest the store knows for this player, then sweep the whole quest-scoped dialogue
     * namespace, because a conversation can remember something about a quest the player never took,
     * filed under an id their quest state has no record of, and a per-id sweep walks straight past
     * it. Deliberately NOT the total memory clear: a greeting a character remembers giving, a name
     * a player told somebody, a one-shot gift already taken are not quest progress, and forgetting
     * all memories is a verb of its own.
     *
     * @return how many quest records were wiped
     */
    public static int resetAll(@Nonnull Subject subject, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef) {
        QuestEngine engine = ProgressionRuntime.quests();
        int wiped = ProgressionRuntime.questScope().around(subject, engine::wipeAllQuests);
        DialogueMemories.forgetAllQuests(store, ref);
        rearm(subject, playerRef, engine);
        return wiped;
    }

    /**
     * Wipe one quest. The caller decides whether the id is worth wiping: a record whose definition
     * has gone is exactly what an administrator may be here to clear, so an orphan is still wiped
     * when the player carries a record under it, and only an id NEITHER side knows is a typo.
     */
    public static void resetOne(@Nonnull Subject subject, @Nonnull String questId,
            @Nonnull PlayerRef playerRef) {
        QuestEngine engine = ProgressionRuntime.quests();
        ProgressionRuntime.questScope().run(subject, s -> engine.wipeQuest(s, questId));
        rearm(subject, playerRef, engine);
    }

    /** The post-wipe maintenance both entry points share; see the class note for why it is here. */
    private static void rearm(@Nonnull Subject subject, @Nonnull PlayerRef playerRef,
            @Nonnull QuestEngine engine) {
        ProgressionRuntime.questScope().run(subject, s -> {
            engine.selfHeal(s);
            engine.autoAcceptAvailable(s);
        });
        TrackedQuestHuds.repaint(playerRef);
    }
}
