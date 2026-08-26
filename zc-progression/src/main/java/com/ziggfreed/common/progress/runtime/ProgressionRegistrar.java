package com.ziggfreed.common.progress.runtime;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;

import javax.annotation.Nonnull;

import com.ziggfreed.common.achievement.AchievementGates;
import com.ziggfreed.common.achievement.AchievementProgressStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
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
 * <p><b>Two shapes of registration, and the difference matters.</b>
 * <ul>
 *   <li><b>One-slot</b> - somebody's store, somebody's subject source. Exactly one answer is
 *   possible, so a consumer outranks a library default silently, and a SECOND consumer trying to
 *   take a slot another consumer already holds is REFUSED and named: two mods each wanting their own
 *   quest store is unresolvable, and quietly picking one is the same double-tracking failure one
 *   level up.
 *   <li><b>Contribution</b> - gates, system gates, taps, moment listeners, kill attributions, kill
 *   qualifiers, text sources. Every registration applies; gates AND, taps and listeners fan out,
 *   attributions, qualifiers and text sources answer in order.
 * </ul>
 *
 * <p><b>CONTENT is not registered here at all, and needs no claim.</b> Every reader folds the whole
 * shared store and publishes what it folded; the layers merge with library defaults first and
 * consumers after, so a consumer's version of an id silently replaces the library's. Rank settles
 * it, which is why there is no content-ownership method here at all.
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

    /** How many quests a player may pin. SEALED. */
    @Nonnull
    public ProgressionRegistrar maxTrackedQuests(int max) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.MAX_TRACKED, this, Integer.valueOf(max));
        return this;
    }

    /** How many quests a player may carry at once, 0 for no limit. */
    @Nonnull
    public ProgressionRegistrar maxActiveQuests(int max) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.MAX_ACTIVE, this, Integer.valueOf(max));
        return this;
    }

    /**
     * The same cap read LIVE, for a consumer whose limit is an owner config value a reload moves.
     *
     * <p>What the consumer supplies is a NUMBER; the refusal built on it - the engine's own
     * {@code log_full} - stays the engine's, so there is one place that decides a quest log is full
     * rather than one per consumer.
     */
    @Nonnull
    public ProgressionRegistrar maxActiveQuests(@Nonnull IntSupplier max) {
        ProgressionRuntime.putSlot(ProgressionRuntime.Slots.MAX_ACTIVE, this, max);
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

    /**
     * Add this mod's owner switch for whether a SYSTEM is on for a player at all.
     *
     * <p>Every gate applies and they AND, so any refusal wins and registration order cannot change
     * the answer; with none registered every system is open. It is not a producer claim - see
     * {@link ProgressionSystemGate} for what a refusal does and does not cost.
     */
    @Nonnull
    public ProgressionRegistrar systemGate(@Nonnull ProgressionSystemGate gate) {
        ProgressionRuntime.addSystemGate(this, gate);
        return this;
    }

    /**
     * Add this mod's REACTION to a lifecycle moment - a toast, a jingle, a broadcast, a command.
     *
     * <p>Every registered hook is called on every moment, each inside its own guard, so registration
     * order is not a precedence and nothing here can mark a moment as already handled. A hook
     * registered after the engines were built still fires, because they call through a live
     * forwarder over whatever is registered right now.
     */
    @Nonnull
    public ProgressionRegistrar feedbackHook(@Nonnull ProgressionFeedbackHook hook) {
        ProgressionRuntime.addFeedbackHook(this, hook);
        return this;
    }

    /** Watch every tapped progress event, whether or not any content wanted it. */
    @Nonnull
    public ProgressionRegistrar dispatchTap(@Nonnull ProgressDispatchTap tap) {
        ProgressionRuntime.addDispatchTap(this, tap);
        return this;
    }

    /**
     * Add this mod's REACTION to a produced moment - a payout, a lifetime counter, a bonus drop.
     *
     * <p>Every registered listener is called on every moment any producer fires, each inside its
     * own guard, BEFORE either engine is asked about it: a player with no quest subject and a server
     * with a system switched off still get their reactions. Registration order is not a precedence
     * and nothing here can refuse a moment; see {@link MomentListener} for what a listener is not.
     */
    @Nonnull
    public ProgressionRegistrar momentListener(@Nonnull MomentListener listener) {
        ProgressionRuntime.addMomentListener(this, listener);
        return this;
    }

    /**
     * Add this mod's answer to "this non-player attacker acts for that player", so a kill by a
     * turret, a summon or a pet this mod spawned produces the moment for its owner.
     *
     * <p>Every registered attribution is asked in registration order and the first non-null answer
     * stands; one that throws is skipped with a warn. See {@link KillAttribution}.
     */
    @Nonnull
    public ProgressionRegistrar killAttribution(@Nonnull KillAttribution attribution) {
        ProgressionRuntime.addKillAttribution(this, attribution);
        return this;
    }

    /**
     * Add this mod's answer to "this killed entity carries that qualifier", so a kill moment can
     * carry a qualifier - e.g. a difficulty tier a companion mod attributes to the mobs it scales -
     * and content authoring that qualifier on a kill criterion matches.
     *
     * <p>Every registered qualifier is asked in registration order and the first non-null answer
     * stands; one that throws is skipped with a warn, and the kill still fires (unqualified when no
     * answer survives). See {@link KillQualifier}.
     */
    @Nonnull
    public ProgressionRegistrar killQualifier(@Nonnull KillQualifier qualifier) {
        ProgressionRuntime.addKillQualifier(this, qualifier);
        return this;
    }

    /** Answer for what this mod's content is CALLED, for a surface with no catalogue of its own. */
    @Nonnull
    public ProgressionRegistrar textSource(@Nonnull ProgressionTextSource source) {
        ProgressionRuntime.addTextSource(this, source);
        return this;
    }

}
