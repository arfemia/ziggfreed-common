package com.ziggfreed.common.progress.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementGates;
import com.ziggfreed.common.achievement.AchievementProgressStore;
import com.ziggfreed.common.achievement.AchievementStatus;
import com.ziggfreed.common.achievement.InMemoryAchievementProgressStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ProgressDispatchTap;
import com.ziggfreed.common.progress.ZoneRef;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestGates;
import com.ziggfreed.common.quest.QuestI18n;
import com.ziggfreed.common.quest.QuestInventoryConsumer;
import com.ziggfreed.common.quest.QuestPossessionProbe;
import com.ziggfreed.common.quest.QuestProgressStore;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The RESOLVED shape of every part the shared runtime was registered with, held as one immutable
 * snapshot and re-derived whole whenever a registration changes.
 *
 * <p>The engines are built ONCE, over the FORWARDERS below rather than over the parts directly. Each
 * forwarder is a one-line delegation that reads the current snapshot per call, which is what makes a
 * registration arriving after the engines were built actually take effect - and, far more
 * importantly, what keeps the ENGINE INSTANCES stable for the whole boot. A rebuild would orphan
 * every reference anybody is holding, which is precisely what a single shared runtime cannot afford.
 *
 * @param questStore                where quest state lives
 * @param achievementStore          where achievement state lives
 * @param possession                how to ask whether a player is carrying something
 * @param inventory                 how to actually take hand-in items
 * @param questI18n                 the quest naming seam
 * @param questFactorContext        how a subject becomes a factor-reading context, quest side
 * @param achievementFactorContext  the same, achievement side
 * @param subjects                  how a player becomes the subject the stores understand
 * @param questScope                what a consumer publishes around a mutating quest call
 * @param achievementScope          the same, achievement side
 * @param rewardRetryQueue          where a failed reward's replayable command goes, or null
 * @param warn                      where warnings go
 * @param questGates                every registered quest gate, composed
 * @param achievementGates          every registered achievement gate, composed
 * @param systemGate                every registered system gate, ANDed
 * @param tap                       every registered dispatch tap, fanned out
 * @param textSources               every registered text source, in registration order
 */
