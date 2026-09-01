package com.ziggfreed.common;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nonnull;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.asset.FrameworkAssetRegistrar;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.commerce.CommerceComponent;
import com.ziggfreed.common.commerce.asset.CommerceEditorDataSets;
import com.ziggfreed.common.commerce.command.ZigCommerceCommand;
import com.ziggfreed.common.commerce.fold.CommerceAudit;
import com.ziggfreed.common.commerce.fold.CommerceDefaults;
import com.ziggfreed.common.commerce.fold.CommerceDestinations;
import com.ziggfreed.common.commerce.fold.CommerceEngines;
import com.ziggfreed.common.commerce.fold.CurrencyRewardKind;
import com.ziggfreed.common.commerce.page.CurrencyChipReading;
import com.ziggfreed.common.currency.asset.CurrencyConfig;
import com.ziggfreed.common.entity.EntityBootstrap;
import com.ziggfreed.common.factor.DerivedFactorConfig;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.factor.HytaleFactors;
import com.ziggfreed.common.feedback.moment.FeedbackEngine;
import com.ziggfreed.common.loot.LootCues;
import com.ziggfreed.common.loot.LootEditorDataSets;
import com.ziggfreed.common.loot.LootFactors;
import com.ziggfreed.common.loot.reward.DroplistRewardKind;
import com.ziggfreed.common.loot.reward.FeetDropOverflow;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.stamp.StackStatsStamper;
import com.ziggfreed.common.loot.stamp.StamperRegistry;
import com.ziggfreed.common.npc.NpcBootstrap;
import com.ziggfreed.common.npc.NpcDestinations;
import com.ziggfreed.common.npc.placement.registry.PlacementFactorRegistry;
import com.ziggfreed.common.objectives.dialogue.DialogueBootstrap;
import com.ziggfreed.common.objectives.runtime.ProgressionBootstrap;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.progress.asset.ProgressEditorDataSets;
import com.ziggfreed.common.reward.EffectRewardKind;
import com.ziggfreed.common.rotation.SelectionStrategies;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.ShopPoolConfig;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.placed.PlacedBlockBootstrap;

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
 * <p>The static primitives register nothing (a consumer calls them directly), and the library's own
 * registrations live in per-module bootstraps this class calls in one authoritative order:
 * {@code EntityBootstrap} (zc-entity), {@code NpcBootstrap} (zc-dialogue),
 * {@code PlacedBlockBootstrap} (zc-world), and {@code ProgressionBootstrap} +
 * {@code DialogueBootstrap} (zc-objectives). Each bootstrap lives in the module that already sees
 * everything its phase wires, so the phase can be read and reasoned about without standing up the
 * whole plugin. What remains a ROOT-OWNED body is only what no single module can host, each phase
 * pinned by a specific line:
 * <ul>
 *   <li>{@link #registerDestinations()} - {@code NpcDestinations} alone is zc-dialogue's, but
 *       {@code CommerceDestinations.register()} pins the pair here: nothing in the library depends
 *       on zc-commerce, and commerce may never import dialogue (the reverse-edge ban in
 *       zc-commerce's build file), so no module can seed both;</li>
 *   <li>{@link FrameworkAssetRegistrar#registerAll} - the framework asset stores, owned at
 *       {@code Server/ZiggfreedCommon/<Type>/} exactly once; its registrations span the commerce,
 *       dialogue, progression and instance domains at once, and only this wiring root sees all of
 *       them;</li>
 *   <li>{@link #registerEditorDataSets()} - the Asset Editor pick lists; pinned by its
 *       {@code CommerceEditorDataSets} datasets (currencies, shops, shop pools, boards, selection
 *       types), which sit beside loot, progression and factor datasets no module could reach
 *       together with them;</li>
 *   <li>{@link #registerLootVocabulary()} - pinned by
 *       {@code EffectRewardKind.registerInto(RewardKinds.shared())}: {@link EffectRewardKind}
 *       lives in this root module because the loot layer may never see the effect module, and no
 *       module can see the root;</li>
 *   <li>{@link #registerCommerce()} - pinned by
 *       {@code CommerceEngines.installGates(ProgressionDefaults::gateEvaluator)}: nothing in the
 *       library depends on zc-commerce, so no module sees commerce and objectives together.</li>
 * </ul>
 * Both of the last two stay WHOLE rather than being split so an orphan line could move: the one
 * pinned line documents the constraint better than a phase scattered across two files would.
 *
 * <p><b>REGISTRATION ONLY (build-enforced).</b> This class registers, wires and populates; it never
 * DECIDES. The wiring root is the one place in the library where an edge between any two modules is
 * legal, so it will absorb any awkward dependency offered to it - and logic that settles here is
 * logic no module can be tested or reasoned about without standing up the whole plugin. When a
 * choice has to be made, it belongs in the module that owns it, behind a seam this class fills.
 * {@code RootRegistrationOnlyTest} fails the build on a loop, a {@code switch} or an {@code else}
 * here AND in every module's {@code *Bootstrap} - the rule follows registration code wherever it
 * lives; a try/catch guard, a null-or-early-return {@code if}, and a null-defaulting ternary all
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
        registerCommerce();
        NpcBootstrap.setupPlacementEngine(this);
        DialogueBootstrap.registerActiveObjectiveHeader();
        EntityBootstrap.registerPerformerIdentity(this);
        EntityBootstrap.registerFlairs(this);
        NpcBootstrap.setupTalkCredit(this);
        NpcBootstrap.registerWorldLifecycle(this);
        EntityBootstrap.registerPlayerIdentity(this);
        // What makes a stamped stat real: the equip bridge turns a held / worn / offhand stack's
        // stored entries into modifiers on the entity, and takes them off again with the item. It is
        // installed here so a server running this library and nothing else still gets working
        // stamped gear; a consumer hangs its own post-apply work on the installed instance rather
        // than installing a second one, which would double every bonus.
        EntityBootstrap.installEquipStatBridge(this);
        PlacedBlockBootstrap.setupPlacedBlockLedger(this);
        ProgressionBootstrap.setupProgressionRuntime(this);
        ProgressionBootstrap.registerFeedbackMoments();
        DialogueBootstrap.registerDialogueVocabulary();
        DialogueBootstrap.registerDialogueMemories(this);
        ProgressionBootstrap.registerQuestListHost();

        LOGGER.atInfo().log("ZiggfreedCommon setup complete (framework stores + shared primitives available).");
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
            CommerceDestinations.register();
        } catch (Throwable t) {
            SafeLog.warn("[destination] could not seed the generic destinations", t);
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
        // The commerce pick lists answer off the folded content itself, so an author picks a wallet,
        // a storefront, a shelf or a board that this server genuinely has - and a pack loaded later
        // simply widens the next answer.
        EditorDataSets.live(getEventRegistry(), CommerceEditorDataSets.CURRENCIES,
                CurrencyConfig.getInstance()::ids);
        EditorDataSets.live(getEventRegistry(), CommerceEditorDataSets.SHOPS,
                ShopConfig.getInstance()::ids);
        EditorDataSets.live(getEventRegistry(), CommerceEditorDataSets.SHOP_POOLS,
                ShopPoolConfig.getInstance()::ids);
        EditorDataSets.live(getEventRegistry(), CommerceEditorDataSets.BOARDS,
                BoardConfig.getInstance()::ids);
        EditorDataSets.live(getEventRegistry(), CommerceEditorDataSets.SELECTION_TYPES,
                SelectionStrategies::types);
    }

    /** Every factor id an author can name here: registered placement providers plus derived assets. */
    @Nonnull
    private static Collection<String> factorVocabulary() {
        Set<String> ids = new TreeSet<>(PlacementFactorRegistry.registeredIds());
        ids.addAll(DerivedFactorConfig.getInstance().definedIds());
        return ids;
    }

    /**
     * The loot vocabulary a bare server starts with: the three framework reward kinds, the droplist
     * kind (a native drop table rolled onto the ground), the effect kind (registered from up here
     * because the loot layer must never see the effect module), the stack-metadata stamper every
     * stamp writes through until a richer mod replaces it, and the default overflow policy - an item
     * reward that does not fit the bag drops on the ground at the player's feet through the one
     * tick-safe spawn seam ({@link FeetDropOverflow}), so a full inventory means a pickup, not a
     * lost reward. Each is a DEFAULT: a consumer's own registration, made in its setup after this
     * one, replaces it.
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
        LootRewardKinds.overflow(new FeetDropOverflow());
        StamperRegistry.register(new StackStatsStamper());
        // What an authored Cue MEANS, for every consumer at once: the cue id IS the FeedbackMoment
        // id. This wiring lives here rather than in either module because loot and presentation are
        // sibling modules that cannot see each other, and this root is the one place that sees both.
        // The mapping is the identity, so a table writes "Cue": "X", a FeedbackMoments/X.json says
        // what X does, and nothing anywhere needs Java. A consumer wanting something richer replaces
        // the presenter; a cue nobody authored a moment for does nothing, which is the feedback
        // engine's own rule.
        LootCues.register((cueId, subject, sourceId) -> FeedbackEngine.fire(cueId, subject, Map.of()));
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
     * The economy a bare server starts with: the per-player component its state is kept on, the
     * component-backed store over it, the currency engine over whatever wallets the packs authored,
     * the reward kind that pays one, and the admin command family that drives all of them.
     *
     * <p>The component TYPE is registered here and unconditionally, because a component type
     * registered after a world has loaded cannot be read off entities saved carrying it - whether a
     * consumer ends up installing its own store is a question that cannot wait for. Attaching one is
     * conditional, and that decision belongs to the module (see {@code CommerceDefaults}).
     *
     * <p>The kind is UNPREFIXED and registered from up here for the same reason the effect kind is:
     * the loot layer sits underneath everything that pays out and must never reach sideways into a
     * domain, so it keeps a hole where a wallet grant would be and this layer - which can see both
     * ends - fills it.
     *
     * <p>The commands belong to the library because the module that owns an engine owns the commands
     * that drive it: every verb reads and writes through the same catalogues, currency engine and
     * state store the pages and the payouts use. A consumer wanting its own spelling registers an
     * alias that calls through, never a second implementation. Their permission nodes are derived
     * and enforced by the engine itself, so no check is written anywhere in the family.
     *
     * <p>The chip reading is contributed beside the kind for the same reason the kind is registered
     * here: a {@code Currency} reward reads as its wallet's own name and icon on every surface at
     * once, so no reward ever authors a display key for a wallet that already knows what it is
     * called.
     *
     * <p>The gate seam is filled with the evaluator the progression runtime's defaults build, so a
     * shop lock and a quest lock answer one {@code Requires} block the same way. It is a supplier
     * read per gate: a consumer that installs its own evaluator at its own setup replaces this one,
     * and until the defaults have registered the seam answers its fail-closed default.
     *
     * <p>The content audit runs once per boot at first player ready, like the placement audit and
     * for the same reason: its checks ask other stores and open registries whether an id exists, and
     * only by then have every store folded and every mod's {@code setup()} run.
     *
     * <p>Installing the defaults here is safe rather than a clobber because every consumer declares
     * this library as a dependency, so the server loads it first and a consumer that keeps this state
     * itself replaces both in its own {@code setup()}.
     */
    private void registerCommerce() {
        try {
            CommerceComponent.register(getEntityStoreRegistry());
            CommerceDefaults.install(this);
            CurrencyRewardKind.registerInto(RewardKinds.shared());
            RewardChips.contribute(CurrencyChipReading.source());
            CommerceEngines.installGates(ProgressionDefaults::gateEvaluator);
            getCommandRegistry().registerCommand(new ZigCommerceCommand());
            getEventRegistry().registerGlobal(PlayerReadyEvent.class,
                    event -> CommerceAudit.runLateAudit());
        } catch (Throwable t) {
            SafeLog.warn("[commerce] economy wiring failed", t);
        }
    }

    @Override
    protected void shutdown() {
        // Nothing to write for placements: each one rides its own chunk's save, so a restart finds
        // them exactly where it left them without this plugin persisting anything of its own.
        LOGGER.atInfo().log("ZiggfreedCommon shutdown complete.");
    }
}
