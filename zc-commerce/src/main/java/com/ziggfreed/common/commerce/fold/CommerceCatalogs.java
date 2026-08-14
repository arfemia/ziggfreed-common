package com.ziggfreed.common.commerce.fold;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.board.asset.BoardAssetStore;
import com.ziggfreed.common.currency.CurrencyCatalog;
import com.ziggfreed.common.progress.asset.GeneratorCore;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.asset.QuestDefinition;
import com.ziggfreed.common.quest.asset.QuestEnumeratorRegistry;
import com.ziggfreed.common.quest.asset.QuestGeneratorExpander;
import com.ziggfreed.common.shop.ShopCatalog;
import com.ziggfreed.common.shop.asset.ShopPoolAsset;
import com.ziggfreed.common.shop.asset.ShopPoolConfig;
import com.ziggfreed.common.util.SafeLog;

/**
 * Where a surface gets the live content: the wallets, the offers and the boards, each read off what
 * this server actually has loaded.
 *
 * <p>Two of the three answer straight off their config fold, so they need nothing kept current. The
 * offers are the exception, because an offer FAMILY written as one generator file has to be expanded
 * before it exists, and expansion needs the value sources a consumer registered. So the shop
 * catalogue is rebuilt on {@link #refreshShops()}, which the wiring root calls off the offer stores'
 * own load events.
 *
 * <p><b>ONE enumerator vocabulary, however many stores walk axes.</b> The rows behind
 * {@code "yourmod:skills"} are registered ONCE, by whichever mod knows them, and both the quest
 * generators and the offer generators read the same list - which is the whole reason the expander
 * takes a seam rather than owning a table. A consumer installs its registry here
 * ({@link #installAxisValues}) exactly as it hands the same registry to the quest store, and
 * {@link #axisValuesOf} is the one-liner that adapts a quest enumerator registry into the shape both
 * take.
 *
 * <p>With nothing installed the generators write nothing and the store's own findings say which
 * source went unanswered, rather than a family silently shipping half-written.
 */
public final class CommerceCatalogs {

    /** Who this module's published content layer is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    private static final AtomicReference<Supplier<GeneratorCore.AxisValueSource>> AXIS_VALUES =
            new AtomicReference<>();

    private CommerceCatalogs() {
    }

    // ==================== The catalogs ====================

    /** Which wallets exist. Live: a reload lands on the next lookup. */
    @Nonnull
    public static CurrencyCatalog currencies() {
        return AssetCurrencyCatalog.getInstance();
    }

    /** Which offers exist, and which shelf each belongs to. Rebuilt by {@link #refreshShops()}. */
    @Nonnull
    public static ShopCatalog shops() {
        return AssetShopCatalog.getInstance();
    }

    /** Which offers exist, in the fuller form a storefront page reads. */
    @Nonnull
    public static AssetShopCatalog shopContent() {
        return AssetShopCatalog.getInstance();
    }

    /** Which boards exist and what may be posted on them. Live: a reload lands on the next lookup. */
    @Nonnull
    public static AssetBoardCatalog boards() {
        return AssetBoardCatalog.getInstance();
    }

    /**
     * The rotating shelves a storefront stands, in authored order. Live off the shelf fold, like the
     * wallets and the boards: a shelf is one authored file and nothing about it has to be expanded
     * first, so there is nothing here to refresh or invalidate.
     */
    @Nonnull
    public static List<ShelfSpec> shelvesOf(@Nonnull String shopId) {
        List<ShopPoolAsset> authored = ShopPoolConfig.getInstance().shelvesOf(shopId);
        List<ShelfSpec> out = new ArrayList<>(authored.size());
        for (ShopPoolAsset asset : authored) {
            if (asset != null && asset.isEnabled()) {
                out.add(ShelfSpec.of(asset));
            }
        }
        return out;
    }

    // ==================== The value-source seam ====================

    /**
     * Install where an offer generator's axes get their rows. Call it at setup with the SAME registry
     * the quest generators read, so one registered vocabulary serves both.
     *
     * <pre>{@code
     * CommerceCatalogs.installAxisValues(() -> CommerceCatalogs.axisValuesOf(myEnumerators));
     * }</pre>
     *
     * <p>It is a supplier rather than a value because a consumer's registry keeps growing after
     * setup: the source is asked at every refresh, so a mod registering a list late simply widens the
     * next fold.
     */
    public static void installAxisValues(@Nullable Supplier<GeneratorCore.AxisValueSource> source) {
        AXIS_VALUES.set(source);
    }

    /**
     * The installed value source, or null when nothing was installed. A throwing supplier answers
     * null with one line, because a broken source must cost the generated offers rather than the
     * whole fold.
     */
    @Nullable
    public static GeneratorCore.AxisValueSource axisValues() {
        Supplier<GeneratorCore.AxisValueSource> source = AXIS_VALUES.get();
        if (source == null) {
            return null;
        }
        try {
            return source.get();
        } catch (Throwable t) {
            SafeLog.warn("[commerce] the installed generator value source could not be read, so no "
                    + "offer family was written this fold", t);
            return null;
        }
    }

    /**
     * A quest enumerator registry as an offer generator reads it. The adaptation itself belongs to
     * the shared expander; this is the one line that says the two stores speak the same vocabulary,
     * so a consumer never has to know they do.
     */
    @Nullable
    public static GeneratorCore.AxisValueSource axisValuesOf(
            @Nullable QuestEnumeratorRegistry enumerators) {
        return QuestGeneratorExpander.axisValues(enumerators);
    }

    // ==================== Keeping the offers current ====================

    /**
     * Rebuild the offer catalogue from whatever the offer stores hold right now. Called off both
     * offer load events, because a family is only whole once the generators have loaded and the
     * catalogue has to be usable in between either way.
     */
    public static void refreshShops() {
        AssetShopCatalog.getInstance().refresh(axisValues());
    }

    /**
     * Hand every authored contract to the shared quest runtime, as the layer this library owns.
     *
     * <p><b>A bounty IS a quest, and this is the line that makes that true at runtime.</b> Until it
     * runs, a board can draw its contracts and name them and still not accept one, because the
     * lifecycle a board drives belongs to the quest engine and the engine has never heard of them.
     * Called by the wiring root off the contract store's own load event, beside the shop refresh.
     *
     * <p>Published as a LAYER rather than merged into anybody's catalogue, so a consumer that
     * publishes its own contracts outranks these the same way it outranks every other library
     * default, and a reload replaces this layer wholesale rather than accumulating.
     */
    public static void publishBounties() {
        try {
            Map<String, QuestDefinition> definitions = BoardAssetStore.getInstance().resolveAll();
            List<Quest> quests = new ArrayList<>(definitions.size());
            for (QuestDefinition definition : definitions.values()) {
                if (definition != null) {
                    quests.add(definition.quest());
                }
            }
            ProgressionRuntime.publishQuests(OWNER, quests);
        } catch (Throwable t) {
            SafeLog.warn("[commerce] the authored contracts could not be published to the quest "
                    + "runtime, so boards have nothing to accept", t);
        }
    }
}
