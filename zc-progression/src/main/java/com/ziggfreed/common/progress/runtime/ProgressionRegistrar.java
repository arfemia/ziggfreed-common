package com.ziggfreed.common.progress.runtime;

import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nonnull;

import com.ziggfreed.common.achievement.AchievementGates;
import com.ziggfreed.common.achievement.AchievementProgressStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.progress.MatchFlavor;
import com.ziggfreed.common.progress.ProgressDispatchTap;
import com.ziggfreed.common.quest.QuestGates;
import com.ziggfreed.common.quest.QuestI18n;
import com.ziggfreed.common.quest.QuestInventoryConsumer;
import com.ziggfreed.common.quest.QuestPossessionProbe;
import com.ziggfreed.common.quest.QuestProgressStore;
import com.ziggfreed.common.subject.Subject;

/**
 * What a mod calls to contribute its own parts to THE shared progression runtime. Obtain one from
 * {@link ProgressionRuntime#registrar(String)} (or {@link ProgressionRuntime#defaults(String)} for
 * the library's own), call whatever applies, and let go of it: the registrar holds nothing, it only
 * attributes.
 *
 * <p>Every method is fluent, idempotent, and conflict-policed. The names deliberately mirror the two
 * engine builders, because that is the vocabulary a consumer already writes.
 *
 * <p><b>Three shapes of registration, and the difference matters.</b>
 * <ul>
 *   <li><b>One-slot</b> - somebody's store, somebody's subject source. Exactly one answer is
 *   possible, so a consumer outranks a library default silently, and a SECOND consumer trying to
 *   take a slot another consumer already holds is REFUSED and named: two mods each wanting their own
 *   quest store is unresolvable, and quietly picking one is the same double-tracking failure one
 *   level up.
 *   <li><b>Contribution</b> - gates, taps, text sources. Every registration applies; gates AND, taps
 *   fan out, text sources answer in order.
 *   <li><b>Keyed replacement</b> - {@link #producesKind}. The claim is per key, so the library's
 *   own generic producer stands down for exactly that kind.
 * </ul>
 *
 * <p><b>CONTENT is not registered here at all, and needs no claim.</b> Every reader folds the whole
 * shared store and publishes what it folded; the layers merge with library defaults first and
 * consumers after, so a consumer's version of an id silently replaces the library's. Rank settles
 * it, which is why there is no content-ownership method beside {@code producesKind}.
 *
 * <p><b>The three shared VOCABULARIES are not here on purpose.</b> Objective kinds, reward kinds and
 * gate kinds are already open registries with their own owner attribution and overwrite policy, and
 * the runtime hands the live ones out ({@link ProgressionRuntime#objectiveKinds()} and friends). A
 * consumer registers into those directly; there is no slot to conflict over, which is the whole
 * point of a registry.
 */
public final class ProgressionRegistrar {

    private final String owner;
    private final boolean libraryDefault;

    ProgressionRegistrar(@Nonnull String owner, boolean libraryDefault) {
        this.owner = owner;
        this.libraryDefault = libraryDefault;
    }

    /** Who everything registered through this registrar is attributed to. */
    @Nonnull
    public String owner() {
        return owner;
    }

    /** True for the library's own parts, which are outranked by any consumer and never outrank one. */
    public boolean isLibraryDefault() {
        return libraryDefault;
    }

    // ==================== one-slot, quest side ====================