record ProgressionParts(@Nonnull QuestProgressStore questStore,
                        @Nonnull AchievementProgressStore achievementStore,
                        @Nonnull QuestPossessionProbe possession,
                        @Nonnull QuestInventoryConsumer inventory,
                        @Nonnull QuestI18n questI18n,
                        @Nonnull Function<Subject, FactorContext> questFactorContext,
                        @Nonnull Function<Subject, FactorContext> achievementFactorContext,
                        @Nonnull ProgressionSubjectSource subjects,
                        @Nonnull ProgressionCallScope questScope,
                        @Nonnull ProgressionCallScope achievementScope,
                        @Nullable BiConsumer<Subject, String> rewardRetryQueue,
                        @Nonnull Consumer<String> warn,
                        @Nonnull QuestGates questGates,
                        @Nonnull AchievementGates achievementGates,
                        @Nonnull ProgressionSystemGate systemGate,
                        @Nonnull ProgressDispatchTap tap,
                        @Nonnull List<ProgressionTextSource> textSources) {

    /** Default warn sink, guarded so a log-manager-less test JVM cannot crash on it. */
    static final Consumer<String> DEFAULT_WARN = message -> SafeLog.warn("[progression] " + message);

    /** A factor context with nothing in it: resolves nothing, so a standing-value read writes nothing. */
    static final Function<Subject, FactorContext> EMPTY_FACTOR_CONTEXT =
            subject -> FactorContext.builder().build();

    /** Builds no subject at all: correct for a runtime nothing player-shaped was registered into. */
    static final ProgressionSubjectSource NO_SUBJECTS = new ProgressionSubjectSource() {

        @Override
        @Nullable
        public Subject questSubject(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                    @Nonnull PlayerRef playerRef) {
            return null;
        }

        @Override
        @Nullable
        public Subject achievementSubject(@Nonnull Store<EntityStore> store,
                                          @Nonnull Ref<EntityStore> ref,
                                          @Nonnull PlayerRef playerRef) {
            return null;
        }
    };

    /**
     * The parts a runtime nobody registered anything into runs: everything inert but working, and a
     * SINGLE instance so the in-memory fallback stores keep their state across a re-derive.
     */
    static final ProgressionParts EMPTY = new ProgressionParts(new InMemoryQuestProgressStore(),
            new InMemoryAchievementProgressStore(),
            QuestPossessionProbe.NONE, QuestInventoryConsumer.NONE, QuestI18n.NONE,
            EMPTY_FACTOR_CONTEXT, EMPTY_FACTOR_CONTEXT, NO_SUBJECTS,
            ProgressionCallScope.DIRECT, ProgressionCallScope.DIRECT,
            null, DEFAULT_WARN, QuestGates.OPEN, AchievementGates.OPEN,
            ProgressionSystemGate.OPEN, ProgressDispatchTap.NONE, List.of());

    // ==================== the forwarders the engines are built over ====================

    /** Reads the live snapshot for every call, so a late store registration is honoured at once. */
    static final QuestProgressStore QUEST_STORE = new QuestProgressStore() {

        @Nonnull
        @Override
        public QuestStatus status(@Nonnull Subject subject, @Nonnull String questId) {
            return ProgressionRuntime.parts().questStore().status(subject, questId);
        }

        @Override
        public void setStatus(@Nonnull Subject subject, @Nonnull String questId,
                              @Nonnull QuestStatus status) {
            ProgressionRuntime.parts().questStore().setStatus(subject, questId, status);
        }

        @Nullable
        @Override
        public String progressPayload(@Nonnull Subject subject, @Nonnull String questId) {
            return ProgressionRuntime.parts().questStore().progressPayload(subject, questId);
        }

        @Override
        public void putProgressPayload(@Nonnull Subject subject, @Nonnull String questId,
                                       @Nonnull String payload) {
            ProgressionRuntime.parts().questStore().putProgressPayload(subject, questId, payload);
        }

        @Override
        public long cooldownStamp(@Nonnull Subject subject, @Nonnull String questId) {
            return ProgressionRuntime.parts().questStore().cooldownStamp(subject, questId);
        }

        @Override
        public void setCooldownStamp(@Nonnull Subject subject, @Nonnull String questId, long epochMs) {
            ProgressionRuntime.parts().questStore().setCooldownStamp(subject, questId, epochMs);
        }

        @Nonnull
        @Override
        public CompletionRecord completions(@Nonnull Subject subject, @Nonnull String questId) {
            return ProgressionRuntime.parts().questStore().completions(subject, questId);
        }

        @Override
        public void setCompletions(@Nonnull Subject subject, @Nonnull String questId,
                                   @Nonnull CompletionRecord record) {
            ProgressionRuntime.parts().questStore().setCompletions(subject, questId, record);
        }

        @Override
        public boolean recordsCompletions() {
            return ProgressionRuntime.parts().questStore().recordsCompletions();
        }

        @Nonnull
        @Override
        public Set<String> knownQuestIds(@Nonnull Subject subject) {
            return ProgressionRuntime.parts().questStore().knownQuestIds(subject);
        }

        @Override
        public void clearQuest(@Nonnull Subject subject, @Nonnull String questId) {
            ProgressionRuntime.parts().questStore().clearQuest(subject, questId);
        }

        @Nonnull
        @Override
        public Map<String, Long> trackedPins(@Nonnull Subject subject) {
            return ProgressionRuntime.parts().questStore().trackedPins(subject);
        }

        @Override
        public void setTrackedPin(@Nonnull Subject subject, @Nonnull String questId, long pinnedAtMs) {
            ProgressionRuntime.parts().questStore().setTrackedPin(subject, questId, pinnedAtMs);
        }

        @Override
        public boolean clearTrackedPin(@Nonnull Subject subject, @Nonnull String questId) {
            return ProgressionRuntime.parts().questStore().clearTrackedPin(subject, questId);
        }

        @Override
        public boolean usesReservedDelimiter(@Nullable String id) {
            return ProgressionRuntime.parts().questStore().usesReservedDelimiter(id);
        }

        @Override
        public void markDirty(@Nonnull Subject subject) {
            ProgressionRuntime.parts().questStore().markDirty(subject);
        }

        @Override
        public void flush(@Nonnull Subject subject) {
            ProgressionRuntime.parts().questStore().flush(subject);
        }
    };

    /** The achievement peer of {@link #QUEST_STORE}. */
    static final AchievementProgressStore ACHIEVEMENT_STORE = new AchievementProgressStore() {

        @Override
        public long progress(@Nonnull Subject subject, @Nonnull String key) {
            return ProgressionRuntime.parts().achievementStore().progress(subject, key);
        }

        @Override
        public void putProgress(@Nonnull Subject subject, @Nonnull String key, long value) {
            ProgressionRuntime.parts().achievementStore().putProgress(subject, key, value);
        }

        @Nonnull
        @Override
        public Set<String> progressKeys(@Nonnull Subject subject) {
            return ProgressionRuntime.parts().achievementStore().progressKeys(subject);
        }

        @Nonnull
        @Override
        public AchievementStatus status(@Nonnull Subject subject, @Nonnull String achievementId) {
            return ProgressionRuntime.parts().achievementStore().status(subject, achievementId);
        }

        @Override
        public void setStatus(@Nonnull Subject subject, @Nonnull String achievementId,
                              @Nonnull AchievementStatus status) {
            ProgressionRuntime.parts().achievementStore().setStatus(subject, achievementId, status);
        }

        @Nonnull
        @Override
        public Set<String> knownAchievementIds(@Nonnull Subject subject) {
            return ProgressionRuntime.parts().achievementStore().knownAchievementIds(subject);
        }

        @Override
        public long unlockedAt(@Nonnull Subject subject, @Nonnull String achievementId) {
            return ProgressionRuntime.parts().achievementStore().unlockedAt(subject, achievementId);
        }

        @Override
        public void setUnlockedAt(@Nonnull Subject subject, @Nonnull String achievementId, long epochMs) {
            ProgressionRuntime.parts().achievementStore().setUnlockedAt(subject, achievementId, epochMs);
        }

        @Nonnull
        @Override
        public AchievementStatus milestoneStatus(@Nonnull Subject subject, int threshold) {
            return ProgressionRuntime.parts().achievementStore().milestoneStatus(subject, threshold);
        }

        @Override
        public void setMilestoneStatus(@Nonnull Subject subject, int threshold,
                                       @Nonnull AchievementStatus status) {
            ProgressionRuntime.parts().achievementStore().setMilestoneStatus(subject, threshold, status);
        }

        @Nonnull
        @Override
        public Set<Integer> knownMilestones(@Nonnull Subject subject) {
            return ProgressionRuntime.parts().achievementStore().knownMilestones(subject);
        }

        @Nonnull
        @Override
        public Map<String, Long> pins(@Nonnull Subject subject) {
            return ProgressionRuntime.parts().achievementStore().pins(subject);
        }

        @Override
        public void setPin(@Nonnull Subject subject, @Nonnull String achievementId, long pinnedAtMs) {
            ProgressionRuntime.parts().achievementStore().setPin(subject, achievementId, pinnedAtMs);
        }

        @Override
        public boolean clearPin(@Nonnull Subject subject, @Nonnull String achievementId) {
            return ProgressionRuntime.parts().achievementStore().clearPin(subject, achievementId);
        }

        @Override
        public boolean usesReservedDelimiter(@Nullable String id) {
            return ProgressionRuntime.parts().achievementStore().usesReservedDelimiter(id);
        }

        @Override
        public void markDirty(@Nonnull Subject subject) {
            ProgressionRuntime.parts().achievementStore().markDirty(subject);
        }

        @Override
        public void flush(@Nonnull Subject subject) {
            ProgressionRuntime.parts().achievementStore().flush(subject);
        }
    };

    static final QuestPossessionProbe POSSESSION = (subject, itemId, count) ->
            ProgressionRuntime.parts().possession().holds(subject, itemId, count);

    static final QuestInventoryConsumer INVENTORY = (subject, itemId, max) ->
            ProgressionRuntime.parts().inventory().take(subject, itemId, max);

    static final ProgressDispatchTap TAP = (subject, kind, target, qualifier, amount, zone) ->
            ProgressionRuntime.parts().tap().observe(subject, kind, target, qualifier, amount, zone);

    static final Consumer<String> WARN = message -> ProgressionRuntime.parts().warn().accept(message);

    static final BiConsumer<Subject, String> REWARD_RETRY_QUEUE = (subject, command) -> {
        BiConsumer<Subject, String> queue = ProgressionRuntime.parts().rewardRetryQueue();
        if (queue != null) {
            queue.accept(subject, command);
        }
    };

    static final Function<Subject, FactorContext> QUEST_FACTOR_CONTEXT = subject ->
            ProgressionRuntime.parts().questFactorContext().apply(subject);

    static final Function<Subject, FactorContext> ACHIEVEMENT_FACTOR_CONTEXT = subject ->
            ProgressionRuntime.parts().achievementFactorContext().apply(subject);

    static final QuestI18n QUEST_I18N = new QuestI18n() {

        @Override
        @Nonnull
        public String keyPrefix() {
            return ProgressionRuntime.parts().questI18n().keyPrefix();
        }

        @Override
        public boolean hasKey(@Nonnull String unprefixedKey) {
            return ProgressionRuntime.parts().questI18n().hasKey(unprefixedKey);
        }
    };

    static final QuestGates QUEST_GATES = new QuestGates() {

        @Override
        public boolean accepts(@Nonnull Subject subject, @Nonnull Quest quest,
                               @Nonnull List<String> reasons) {
            return ProgressionRuntime.parts().questGates().accepts(subject, quest, reasons);
        }

        @Override
        public boolean prerequisitesMet(@Nonnull Subject subject, @Nonnull Quest quest) {
            return ProgressionRuntime.parts().questGates().prerequisitesMet(subject, quest);
        }

        @Override
        public boolean opensFor(@Nonnull Subject subject, @Nonnull Quest quest,
                                @Nonnull List<String> reasons) {
            return ProgressionRuntime.parts().questGates().opensFor(subject, quest, reasons);
        }

        @Override
        public boolean canReceiveRewards(@Nonnull Subject subject, @Nonnull Quest quest) {
            return ProgressionRuntime.parts().questGates().canReceiveRewards(subject, quest);
        }

        @Override
        public long preSatisfiedAmount(@Nonnull Subject subject, @Nonnull Quest quest,
                                       @Nonnull ObjectiveDef objective) {
            return ProgressionRuntime.parts().questGates().preSatisfiedAmount(subject, quest, objective);
        }
    };

    static final AchievementGates ACHIEVEMENT_GATES = new AchievementGates() {

        @Override
        public boolean canProgress(@Nonnull Subject subject, @Nonnull Achievement achievement) {
            return ProgressionRuntime.parts().achievementGates().canProgress(subject, achievement);
        }

        @Override
        public boolean canUnlock(@Nonnull Subject subject, @Nonnull Achievement achievement) {
            return ProgressionRuntime.parts().achievementGates().canUnlock(subject, achievement);
        }

        @Override
        public boolean canReceiveRewards(@Nonnull Subject subject, @Nonnull Achievement achievement) {
            return ProgressionRuntime.parts().achievementGates().canReceiveRewards(subject, achievement);
        }

        @Override
        public boolean visible(@Nonnull Subject subject, @Nonnull Achievement achievement) {
            return ProgressionRuntime.parts().achievementGates().visible(subject, achievement);
        }
    };

    // ==================== composition ====================

    /**
     * Every registered quest gate, ANDed.
     *
     * <p>{@code accepts} deliberately does NOT short-circuit, so every contributor's refusal token
     * lands in the one reasons list and a player is told all of it rather than the first of it. The
     * yes/no gates may short-circuit, since a second no adds nothing. {@code preSatisfiedAmount}
     * folds as a MAXIMUM, which is not a tie-break: the engine already applies these as high-water
     * values, so the largest is what any order of application would leave.
     */
    @Nonnull
    static QuestGates composeQuestGates(@Nonnull List<QuestGates> gates) {
        if (gates.isEmpty()) {
            return QuestGates.OPEN;
        }
        if (gates.size() == 1) {
            return gates.get(0);
        }
        List<QuestGates> frozen = List.copyOf(gates);
        return new QuestGates() {

            @Override
            public boolean accepts(@Nonnull Subject subject, @Nonnull Quest quest,
                                   @Nonnull List<String> reasons) {
                boolean allowed = true;
                for (QuestGates gate : frozen) {
                    allowed &= gate.accepts(subject, quest, reasons);
                }
                return allowed;
            }

            @Override
            public boolean prerequisitesMet(@Nonnull Subject subject, @Nonnull Quest quest) {
                for (QuestGates gate : frozen) {
                    if (!gate.prerequisitesMet(subject, quest)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean opensFor(@Nonnull Subject subject, @Nonnull Quest quest,
                                    @Nonnull List<String> reasons) {
                // Each contributor answers its OWN pair, so a gate that reads both from one pass
                // keeps doing so here. Like accepts, it does not short-circuit: a player is told
                // every contributor's refusal rather than the first one.
                boolean open = true;
                for (QuestGates gate : frozen) {
                    open &= gate.opensFor(subject, quest, reasons);
                }
                return open;
            }

            @Override
            public boolean canReceiveRewards(@Nonnull Subject subject, @Nonnull Quest quest) {
                for (QuestGates gate : frozen) {
                    if (!gate.canReceiveRewards(subject, quest)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public long preSatisfiedAmount(@Nonnull Subject subject, @Nonnull Quest quest,
                                           @Nonnull ObjectiveDef objective) {
                long best = 0L;
                for (QuestGates gate : frozen) {
                    best = Math.max(best, gate.preSatisfiedAmount(subject, quest, objective));
                }
                return best;
            }
        };
    }

    /** Every registered achievement gate, ANDed. */
    @Nonnull
    static AchievementGates composeAchievementGates(@Nonnull List<AchievementGates> gates) {
        if (gates.isEmpty()) {
            return AchievementGates.OPEN;
        }
        if (gates.size() == 1) {
            return gates.get(0);
        }
        List<AchievementGates> frozen = List.copyOf(gates);
        return new AchievementGates() {

            @Override
            public boolean canProgress(@Nonnull Subject subject, @Nonnull Achievement achievement) {
                for (AchievementGates gate : frozen) {
                    if (!gate.canProgress(subject, achievement)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean canUnlock(@Nonnull Subject subject, @Nonnull Achievement achievement) {
                for (AchievementGates gate : frozen) {
                    if (!gate.canUnlock(subject, achievement)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean canReceiveRewards(@Nonnull Subject subject, @Nonnull Achievement achievement) {
                for (AchievementGates gate : frozen) {
                    if (!gate.canReceiveRewards(subject, achievement)) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean visible(@Nonnull Subject subject, @Nonnull Achievement achievement) {
                for (AchievementGates gate : frozen) {
                    if (!gate.visible(subject, achievement)) {
                        return false;
                    }
                }
                return true;
            }
        };
    }

    /**
     * Every registered system gate, ANDed: any refusal wins, so registration order cannot change
     * the answer and no mod can re-open a system another mod shut.
     *
     * <p>Each call is individually guarded and a THROWING gate counts as OPEN, with one warn. An
     * owner switch that failed to answer must never read as "off": that would turn a whole system
     * off for every player on the server on the strength of one bug, in silence, where failing open
     * costs at most the single refusal that gate meant to make.
     */
    @Nonnull
    static ProgressionSystemGate composeSystemGates(@Nonnull List<ProgressionSystemGate> gates,
                                                   @Nonnull Consumer<String> warn) {
        if (gates.isEmpty()) {
            return ProgressionSystemGate.OPEN;
        }
        List<ProgressionSystemGate> frozen = List.copyOf(gates);
        return (system, subject) -> {
            boolean enabled = true;
            for (ProgressionSystemGate gate : frozen) {
                try {
                    enabled &= gate.enabled(system, subject);
                } catch (Throwable t) {
                    warn.accept("a " + system.label() + " system gate failed and is read as open: "
                            + t.getMessage());
                }
            }
            return enabled;
        };
    }

    /**
     * Every registered tap, fanned out, each call individually guarded: one mod's throwing tap costs
     * its own observation and nobody else's, and never the dispatch it was watching.
     */
    @Nonnull
    static ProgressDispatchTap composeTaps(@Nonnull List<ProgressDispatchTap> taps,
                                           @Nonnull Consumer<String> warn) {
        if (taps.isEmpty()) {
            return ProgressDispatchTap.NONE;
        }
        List<ProgressDispatchTap> frozen = List.copyOf(taps);
        return (subject, kind, target, qualifier, amount, zone) -> {
            for (ProgressDispatchTap tap : frozen) {
                try {
                    tap.observe(subject, kind, target, qualifier, amount, zone);
                } catch (Throwable t) {
                    warn.accept("a progress tap failed on '" + kind + "': " + t.getMessage());
                }
            }
        };
    }

    /** The first non-null answer from {@code sources}, walked in registration order. */
    @Nullable
    static <T> T firstAnswer(@Nonnull List<ProgressionTextSource> sources,
                             @Nonnull Function<ProgressionTextSource, T> ask) {
        for (ProgressionTextSource source : sources) {
            try {
                T answer = ask.apply(source);
                if (answer != null) {
                    return answer;
                }
            } catch (Throwable t) {
                SafeLog.warn("[progression] a text source failed: " + t.getMessage());
            }
        }
        return null;
    }

    /** A defensive copy of {@code sources} in registration order. */
    @Nonnull
    static List<ProgressionTextSource> freezeTextSources(@Nonnull List<ProgressionTextSource> sources) {
        return List.copyOf(new ArrayList<>(sources));
    }
}
