package com.ziggfreed.common;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.asset.FrameworkAssetRegistrar;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.entity.PlayerIdentityCache;
import com.ziggfreed.common.factor.DerivedFactorConfig;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.factor.HytaleFactors;
import com.ziggfreed.common.loot.LootEditorDataSets;
import com.ziggfreed.common.loot.LootFactors;
import com.ziggfreed.common.loot.reward.DroplistRewardKind;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.stamp.StackStatsStamper;
import com.ziggfreed.common.loot.stamp.StamperRegistry;
import com.ziggfreed.common.npc.NpcActions;
import com.ziggfreed.common.npc.NpcDestinations;
import com.ziggfreed.common.npc.NpcQuestListHosts;
import com.ziggfreed.common.objectives.questlist.NpcQuestPages;
import com.ziggfreed.common.npc.NpcTalkDialogue;
import com.ziggfreed.common.npc.TalkCredits;
import com.ziggfreed.common.npc.placement.NpcPlacementConfig;
import com.ziggfreed.common.npc.placement.NpcPlacementLedger;
import com.ziggfreed.common.npc.placement.NpcPlacementOverrides;
import com.ziggfreed.common.npc.placement.NpcPlacementReconciler;
import com.ziggfreed.common.npc.placement.PlacedNpcComponent;
import com.ziggfreed.common.npc.placement.PlacementMarkerSystem;
import com.ziggfreed.common.npc.placement.PlacementFactorRegistry;
import com.ziggfreed.common.npc.placement.PlacementNpcActions;
import com.ziggfreed.common.objectives.book.ObjectiveBookInteractions;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.objectives.store.ZigProgressComponent;
import com.ziggfreed.common.progress.asset.ProgressEditorDataSets;
import com.ziggfreed.common.reward.EffectRewardKind;
import com.ziggfreed.common.util.SafeLog;

/**
 * Entry point for Ziggfreed Common, a shared, mod-agnostic Hytale utility mod.
 *
 * <p>It ships a set of stateless, config-free primitives ({@code sound/}, {@code camera/},
 * {@code util/}, {@code feedback/}, {@code ui/}) lifted from the MMO Skill Tree mod and the Kweebec
 * Nightmare minigame so that standalone Ziggfreed minigames (and eventually the MMO) can consume
 * one battle-tested implementation instead of re-deriving it per mod, PLUS the reusable
 * instance-experience framework (dialogues, instance presets, leaderboard layout, party settings,
 * multi-phase bosses, banded effects, encounter rules, prefab placements). There is NO MMO config,
 * no per-player component, and no Perfect Utils coupling here - the only dependency is the Hytale
 * server jar.
 *
 * <p>The static primitives register nothing (a consumer calls them directly), but this plugin DOES
 * own six registrations of its own:
 * <ul>
 *   <li>the framework asset stores ({@link FrameworkAssetRegistrar}), so common owns each store at
 *       {@code Server/ZiggfreedCommon/<Type>/} exactly once;</li>
 *   <li>the two generic destinations ({@link NpcDestinations}), because a conversation is this
 *       library's own engine and a character's quest list routes through a host seam, so neither
 *       needs anything a consumer has to wire;</li>
 *   <li>the Asset Editor pick lists for the fields naming a factor id ({@link EditorDataSets}),
 *       because the vocabulary they offer spans the placement registry and the Factors assets and
 *       only this wiring root can see both;</li>
 *   <li>the NPC placement engine's component, marker system, press-F action and first-player-ready
 *       content audit, because that engine is common's own and no consumer can be asked to register
 *       another mod's pieces;</li>
 *   <li><b>its own {@code AddWorldEvent}/{@code RemoveWorldEvent} listeners.</b> World eviction
 *       used to be driven only from CONSUMER listeners, so with two consumer mods installed the
 *       fan-out fired twice per world. That is harmless for an evictor that removes a map entry
 *       and corrupting for one that maintains a reference count, which the placement chunk-pin
 *       bookkeeping does. Common driving it itself, plus the idempotence guard in
 *       {@link WorldEvictors}, makes the count right however many consumers are installed.</li>
 *   <li><b>the {@link PlayerIdentityCache} lifecycle listeners.</b> The cache is common's own
 *       primitive and the only supported way to identify a player off the world thread, so it has
 *       to be kept current here rather than by whichever consumer happens to read it.</li>
 * </ul>
 *
 * <p><b>REGISTRATION ONLY (build-enforced).</b> This class registers, wires and populates; it never
 * DECIDES. The wiring root is the one place in the library where an edge between any two modules is
 * legal, so it will absorb any awkward dependency offered to it - and logic that settles here is
 * logic no module can be tested or reasoned about without standing up the whole plugin. When a
 * choice has to be made, it belongs in the module that owns it, behind a seam this class fills.
 * {@code RootRegistrationOnlyTest} fails the build on a loop, a {@code switch} or an {@code else}
 * here; a try/catch guard, a null-or-early-return {@code if}, and a null-defaulting ternary all
 * pass. The escape hatch is {@code // ROOT-LOGIC-OK: <reason>} with a real reason, and reaching for
 * it should feel like a defeat.
 */
