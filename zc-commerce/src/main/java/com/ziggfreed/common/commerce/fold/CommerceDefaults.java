package com.ziggfreed.common.commerce.fold;

import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.commerce.CommerceStore;
import com.ziggfreed.common.commerce.CommerceStores;
import com.ziggfreed.common.commerce.ComponentCommerceStore;
import com.ziggfreed.common.commerce.page.CurrencyNotice;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.progress.gate.GatedContent;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestGates;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a BARE server's economy runs on: the state store nothing has replaced yet, and the currency
 * engine reading the wallets its own packs authored.
 *
 * <p>Both are producer REPLACEMENT rather than layering. A consumer keeping this state itself
 * installs its own at setup and the previous one stops being asked, so two stores holding two
 * versions of one wallet cannot exist. Common's own {@code setup()} runs first (every consumer
 * declares it as a dependency, so the server loads it first), which is what makes installing the
 * defaults here safe rather than a clobber.
 *
 * <p>The store default is deliberately said out loud rather than left implicit: which store a server
 * is running is the difference between purchases that survive a restart and purchases that do not,
 * and it should be readable in the boot log rather than inferred from behaviour.
 *
 * <p><b>The component attach is the one piece with a condition on it.</b> The component TYPE is
 * registered unconditionally by the wiring root, because a type registered after a world has loaded
 * cannot be read off entities saved carrying it; ATTACHING one is skipped where a consumer installed
 * its own store, so a server that keeps this state elsewhere never has an unread component stamped
 * onto every player.
 */
public final class CommerceDefaults {

    private static final AtomicReference<CurrencyEngine> CURRENCY = new AtomicReference<>();

    private CommerceDefaults() {
    }

    /**
     * Whether the board a contract was taken from is still up to collect it at: the quest engine's
     * "is the place still there" question ({@link QuestGates#siteStillThere}), answered for contracts
     * alone. A contract's site is the board's id; the board is there while one of that id is loaded and
     * {@link BoardAsset#isAvailable()} (switched on, in its season, its feature on), never judged by a
     * viewer's {@code Where}. A board taken down or out of season is gone, so what was taken there is
     * collected anywhere. Every quest no board posts keeps its site.
     */
    static final QuestGates BOARD_SITES = new QuestGates() {
        @Override
        public boolean siteStillThere(@Nonnull Quest quest, @Nonnull String siteId) {
            if (AssetBoardCatalog.getInstance().bounty(quest.id()) == null) {
                return true;
            }
            BoardAsset board = BoardConfig.getInstance().resolve(siteId);
            return board != null && board.isAvailable();
        }
    };

    /**
     * Install the defaults, once, from the wiring root's {@code setup()}: the component-backed state
     * store, the currency engine over the authored wallets, the connect hook that gives each
     * player somewhere to keep it, the gated offers and board slots a requirement's reader
     * lists ({@link CommerceGatedContent}), and the board answer to whether a contract's site is still
     * there ({@link #registerQuestSites}).
     */
    public static void install(@Nonnull PluginBase plugin) {
        CommerceStores.install(ComponentCommerceStore.INSTANCE);
        installCurrencyEngine(assetBacked());
        CurrencyFactors.contribute();
        GatedContent.register(CommerceGatedContent.ID, CommerceGatedContent::entries);
        registerQuestSites();
        plugin.getEventRegistry().register(PlayerConnectEvent.class, CommerceDefaults::onPlayerConnect);
        SafeLog.info("[commerce] economy ready: wallets, prices and rotations read the authored "
                + "content, and commerce state is kept on a per-player component saved with the world");
    }

    /**
     * Contribute {@link #BOARD_SITES} to the shared progression runtime at library-default rank, so a
     * server running the library alone collects a contract whose board is gone. Called by
     * {@link #install}; idempotent, since the runtime keeps one contribution per instance.
     */
    public static void registerQuestSites() {
        ProgressionRuntime.defaults(CommerceDestinations.OWNER).questGates(BOARD_SITES);
    }

    /**
     * Give a connecting player a commerce component, the one moment a {@code Holder} is in hand.
     * Skipped wherever a consumer installed a store of its own, since nothing would ever read it.
     */
    private static void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        try {
            if (!usesComponentStore()) {
                return;
            }
            ComponentCommerceStore.ensureOn(event.getHolder());
        } catch (Throwable t) {
            SafeLog.warn("[commerce] could not ensure the commerce component", t);
        }
    }

    /** True while the library's own component-backed store is the one in force. */
    public static boolean usesComponentStore() {
        CommerceStore store = CommerceStores.get();
        return store == ComponentCommerceStore.INSTANCE;
    }

    /**
     * Replace the currency engine every commerce surface reads through. Passing null restores the
     * default one over the authored wallets.
     *
     * <p>The library's own listeners ride whichever engine is installed ({@link #withLibraryObservers}):
     * they hear each earn AFTER the engine's own observer, so a consumer's replacement keeps its own
     * announcements and the library's notice still speaks on a server running the library alone.
     */
    public static void installCurrencyEngine(@Nullable CurrencyEngine engine) {
        CURRENCY.set(engine == null ? null : withLibraryObservers(engine));
    }

    /**
     * The currency engine in force. Never null: a caller reaching for it before anything installed
     * one - an early command, a test - gets the default over the authored wallets rather than
     * having to guard.
     */
    @Nonnull
    public static CurrencyEngine currencyEngine() {
        return CURRENCY.updateAndGet(current -> current != null ? current : assetBacked());
    }

    /**
     * The default engine: the authored wallets, the real inventory behind an item-backed balance, and
     * whichever state store is installed AT CALL TIME (never captured, because a consumer's setup may
     * run after this).
     */
    @Nonnull
    private static CurrencyEngine assetBacked() {
        return withLibraryObservers(CurrencyEngine.builder().catalog(CommerceCatalogs.currencies()).build());
    }

    /**
     * {@code engine} with what the library itself hears of an earn added after its own observer: the
     * first-earn notice that tells a player what a wallet is for ({@link CurrencyNotice}). An engine that
     * already carries it is answered unchanged, so re-installing the engine in force adds nothing twice.
     */
    @Nonnull
    private static CurrencyEngine withLibraryObservers(@Nonnull CurrencyEngine engine) {
        return engine.alsoObservedBy(CurrencyNotice.OBSERVER);
    }
}
