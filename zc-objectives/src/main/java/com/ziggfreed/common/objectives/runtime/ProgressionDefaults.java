package com.ziggfreed.common.objectives.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
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
import com.ziggfreed.common.inventory.InventoryUtil;
import com.ziggfreed.common.objectives.hud.TrackedQuestHuds;
import com.ziggfreed.common.objectives.producer.ZigBlockBreakProducer;
import com.ziggfreed.common.objectives.producer.ZigCraftProducer;
import com.ziggfreed.common.objectives.producer.ZigInstanceRoundProducer;
import com.ziggfreed.common.objectives.producer.ZigMobKillProducer;
import com.ziggfreed.common.objectives.producer.ZigPickupProducer;
import com.ziggfreed.common.objectives.producer.ZigPlaceBlockProducer;
import com.ziggfreed.common.objectives.store.ProgressHandle;
import com.ziggfreed.common.objectives.store.ProgressSubjects;
import com.ziggfreed.common.objectives.store.ZigAchievementStore;
import com.ziggfreed.common.objectives.store.ZigProgressComponent;
import com.ziggfreed.common.objectives.store.ZigQuestStore;
import com.ziggfreed.common.progress.ContentText;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.runtime.ProgressionGates;
import com.ziggfreed.common.progress.runtime.ProgressionRegistrar;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionTextSource;
import com.ziggfreed.common.quest.NpcOffer;
import com.ziggfreed.common.quest.NpcOfferProvider;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestInventoryConsumer;
import com.ziggfreed.common.quest.QuestPossessionProbe;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.quest.RequiresGates;
import com.ziggfreed.common.quest.asset.QuestAssetStore;
import com.ziggfreed.common.quest.asset.QuestDefinition;
import com.ziggfreed.common.quest.asset.QuestPool;
import com.ziggfreed.common.quest.asset.QuestPoolValidator;
import com.ziggfreed.common.subject.Subject;
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
 * component TYPE is registered, the component is ATTACHED to every player, and the five producer
 * systems are registered, all at {@code setup()}. A component type registered after a world loads
 * cannot be read off entities saved carrying it, and an ECS system is a setup-time registration.
 * Those five producers always run, unconditionally, and nothing stands them down; the component
 * stays because it also holds what conversations remember, and the dialogue engine belongs to every
 * server whoever owns its quests (see {@link #onPlayerConnect}).
 */
public final class ProgressionDefaults {

    /** The owner name these registrations are attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    /** How many quests a player may pin, unless a consumer says otherwise. */
    private static final int MAX_TRACKED = 5;

    /** How many achievements a player may pin, unless a consumer says otherwise. */
    private static final int MAX_PINNED = 5;

    /** Registration is once per boot: a second pass would mint parts nothing is holding. */
    private static boolean registered;

    /** Every listener told when a player's progress state changed. See {@link #onProgressDirty}. */
    private static final List<Consumer<Subject>> DIRTY_LISTENERS = new CopyOnWriteArrayList<>();

    /** Every listener told to commit a player's pending writes. See {@link #onProgressFlush}. */
    private static final List<Consumer<Subject>> FLUSH_LISTENERS = new CopyOnWriteArrayList<>();

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

            RequiresGates gates = ProgressionGates.gates();

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
                    // ONE gate for BOTH engines: a quest's Requires block and an achievement's are
                    // the same question about the same player, so two implementations could only
                    // ever answer them two ways.
                    .questGates(gates)
                    .achievementGates(gates)
                    .textSource(RuntimeText.INSTANCE)
                    .maxTrackedQuests(MAX_TRACKED)
                    // No cap on how many quests may be carried at once. Capping it would make the
                    // engine's log_full refusal reachable, and a surface would then need a line for
                    // it - there is deliberately none, because there is deliberately no cap.
                    .maxActiveQuests(0)
                    .maxPinnedAchievements(MAX_PINNED)
                    .warn(SafeLog::warn);

            NpcOfferProviders.register(OWNER, OWNER, RuntimeOffers.INSTANCE);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the library's default parts could not be registered", t);
        }
    }

    /**
     * Register everything that has to exist whoever ends up owning the runtime: the player lifecycle
     * listeners, the five producer systems plus the one producer that is an event-bus listener
     * (a finished instance round is announced about a group of players rather than happening to an
     * entity, so it arrives on the shared bus), and the tracked-quest HUD with its six event
     * subscriptions. All of it is unconditional, and so is every dispatch those producers make.
     *
     * <p>The HUD installs itself LAST and guards itself, so a failure there costs the tracker and
     * nothing registered before it. Its attach rides the ready event at a LATER priority than the
     * maintenance pass registered here, so a player's first paint already shows what that pass did.
     */
    public static void install(@Nonnull PluginBase plugin) {
        register();
        plugin.getEventRegistry().register(PlayerConnectEvent.class, ProgressionDefaults::onPlayerConnect);
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, ProgressionDefaults::onPlayerReady);
        plugin.getEntityStoreRegistry().registerSystem(new ZigBlockBreakProducer());
        plugin.getEntityStoreRegistry().registerSystem(new ZigMobKillProducer());
        plugin.getEntityStoreRegistry().registerSystem(new ZigCraftProducer());
        plugin.getEntityStoreRegistry().registerSystem(new ZigPickupProducer());
        plugin.getEntityStoreRegistry().registerSystem(new ZigPlaceBlockProducer());
        ZigInstanceRoundProducer.install(plugin);
        SafeLog.info("[progression] producers always-on: " + producedKinds()
                + " (a mod firing a new moment calls ProgressDispatch.fire directly, no registration"
                + " needed)");
        TrackedQuestHuds.install(plugin);
    }

    /**
     * The kinds this library's own producers fire, as one readable list.
     *
     * <p>Named rather than counted because the wiring root's failure line uses it too: if
     * {@link #install} throws part way, the producers after the throw were never registered and
     * their moments simply stop happening, which is the one failure here nothing else reports.
     */
    @Nonnull
    public static String producedKinds() {
        return String.join(", ", ZigBlockBreakProducer.KIND, ZigMobKillProducer.KIND,
                ZigCraftProducer.KIND, ZigPickupProducer.KIND, ZigPlaceBlockProducer.KIND,
                ZigInstanceRoundProducer.KIND_ENDED, ZigInstanceRoundProducer.KIND_WON);
    }

    // ==================== persistence notifications ====================

    /**
     * Be told whenever a player's progress state CHANGED, so a consumer with a persistence backend
     * of its own can mark that player dirty without having to replace the store.
     *
     * <p>This is the seam a mod keeping progress somewhere other than the saved world needs. The
     * library's own default stores write straight onto the persisted component and have nothing to
     * report, so the notification exists purely for whoever else is holding a copy: a fleet database,
     * a write-behind cache, a live export. Register a consumer's own dirty hook here and the default
     * stores stay THE store, which is the whole point - two stores would be two versions of one
     * player's state.
     *
     * <p><b>What counts as a change.</b> Every write the two default stores make on the shared
     * quest and achievement engines' behalf, pins, unpins and re-arms included, plus every dialogue
     * memory a conversation remembers or forgets - all three land on the one persisted component,
     * so all three report here. Each ENGINE method that writes reports its own write, including the
     * public ones an outside layer calls ({@code QuestEngine.clearQuest}, {@code markCompleted},
     * {@code markUnclaimed}), so a re-arm arriving from a rotating offer or a chained quest's pool
     * is not a silent one.
     *
     * <p><b>What does NOT report is a caller going AROUND the engine</b>, and there are two such
     * doors. One is a caller holding the component and writing it directly:
     * {@code ZigProgressComponent.claimMigration} is the only instance today, and whoever claims a
     * migration marks the player dirty itself, since it is the only side that knows a claim was
     * made. The other is a caller reaching the store adapter itself - {@code store().clearQuest},
     * {@code setStatus}, {@code putProgress} - instead of the engine call that owns that write.
     * Neither is reported for it, and both carry the same obligation the engine carries: whoever
     * makes the write says so.
     *
     * <p><b>Contributions STACK.</b> Every registered listener is called on every change, in
     * registration order, and registering one never displaces another. A listener that THROWS is
     * reported by name and the remaining listeners still run, so one misbehaving backend cannot
     * silence another's writes. Registration order is the order they are ASKED in, never a
     * precedence: no listener can consume a notification or stop the next one hearing it.
     *
     * <p>Called on the world thread the change happened on. Keep the body short: hand the work to
     * whatever queue the backend already has rather than doing IO here.
     *
     * @throws NullPointerException when {@code listener} is null, at the consumer's own setup rather
     *         than mid-transition inside somebody else's quest hand-in
     */
    public static void onProgressDirty(@Nonnull Consumer<Subject> listener) {
        DIRTY_LISTENERS.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Be told when a player's pending writes should be COMMITTED NOW, at a transaction boundary the
     * engines name themselves. There are exactly FIVE of them, and the list is the whole contract -
     * a backend sizes its write budget on it:
     *
     * <ul>
     *   <li>a quest COLLECTED ({@code QuestEngine.claim}), whether or not that quest had anything to
     *       hand over: the player pressed the button, so the outcome sticks;</li>
     *   <li>a points milestone COLLECTED ({@code AchievementEngine.claimMilestone}), same rule;</li>
     *   <li>an achievement COLLECTED ({@code AchievementEngine.claim}), same rule;</li>
     *   <li>a quest that paid out the instant it finished (the auto-claim path in
     *       {@code QuestEngine.checkCompletion}), <b>only when a reward was actually delivered</b>;</li>
     *   <li>an administrator's close-out ({@code QuestEngine.forceComplete}), on the same
     *       delivered-something condition.</li>
     * </ul>
     *
     * <p><b>What deliberately does NOT flush, and why the list is short.</b> Earning an achievement,
     * reaching a points milestone, and a payout that delivered nothing all report dirty and wait for
     * the batch. Earning is something the engine DECIDES rather than something the player asked for,
     * and it arrives in bulk: a self-heal walks the whole catalogue on login, one earn cascades
     * through a chain of meta achievements, and each earn re-checks the milestones. Committing at
     * any of those turns one login into a write per achievement the player already had. Nothing in a
     * self-heal, a re-arm, a prune, or a pin sweep commits, and no single engine call commits twice.
     * A quest that finishes and PARKS for collection has paid nothing yet, so it waits too - and
     * then commits when the player comes to collect it, which is the first bullet.
     *
     * <p>Same shape and same rules as {@link #onProgressDirty} - additive, every listener asked in
     * registration order, every listener guarded - and it exists beside it rather than being folded
     * into it because the two say different things. Dirty says "this player changed, get to it";
     * flush says "do not let a crash in the next second cost this player the thing they just
     * earned". A backend that batches its writes needs both, and one that does not can register
     * only the first.
     *
     * @throws NullPointerException when {@code listener} is null, for the same reason as its peer
     */
    public static void onProgressFlush(@Nonnull Consumer<Subject> listener) {
        FLUSH_LISTENERS.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Tell every dirty listener about {@code subject}. The library's own default stores call this
     * from their {@code markDirty}; it is not a consumer entry point (a consumer REGISTERS through
     * {@link #onProgressDirty} and lets its own store seam decide when a change happened).
     */
    public static void fireProgressDirty(@Nonnull Subject subject) {
        fire(DIRTY_LISTENERS, subject, "dirty");
    }

    /**
     * Tell every flush listener about {@code subject}. The library's own default stores call this
     * from their {@code flush}; like its peer it is a dispatch point, not a consumer entry point.
     */
    public static void fireProgressFlush(@Nonnull Subject subject) {
        fire(FLUSH_LISTENERS, subject, "flush");
    }

    /**
     * One listener at a time, each in its own guard: a backend that throws is a backend that failed,
     * not a reason for the next one never to hear about the change.
     */
    private static void fire(@Nonnull List<Consumer<Subject>> listeners, @Nonnull Subject subject,
            @Nonnull String what) {
        for (Consumer<Subject> listener : listeners) {
            try {
                listener.accept(subject);
            } catch (Throwable t) {
                SafeLog.warn("[progression] a progress " + what + " listener ("
                        + listener.getClass().getName() + ") failed for '" + subject.name() + "'", t);
            }
        }
    }

    // ==================== content ====================

    /**
     * Fold every loaded asset into the shared runtime and AUDIT it in the same pass.
     *
     * <p>The audit runs here because this is the one moment both halves are in hand - the folded pool
     * and the engine that will run it - and because a pool nobody validated is a pool whose broken
     * content simply never progresses, with nothing said at load and no way to tell later.
     *
     * <p><b>Everything folded here is published, including content a consumer also folds.</b> RANK
     * is what resolves the overlap, and it needs no claim to do it: this layer is published at
     * library-default rank, a consumer's at consumer rank, and the content layers merge defaults
     * first - so wherever both hold the same id the consumer's richer version silently replaces
     * this one, while an id only this layer folded still reaches the engines. A server with no
     * consumer running progression therefore gets the whole shared store on the generic engines,
     * and a server with one gets that consumer's reading of it.
     */
    public static void publishAssetContent() {
        try {
            QuestPool quests = QuestAssetStore.getInstance().resolveAll(null);
            AchievementPool achievements = AchievementAssetStore.getInstance().resolveAll();
            QUEST_POOL = quests;
            ACHIEVEMENT_POOL = achievements;

            ProgressionRuntime.publishQuests(OWNER, engineQuests(quests));
            ProgressionRuntime.publishAchievements(OWNER, engineAchievements(achievements));
            // The points ladder resolves exactly the same way: a milestone is a reward for a TOTAL,
            // and a total is one number however many mods contributed to it, so a consumer
            // publishing its own layer outranks this one rung for rung, by threshold.
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

    /** The engine half of every folded quest - the presentation half stays here, for the text source. */
    @Nonnull
    private static List<Quest> engineQuests(@Nonnull QuestPool pool) {
        List<Quest> out = new ArrayList<>();
        for (QuestDefinition definition : pool.definitions().values()) {
            out.add(definition.quest());
        }
        return out;
    }

    /** The engine half of every folded achievement, on the same terms. */
    @Nonnull
    private static List<Achievement> engineAchievements(@Nonnull AchievementPool pool) {
        List<Achievement> out = new ArrayList<>();
        for (AchievementDefinition definition : pool.definitions().values()) {
            out.add(definition.achievement());
        }
        return out;
    }

    /**
     * The item an achievement is illustrated with, when this library folded it. The runtime object
     * carries the picture too ({@code Achievement.icon()}), so a surface reads the model first and
     * asks here only as the fallback for content whose fold predates the model carrying it.
     */
    @Nullable
    public static String achievementIcon(@Nonnull String achievementId) {
        AchievementPool pool = ACHIEVEMENT_POOL;
        AchievementDefinition definition = pool == null ? null : pool.definition(achievementId);
        return definition == null ? null : definition.icon();
    }

    /**
     * The grouping label an achievement filed itself under, when this library folded it. Like the
     * icon, the runtime object carries this too ({@code Achievement.category()}); this read stays
     * as the fallback for content whose fold predates the model carrying it.
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
     * Names EVERY piece of content in the shared catalogue, whoever folded it and in whatever format
     * they authored it.
     *
     * <p>It reads the runtime object rather than this module's own pool, and that is the whole point:
     * every fold puts a content's words onto the object it publishes, so one source answers for a
     * shared-schema file and for a consumer's older format alike. A source per format was how half a
     * merged list rendered blank while the other half read normally.
     */
    private static final class RuntimeText implements ProgressionTextSource {

        static final RuntimeText INSTANCE = new RuntimeText();

        @Override
        @Nullable
        public Message title(@Nonnull String contentId) {
            ContentText text = textOf(contentId);
            return text == null ? null : text.title();
        }

        @Override
        @Nullable
        public Message flavor(@Nonnull String contentId) {
            ContentText text = textOf(contentId);
            return text == null ? null : text.flavor();
        }

        /**
         * A quest names its steps by id; an achievement numbers its criteria, so its "objective id"
         * is the position written out. Either way the answer is whichever key the content carried
         * for that step, and null when it carried none.
         */
        @Override
        @Nullable
        public Message objective(@Nonnull String contentId, @Nonnull String objectiveId) {
            ContentText text = textOf(contentId);
            return text == null ? null : text.objective(objectiveId);
        }

        /**
         * The narrative for a lifecycle state: the shared {@code quest.<id>.md.<state>} convention
         * first, then whatever the content itself wrote for that state. The key wins because a key
         * is resolved by the player's own client in their own language while an authored paragraph
         * is one language typed into a file.
         */
        @Override
        @Nullable
        public Message lore(@Nonnull String contentId, @Nonnull String state) {
            Message byConvention = ProgressionTextSource.loreByConvention(contentId, state);
            if (byConvention != null) {
                return byConvention;
            }
            ContentText text = textOf(contentId);
            return text == null ? null : text.lore(state);
        }

        /**
         * The words behind an id, quest side first. Nothing is resolved before the runtime is built,
         * because a text read during another mod's setup would seal every sealed part early.
         */
        @Nullable
        private static ContentText textOf(@Nonnull String contentId) {
            if (!ProgressionRuntime.isBuilt()) {
                return null;
            }
            Quest quest = ProgressionRuntime.quests().quest(contentId);
            if (quest != null) {
                return quest.text().isEmpty() ? null : quest.text();
            }
            Achievement achievement = ProgressionRuntime.achievements().achievement(contentId);
            if (achievement == null) {
                return null;
            }
            return achievement.text().isEmpty() ? null : achievement.text();
        }
    }

    /**
     * What a character is holding out, answered off the shared catalogue.
     *
     * <p>Which quests a place hands out is an authoring-layer association and whether the player may
     * take one is a gate pass; both now ride the runtime object, so this needs no pool and no
     * consumer provider. Every quest a character was authored to hand out is reported in WHATEVER
     * state the player has it - takeable now, gated until later, being carried, finished - because
     * that is the character's whole business with this player; {@code available} stays the narrow
     * "can this be taken right now", which is what the hail and marker surfaces read.
     *
     * <p>It filters on {@link QuestEngine#isOfferable}, NOT on the open-listing read beside it. A
     * quest marked out of sight is marked out of sight on a BROWSABLE list; at the one character
     * whose business it is, hiding it leaves them standing silently beside the thing they exist to
     * hand out. The giver read asks only whether the quest is switched on and whether the player is
     * past what it asks for first.
     *
     * <p>The lock reasons are left empty deliberately: the accept check answers a boolean plus the
     * engine's own opaque tokens, and turning those into words is the consumer's job, so inventing
     * a reason here would be a second copy of somebody else's vocabulary.
     */
    private static final class RuntimeOffers implements NpcOfferProvider {

        static final RuntimeOffers INSTANCE = new RuntimeOffers();

        @Override
        @Nonnull
        public List<NpcOffer> offersAt(@Nonnull Subject subject,
                @Nonnull Collection<String> answersTo) {
            if (answersTo.isEmpty() || !ProgressionRuntime.isBuilt()) {
                return List.of();
            }
            QuestEngine engine = ProgressionRuntime.quests();
            List<Quest> given = new ArrayList<>();
            for (Quest quest : engine.quests()) {
                String giver = quest.npcViewId();
                if (giver != null && containsIgnoreCase(answersTo, giver)
                        && engine.isOfferable(subject, quest)) {
                    given.add(quest);
                }
            }
            given.sort(Comparator.comparingInt(Quest::listOrder).thenComparing(Quest::id));
            List<NpcOffer> out = new ArrayList<>(given.size());
            for (Quest quest : given) {
                String titleKey = quest.text().resolvableTitleKey();
                boolean takeable = engine.status(subject, quest) == QuestStatus.NOT_STARTED
                        && engine.canAccept(subject, quest).allowed();
                out.add(takeable ? NpcOffer.available(quest.id(), titleKey)
                        : NpcOffer.locked(quest.id(), titleKey, List.of()));
            }
            return out;
        }

        /**
         * The cheap answer, which is the one a render path actually asks for.
         *
         * <p>"Has this character anything for me" is asked once per character on screen, so it stops
         * at the FIRST quest that is takeable now instead of building the whole list: no titles
         * resolved, no locked entries assembled, and no gate evaluated for a quest the player is
         * already carrying. A quest they have started can never make this true anyway - it is
         * reported as locked in the full list, and the boolean asks only about what is on offer.
         */
        @Override
        public boolean hasOffersAt(@Nonnull Subject subject, @Nonnull Collection<String> answersTo) {
            if (answersTo.isEmpty() || !ProgressionRuntime.isBuilt()) {
                return false;
            }
            QuestEngine engine = ProgressionRuntime.quests();
            for (Quest quest : engine.quests()) {
                String giver = quest.npcViewId();
                if (giver == null || !containsIgnoreCase(answersTo, giver)) {
                    continue;
                }
                if (engine.status(subject, quest) == QuestStatus.NOT_STARTED
                        && engine.canAccept(subject, quest).allowed()) {
                    return true;
                }
            }
            return false;
        }

        private static boolean containsIgnoreCase(@Nonnull Collection<String> answersTo,
                @Nonnull String giver) {
            for (String answered : answersTo) {
                if (answered != null && answered.equalsIgnoreCase(giver)) {
                    return true;
                }
            }
            return false;
        }
    }

    // ==================== the seams the defaults fill ====================

    /**
     * The question a factor is answered against for one subject: the live entity behind it, when
     * there is one. A subject carrying no live entity at all builds an empty context, which resolves
     * nothing and therefore writes nothing - the standing-value probe's own contract.
     *
     * <p><b>Two ways a subject can carry that entity, and both are asked.</b> This runtime's own
     * subjects hang a {@link ProgressHandle}, which already holds the store and the ref. Every OTHER
     * surface driving these same engines - a storefront, a board, a consumer's own screen - hangs
     * whatever its own vocabulary uses, and the one thing the whole library agrees a handle answers
     * for is the live {@link Player}. So a handle that is not a {@code ProgressHandle} is asked for
     * a player, and the store and ref are derived from its own reference. Without that second rung
     * every subject-reading factor - a stat threshold, a permission, any of the tool readings -
     * would fail closed on a server whose consumer builds its subjects any other way, which is a
     * gate shutting on content that is perfectly correct.
     */
    @Nonnull
    private static FactorContext factorContextOf(@Nonnull Subject subject) {
        ProgressHandle handle = subject.handleAs(ProgressHandle.class);
        if (handle != null) {
            return FactorContext.builder().store(handle.store()).subject(handle.ref()).build();
        }
        Player player = subject.handleAs(Player.class);
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        if (ref == null || !ref.isValid()) {
            return FactorContext.builder().build();
        }
        return FactorContext.builder().store(ref.getStore()).subject(ref).build();
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
     * <p><b>Unconditional, and it has to be.</b> This attach used to stand down where a consumer
     * kept its own quest store, on the grounds that a component nothing reads is per-player
     * pollution. That stopped being true the moment the component also held what conversations
     * remember: dialogue is the library's own engine and every mod on the server uses it, so a
     * server whose quests belong to a consumer still has memories to keep - and a component that
     * was never attached would read every one of them as forgotten and drop every write, which is
     * precisely the silent failure this whole storage layer exists to end. A component type
     * registered after a world has loaded cannot be read off entities saved carrying it, and this
     * is the only lifecycle hook that carries a holder, so there is no later moment to defer to.
     *
     * <p>What that costs a server running its own progression is one component per player carrying
     * empty quest and achievement leaves, which is a handful of bytes in their save.
     */
    private static void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        try {
            if (ZigProgressComponent.TYPE == null) {
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
            // The subject source below resolves the player itself and answers null for anything
            // that is not one, so this asks once rather than reading the component twice.
            Subject subject = ProgressionRuntime.subjects().questSubject(store, ref);
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

    /**
     * THE requirement evaluator, which lives one module down beside the runtime it reads.
     *
     * <p>It is kept there rather than here so a surface that only wants to answer a {@code Requires}
     * block never has to load this class, whose own statics reach the five producer systems. A seam
     * taking a SUPPLIER (the commerce gate seam) points at this method, and gets the same one
     * instance every other surface has.
     */
    @Nonnull
    public static GateEvaluator gateEvaluator() {
        return ProgressionGates.evaluator();
    }

    /** Forget this module's own folded catalogues and registrations (test reset, and shutdown). */
    public static synchronized void reset() {
        QUEST_POOL = QuestPool.EMPTY;
        ACHIEVEMENT_POOL = null;
        registered = false;
        DIRTY_LISTENERS.clear();
        FLUSH_LISTENERS.clear();
    }
}
