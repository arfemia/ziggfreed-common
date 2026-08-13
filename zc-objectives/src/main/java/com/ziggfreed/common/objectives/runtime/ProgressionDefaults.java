package com.ziggfreed.common.objectives.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.asset.AchievementAssetStore;
import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.achievement.asset.AchievementCategoryConfig;
import com.ziggfreed.common.achievement.asset.AchievementDefinition;
import com.ziggfreed.common.achievement.asset.AchievementMilestoneConfig;
import com.ziggfreed.common.achievement.asset.AchievementPool;
import com.ziggfreed.common.achievement.asset.AchievementPoolValidator;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.factor.HytaleFactors;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.inventory.InventoryUtil;
import com.ziggfreed.common.objectives.producer.ZigBlockBreakProducer;
import com.ziggfreed.common.objectives.producer.ZigCraftProducer;
import com.ziggfreed.common.objectives.producer.ZigMobKillProducer;
import com.ziggfreed.common.objectives.producer.ZigPickupProducer;
import com.ziggfreed.common.objectives.store.ProgressHandle;
import com.ziggfreed.common.objectives.store.ProgressSubjects;
import com.ziggfreed.common.objectives.store.ZigAchievementStore;
import com.ziggfreed.common.objectives.store.ZigProgressComponent;
import com.ziggfreed.common.objectives.store.ZigQuestStore;
import com.ziggfreed.common.progress.MatchFlavor;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.progress.runtime.ProgressionRegistrar;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionTextSource;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestInventoryConsumer;
import com.ziggfreed.common.quest.QuestPossessionProbe;
import com.ziggfreed.common.quest.asset.AssetQuestGates;
import com.ziggfreed.common.quest.asset.QuestAssetStore;
import com.ziggfreed.common.quest.asset.QuestDefinition;
import com.ziggfreed.common.quest.asset.QuestPool;
import com.ziggfreed.common.quest.asset.QuestPoolValidator;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.NumberFormatter;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * The LIBRARY'S OWN parts of the shared progression runtime: the persisted component behind them,
 * the two stores, both halves of a hand-in, the factor vocabulary, the asset-driven gate, the folded
 * asset catalogues, and the text a surface reads them by.
 *
 * <p>Every one of them registers at {@link ProgressionRuntime#defaults(String) library-default rank},
 * so a consumer that brings its own is silently in charge and nothing here has to know that it did.
 * There is NO decision left: the runtime is always on, and the only question is which parts it ended
 * up built over. That is what the boot diagnostic prints.
 *
 * <p><b>What these defaults deliberately do NOT supply.</b> There is no reward retry queue: there is
 * no per-player retry store here, so a reward that fails to grant is reported rather than queued for
 * next connect. That is a choice; a consumer that has one registers it and the runtime picks it up.
 *
 * <p>Points MILESTONES are supplied, from the same place everything else here comes from: files. A
 * server with milestone assets gets its ladder published whether or not any mod wired one, and a
 * consumer publishing its own layer outranks this one rung by rung.
 *
 * <p><b>What still happens even when a consumer owns everything</b>, because it has to: the progress
 * component TYPE is registered and the four producer systems are registered, both at {@code setup()}.
 * A component type registered after a world loads cannot be read off entities saved carrying it, and
 * an ECS system is a setup-time registration. Nothing attaches the component and every producer
 * returns on its first line, so the cost is one map read per event.
 */
public final class ProgressionDefaults {

    /** The owner name these registrations are attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    /** How many quests a player may pin, unless a consumer says otherwise. */
    private static final int MAX_TRACKED = 5;

    /** How many achievements a player may pin, unless a consumer says otherwise. */
    private static final int MAX_PINNED = 5;

    /** The objective kinds the four producers below fire, for the boot diagnostic's stand-down count. */
    private static final Set<String> PRODUCER_KINDS = Set.of(ZigBlockBreakProducer.KIND,
            ZigMobKillProducer.KIND, ZigCraftProducer.KIND, ZigPickupProducer.KIND);

    /** The gate that reads a quest's own authored {@code Requires} block; held so it can be re-pooled. */
    @Nullable
    private static volatile AssetQuestGates assetGates;

    /** Registration is once per boot: a second pass would mint parts nothing is holding. */
    private static boolean registered;

    private ProgressionDefaults() {
    }

    // ==================== registration ====================

    /**
     * Register every default part. Call from the wiring root's {@code setup()}, before any consumer's
     * - though rank, not order, is what actually decides who wins.
     */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        try {
            FactorRegistry factors = new FactorRegistry("zc-objective-factor");
            HytaleFactors.registerInto(factors, OWNER);
            Function<Subject, FactorContext> factorContext = ProgressionDefaults::factorContextOf;

            GateEvaluator gate = GateEvaluator.builder()
                    .factors(factors)
                    .factorContext(factorContext)
                    .permissions(ProgressionDefaults::holdsPermission)
                    .build();
            AssetQuestGates gates = AssetQuestGates.of(gate);
            assetGates = gates;

            ProgressionRegistrar registrar = ProgressionRuntime.defaults(OWNER);
            registrar.questStore(ZigQuestStore.INSTANCE)
                    .achievementStore(ZigAchievementStore.INSTANCE)
                    .subjects(ProgressSubjects.INSTANCE)
                    .factors(factors)
                    .questFactorContext(factorContext)
                    .achievementFactorContext(factorContext)
                    // Both halves of a hand-in, or neither: the engine's defaults refuse everything,
                    // which would leave the book's Hand in button dead for every item delivery.
                    .questPossession((QuestPossessionProbe) ProgressionDefaults::holdsItems)
                    .questInventory((QuestInventoryConsumer) ProgressionDefaults::takeItems)
                    .questGates(gates)
                    .textSource(AssetText.INSTANCE)
                    .questMatchFlavor(MatchFlavor.STRICT)
                    .achievementMatchFlavor(MatchFlavor.LENIENT)
                    .maxTrackedQuests(MAX_TRACKED)
                    // No cap on how many quests may be carried at once. Capping it would make the
                    // engine's log_full refusal reachable, and a surface would then need a line for
                    // it - there is deliberately none, because there is deliberately no cap.
                    .maxActiveQuests(0)
                    .maxPinnedAchievements(MAX_PINNED)
                    .warn(SafeLog::warn);

            ProgressionRuntime.declareDefaultProducerKinds(PRODUCER_KINDS);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the library's default parts could not be registered", t);
        }
    }

    /**
     * Register everything that has to exist whoever ends up owning the runtime: the player lifecycle
     * listeners and the four producer systems. Registration is unconditional and dispatching is not.
     */
    public static void install(@Nonnull PluginBase plugin) {
        register();
        plugin.getEventRegistry().register(PlayerConnectEvent.class, ProgressionDefaults::onPlayerConnect);
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, ProgressionDefaults::onPlayerReady);
        plugin.getEntityStoreRegistry().registerSystem(new ZigBlockBreakProducer());
        plugin.getEntityStoreRegistry().registerSystem(new ZigMobKillProducer());
        plugin.getEntityStoreRegistry().registerSystem(new ZigCraftProducer());
        plugin.getEntityStoreRegistry().registerSystem(new ZigPickupProducer());
    }

    // ==================== content ====================

    /**
     * Fold every loaded asset into the shared runtime and AUDIT it in the same pass.
     *
     * <p>The audit runs here because this is the one moment both halves are in hand - the folded pool
     * and the engine that will run it - and because a pool nobody validated is a pool whose broken
     * content simply never progresses, with nothing said at load and no way to tell later.
     *
     * <p>Definitions a consumer has CLAIMED are dropped: that consumer folds the same files itself,
     * usually into something richer, and shipping both copies into one catalogue is the duplicate the
     * namespace claim exists to prevent.
     */
    public static void publishAssetContent() {
        try {
            QuestPool quests = QuestAssetStore.getInstance().resolveAll(null, null);
            AchievementPool achievements = AchievementAssetStore.getInstance().resolveAll(null);
            QUEST_POOL = quests;
            ACHIEVEMENT_POOL = achievements;

            AssetQuestGates gates = assetGates;
            if (gates != null) {
                gates.pool(quests);
                gates.useEngine(ProgressionRuntime.quests());
            }

            ProgressionRuntime.publishQuests(OWNER, unclaimedQuests(quests));
            ProgressionRuntime.publishAchievements(OWNER, unclaimedAchievements(achievements));
            // The points ladder has no owner field and nothing to claim it away: a milestone is a
            // reward for a TOTAL, and a total is one number however many mods contributed to it. A
            // consumer that publishes its own layer outranks this one rung for rung, by threshold.
            ProgressionRuntime.publishMilestones(OWNER,
                    AchievementMilestoneConfig.getInstance().milestones());

            report(QuestPoolValidator.validate(quests, ProgressionRuntime.quests(),
                    ProgressionRuntime.gateKinds()), "quest");
            report(AchievementPoolValidator.validate(achievements, ProgressionRuntime.achievements(),
                    ProgressionRuntime.gateKinds()), "achievement");
        } catch (Throwable t) {
            SafeLog.warn("[progression] the asset content could not be published", t);
        }
    }

    @Nonnull
    private static List<Quest> unclaimedQuests(@Nonnull QuestPool pool) {
        List<Quest> out = new ArrayList<>();
        for (QuestDefinition definition : pool.definitions().values()) {
            if (!claimed(definition.owner())) {
                out.add(definition.quest());
            }
        }
        return out;
    }

    @Nonnull
    private static List<Achievement> unclaimedAchievements(@Nonnull AchievementPool pool) {
        List<Achievement> out = new ArrayList<>();
        for (AchievementDefinition definition : pool.definitions().values()) {
            if (!claimed(definition.owner())) {
                out.add(definition.achievement());
            }
        }
        return out;
    }

    /** Unowned content belongs to nobody in particular, so nobody can claim it away. */
    private static boolean claimed(@Nullable String owner) {
        return owner != null && ProgressionRuntime.ownsContentNamespace(owner);
    }

    /**
     * The item an achievement is illustrated with, when this library folded it. Presentation the
     * engine model deliberately does not carry, so a surface asks here and simply gets null for
     * content somebody else folded.
     */
    @Nullable
    public static String achievementIcon(@Nonnull String achievementId) {
        AchievementPool pool = ACHIEVEMENT_POOL;
        AchievementDefinition definition = pool == null ? null : pool.definition(achievementId);
        return definition == null ? null : definition.icon();
    }

    /**
     * The grouping label an achievement filed itself under, when this library folded it. Like the
     * icon, this is presentation the engine model deliberately does not carry, so content somebody
     * else folded simply reads as belonging to no group.
     */
    @Nullable
    public static String achievementCategory(@Nonnull String achievementId) {
        AchievementPool pool = ACHIEVEMENT_POOL;
        AchievementDefinition definition = pool == null ? null : pool.definition(achievementId);
        String category = definition == null ? null : definition.category();
        return category == null || category.isBlank() ? null : category.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Where a category reads among the others, from the folded taxonomy. A category nothing
     * describes, and content belonging to no category at all, sort AFTER every described one rather
     * than jumping to the front of a list.
     */
    public static int categoryRank(@Nullable String category) {
        AchievementCategoryAsset presentation = AchievementCategoryConfig.getInstance().category(category);
        return presentation == null ? Integer.MAX_VALUE : presentation.orderOrLast();
    }

    private static void report(@Nonnull List<Finding> findings, @Nonnull String what) {
        ValidationReport.logAll("[progression] " + what + " content", findings,
                SafeLog::warn, SafeLog::info);
    }

    /** The folded quest catalogue this module loaded, for the text source. */
    @Nonnull
    private static volatile QuestPool QUEST_POOL = QuestPool.EMPTY;

    /** The folded achievement catalogue this module loaded, for the text source. */
    @Nullable
    private static volatile AchievementPool ACHIEVEMENT_POOL;

    /**
     * Names the content the shared schema carries, which is everything this library itself folded.
     * Registered LAST among the defaults' contributions so a consumer's own source answers first for
     * whatever it owns.
     */
    private static final class AssetText implements ProgressionTextSource {

        static final AssetText INSTANCE = new AssetText();

        @Override
        @Nullable
        public Message title(@Nonnull String contentId) {
            QuestDefinition quest = QUEST_POOL.definition(contentId);
            if (quest != null) {
                return key(quest.titleKey(), quest.displayName(), quest.titleArgs(),
                        primaryAmount(quest.quest().objectives()));
            }
            AchievementDefinition earned = achievement(contentId);
            return earned == null ? null : key(earned.titleKey(), earned.displayName(),
                    earned.titleArgs(), primaryAmount(earned.achievement().criteria()));
        }

        @Override
        @Nullable
        public Message flavor(@Nonnull String contentId) {
            QuestDefinition quest = QUEST_POOL.definition(contentId);
            if (quest != null) {
                return key(quest.flavorKey(), null, quest.flavorArgs(),
                        primaryAmount(quest.quest().objectives()));
            }
            AchievementDefinition earned = achievement(contentId);
            return earned == null ? null : key(earned.flavorKey(), null, earned.flavorArgs(),
                    primaryAmount(earned.achievement().criteria()));
        }

        /**
         * A quest names its steps by id; an achievement numbers its criteria, so its "objective id"
         * is the position written out. A caller that hands a number for a quest, or a name for an
         * achievement, simply gets null and the next source has its turn.
         */
        @Override
        @Nullable
        public Message objective(@Nonnull String contentId, @Nonnull String objectiveId) {
            QuestDefinition quest = QUEST_POOL.definition(contentId);
            if (quest != null) {
                return key(quest.objectiveTextKey(objectiveId), null, List.of(), 0L);
            }
            AchievementDefinition earned = achievement(contentId);
            if (earned == null) {
                return null;
            }
            try {
                return key(earned.criterionTextKey(Integer.parseInt(objectiveId.trim())), null,
                        List.of(), 0L);
            } catch (NumberFormatException notAnIndex) {
                return null;
            }
        }

        @Nullable
        private static AchievementDefinition achievement(@Nonnull String contentId) {
            AchievementPool pool = ACHIEVEMENT_POOL;
            return pool == null ? null : pool.definition(contentId);
        }

        /** A key resolves on the player's own client; a plain name is the fallback while one is written. */
        @Nullable
        private static Message key(@Nullable String localizationKey, @Nullable String displayName,
                @Nonnull List<String> authoredArgs, long amount) {
            if (localizationKey != null && !localizationKey.isBlank()) {
                return Msg.key(localizationKey, args(authoredArgs, amount));
            }
            return displayName == null || displayName.isBlank() ? null : Msg.raw(displayName);
        }

        /**
         * What an authored {@code TextArgs} list binds to a key's {@code {0}/{1}/...} slots.
         *
         * <p>A whole ladder of content is usually ONE translated line with each rung supplying its
         * own number, so a key resolved WITHOUT its args renders that line with an empty slot on
         * every rung at once - and the content file still reads as perfectly correct.
         *
         * <p>{@code @amount} is the one sentinel the shared content schema names, and its VALUE is a
         * rendering decision: here it is the number the content asks for, grouped for readability
         * and passed as a raw value rather than a translated one, since a digit needs no
         * translating. Anything else an author wrote is passed through exactly as typed, so a
         * sentinel nothing answers shows up in the line instead of leaving a blank nobody can
         * diagnose.
         */
        @Nonnull
        private static Object[] args(@Nonnull List<String> authored, long amount) {
            return ContentTextAsset.expand(authored, sentinel ->
                    ContentTextAsset.ARG_AMOUNT.equals(sentinel)
                            ? NumberFormatter.grouped(amount) : null);
        }

        /**
         * The number a piece of content asks for, for {@code @amount}: the first step's, since a
         * ladder written as one translated line is a ladder whose rungs differ in exactly that
         * number. Content with no steps at all asks for nothing.
         */
        private static long primaryAmount(@Nonnull List<ObjectiveDef> objectives) {
            return objectives.isEmpty() ? 0L : objectives.get(0).amount();
        }
    }

    // ==================== the seams the defaults fill ====================

    /**
     * The question a factor is answered against for one subject: the live entity behind it, when
     * there is one. A handle-less subject builds an empty context, which resolves nothing and
     * therefore writes nothing - the standing-value probe's own contract.
     */
    @Nonnull
    private static FactorContext factorContextOf(@Nonnull Subject subject) {
        ProgressHandle handle = subject.handleAs(ProgressHandle.class);
        if (handle == null) {
            return FactorContext.builder().build();
        }
        return FactorContext.builder().store(handle.store()).subject(handle.ref()).build();
    }

    /**
     * Read a permission off the subject's own handle. A handle-less subject refuses, which is the
     * fail-closed answer a gate needs.
     */
    private static boolean holdsPermission(@Nonnull Subject subject, @Nonnull String permission) {
        ProgressHandle handle = subject.handleAs(ProgressHandle.class);
        return handle != null && handle.playerRef().hasPermission(permission);
    }

    // ==================== hand-ins ====================

    /**
     * The READ half of a hand-in: could this player give up {@code count} of {@code itemId} right
     * now? Nothing is taken, so a surface can offer a hand-in only when it will actually land.
     *
     * <p>Answered across every inventory section, which is what a player emptying a chest into their
     * backpack expects. A handle-less subject refuses, matching the engine's own fail-closed default.
     */
    public static boolean holdsItems(@Nonnull Subject subject, @Nonnull String itemId, int count) {
        ProgressHandle handle = subject.handleAs(ProgressHandle.class);
        return handle != null && InventoryUtil.has(handle.store(), handle.ref(), itemId, count);
    }

    /**
     * The WRITE half: take up to {@code max} and report how many were REALLY taken, so a player
     * carrying part of a large delivery is credited for the part they brought and still owes the
     * rest. A handle-less subject takes nothing and says so.
     */
    public static int takeItems(@Nonnull Subject subject, @Nonnull String itemId, int max) {
        ProgressHandle handle = subject.handleAs(ProgressHandle.class);
        return handle == null ? 0 : InventoryUtil.take(handle.store(), handle.ref(), itemId, max);
    }

    // ==================== player lifecycle ====================

    /**
     * Create the progress component, the one moment a {@code Holder} is in hand.
     *
     * <p>Gated on {@link ProgressionRuntime#usesDefaultStores()}: registration is final at setup while
     * the runtime is only BUILT later, and stamping a component onto every player of a server whose
     * consumer keeps its own store would be per-player pollution nothing ever reads.
     */
    private static void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        try {
            if (!ProgressionRuntime.usesDefaultStores() || ZigProgressComponent.TYPE == null) {
                return;
            }
            event.getHolder().ensureAndGetComponent(ZigProgressComponent.TYPE);
        } catch (Throwable t) {
            SafeLog.warn("[progression] could not ensure the progress component", t);
        }
    }

    /**
     * Build the shared runtime on the first player ready, then run this player's maintenance:
     * self-heal both engines, then accept whatever is waiting to be auto-accepted.
     *
     * <p>Self-heal runs FIRST so a repeatable that has come back around reads offerable by the time
     * the auto-accept pass looks at it. Both are skipped when a consumer owns the stores: that
     * consumer runs its own maintenance on the same beat, and running both would double-run an
     * idempotent pass and muddy the log for no gain.
     */
    private static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            Ref<EntityStore> ref = event.getPlayerRef();
            World world = player.getWorld();
            if (world == null) {
                return;
            }
            world.execute(() -> onReadyOnWorldThread(ref));
        } catch (Throwable t) {
            SafeLog.warn("[progression] player-ready handling failed", t);
        }
    }

    private static void onReadyOnWorldThread(@Nonnull Ref<EntityStore> ref) {
        try {
            bootstrapOnce();
            if (!ProgressionRuntime.usesDefaultStores() || !ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            if (store == null) {
                return;
            }
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                return;
            }
            Subject subject = ProgressionRuntime.subjects().questSubject(store, ref, playerRef);
            if (subject == null) {
                return;
            }
            QuestEngine quests = ProgressionRuntime.quests();
            AchievementEngine achievements = ProgressionRuntime.achievements();
            quests.selfHeal(subject);
            achievements.selfHeal(subject);
            quests.autoAcceptAvailable(subject);
        } catch (Throwable t) {
            SafeLog.warn("[progression] player-ready maintenance failed", t);
        }
    }

    /** Build the runtime and publish this library's own content, once per boot. */
    private static void bootstrapOnce() {
        if (ProgressionRuntime.isBuilt()) {
            return;
        }
        ProgressionRuntime.ensureBuilt();
        publishAssetContent();
    }

    /** Forget this module's own folded catalogues and registrations (test reset, and shutdown). */
    public static synchronized void reset() {
        QUEST_POOL = QuestPool.EMPTY;
        ACHIEVEMENT_POOL = null;
        assetGates = null;
        registered = false;
    }
}