public class ZiggfreedCommonPlugin extends JavaPlugin {

    /**
     * The library's logger, kept here as a convenience for anything already holding the plugin
     * class. It is the very same handle as {@link CommonLog#LOGGER} (same name, same backend), so
     * either route prints identical lines; prefer {@code CommonLog.LOGGER} in library code, which
     * needs no reference to the plugin entry point at all.
     */
    public static final HytaleLogger LOGGER = CommonLog.LOGGER;

    private static ZiggfreedCommonPlugin instance;

    @Nonnull
    public static ZiggfreedCommonPlugin getInstance() {
        return instance;
    }

    public ZiggfreedCommonPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        LOGGER.atInfo().log("ZiggfreedCommon initializing...");
    }

    @Override
    protected void setup() {
        // Seed the shared destination vocabulary before the stores whose assets embed it, so no
        // read can reach a half-built vocabulary.
        registerDestinations();

        // Register the framework asset stores ONCE (common owns them at
        // Server/ZiggfreedCommon/<Type>/). The stateless static primitives still register
        // nothing - a consumer calls them directly.
        FrameworkAssetRegistrar.registerAll(this);

        registerEditorDataSets();
        registerLootVocabulary();
        setupPlacementEngine();
        setupTalkCredit();
        registerWorldLifecycle();
        registerPlayerIdentity();
        setupProgressionRuntime();
        registerQuestListHost();

        LOGGER.atInfo().log("ZiggfreedCommon setup complete (framework stores + shared primitives available).");
    }

    /**
     * The library's own NPC quest page is the DEFAULT target the Quests destination opens - a bare
     * server gets a working quest list from this jar alone. A consumer wanting a different screen
     * registers its own host under its own id; the walk tries hosts in sorted id order.
     */
    private void registerQuestListHost() {
        try {
            NpcQuestListHosts.register("ziggfreedcommon", "ziggfreedcommon", NpcQuestPages::open);
        } catch (Throwable t) {
            SafeLog.warn("[questlist] default quest-page host wiring failed", t);
        }
    }

    /**
     * Wire this library's parts of THE shared progression runtime: the persisted per-player progress
     * component, the Objective Book's interaction Type (which the shipped book item names, so it
     * must be registered before any asset decode), and the default registrations plus the player
     * lifecycle and generic producer systems behind {@link ProgressionDefaults#install}.
     *
     * <p>Every registration is unconditional, and none of them decides anything. There is one
     * runtime per server whoever is on it; a consumer that brings its own store, gates or producers
     * registers them at its own {@code setup()} and outranks the defaults registered here. A
     * component type registered after a world has loaded cannot be read off entities saved carrying
     * it, and an ECS system is a setup-time registration, so neither can wait for that. Where a
     * consumer owns the stores, nothing attaches the component and every producer it replaced
     * returns on its first line.
     */
    private void setupProgressionRuntime() {
        try {
            ZigProgressComponent.register(getEntityStoreRegistry());
            ObjectiveBookInteractions.register(this);
            ProgressionDefaults.install(this);
        } catch (Throwable t) {
            SafeLog.warn("[progression] shared runtime wiring failed", t);
        }
    }

    /**
     * Serve the Asset Editor pick lists for the fields that name a FACTOR id, so an author picks a
     * factor from the vocabulary actually present on this server rather than typing one.
     *
     * <p>Both datasets answer the same union - the process-wide placement vocabulary plus every
     * asset-defined factor - because that union is the whole of what common can enumerate from
     * here. A factor a mod registers into its OWN per-consumer registry appears once that mod also
     * claims it in the shared placement facade, which is the only place the ids are process-wide.
     * The dropdown is a convenience either way: a hand-written JSON never passes through the editor,
     * so the validators stay the real check, and a free-typed id still resolves.
     */
    /**
     * The loot vocabulary a bare server starts with: the three framework reward kinds, the droplist
     * kind (a native drop table rolled onto the ground), the effect kind (registered from up here
     * because the loot layer must never see the effect module), and the stack-metadata stamper every
     * stamp writes through until a richer mod replaces it.
     *
     * <p>These are the JAVA half of the vocabulary. Kinds written as files
     * ({@code Server/ZiggfreedCommon/RewardKinds/}) join the same table from
     * {@link FrameworkAssetRegistrar}, once the stores have loaded and every consumer's own setup has
     * had its say - which is what lets an owner's file take a kind over from the mod that shipped it.
     *
     * <p>The two rolling kinds ({@code Lootable} and {@code Stamped_Item}) also need the READ
     * vocabulary their gates and chances are written against, or every one of those gates fails
     * closed and a rolled reward pays out only its ungated rolls. They are pointed at a registry
     * carrying the portable {@code hytale:} standard library plus the two instance readings a run's
     * score and outcome are asked through, so a table authored anywhere on the server resolves the
     * same ids it would inside a run.
     */
    private void registerLootVocabulary() {
        LootRewardKinds.registerInto(RewardKinds.shared());
        DroplistRewardKind.registerInto(RewardKinds.shared());
        EffectRewardKind.registerInto(RewardKinds.shared());
        LootRewardKinds.factors(lootFactorVocabulary());
        StamperRegistry.register(new StackStatsStamper());
    }

    /**
     * The factor vocabulary a rolled reward reads through: the portable engine readings about the
     * receiving player, plus the two instance readings ({@code instance_score} / {@code instance_win}),
     * which answer only where the asking moment carried a run outcome and stay unanswerable - so
     * fail-closed - everywhere else.
     */
    @Nonnull
    private static FactorRegistry lootFactorVocabulary() {
        FactorRegistry registry = new FactorRegistry("loot");
        HytaleFactors.registerInto(registry, LootFactors.OWNER);
        LootFactors.registerInto(registry, LootFactors.OWNER);
        return registry;
    }

    /**
     * Seed the two destinations every server has - a conversation and a character's quest list - into
     * the shared routing vocabulary.
     *
     * <p>It runs in {@code setup()} because a file naming a destination {@code Type} nothing has
     * registered fails to load, and assets are read only after every plugin's setup has returned.
     * Both belong to common rather than to a consumer: the library owns the dialogue engine, and the
     * quest list routes to whatever quest UI registered a host, which is nothing to wire where none
     * did.
     */
    private void registerDestinations() {
        try {
            NpcDestinations.register();
        } catch (Throwable t) {
            SafeLog.warn("[destination] could not seed the generic destinations", t);
        }
    }

    private void registerEditorDataSets() {
        EditorDataSets.live(getEventRegistry(), EditorDataSets.PLACEMENT_FACTORS,
                ZiggfreedCommonPlugin::factorVocabulary);
        EditorDataSets.live(getEventRegistry(), LootEditorDataSets.LOOTABLES,
                LootEditorDataSets::lootableIds);
        EditorDataSets.live(getEventRegistry(), LootEditorDataSets.ROLL_POOLS,
                LootEditorDataSets::rollPoolIds);
        EditorDataSets.live(getEventRegistry(), LootEditorDataSets.REWARD_KINDS,
                LootEditorDataSets::rewardKindIds);
        EditorDataSets.live(getEventRegistry(), EditorDataSets.FACTORS,
                ZiggfreedCommonPlugin::factorVocabulary);
        // The quest vocabularies are per-consumer (each mod builds its own registries), so they are
        // answered from whatever consumers have advertised, plus the engine-generic objective kinds
        // every engine starts with.
        EditorDataSets.live(getEventRegistry(), ProgressEditorDataSets.OBJECTIVE_KINDS,
                ProgressEditorDataSets::objectiveKindIds);
        EditorDataSets.live(getEventRegistry(), ProgressEditorDataSets.REWARD_KINDS,
                ProgressEditorDataSets::rewardKindIds);
    }

    /** Every factor id an author can name here: registered placement providers plus derived assets. */
    @Nonnull
    private static Collection<String> factorVocabulary() {
        Set<String> ids = new TreeSet<>(PlacementFactorRegistry.registeredIds());
        ids.addAll(DerivedFactorConfig.getInstance().ids());
        return ids;
    }

    /**
     * Wire the NPC placement engine. The component and the press-F action must both be registered
     * before any asset decode: a role naming an unregistered action type silently fails to parse,
     * and a component registered after a world loads cannot be read off entities that were saved
     * carrying it.
     *
     * <p>The last registration is the placement content's CROSS-ASSET audit, on the first player
     * ready. Those checks ask another store, an open registry or the engine's loaded assets whether
     * an id exists, and only by then have every store folded and every mod's {@code setup()} run -
     * asked at fold time they report whatever had not loaded yet. The audit runs once per boot and
     * stands down where a consumer claimed it, both decided by
     * {@link NpcPlacementConfig#runLateAudit()}.
     */
    private void setupPlacementEngine() {
        try {
            PlacedNpcComponent.register(getEntityStoreRegistry());
            PlacementNpcActions.register();
            getEntityStoreRegistry().registerSystem(new PlacementMarkerSystem());
            NpcPlacementOverrides.getInstance().load();
            NpcPlacementLedger.getInstance().load();
            getEventRegistry().registerGlobal(PlayerReadyEvent.class,
                    event -> NpcPlacementConfig.getInstance().runLateAudit());
        } catch (Throwable t) {
            SafeLog.warn("[placement] engine setup failed", t);
        }
    }

    /**
     * Wire the talk-credit engine: register the {@code ZigTalkCredit} NPC action, join a
     * conversation's {@code MarkTalked} beat to it, and drop a departing player's re-trigger windows.
     *
     * <p>All three belong to common rather than to a consumer. The dialogue engine deliberately stops
     * at resolving WHO a beat is about, so something has to say what crediting that character means;
     * the NPC action needs nothing from any consumer, unlike {@code ZigOpenDialogue}, which cannot
     * work without one; and the re-trigger window is in memory, so a player who leaves and returns
     * should not find their next conversation swallowed by the last one.
     *
     * <p>The action registration runs here, in {@code setup()}, because a role asset naming a
     * {@code Type} nothing has registered yet silently fails to parse.
     */
    private void setupTalkCredit() {
        try {
            NpcActions.registerTalkCredit();
            NpcTalkDialogue.install();
            getEventRegistry().register(PlayerDisconnectEvent.class, event -> {
                PlayerRef playerRef = event.getPlayerRef();
                UUID uuid = playerRef == null ? null : playerRef.getUuid();
                if (uuid != null) {
                    TalkCredits.clearPlayer(uuid);
                }
            });
        } catch (Throwable t) {
            SafeLog.warn("[talk] could not wire the talk-credit engine", t);
        }
    }

    /**
     * Drive world eviction from common itself (see the class javadoc for why), and sweep a world
     * for missing placements as it is added.
     */
    private void registerWorldLifecycle() {
        try {
            getEventRegistry().registerGlobal(AddWorldEvent.class, event -> {
                try {
                    if (event.isCancelled()) {
                        return;
                    }
                    World added = event.getWorld();
                    if (added == null) {
                        return;
                    }
                    WorldEvictors.onWorldAdded(added);
                    NpcPlacementReconciler.clearDebounce(added);
                    NpcPlacementReconciler.requestSweep(added, added.getEntityStore().getStore());
                } catch (Throwable t) {
                    SafeLog.warn("[placement] world-add handling failed: " + t.getMessage());
                }
            });
            getEventRegistry().registerGlobal(RemoveWorldEvent.class, event -> {
                try {
                    if (event.isCancelled()) {
                        return;
                    }
                    World removed = event.getWorld();
                    if (removed != null) {
                        WorldEvictors.onWorldRemoved(removed);
                    }
                } catch (Throwable t) {
                    SafeLog.warn("[placement] world-removal teardown failed: " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            SafeLog.warn("[placement] could not register the world lifecycle listeners", t);
        }
    }

    /**
     * Keep {@link PlayerIdentityCache} current. Common owns these two listeners for the same reason
     * it owns the world-lifecycle pair above: the cache is common's own primitive, several
     * consumers read it off the world thread, and no consumer can be asked to register another
     * mod's plumbing (nor should two installed consumers each register their own copy).
     */
    private void registerPlayerIdentity() {
        try {
            getEventRegistry().registerGlobal(PlayerReadyEvent.class, PlayerIdentityCache::onPlayerReady);
            getEventRegistry().register(PlayerDisconnectEvent.class, PlayerIdentityCache::onPlayerDisconnect);
        } catch (Throwable t) {
            SafeLog.warn("[identity] could not register the player-identity listeners", t);
        }
    }

    @Override
    protected void shutdown() {
        LOGGER.atInfo().log("ZiggfreedCommon shutdown complete.");
    }
}
