package com.ziggfreed.common.progress.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.AchievementGates;
import com.ziggfreed.common.achievement.AchievementMilestone;
import com.ziggfreed.common.achievement.AchievementProgressStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.progress.MatchFlavor;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.ProgressDispatchTap;
import com.ziggfreed.common.progress.gate.GateKindRegistry;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestGates;
import com.ziggfreed.common.quest.QuestI18n;
import com.ziggfreed.common.quest.QuestInventoryConsumer;
import com.ziggfreed.common.quest.QuestPossessionProbe;
import com.ziggfreed.common.quest.QuestProgressStore;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * THE quest and achievement runtime a server runs: one engine pair, one store, one vocabulary set,
 * however many mods contribute to it.
 *
 * <p><b>Why one, and not one per mod.</b> Two runtimes over two stores is double-tracking: the same
 * block break advances two copies of the same objective, the player sees one of them, and which one
 * is a coin flip. Worse, the half a surface reads is not always the half that can pay out, so a
 * hand-in reads as ready and does nothing. Making the runtime SHARED and the parts REGISTERED means
 * that failure can exist at most once on a server rather than once per mod, and in practice not at
 * all - the conflict policy below turns it into a startup line instead of a mystery in play.
 *
 * <p><b>The shape of a boot.</b> The library registers its own defaults at
 * {@link #defaults(String) library-default rank}; each consumer registers its own parts at
 * {@link #registrar(String) consumer rank} from its plugin setup; the first read or the first player
 * ready calls {@link #ensureBuilt()}, which seals the sealed parts, composes the contributions,
 * builds both engines and logs one diagnostic naming who answered for what. Nothing decides whether
 * to run: there is one runtime, always, and the only question is which parts it was built over.
 *
 * <p><b>The engines are built once and never rebuilt.</b> Everything the engines reach the outside
 * world through is a forwarder over {@link ProgressionParts}, so a registration arriving late is
 * honoured on the next call rather than needing a rebuild - and every reference anybody cached stays
 * valid, which is what one shared instance has to guarantee.
 */
public final class ProgressionRuntime {

    /** Which one-slot part a registration is for, and what happens when it arrives late. */
    enum Slots {

        QUEST_STORE("quest store", LatePolicy.LIVE_LOUD),
        ACHIEVEMENT_STORE("achievement store", LatePolicy.LIVE_LOUD),
        QUEST_POSSESSION("quest possession probe", LatePolicy.LIVE),
        QUEST_INVENTORY("quest inventory consumer", LatePolicy.LIVE),
        QUEST_FACTOR_CONTEXT("quest factor context", LatePolicy.LIVE),
        ACHIEVEMENT_FACTOR_CONTEXT("achievement factor context", LatePolicy.LIVE),
        QUEST_I18N("quest i18n", LatePolicy.LIVE),
        QUEST_SCOPE("quest call scope", LatePolicy.LIVE),
        ACHIEVEMENT_SCOPE("achievement call scope", LatePolicy.LIVE),
        SUBJECTS("subject source", LatePolicy.LIVE),
        // Refused late for one honest reason: whether a queue EXISTS is read once, when the reward
        // pass is wired, and a queue that arrived afterwards would have failed rewards reported as
        // safely queued while nothing ever replayed them.
        REWARD_RETRY_QUEUE("reward retry queue", LatePolicy.REFUSE_WARN),
        WARN("warn sink", LatePolicy.LIVE),
        FACTORS("factor vocabulary", LatePolicy.REFUSE_SEVERE),
        QUEST_MATCH_FLAVOR("quest match flavor", LatePolicy.REFUSE_SEVERE),
        ACHIEVEMENT_MATCH_FLAVOR("achievement match flavor", LatePolicy.REFUSE_SEVERE),
        MAX_TRACKED("max tracked quests", LatePolicy.REFUSE_WARN),
        // Read LIVE, because the cap it names is an owner's config value: a reload has to move it,
        // and an engine holding the number it was built with would refuse against a stale one.
        MAX_ACTIVE("max active quests", LatePolicy.LIVE),
        MAX_PINNED("max pinned achievements", LatePolicy.REFUSE_WARN);

        private final String label;
        private final LatePolicy latePolicy;

        Slots(@Nonnull String label, @Nonnull LatePolicy latePolicy) {
            this.label = label;
            this.latePolicy = latePolicy;
        }
    }

    /** What a registration arriving AFTER the runtime was built does. */
    private enum LatePolicy {

        /** Applied at once through the forwarders, silently. */
        LIVE,

        /** Applied at once, and said so: it moves where a player's data lives. */
        LIVE_LOUD,

        /** Refused: the engines read it once, at build. Loud, because it will not behave. */
        REFUSE_SEVERE,

        /** Refused, but a cosmetic cap rather than a semantic one. */
        REFUSE_WARN
    }

    /** One resolved slot: what is in it, who put it there, and at which rank. */
    private record Held(@Nonnull Object value, @Nonnull String owner, boolean libraryDefault) {
    }

    // ==================== registration state ====================

    private static final Map<Slots, Held> SLOTS = new LinkedHashMap<>();

    private static final List<Contribution<QuestGates>> QUEST_GATES = new ArrayList<>();
    private static final List<Contribution<AchievementGates>> ACHIEVEMENT_GATES = new ArrayList<>();
    private static final List<Contribution<ProgressDispatchTap>> TAPS = new ArrayList<>();
    private static final List<Contribution<ProgressionTextSource>> TEXT_SOURCES = new ArrayList<>();

    private record Contribution<T>(@Nonnull String owner, @Nonnull T value) {
    }

    /** Every registrar that registered anything, in first-registration order. */
    private static final Map<String, Boolean> REGISTRARS = new LinkedHashMap<>();

    private static final ProducerClaims CLAIMS = new ProducerClaims();

    private static final ContentLayers<Quest> QUEST_LAYERS =
            new ContentLayers<>(Quest::id, "quest");
    private static final ContentLayers<Achievement> ACHIEVEMENT_LAYERS =
            new ContentLayers<>(Achievement::id, "achievement");
    private static final ContentLayers<AchievementMilestone> MILESTONE_LAYERS =
            new ContentLayers<>(m -> Integer.toString(m.threshold()), "milestone");

    // ==================== the shared vocabularies ====================

    private static volatile ObjectiveKindRegistry objectiveKinds =
            new ObjectiveKindRegistry("progression-objective");
    private static volatile GateKindRegistry gateKinds = new GateKindRegistry("progression-gate");

    // ==================== the built runtime ====================

    private static final AtomicBoolean BUILT = new AtomicBoolean();

    private static volatile ProgressionParts parts = ProgressionParts.EMPTY;

    @Nullable private static volatile QuestEngine questEngine;

    @Nullable private static volatile AchievementEngine achievementEngine;

    private ProgressionRuntime() {
    }

    // ==================== registration ====================

    /** A registrar attributing everything it registers to {@code owner}, at CONSUMER rank. */
    @Nonnull
    public static ProgressionRegistrar registrar(@Nonnull String owner) {
        return newRegistrar(owner, false);
    }

    /**
     * A registrar at LIBRARY-DEFAULT rank: outranked by any consumer, and it never outranks one, so
     * load order cannot decide who wins.
     */
    @Nonnull
    public static ProgressionRegistrar defaults(@Nonnull String owner) {
        return newRegistrar(owner, true);
    }

    /** Everyone who has registered anything, in registration order. */
    @Nonnull
    public static synchronized List<String> registrars() {
        return List.copyOf(REGISTRARS.keySet());
    }

    /**
     * Did {@code owner} register at LIBRARY-DEFAULT rank? Nobody who never registered has, so an
     * unknown name reads false.
     *
     * <p>Worth asking out loud, because rank is what settles overlapping CONTENT: a consumer's
     * layer replaces a library default's entry for the same id silently, while two layers at the
     * same rank are a clash nothing can resolve. A mod checking that it registered the way it meant
     * to is checking the one thing that decides whose reading of a shared file the player gets.
     */
    public static synchronized boolean isLibraryDefault(@Nonnull String owner) {
        return Boolean.TRUE.equals(REGISTRARS.get(normalizeOwner(owner)));
    }

    @Nonnull
    private static synchronized ProgressionRegistrar newRegistrar(@Nonnull String owner,
                                                                  boolean libraryDefault) {
        String name = normalizeOwner(owner);
        REGISTRARS.putIfAbsent(name, Boolean.valueOf(libraryDefault));
        return new ProgressionRegistrar(name, libraryDefault);
    }

    /**
     * The one-slot conflict policy, in one place.
     *
     * <p>A library default takes an empty slot and never takes an occupied one; a consumer takes a
     * slot a default holds; a consumer re-registering the SAME instance is a silent no-op (a reload
     * does that every time). A consumer registering a DIFFERENT instance over another consumer is
     * REFUSED and named at SEVERE - that conflict has no correct resolution, and choosing one
     * silently is the failure this whole design exists to prevent.
     */
    static synchronized void putSlot(@Nonnull Slots slot, @Nonnull ProgressionRegistrar registrar,
                                     @Nonnull Object value) {
        Held current = SLOTS.get(slot);
        String owner = registrar.owner();
        boolean libraryDefault = registrar.isLibraryDefault();

        if (current != null && sameValue(current.value(), value)) {
            return;
        }
        if (current != null && libraryDefault) {
            return;
        }
        if (current != null && !current.libraryDefault()) {
            SafeLog.severe("[progression] '" + owner + "' cannot take the " + slot.label
                    + ": '" + current.owner() + "' already holds it, and there is only one."
                    + " The one from '" + current.owner() + "' stands for this boot");
            return;
        }
        if (BUILT.get() && !applyLate(slot, current, owner)) {
            return;
        }
        SLOTS.put(slot, new Held(value, owner, libraryDefault));
        rederive();
    }

    /**
     * Apply a registration that arrived after the engines were built, per the slot's own policy.
     *
     * @return true when the caller should go on and store it
     */
    private static boolean applyLate(@Nonnull Slots slot, @Nullable Held current,
                                     @Nonnull String owner) {
        String previous = current == null ? "nobody" : current.owner();
        switch (slot.latePolicy) {
            case LIVE -> {
                return true;
            }
            case LIVE_LOUD -> {
                SafeLog.warn("[progression] LATE " + slot.label + ": '" + owner + "' replaces '"
                        + previous + "' after the runtime was built, which moves where player data"
                        + " for it lives");
                return true;
            }
            case REFUSE_SEVERE -> SafeLog.severe("[progression] LATE " + slot.label + " from '" + owner
                    + "' is refused: both engines read it once, when they were built, so '" + previous
                    + "' stands for this boot");
            case REFUSE_WARN -> SafeLog.warn("[progression] LATE " + slot.label + " from '" + owner
                    + "' is refused: it is fixed when the engines are built, so '" + previous
                    + "' stands for this boot");
        }
        return false;
    }

    /** Identity for a seam, equality for a value - the same rule the shared registry ledger keeps. */
    private static boolean sameValue(@Nonnull Object current, @Nonnull Object incoming) {
        if (current == incoming) {
            return true;
        }
        return (current instanceof Integer || current instanceof Enum<?>) && current.equals(incoming);
    }

    static synchronized void addQuestGates(@Nonnull ProgressionRegistrar registrar,
                                           @Nonnull QuestGates gates) {
        if (addContribution(QUEST_GATES, registrar.owner(), gates)) {
            rederive();
        }
    }

    static synchronized void addAchievementGates(@Nonnull ProgressionRegistrar registrar,
                                                 @Nonnull AchievementGates gates) {
        if (addContribution(ACHIEVEMENT_GATES, registrar.owner(), gates)) {
            rederive();
        }
    }

    static synchronized void addDispatchTap(@Nonnull ProgressionRegistrar registrar,
                                            @Nonnull ProgressDispatchTap tap) {
        if (addContribution(TAPS, registrar.owner(), tap)) {
            rederive();
        }
    }

    static synchronized void addTextSource(@Nonnull ProgressionRegistrar registrar,
                                           @Nonnull ProgressionTextSource source) {
        if (addContribution(TEXT_SOURCES, registrar.owner(), source)) {
            rederive();
        }
    }

    private static <T> boolean addContribution(@Nonnull List<Contribution<T>> into,
                                               @Nonnull String owner, @Nonnull T value) {
        for (Contribution<T> existing : into) {
            if (existing.value() == value) {
                return false;
            }
        }
        into.add(new Contribution<>(owner, value));
        return true;
    }

    static synchronized void claimKind(@Nonnull ProgressionRegistrar registrar,
                                       @Nonnull String objectiveKindId) {
        String clash = CLAIMS.claimKind(objectiveKindId, registrar.owner());
        if (clash != null) {
            SafeLog.warn("[progression] '" + registrar.owner() + "' also claims to fire '"
                    + objectiveKindId + "', which '" + clash + "' already claims; both will fire it");
        }
    }

    // ==================== the shared vocabularies ====================

    /** The ONE objective vocabulary both engines dispatch against. Contribute into it, never swap it. */
    @Nonnull
    public static ObjectiveKindRegistry objectiveKinds() {
        return objectiveKinds;
    }

    /** The ONE reward vocabulary. Shared with every other engine in this library that grants. */
    @Nonnull
    public static RewardKindRegistry rewardKinds() {
        return RewardKinds.shared();
    }

    /** The ONE gate vocabulary a content gate evaluator reads. */
    @Nonnull
    public static GateKindRegistry gateKinds() {
        return gateKinds;
    }

    /**
     * The ONE factor vocabulary registered on this server, or null when nobody registered one.
     *
     * <p>Read LIVE by whatever answers a {@code Requires} block, so a consumer's vocabulary feeds
     * the same evaluator every gated surface consults rather than each surface building its own -
     * two vocabularies over one requirement model is how a shop row and a quest end up disagreeing
     * about the same player's mining level.
     */
    @Nullable
    public static FactorRegistry factors() {
        return slot(Slots.FACTORS);
    }

    /**
     * The active quest store, WITHOUT building the engines.
     *
     * <p>The forwarder, so a late store registration is honoured; and no {@code ensureBuilt}, so a
     * gate evaluated during another mod's setup reads player records without sealing the runtime
     * before every mod has registered into it.
     */
    @Nonnull
    public static QuestProgressStore questStore() {
        return ProgressionParts.QUEST_STORE;
    }

    /**
     * The context a REQUIREMENT is read against for one subject.
     *
     * <p>A {@code Requires} block is asked about a PLAYER rather than about an engine, so there is
     * one answer here even though the two engines each register their own. The quest reading is
     * asked first and the achievement reading covers the case it cannot answer, which is what a
     * consumer whose two engine subjects are shaped differently needs; a consumer registering one
     * function for both - the ordinary case - sees exactly that function either way.
     */
    @Nonnull
    public static FactorContext gateFactorContext(@Nonnull Subject subject) {
        ProgressionParts live = parts;
        FactorContext primary = live.questFactorContext().apply(subject);
        if (informative(primary)) {
            return primary;
        }
        FactorContext secondary = live.achievementFactorContext().apply(subject);
        if (informative(secondary)) {
            return secondary;
        }
        return primary != null ? primary : FactorContext.builder().build();
    }

    /** Does this context carry anything a provider could read a player out of? */
    private static boolean informative(@Nullable FactorContext ctx) {
        return ctx != null && (ctx.payload() != null || ctx.hasLiveSubject());
    }

    /**
     * How many quests a player may carry at once, read LIVE. Zero means no cap at all, which is what
     * makes the engine's own log-full refusal unreachable rather than switched off.
     */
    public static int maxActiveQuests() {
        Held held = SLOTS.get(Slots.MAX_ACTIVE);
        if (held == null) {
            return 0;
        }
        Object value = held.value();
        return value instanceof IntSupplier supplier ? Math.max(0, supplier.getAsInt())
                : ((Integer) value).intValue();
    }

    // ==================== the resolved parts a surface reads back ====================

    /** How a player becomes the subject the ACTIVE stores understand. Never null. */
    @Nonnull
    public static ProgressionSubjectSource subjects() {
        return parts.subjects();
    }

    /** What to publish around a mutating QUEST call. Never null; {@code DIRECT} when nobody asked. */
    @Nonnull
    public static ProgressionCallScope questScope() {
        return parts.questScope();
    }

    /** What to publish around a mutating ACHIEVEMENT call. */
    @Nonnull
    public static ProgressionCallScope achievementScope() {
        return parts.achievementScope();
    }

    /**
     * Who registered a QUEST gate, in registration order. An admin read, and the one a boot check
     * asks: a progression engine with an empty list is an engine nothing gates.
     */
    @Nonnull
    public static synchronized List<String> questGateOwners() {
        return ownerNames(QUEST_GATES);
    }

    /** Who registered an ACHIEVEMENT gate, on the same terms. */
    @Nonnull
    public static synchronized List<String> achievementGateOwners() {
        return ownerNames(ACHIEVEMENT_GATES);
    }

    @Nonnull
    private static <T> List<String> ownerNames(@Nonnull List<Contribution<T>> contributions) {
        List<String> names = new ArrayList<>(contributions.size());
        for (Contribution<T> contribution : contributions) {
            names.add(contribution.owner());
        }
        return List.copyOf(names);
    }

    /** Every registered text source, in registration order; first non-null wins. */
    @Nonnull
    public static List<ProgressionTextSource> textSources() {
        return parts.textSources();
    }

    /** The live snapshot every forwarder reads. */
    @Nonnull
    static ProgressionParts parts() {
        return parts;
    }

    // ==================== the engines ====================

    /** THE quest engine. Builds the runtime on first read, so it is never null. */
    @Nonnull
    public static QuestEngine quests() {
        ensureBuilt();
        QuestEngine engine = questEngine;
        return engine != null ? engine : QuestEngine.builder().build();
    }

    /** THE achievement engine. Builds the runtime on first read, so it is never null. */
    @Nonnull
    public static AchievementEngine achievements() {
        ensureBuilt();
        AchievementEngine engine = achievementEngine;
        return engine != null ? engine : AchievementEngine.builder().build();
    }

    /** Have the engines been built yet? */
    public static boolean isBuilt() {
        return BUILT.get();
    }

    // ==================== content ====================

    /** Replace {@code owner}'s whole quest layer and recompose the catalogue. */
    public static void publishQuests(@Nonnull String owner, @Nonnull Collection<Quest> layer) {
        publish(QUEST_LAYERS, owner, layer);
    }

    /** Replace {@code owner}'s whole achievement layer and recompose the catalogue. */
    public static void publishAchievements(@Nonnull String owner,
                                           @Nonnull Collection<Achievement> layer) {
        publish(ACHIEVEMENT_LAYERS, owner, layer);
    }

    /** Replace {@code owner}'s whole points-milestone layer. */
    public static void publishMilestones(@Nonnull String owner,
                                         @Nonnull List<AchievementMilestone> layer) {
        publish(MILESTONE_LAYERS, owner, layer);
    }

    private static synchronized <T> void publish(@Nonnull ContentLayers<T> layers,
                                                 @Nonnull String owner,
                                                 @Nonnull Collection<T> layer) {
        String name = normalizeOwner(owner);
        layers.publish(name, Boolean.TRUE.equals(REGISTRARS.get(name)), layer);
        if (BUILT.get()) {
            applyContent();
        }
    }

    /** Push every composed layer into the engines. Called at build and on every later publish. */
    private static void applyContent() {
        QuestEngine quests = questEngine;
        AchievementEngine achievements = achievementEngine;
        Consumer<String> warn = parts.warn();
        if (quests != null) {
            quests.setQuests(QUEST_LAYERS.compose(warn));
        }
        if (achievements != null) {
            achievements.setAchievements(ACHIEVEMENT_LAYERS.compose(warn));
            achievements.setMilestones(MILESTONE_LAYERS.compose(warn));
        }
    }

    // ==================== the claim a producer reads ====================

    /**
     * Is {@code kindId} still the library's own generic producer's to fire? A producer's first line.
     * Registration is final at setup, so this is safe to read from an event handler.
     */
    public static boolean defaultProducesKind(@Nonnull String kindId) {
        return CLAIMS.defaultProduces(kindId);
    }

    // A content namespace claim used to sit here beside the producer claim, so the library's own
    // default source could drop every definition a consumer said it would fold itself. RANK does
    // that job on its own and does it better, which is why there is nothing to claim: every reader
    // folds the whole store, both publish what they folded, and ContentLayers merges LIBRARY
    // DEFAULTS FIRST, then consumers in registration order. A consumer's entry therefore replaces
    // the library's for the same id, SILENTLY - that branch is written out in ContentLayers.compose
    // as the claim contract working - so a definition a consumer folded into something richer wins
    // wherever the two meet, and a definition only the library folded still reaches the engines.
    // Only two CONSUMERS landing on one id is a real clash, and that is the case compose warns
    // about, naming both. Milestones resolve the same way, rung by rung, by threshold.

    /**
     * Is the LIBRARY DEFAULT quest store still the active one? Final at setup, so it is safe to read
     * before the runtime is built - which is what the player-connect hop needs, since a component
     * type has to be stamped onto a player long before anything reads it.
     */
    public static boolean usesDefaultStores() {
        Held held = SLOTS.get(Slots.QUEST_STORE);
        return held == null || held.libraryDefault();
    }

    // ==================== boot ====================

    /**
     * Seal the sealed parts, build both engines over the forwarders, apply every published layer, and
     * log the one diagnostic. Idempotent, and safe to call from anywhere - the first caller wins,
     * whether that is the first player becoming ready or the first read of either engine.
     */
    public static void ensureBuilt() {
        if (BUILT.get()) {
            return;
        }
        buildOnce();
    }

    private static synchronized void buildOnce() {
        if (!BUILT.compareAndSet(false, true)) {
            return;
        }
        try {
            rederive();
            FactorRegistry factors = slot(Slots.FACTORS);
            // Passed through only when a queue really is registered: the reward pass treats a
            // non-null queue as "this will be retried", so a forwarder over nothing would report a
            // lost reward as safely queued.
            BiConsumer<Subject, String> retryQueue =
                    SLOTS.containsKey(Slots.REWARD_RETRY_QUEUE)
                            ? ProgressionParts.REWARD_RETRY_QUEUE : null;

            QuestEngine quests = QuestEngine.builder()
                    .objectiveKinds(objectiveKinds)
                    .rewardKinds(RewardKinds.shared())
                    .store(ProgressionParts.QUEST_STORE)
                    .gates(ProgressionParts.QUEST_GATES)
                    .matchFlavor(slotOr(Slots.QUEST_MATCH_FLAVOR, MatchFlavor.STRICT))
                    .possessionProbe(ProgressionParts.POSSESSION)
                    .inventoryConsumer(ProgressionParts.INVENTORY)
                    .dispatchTap(ProgressionParts.TAP)
                    .factors(factors)
                    .factorContext(ProgressionParts.QUEST_FACTOR_CONTEXT)
                    .i18n(ProgressionParts.QUEST_I18N)
                    .rewardRetryQueue(retryQueue)
                    .warn(ProgressionParts.WARN)
                    .maxTracked(intSlot(Slots.MAX_TRACKED, 5))
                    .maxActive(ProgressionRuntime::maxActiveQuests)
                    .build();

            AchievementEngine achievements = AchievementEngine.builder()
                    .objectiveKinds(objectiveKinds)
                    .rewardKinds(RewardKinds.shared())
                    .store(ProgressionParts.ACHIEVEMENT_STORE)
                    .gates(ProgressionParts.ACHIEVEMENT_GATES)
                    .matchFlavor(slotOr(Slots.ACHIEVEMENT_MATCH_FLAVOR, MatchFlavor.LENIENT))
                    .dispatchTap(ProgressionParts.TAP)
                    .factors(factors)
                    .factorContext(ProgressionParts.ACHIEVEMENT_FACTOR_CONTEXT)
                    .rewardRetryQueue(retryQueue)
                    .warn(ProgressionParts.WARN)
                    .maxPinned(intSlot(Slots.MAX_PINNED, 5))
                    .build();

            questEngine = quests;
            achievementEngine = achievements;
            applyContent();
            logDiagnostic();
        } catch (Throwable t) {
            SafeLog.warn("[progression] the shared runtime could not be built", t);
        }
    }

    /** Drop every registration, claim, layer and engine. Test reset, and the shutdown path. */
    public static synchronized void resetForTests() {
        SLOTS.clear();
        QUEST_GATES.clear();
        ACHIEVEMENT_GATES.clear();
        TAPS.clear();
        TEXT_SOURCES.clear();
        REGISTRARS.clear();
        CLAIMS.clear();
        QUEST_LAYERS.clear();
        ACHIEVEMENT_LAYERS.clear();
        MILESTONE_LAYERS.clear();
        objectiveKinds = new ObjectiveKindRegistry("progression-objective");
        gateKinds = new GateKindRegistry("progression-gate");
        questEngine = null;
        achievementEngine = null;
        parts = ProgressionParts.EMPTY;
        BUILT.set(false);
    }

    // ==================== internals ====================

    /** Rebuild the resolved snapshot from whatever is registered right now. */
    private static void rederive() {
        ProgressionParts empty = ProgressionParts.EMPTY;
        Consumer<String> warn = slotOr(Slots.WARN, ProgressionParts.DEFAULT_WARN);
        parts = new ProgressionParts(
                slotOr(Slots.QUEST_STORE, empty.questStore()),
                slotOr(Slots.ACHIEVEMENT_STORE, empty.achievementStore()),
                slotOr(Slots.QUEST_POSSESSION, QuestPossessionProbe.NONE),
                slotOr(Slots.QUEST_INVENTORY, QuestInventoryConsumer.NONE),
                slotOr(Slots.QUEST_I18N, QuestI18n.NONE),
                slotOr(Slots.QUEST_FACTOR_CONTEXT, ProgressionParts.EMPTY_FACTOR_CONTEXT),
                slotOr(Slots.ACHIEVEMENT_FACTOR_CONTEXT, ProgressionParts.EMPTY_FACTOR_CONTEXT),
                slotOr(Slots.SUBJECTS, ProgressionParts.NO_SUBJECTS),
                slotOr(Slots.QUEST_SCOPE, ProgressionCallScope.DIRECT),
                slotOr(Slots.ACHIEVEMENT_SCOPE, ProgressionCallScope.DIRECT),
                slot(Slots.REWARD_RETRY_QUEUE),
                warn,
                ProgressionParts.composeQuestGates(values(QUEST_GATES)),
                ProgressionParts.composeAchievementGates(values(ACHIEVEMENT_GATES)),
                ProgressionParts.composeTaps(values(TAPS), warn),
                ProgressionParts.freezeTextSources(values(TEXT_SOURCES)));
    }

    @Nonnull
    private static <T> List<T> values(@Nonnull List<Contribution<T>> contributions) {
        List<T> out = new ArrayList<>(contributions.size());
        for (Contribution<T> contribution : contributions) {
            out.add(contribution.value());
        }
        return out;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <T> T slot(@Nonnull Slots key) {
        Held held = SLOTS.get(key);
        return held == null ? null : (T) held.value();
    }

    @Nonnull
    @SuppressWarnings("unchecked")
    private static <T> T slotOr(@Nonnull Slots key, @Nonnull T fallback) {
        Held held = SLOTS.get(key);
        return held == null ? fallback : (T) held.value();
    }

    private static int intSlot(@Nonnull Slots key, int fallback) {
        Held held = SLOTS.get(key);
        return held == null ? fallback : ((Integer) held.value()).intValue();
    }

    @Nonnull
    private static String normalizeOwner(@Nonnull String owner) {
        return owner.isBlank() ? "unattributed" : owner.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The one boot diagnostic: six stable lines naming WHO answered for what.
     *
     * <p>At info, not debug, because it is the answer to "why is my quest not advancing" and that
     * question is asked from a log somebody already has rather than from a run they can redo. A
     * default-held slot renders as its owner name rather than the word "default", so a reader always
     * sees a name they can go and ask.
     */
    private static void logDiagnostic() {
        List<String> registrars = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : REGISTRARS.entrySet()) {
            registrars.add(entry.getKey() + (Boolean.TRUE.equals(entry.getValue()) ? " (default)" : ""));
        }
        SafeLog.info("[progression] runtime built over " + registrars.size() + " registrars: "
                + String.join(", ", registrars));
        SafeLog.info("[progression]   stores      quest=" + ownerOf(Slots.QUEST_STORE)
                + ", achievement=" + ownerOf(Slots.ACHIEVEMENT_STORE));
        SafeLog.info("[progression]   gates       quest=" + owners(QUEST_GATES)
                + ", achievement=" + owners(ACHIEVEMENT_GATES));

        Map<String, String> claimedKinds = CLAIMS.claimedKinds();
        Set<String> producible = defaultProducerKinds();
        List<String> producerLine = new ArrayList<>();
        for (Map.Entry<String, String> entry : claimedKinds.entrySet()) {
            producerLine.add(entry.getKey().toUpperCase(Locale.ROOT) + "=" + entry.getValue());
        }
        SafeLog.info("[progression]   producers   "
                + (producerLine.isEmpty() ? "none claimed" : String.join(", ", producerLine))
                + " (" + CLAIMS.claimedCount(producible) + " of " + producible.size()
                + " library defaults standing down)");
        SafeLog.info("[progression]   vocabulary  objective kinds=" + objectiveKinds.ids().size()
                + ", reward kinds=" + RewardKinds.shared().ids().size()
                + ", gate kinds=" + gateKinds.ids().size()
                + ", factors=" + ownerOf(Slots.FACTORS)
                + ", text sources=" + owners(TEXT_SOURCES));
        SafeLog.info("[progression]   content     quests=" + counts(QUEST_LAYERS)
                + ", achievements=" + counts(ACHIEVEMENT_LAYERS)
                + ", milestones=" + counts(MILESTONE_LAYERS));
    }

    /**
     * The kinds the library's own generic producers cover, so the diagnostic can say how many of them
     * stood down. Registered by whoever installs them; an empty set simply prints "0 of 0".
     */
    @Nonnull
    private static Set<String> defaultProducerKinds() {
        return new TreeSet<>(DEFAULT_PRODUCER_KINDS);
    }

    /** The normalized kind ids the library's own producers fire, filled in by whoever installs them. */
    private static final Set<String> DEFAULT_PRODUCER_KINDS = new TreeSet<>();

    /**
     * Tell the runtime which kinds the library's own generic producers cover, so the boot diagnostic
     * can report how many of them a consumer stood down. Purely a reporting fact.
     */
    public static synchronized void declareDefaultProducerKinds(@Nonnull Collection<String> kindIds) {
        for (String kindId : kindIds) {
            if (kindId != null && !kindId.isBlank()) {
                DEFAULT_PRODUCER_KINDS.add(kindId.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    @Nonnull
    private static String ownerOf(@Nonnull Slots slot) {
        Held held = SLOTS.get(slot);
        return held == null ? "nobody" : held.owner();
    }

    @Nonnull
    private static <T> String owners(@Nonnull List<Contribution<T>> contributions) {
        if (contributions.isEmpty()) {
            return "[]";
        }
        List<String> names = new ArrayList<>();
        for (Contribution<T> contribution : contributions) {
            names.add(contribution.owner());
        }
        return "[" + String.join(", ", names) + "]";
    }

    @Nonnull
    private static <T> String counts(@Nonnull ContentLayers<T> layers) {
        Map<String, Integer> counts = layers.counts();
        int total = 0;
        List<String> perOwner = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            total += entry.getValue().intValue();
            perOwner.add(entry.getKey() + " " + entry.getValue());
        }
        return total + (perOwner.isEmpty() ? "" : " (" + String.join(", ", perOwner) + ")");
    }
}