    /** Where quest state lives for every consumer on this server. */
    @Nonnull
    public ProgressionRegistrar questStore(@Nonnull QuestProgressStore store) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.QUEST_STORE, this, store);
        return this;
    }

    /** How to ask whether a player is carrying something. */
    @Nonnull
    public ProgressionRegistrar questPossession(@Nonnull QuestPossessionProbe probe) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.QUEST_POSSESSION, this, probe);
        return this;
    }

    /** How to actually take hand-in items. */
    @Nonnull
    public ProgressionRegistrar questInventory(@Nonnull QuestInventoryConsumer consumer) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.QUEST_INVENTORY, this, consumer);
        return this;
    }

    /** How a subject becomes the context a factor provider reads, on the quest side. */
    @Nonnull
    public ProgressionRegistrar questFactorContext(@Nonnull Function<Subject, FactorContext> context) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.QUEST_FACTOR_CONTEXT, this, context);
        return this;
    }

    /** The quest naming seam. */
    @Nonnull
    public ProgressionRegistrar questI18n(@Nonnull QuestI18n i18n) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.QUEST_I18N, this, i18n);
        return this;
    }

    /** What to publish around a mutating quest call; see {@link ProgressionCallScope}. */
    @Nonnull
    public ProgressionRegistrar questScope(@Nonnull ProgressionCallScope scope) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.QUEST_SCOPE, this, scope);
        return this;
    }

    // ==================== one-slot, achievement side ====================

    /** Where achievement state lives for every consumer on this server. */
    @Nonnull
    public ProgressionRegistrar achievementStore(@Nonnull AchievementProgressStore store) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.ACHIEVEMENT_STORE, this, store);
        return this;
    }

    /** How a subject becomes the context a factor provider reads, on the achievement side. */
    @Nonnull
    public ProgressionRegistrar achievementFactorContext(
            @Nonnull Function<Subject, FactorContext> context) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.ACHIEVEMENT_FACTOR_CONTEXT, this, context);
        return this;
    }

    /** What to publish around a mutating achievement call. */
    @Nonnull
    public ProgressionRegistrar achievementScope(@Nonnull ProgressionCallScope scope) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.ACHIEVEMENT_SCOPE, this, scope);
        return this;
    }

    // ==================== one-slot, shared ====================

    /** How a player becomes the subject the active stores understand. */
    @Nonnull
    public ProgressionRegistrar subjects(@Nonnull ProgressionSubjectSource source) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.SUBJECTS, this, source);
        return this;
    }

    /**
     * The factor vocabulary both engines read a standing value through.
     *
     * <p>SEALED: the engines build their standing-value probe from a concrete registry once, inside
     * their own constructors, so unlike every other slot this one cannot be forwarded and cannot be
     * changed after the runtime is built.
     */
    @Nonnull
    public ProgressionRegistrar factors(@Nonnull FactorRegistry factors) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.FACTORS, this, factors);
        return this;
    }

    /** Where a failed reward's replayable command goes for a later attempt. */
    @Nonnull
    public ProgressionRegistrar rewardRetryQueue(@Nonnull BiConsumer<Subject, String> queue) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.REWARD_RETRY_QUEUE, this, queue);
        return this;
    }

    /** Where the runtime's warnings go. */
    @Nonnull
    public ProgressionRegistrar warn(@Nonnull Consumer<String> warn) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.WARN, this, warn);
        return this;
    }

    // ==================== one-slot, sealed scalars ====================

    /** Which matching dialect quests run. SEALED: a build-time final on the engine. */
    @Nonnull
    public ProgressionRegistrar questMatchFlavor(@Nonnull MatchFlavor flavor) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.QUEST_MATCH_FLAVOR, this, flavor);
        return this;
    }

    /** Which matching dialect achievements run. SEALED. */
    @Nonnull
    public ProgressionRegistrar achievementMatchFlavor(@Nonnull MatchFlavor flavor) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.ACHIEVEMENT_MATCH_FLAVOR, this, flavor);
        return this;
    }

    /** How many quests a player may pin. SEALED. */
    @Nonnull
    public ProgressionRegistrar maxTrackedQuests(int max) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.MAX_TRACKED, this, Integer.valueOf(max));
        return this;
    }

    /** How many quests a player may carry at once, 0 for no limit. SEALED. */
    @Nonnull
    public ProgressionRegistrar maxActiveQuests(int max) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.MAX_ACTIVE, this, Integer.valueOf(max));
        return this;
    }

    /** How many achievements a player may pin. SEALED. */
    @Nonnull
    public ProgressionRegistrar maxPinnedAchievements(int max) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.MAX_PINNED, this, Integer.valueOf(max));
        return this;
    }

    // ==================== contributions ====================

    /** Add this mod's say on accepting, seeing and being paid for a quest. Every gate applies. */
    @Nonnull
    public ProgressionRegistrar questGates(@Nonnull QuestGates gates) {
        ProgressionRuntime.addQuestGates(this, gates);
        return this;
    }

    /** Add this mod's say on progressing, unlocking and being paid for an achievement. */
    @Nonnull
    public ProgressionRegistrar achievementGates(@Nonnull AchievementGates gates) {
        ProgressionRuntime.addAchievementGates(this, gates);
        return this;
    }

    /** Watch every tapped progress event, whether or not any content wanted it. */
    @Nonnull
    public ProgressionRegistrar dispatchTap(@Nonnull ProgressDispatchTap tap) {
        ProgressionRuntime.addDispatchTap(this, tap);
        return this;
    }

    /** Answer for what this mod's content is CALLED, for a surface with no catalogue of its own. */
    @Nonnull
    public ProgressionRegistrar textSource(@Nonnull ProgressionTextSource source) {
        ProgressionRuntime.addTextSource(this, source);
        return this;
    }

    // ==================== keyed replacement ====================

    /**
     * Declare that this mod's own event systems fire {@code objectiveKindId}, so the library's
     * generic producer for it stands down.
     *
     * <p>State it EXPLICITLY, one kind at a time, and never derive the set from what content is
     * allowed to author: those are different questions, and deriving it stands a producer down for a
     * kind nobody fires, which stops progress with nothing logged.
     */
    @Nonnull
    public ProgressionRegistrar producesKind(@Nonnull String objectiveKindId) {
        ProgressionRuntime.claimKind(this, objectiveKindId);
        return this;
    }

    /** {@link #producesKind} for several at once. */
    @Nonnull
    public ProgressionRegistrar producesKinds(@Nonnull Collection<String> objectiveKindIds) {
        for (String kindId : objectiveKindIds) {
            ProgressionRuntime.claimKind(this, kindId);
        }
        return this;
    }

}
