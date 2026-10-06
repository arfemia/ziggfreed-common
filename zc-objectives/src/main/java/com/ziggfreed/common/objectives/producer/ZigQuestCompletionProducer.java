package com.ziggfreed.common.objectives.producer;

import java.util.UUID;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.QuestCompletionQualifier;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestCadence;
import com.ziggfreed.common.quest.event.QuestClaimedEvent;
import com.ziggfreed.common.util.SafeLog;

/**
 * Turns a finished quest into quest and achievement progress: {@code COMPLETE_QUEST} once per quest
 * collected, so a criterion counting finished quests (any, a cadence's worth, or one quest by id)
 * and a quest step asking for another quest to be finished both advance on any server, whoever
 * authored the quest.
 *
 * <p><b>It counts at the CLAIM</b> ({@link QuestClaimedEvent}), never at the completion. A quest
 * that pays out the instant its last step lands fires both events back to back, so either would
 * count it; a quest that PARKS its reward fires the completion at once and the claim only when the
 * player collects, and the collect is when it reads as finished everywhere else: the
 * {@code quest_completed} reading, a prerequisite, the auto-accepts it arms. The claim fires once
 * per payout, so a repeatable counts once per collection and a refused second collect counts
 * nothing.
 *
 * <p>The contract content sees: {@code Target} is the quest id, {@code Qualifier} is how often it
 * comes round ({@link #qualifierOf(String, Quest)}), {@code Amount} is 1.
 *
 * <p>An EVENT-BUS listener like {@link ZigCalendarProducer}, fed on the player's own world thread
 * through {@link PlayerMomentDispatch}. Inline when the claim is already there (the normal case):
 * the claim event fires after the payout settled and was committed, so the dispatch re-enters an
 * engine with nothing left in flight, unlike a loot announcement made mid-grant
 * ({@link ZigLootReceivedProducer}). Registered at {@link EventPriority#LATE}, so every reaction to
 * the claim itself runs before the knock-on progress this moment causes, which can settle and claim
 * another quest inside it.
 */
public final class ZigQuestCompletionProducer {

    /** Fired once per quest collected. The built-in kind content already authors. */
    public static final String KIND = "COMPLETE_QUEST";

    private static final long AMOUNT = 1L;

    private static final String LABEL = "quest-completion";

    private ZigQuestCompletionProducer() {
    }

    /** Listen for collected quests on the shared bus. Registration only, from {@code ProgressionDefaults.install}. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().registerGlobal(EventPriority.LATE, QuestClaimedEvent.class,
                ZigQuestCompletionProducer::onClaimed);
    }

    /** Where one player's moment goes. A seam purely so the fan-out needs no server to test. */
    @FunctionalInterface
    interface Sink {

        void accept(@Nonnull UUID playerId, @Nonnull String kindId, @Nonnull String target, @Nullable String qualifier,
                long amount);
    }

    /** Guarded whole: a producer that throws would take the claim's other listeners down with it. */
    static void onClaimed(@Nonnull QuestClaimedEvent event) {
        try {
            fanOut(event, ZigQuestCompletionProducer::qualifierOf, (playerId, kindId, target, qualifier, amount) ->
                    PlayerMomentDispatch.fire(LABEL, playerId, kindId, target, qualifier, amount, null));
        } catch (Throwable t) {
            SafeLog.warn("[progression] quest-completion progress failed", t);
        }
    }

    /** One moment per collected quest, qualified by {@code qualifiers}. */
    static void fanOut(@Nonnull QuestClaimedEvent event, @Nonnull Function<String, String> qualifiers,
            @Nonnull Sink sink) {
        sink.accept(event.playerId(), KIND, event.questId(), qualifiers.apply(event.questId()), AMOUNT);
    }

    /** {@link #qualifierOf(String, Quest)} for the quest as this server's one runtime holds it. */
    @Nullable
    private static String qualifierOf(@Nonnull String questId) {
        return qualifierOf(questId, ProgressionRuntime.quests().quest(questId));
    }

    /**
     * The qualifier a collected quest's {@code COMPLETE_QUEST} moment carries: the first answer a
     * registered {@link QuestCompletionQualifier} gives, else the quest's own cadence word
     * ({@link QuestCadence#qualifier()}: {@code NORMAL}, {@code REPEATABLE}, {@code DAILY} or
     * {@code WEEKLY}), else none for a quest the runtime does not hold.
     *
     * @param quest the quest as the runtime holds it, or null when it holds none under that id
     */
    @Nullable
    public static String qualifierOf(@Nonnull String questId, @Nullable Quest quest) {
        String answered = ProgressionRuntime.questCompletionQualifier().qualifierFor(questId);
        if (answered != null) {
            return answered;
        }
        return quest == null ? null : QuestCadence.of(quest.repeat()).qualifier();
    }
}
