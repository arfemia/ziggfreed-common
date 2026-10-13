package com.ziggfreed.common.commerce.page;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.commerce.CommerceStores;
import com.ziggfreed.common.commerce.asset.WhereAxis;
import com.ziggfreed.common.commerce.fold.CommerceDefaults;
import com.ziggfreed.common.commerce.fold.CommerceEngines;
import com.ziggfreed.common.commerce.fold.ShelfSpec;
import com.ziggfreed.common.commerce.fold.ShopEntryOffer;
import com.ziggfreed.common.commerce.fold.StorefrontView;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.cost.ItemCost;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.gate.GateRefusal;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.LockReasons;
import com.ziggfreed.common.shop.PurchaseLimits;
import com.ziggfreed.common.shop.ShopEngine;
import com.ziggfreed.common.shop.ShopOffer;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.StorefrontAsset;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.UiText;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.icon.IconRenderer;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Pill;
import com.ziggfreed.common.ui.kit.TilePainter;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.ui.kit.WareTile;
import com.ziggfreed.common.ui.toast.RewardToastLines;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * A storefront: what it sells on the left, the offer being read on the right, and what the buyer is
 * carrying across the top.
 *
 * <p>This is the GENERIC screen, driven entirely through the commerce engines and the authored
 * catalogue, so it renders whatever storefronts a server has whoever shipped them. A consumer
 * contributes what the library cannot know through {@link CommercePageDeps} - a theme, what its
 * wallets are called, what a generated row's arguments mean - and never a page of its own.
 *
 * <h2>Two kinds of run, one list</h2>
 *
 * <p>A storefront's rotating SHELVES read first, each under a heading carrying the countdown to its
 * next turnover, because they are the part that will not be there tomorrow. Its standing catalogue
 * reads below, in the category order the storefront itself declared. Every run is the kit's titled
 * section of ware tiles, the vanilla Memories grid: a picture, a name and a price per ware, the way a
 * vendor's window reads, and a ware out of reach dimmed with its reason on hover.
 *
 * <p>Two dropdowns narrow the list: one names every run the storefront draws (each shelf, each
 * category), the other keeps only the rotating or only the standing stock. Both are page-instance
 * state like the selection - alive across every rebuild of one visit, forgotten when the page is
 * next opened - and the count line always reports what the filters actually left on the page. A
 * pair that keeps nothing shows the empty line that says so, which is the honest reading of it.
 *
 * <h2>Nothing is charged for something that could not have happened</h2>
 *
 * <p>Every button on this page is bound whether or not it can succeed, and every press re-asks the
 * engine rather than trusting what the screen said: an offer that rotated out between render and
 * click refuses with a line instead of charging, and a reroll probes for an alternative BEFORE its
 * price is drained. A locked offer still shows its reason up front, so the answer is legible before
 * anybody clicks.
 *
 * <p>EVERY exit path sends a response - a reopen, a partial update, or a close - or the client spins
 * forever.
 */
public final class ZigShopPage extends ToastablePage<ShopEventData> {

    private static final String PAGE_TEMPLATE = "Pages/ZigShopPage.ui";

    /** The scrolling list the runs are appended into, one titled section of tiles each. */
    private static final String LIST = "#OfferList";

    /** What a theme is offered to repaint: the panel carrying the catalogue. */
    private static final String FRAME_SELECTOR = "#LeftPanel";

    /** This library's own lang namespace; {@link Msg#tr} concatenates it with the key verbatim. */
    private static final String PREFIX = "ziggfreedcommon.";

    /** The domain segment every key on this page carries (the {@code ziggfreedcommon.commerce.lang} file). */
    private static final String DOMAIN = "commerce.";

    /** A hard ceiling on tiles, so a very large catalogue cannot build an unbounded page. */
    private static final int MAX_TILES = 200;

    /** A ceiling on the lines inside one detail section. */
    private static final int MAX_LINES = 24;

    /** A ceiling on the chips in one strip, so a wide price cannot push a row off the panel. */
    private static final int MAX_CHIPS = 6;

    /** The reroll button's words at rest; armed, they turn the short colour. */
    private static final String REROLL_TEXT = "#c6d4e4";

    /** What stands between this buyer and an offer, as its tile shows it. */
    private enum Reach {
        /** Buyable now. */
        READY,
        /** Short of a wallet or an item: the price reads in the danger ink. */
        SHORT,
        /** No room in the bag for what it hands over. */
        NO_ROOM,
        /** A daily or total limit is used up: dimmed, its pill says which. */
        LIMITED,
        /** Behind a requirement, switched off or rotated away: dimmed, with the lock. */
        LOCKED
    }

    @Nonnull private final String shopId;

    @Nonnull private final CommercePageDeps deps;

    @Nullable private String selectedOfferId;

    /**
     * The two list filters: one run by {@link ShopSections#runKey}, and one of the
     * {@link ShopSections#KIND_ROTATING} / {@link ShopSections#KIND_STANDING} kinds. Page-instance
     * state exactly like the selection - they survive the rebuild a purchase forces and die with
     * the page, so every fresh open starts unfiltered and nothing is persisted anywhere.
     */
    @Nonnull private String runFilter = ShopSections.FILTER_ALL;
    @Nonnull private String kindFilter = ShopSections.FILTER_ALL;

    /** Two clicks before a reroll charges anything. Page-instance state, never persisted. */
    private final ConfirmArm rerollArm = new ConfirmArm();

    /**
     * The exact tiles the last full build drew: each offer's normalized id to the button selector
     * {@link TilePainter#wares} answered for it, so a partial update addresses the tile the client
     * DOM actually holds. An offer the record lacks reopens the page instead. Every press that
     * changes what a tile says reopens this page, so this is read only as the record of what is on
     * screen.
     */
    private final Map<String, String> wareOf = new LinkedHashMap<>();

    /** Which shelf and position each drawn offer came from, for a reroll press. */
    private final Map<String, ShelfPosition> shelfOf = new LinkedHashMap<>();

    /** Where one drawn offer sits: which rotating shelf, and which slot of it. */
    private record ShelfPosition(@Nonnull String shelfId, int position) {
    }

    public ZigShopPage(@Nonnull PlayerRef playerRef, @Nonnull String shopId,
            @Nonnull CommercePageDeps deps) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ShopEventData.CODEC);
        this.shopId = shopId;
        this.deps = deps;
    }

    // ==================== build ====================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        appendTemplate(cmd);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                EventData.of("Action", "close"));

        wareOf.clear();
        shelfOf.clear();

        StorefrontAsset asset = shopAsset();
        cmd.set("#ShopTitle.TextSpans", CommerceText.title(asset == null ? null : asset.getText(),
                deps.titleArgs(), text("shop.title")));
        setIcon(cmd, "#ShopIconSlot", "#ShopIcon", asset == null ? null : asset.getIcon());

        Subject subject = subjectOf(store, ref);
        if (subject == null) {
            // Nothing can be read for this player, so say so once rather than painting an empty
            // catalogue, which is a different sentence.
            showEmpty(cmd, text("shop.empty.unavailable"));
            renderToastInto(cmd);
            return;
        }

        CurrencyEngine currencies = CommerceDefaults.currencyEngine();
        CommerceChips.render(cmd, "#BalanceRow",
                CommerceChips.balances(currencies, subject,
                        StorefrontView.currencyIds(shopId), deps.currencyNames()),
                MAX_CHIPS);

        // Switched off, hidden by a feature that reads off, or not in the world this player stands
        // in: any of them and it is not open here.
        if (asset != null && !asset.isAvailableIn(WhereAxis.viewer(store))) {
            showEmpty(cmd, text("shop.empty.closed"));
            renderToastInto(cmd);
            return;
        }

        long now = System.currentTimeMillis();
        ShopEngine engine = CommerceEngines.shopsAt(shopId);
        List<Run> runs = runsOf(engine, subject, now);
        if (runs.isEmpty()) {
            // The filters stay hidden with nothing to narrow: an empty storefront is its own line.
            showEmpty(cmd, text("shop.empty.nothing"));
            renderToastInto(cmd);
            return;
        }
        renderFilters(cmd, events, runs);

        List<Run> visibleRuns = new ArrayList<>(runs.size());
        List<String> orderedIds = new ArrayList<>();
        for (Run run : runs) {
            if (!ShopSections.runMatches(run.kind(), run.runId(), runFilter, kindFilter)) {
                continue;
            }
            visibleRuns.add(run);
            for (ShopOffer offer : run.offers()) {
                orderedIds.add(offer.offerId());
            }
        }
        // The count reports what is actually on the page, filters applied, or the line would say
        // "13 for sale" over a list showing three.
        cmd.set("#OfferCount.TextSpans", text("shop.count", orderedIds.size()));
        if (orderedIds.isEmpty()) {
            showEmpty(cmd, text("shop.empty.filtered"));
            renderToastInto(cmd);
            return;
        }
        this.selectedOfferId = ShopSections.select(orderedIds, selectedOfferId);

        appendRuns(cmd, events, engine, subject, visibleRuns, now);

        // Bound ONCE per build with no offer id: the handlers act on whatever the detail panel is
        // showing, so a partial update can swap the panel without needing a binding it cannot add.
        bindAction(events, "#BuyBtn", "buy");
        bindAction(events, "#RerollBtn", "reroll");

        ShopOffer selected = selectedOfferId == null ? null : engine.catalog().offer(selectedOfferId);
        if (selected != null) {
            renderDetail(cmd, engine, subject, selected, now);
        } else {
            cmd.set("#RightPanel.Visible", false);
        }
        renderToastInto(cmd);
    }

    /**
     * Get the page's markup onto the screen, through a consumer's theme where there is one.
     *
     * <p>Guarded, and the fallback is the plain append rather than nothing: a theme is decoration,
     * and a decoration that throws must not cost the player the whole screen. A theme that threw
     * AFTER appending would append twice, so the retry only runs when nothing landed.
     */
    private void appendTemplate(@Nonnull UICommandBuilder cmd) {
        try {
            deps.theme().appendThemed(cmd, PAGE_TEMPLATE, FRAME_SELECTOR);
            return;
        } catch (Throwable t) {
            SafeLog.warn("[commerce] a page theme failed, so the storefront renders plain: "
                    + t.getMessage());
        }
        cmd.append(PAGE_TEMPLATE);
    }

    /**
     * The subject the commerce store and the wallets understand.
     *
     * <p>It comes from the shared progression runtime rather than being built here, and that is not a
     * formality: a subject built locally carries no handle the installed stores recognise, so every
     * balance reads zero and every purchase count silently fails to record. Commerce and progression
     * deliberately speak the ONE subject vocabulary, which is also what lets a board's contracts and
     * a storefront's wallet belong to the same player.
     */
    @Nullable
    private Subject subjectOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            // The build argument is the page's ANCHOR, which at a character is that character's own
            // entity, so the player is resolved from the reference this page was built with.
            return ProgressionRuntime.subjects().questSubject(store, playerEntityRef(ref));
        } catch (Throwable t) {
            SafeLog.warn("[commerce] no subject could be built for this player: " + t.getMessage());
            return null;
        }
    }

    /**
     * The storefront this page was opened at, or null when this server holds no such file. Read on
     * demand rather than held, because a reload replaces the asset while the page is open, and
     * guarded, because a storefront that cannot be read must cost the page its decoration rather than
     * its contents.
     */
    @Nullable
    private StorefrontAsset shopAsset() {
        try {
            return ShopConfig.getInstance().resolve(shopId);
        } catch (Throwable notLoadedYet) {
            return null;
        }
    }

    // ==================== the runs ====================

    /**
     * One run of rows: its heading, the clock under it when it has one, what it holds, and the
     * kind + id the filters and {@link ShopSections#runKey} know it by (a shelf's id, or a
     * standing category's key).
     */
    private record Run(@Nonnull Message heading, @Nullable Message meta,
                       @Nonnull List<ShopOffer> offers, @Nonnull ShopSections.Kind kind,
                       @Nonnull String runId) {
    }

    /**
     * What this storefront shows right now: its rotating shelves, each drawn for THIS buyer (their
     * own re-rolled positions laid over the shared draw), then its standing catalogue grouped the way
     * the storefront asked for.
     */
    @Nonnull
    private List<Run> runsOf(@Nonnull ShopEngine engine, @Nonnull Subject subject, long now) {
        List<Run> runs = new ArrayList<>();
        List<String> shelved = new ArrayList<>();
        for (ShelfSpec shelf : StorefrontView.shelves(shopId)) {
            List<ShopOffer> drawn = engine.activeShelfFor(subject, shelf, now);
            if (drawn.isEmpty()) {
                continue;
            }
            for (int position = 0; position < drawn.size(); position++) {
                ShopOffer offer = drawn.get(position);
                shelved.add(CommerceText.normalize(offer.offerId()));
                shelfOf.put(CommerceText.normalize(offer.offerId()),
                        new ShelfPosition(shelf.shelfId(), position));
            }
            runs.add(new Run(
                    CommerceText.title(shelf.asset().getText(), deps.titleArgs(), text("shop.shelf")),
                    text("shop.refresh_in",
                            CommerceText.countdownMessage(shelf.rotation().millisUntilNext(now))),
                    drawn, ShopSections.Kind.SHELF, shelf.shelfId()));
        }

        List<ShopSections.Entry> standing = new ArrayList<>();
        Map<String, ShopOffer> byId = new LinkedHashMap<>();
        // Only what is on sale right now: an offer switched off, hidden by a feature or its Season, or
        // standing in a storefront that is gone is absent from the page, never shown locked. An included
        // storefront's offers follow this one's own, standing in this one (StorefrontView).
        for (ShopEntryOffer offer : StorefrontView.offers(shopId)) {
            String id = CommerceText.normalize(offer.offerId());
            if (offer.poolId() != null || shelved.contains(id)) {
                // A shelf offer stands on its shelf or nowhere: showing it twice would let one
                // rotating slot read as two things for sale.
                continue;
            }
            byId.put(id, offer);
            standing.add(new ShopSections.Entry(offer.offerId(),
                    offer.asset().getListing() == null ? null : offer.asset().getListing().getCategory(),
                    offer.asset().getListing() == null ? 0
                            : offer.asset().getListing().sortOrderOrZero()));
        }
        List<String> categoryOrder = StorefrontView.categoryOrder(shopId);
        for (ShopSections.Section section : ShopSections.standing(standing, categoryOrder)) {
            List<ShopOffer> offers = new ArrayList<>();
            for (String id : section.offerIds()) {
                ShopOffer offer = byId.get(CommerceText.normalize(id));
                if (offer != null) {
                    offers.add(offer);
                }
            }
            if (!offers.isEmpty()) {
                runs.add(new Run(categoryHeading(section.id()), null, offers,
                        ShopSections.Kind.CATEGORY, section.id()));
            }
        }
        return runs;
    }

    /**
     * What a category run is called, on the one ladder both screens use: what the storefront (or a
     * storefront it includes) wrote beside that category, then what a consumer ships for it, then this
     * library's own word for the common shelves, then the category itself. The bucket carrying no
     * category at all is not a shelf, so it reads as the generic catalogue line instead.
     */
    @Nonnull
    private Message categoryHeading(@Nonnull String categoryId) {
        if (categoryId.isEmpty()) {
            return text("shop.section.catalogue");
        }
        return CommerceLabels.category(StorefrontView.namingCategory(shopId, categoryId), categoryId,
                deps.titleArgs());
    }

    /**
     * The two list filters, offered over EVERY run the storefront draws - whatever the filters
     * currently hide - so the way back out is always on the page. A run filter naming a run that
     * is no longer drawn (its shelf rotated away) heals to All rather than pinning the page to an
     * empty list nothing on screen explains; the dropdowns are String-only sinks, so every label
     * flattens.
     */
    private void renderFilters(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull List<Run> runs) {
        cmd.set("#FilterRow.Visible", true);

        List<DropdownEntryInfo> runEntries = new ArrayList<>(runs.size() + 1);
        runEntries.add(SettingsUiUtil.entry(UiText.flatten(text("shop.filter.all_sections")),
                ShopSections.FILTER_ALL));
        boolean filterStillDrawn = false;
        for (Run run : runs) {
            String value = ShopSections.runKey(run.kind(), run.runId());
            runEntries.add(SettingsUiUtil.entry(UiText.flatten(run.heading()), value));
            filterStillDrawn = filterStillDrawn || CommerceText.sameId(value, runFilter);
        }
        if (!filterStillDrawn) {
            this.runFilter = ShopSections.FILTER_ALL;
        }
        SettingsUiUtil.populate(cmd, "#RunFilter", runEntries, runFilter);
        SettingsUiUtil.bindDropdown(events, "#RunFilter", "filter_run");

        List<DropdownEntryInfo> kindEntries = List.of(
                SettingsUiUtil.entry(UiText.flatten(text("shop.filter.kind_both")),
                        ShopSections.FILTER_ALL),
                SettingsUiUtil.entry(UiText.flatten(text("shop.filter.kind_rotating")),
                        ShopSections.KIND_ROTATING),
                SettingsUiUtil.entry(UiText.flatten(text("shop.filter.kind_standing")),
                        ShopSections.KIND_STANDING));
        SettingsUiUtil.populate(cmd, "#KindFilter", kindEntries, kindFilter);
        SettingsUiUtil.bindDropdown(events, "#KindFilter", "filter_kind");
    }

    /**
     * One titled section of ware tiles per run, through the kit's {@link TilePainter}, recording each
     * tile's selector for the partial updates. The tile ceiling is shared by the whole page, and a
     * section is only started with room for at least one tile under it, so a list cut short never
     * ends on a heading with nothing beneath.
     */
    private void appendRuns(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull ShopEngine engine, @Nonnull Subject subject, @Nonnull List<Run> runs, long now) {
        int section = 0;
        int budget = MAX_TILES;
        for (Run run : runs) {
            if (budget <= 0) {
                break;
            }
            List<ShopOffer> shown = run.offers().subList(0, Math.min(run.offers().size(), budget));
            budget -= shown.size();
            String grid = TilePainter.section(cmd, LIST, section++, run.heading(), run.meta());
            List<WareTile> tiles = new ArrayList<>(shown.size());
            for (ShopOffer offer : shown) {
                tiles.add(wareTile(engine, subject, offer, now));
            }
            List<String> selectors = TilePainter.wares(cmd, events, grid, tiles,
                    tile -> EventData.of("Action", "select").append("OfferId", tile.id()));
            for (int i = 0; i < shown.size(); i++) {
                // An offer drawn twice (two shelves landing on it) answers with its first tile.
                wareOf.putIfAbsent(CommerceText.normalize(shown.get(i).offerId()), selectors.get(i));
            }
        }
    }

    /**
     * One offer as its tile reads: its picture, name and price, the pill counting what its limits
     * leave, dimmed when it is out of reach (the lock when a requirement or a switch stands in the
     * way), its whole name and the refusal it would answer with as the tooltip, and the selected look
     * on the offer the panel shows.
     */
    @Nonnull
    private WareTile wareTile(@Nonnull ShopEngine engine, @Nonnull Subject subject,
            @Nonnull ShopOffer offer, long now) {
        ShopEngine.PurchaseCheck check = engine.canPurchase(subject, offer, now);
        Reach reach = reachOf(check);
        Cost price = engine.priceFor(subject, offer);
        CurrencyEngine currencies = CommerceDefaults.currencyEngine();
        Picture costPicture = Picture.NONE;
        Message cost = text("shop.tile.free");
        if (!price.isFree()) {
            long amount = 0L;
            boolean found = false;
            for (Map.Entry<String, Long> entry : price.currencies().entrySet()) {
                CurrencyDef def = currencies.catalog().get(entry.getKey());
                costPicture = Picture.item(def == null ? null : CurrencyText.iconOf(def));
                amount = entry.getValue() == null ? 0L : entry.getValue().longValue();
                found = true;
                break;
            }
            if (!found) {
                for (ItemCost item : price.items()) {
                    if (item != null && !item.isBlank()) {
                        costPicture = Picture.item(item.item());
                        amount = item.count();
                        found = true;
                        break;
                    }
                }
            }
            if (found) {
                // Every component is owed under All, so the tile says how many more there are; under Any
                // the first one alone pays, so its amount is the whole story.
                int others = price.componentCount() - 1;
                cost = others > 0 && price.combine() != Cost.Combine.ANY
                        ? text("shop.tile.cost_more", amount, others) : Msg.num(amount);
            }
        }
        Message name = offerName(offer);
        return new WareTile(offer.offerId(), name, Picture.item(pictureOf(offer)), costPicture,
                cost, reach == Reach.SHORT, limitBadge(offer, subject, now),
                reach == Reach.LIMITED, reach == Reach.LOCKED,
                tileTooltip(engine, subject, offer, name, check),
                CommerceText.sameId(offer.offerId(), selectedOfferId));
    }

    /**
     * The item whose picture stands for an offer: its own {@code Icon}, else whatever its first reward
     * can supply (the fallback the offer's {@code Icon} documents), else none.
     */
    @Nullable
    private String pictureOf(@Nonnull ShopOffer offer) {
        String icon = offer instanceof ShopEntryOffer entry ? entry.asset().getIcon() : null;
        if (icon != null && !icon.isBlank()) {
            return icon;
        }
        List<RewardChip> chips = RewardChips.chipsFor(offer.rewards(), deps.rewardChips());
        return chips.isEmpty() ? null : chips.get(0).iconItemId();
    }

    private static void showEmpty(@Nonnull UICommandBuilder cmd, @Nonnull Message line) {
        cmd.set("#EmptyLabel.TextSpans", line);
        cmd.set("#EmptyLabel.Visible", true);
        cmd.set("#RightPanel.Visible", false);
    }

    // ==================== the detail panel ====================

    /**
     * Paint the right-hand panel for {@code offer}. IDEMPOTENT, so a partial update can re-render it
     * in place for a newly selected or just-bought offer: it clears every appended container first
     * and sets EVERY conditional control's visibility explicitly, so nothing from the previous offer
     * survives.
     */
    private void renderDetail(@Nonnull UICommandBuilder cmd, @Nonnull ShopEngine engine,
            @Nonnull Subject subject, @Nonnull ShopOffer offer, long now) {
        cmd.clear("#RewardsList");
        cmd.clear("#StatusList");
        cmd.set("#RightPanel.Visible", true);
        cmd.set("#Flavor.Visible", false);
        cmd.set("#RewardsHeader.Visible", false);
        cmd.set("#LimitLabel.Visible", false);
        cmd.set("#RerollBtn.Visible", false);
        cmd.set("#RerollCostRow.Visible", false);
        cmd.set("#DetailCategory.Visible", false);
        cmd.set("#StatusNote.Visible", false);

        ContentTextAsset text = textOf(offer);
        cmd.set("#DetailTitle.TextSpans", offerName(offer));
        // The tile's own picture, larger; the row closes up when the offer has none.
        cmd.set("#DetailPicRow.Visible", IconRenderer.applyPlainIcon(cmd, "#DetailPic", pictureOf(offer), null));
        Message flavor = CommerceText.flavor(text, deps.titleArgs());
        if (flavor != null) {
            cmd.set("#Flavor.TextSpans", flavor);
            cmd.set("#Flavor.Visible", true);
        }
        String category = categoryOf(offer);
        if (category != null) {
            cmd.set("#DetailCategory.TextSpans", categoryHeading(category));
            cmd.set("#DetailCategory.Visible", true);
        }

        CurrencyEngine currencies = CommerceDefaults.currencyEngine();
        Cost price = engine.priceFor(subject, offer);
        cmd.set("#CostHeader.TextSpans", text(price.combine() == Cost.Combine.ANY
                ? "shop.header.cost_any" : "shop.header.cost"));
        CommerceChips.render(cmd, "#CostRow",
                CommerceChips.price(price, currencies, subject, deps.currencyNames()), MAX_CHIPS);

        renderLimits(cmd, engine, subject, offer, now);
        renderRewards(cmd, offer);

        ShopEngine.PurchaseCheck check = engine.canPurchase(subject, offer, now);
        ZigRichButton.text(cmd, "#BuyBtn", text(price.isFree() ? "shop.action.take" : "shop.action.buy"));
        cmd.set("#BuyBtn.Visible", true);
        if (!check.ok()) {
            // The button stays BOUND: the press re-checks and answers with the same line as a toast,
            // so a stale screen can never silently do nothing.
            paintLocked(cmd, "#BuyBtn");
            renderStatus(cmd, engine, subject, offer, check.reason());
        }
        renderReroll(cmd, engine, subject, offer, now);
    }

    /** How much of a capped offer is left, as the line under the price. */
    private void renderLimits(@Nonnull UICommandBuilder cmd, @Nonnull ShopEngine engine,
            @Nonnull Subject subject, @Nonnull ShopOffer offer, long now) {
        PurchaseLimits limits = offer.limits();
        if (limits == null || limits.isOpen()) {
            return;
        }
        long day = ShopEngine.epochDay(now);
        List<Message> parts = new ArrayList<>();
        if (limits.daily() != null) {
            parts.add(text("shop.limit.daily",
                    CommerceStores.get().purchasesToday(subject, offer.offerId(), day),
                    limits.daily().intValue()));
        }
        if (limits.total() != null) {
            parts.add(text("shop.limit.total",
                    CommerceStores.get().purchasesTotal(subject, offer.offerId()),
                    limits.total().intValue()));
        }
        if (parts.isEmpty()) {
            return;
        }
        Message line = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            line = Msg.join(line, Msg.raw("   "), parts.get(i));
        }
        cmd.set("#LimitLabel.TextSpans", line);
        cmd.set("#LimitLabel.Visible", true);
    }

    private void renderRewards(@Nonnull UICommandBuilder cmd, @Nonnull ShopOffer offer) {
        List<RewardChip> chips = RewardChips.chipsFor(offer.rewards(), deps.rewardChips());
        if (chips.isEmpty()) {
            return;
        }
        cmd.set("#RewardsHeader.TextSpans", text("shop.header.rewards"));
        cmd.set("#RewardsHeader.Visible", true);
        int shown = Math.min(chips.size(), MAX_LINES);
        for (int i = 0; i < shown; i++) {
            RewardChip chip = chips.get(i);
            CommerceChips.setRewardLine(cmd, CommerceChips.appendLine(cmd, "#RewardsList", i), chip,
                    CommerceChips.COLOR_LINE);
        }
    }

    /**
     * Why this offer is out of reach, one line per thing the player can act on, in the status note
     * ({@link CommerceChips#setNote}), which wraps where a detail line would cut a long requirement short.
     *
     * <p>A gate refusal names every unmet requirement ({@link #refusalLines}), the way the board's
     * locked contract does; a shortfall names the wallet or the item it is short of, resolved HERE so
     * it reads in the player's own language; every other commerce refusal is the engine's token
     * turned into its own sentence.
     */
    private void renderStatus(@Nonnull UICommandBuilder cmd, @Nonnull ShopEngine engine,
            @Nonnull Subject subject, @Nonnull ShopOffer offer, @Nullable String reason) {
        CommerceChips.setNote(cmd, "#StatusNote", refusalLines(engine, subject, offer, reason));
    }

    /**
     * A refusal as the lines it reads with. When a gate refused, every unmet requirement is rendered
     * from the evaluator's records ({@link ShopEngine#gateRefusals}) through the shared lock-reason
     * mapping, the same words every other locked surface shows: a reputation rank by its name and
     * the reputation's ("Friendly with the Hallowed"), an either-or gate as "Unlocked by: A or B".
     * The bare token drops what a factor asks for, so it is only the fallback, for a gate whose
     * records name nothing. Any other refusal is its one line ({@link #refusalLine}).
     */
    @Nonnull
    private List<Message> refusalLines(@Nonnull ShopEngine engine, @Nonnull Subject subject,
            @Nonnull ShopOffer offer, @Nullable String reason) {
        if (GateRefusal.fromToken(reason) != null) {
            List<Message> lines = LockReasons.linesOf(engine.gateRefusals(subject, offer));
            if (!lines.isEmpty()) {
                return lines;
            }
        }
        return List.of(refusalLine(reason));
    }

    /**
     * A ware's hover text: its whole name, since a long one can run past the tile's lines, and when it cannot be
     * bought, its refusal's lines under it.
     */
    @Nonnull
    private Message tileTooltip(@Nonnull ShopEngine engine, @Nonnull Subject subject, @Nonnull ShopOffer offer,
            @Nonnull Message name, @Nonnull ShopEngine.PurchaseCheck check) {
        if (check.ok()) {
            return name;
        }
        List<Message> lines = new ArrayList<>();
        lines.add(name);
        lines.addAll(refusalLines(engine, subject, offer, check.reason()));
        return CommerceChips.stacked(lines);
    }

    /**
     * The reroll footer, for an offer that came off a rotating shelf. Shown only when the shelf
     * offers rerolls at all; when the period's rerolls are spent the button is left visible and
     * LOCKED with the reason beside it, so the limit is legible rather than the control simply
     * vanishing.
     */
    private void renderReroll(@Nonnull UICommandBuilder cmd, @Nonnull ShopEngine engine,
            @Nonnull Subject subject, @Nonnull ShopOffer offer, long now) {
        ShelfPosition at = shelfOf.get(CommerceText.normalize(offer.offerId()));
        if (at == null) {
            return;
        }
        ShelfSpec shelf = shelfById(at.shelfId());
        if (shelf == null || shelf.reroll() == null) {
            return;
        }
        cmd.set("#RerollBtn.Visible", true);
        boolean armed = rerollArm.isArmed(armKey(offer.offerId()), now);
        ZigRichButton.text(cmd, "#RerollBtn",
                text(armed ? "action.confirm" : "shop.action.reroll"));
        ZigRichButton.color(cmd, "#RerollBtn", armed ? CommerceChips.COLOR_SHORT : REROLL_TEXT);

        ShopEngine.PurchaseCheck check = engine.canRerollShelf(subject, shelf, at.position(), now);
        if (!check.ok()) {
            paintLocked(cmd, "#RerollBtn");
            // The status list holds only this line (the purchase's own refusal is the note above it), and
            // renderDetail cleared it, so it is row 0.
            CommerceChips.setLine(cmd, CommerceChips.appendLine(cmd, "#StatusList", 0),
                    refusalLine(check.reason()), CommerceChips.COLOR_REFUSAL);
            return;
        }
        Cost price = shelf.reroll().cost();
        if (!price.isFree()) {
            cmd.set("#RerollCostRow.Visible", true);
            CommerceChips.render(cmd, "#RerollCostRow", CommerceChips.price(price,
                    CommerceDefaults.currencyEngine(), subject, deps.currencyNames()), MAX_CHIPS);
        }
    }

    // ==================== events ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull ShopEventData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        String action = data.action;
        if (action == null || "close".equals(action)) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }
        if ("select".equals(action)) {
            selectOffer(ref, store, player, data.offerId);
            return;
        }
        if ("filter_run".equals(action) || "filter_kind".equals(action)) {
            // A filter change reshuffles the whole list, so it rebuilds, and an armed confirm must
            // not survive a reshuffle that can move the selection it was armed behind.
            rerollArm.reset();
            String value = filterValue(data.dropdownValue);
            if ("filter_run".equals(action)) {
                this.runFilter = value;
            } else {
                this.kindFilter = value;
            }
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }

        StorefrontAsset asset = shopAsset();
        if (asset != null && !asset.existsIn(WhereAxis.viewer(store))) {
            // A page left open across a move into a world this storefront is not in: it sells and
            // rerolls nothing there, refuses as a switched-off one does, and reopens closed.
            showToast(ToastKind.ERROR, refusalLine(ShopEngine.REASON_DISABLED));
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        Subject subject = subjectOf(store, ref);
        ShopEngine engine = CommerceEngines.shopsAt(shopId);
        ShopOffer offer = selectedOfferId == null ? null : engine.catalog().offer(selectedOfferId);
        if (subject == null || offer == null) {
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        if ("buy".equals(action)) {
            doBuy(engine, subject, offer);
        } else if ("reroll".equals(action)) {
            doReroll(engine, subject, offer);
        }
        // A purchase moves a balance, a limit and possibly the whole shelf, so the page rebuilds
        // rather than trying to patch a screen whose every row may now read differently.
        player.getPageManager().openCustomPage(ref, store, this);
    }

    /**
     * Swap the selected tile and re-render the detail panel in place, so the grid keeps its scroll
     * position. Falls back to a full reopen when the clicked tile is not one the last build recorded,
     * because an unresolved selector disconnects the player.
     */
    private void selectOffer(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Player player, @Nullable String offerId) {
        // An arm left standing behind a different tile would charge for a click that was never about
        // it, so changing what the panel shows forgets every arm.
        rerollArm.reset();
        ShopEngine engine = CommerceEngines.shopsAt(shopId);
        ShopOffer offer = offerId == null ? null : engine.catalog().offer(offerId);
        String tile = offerId == null ? null : wareOf.get(CommerceText.normalize(offerId));
        Subject subject = subjectOf(store, ref);
        if (offer == null || tile == null || subject == null) {
            this.selectedOfferId = offerId;
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        String previous = this.selectedOfferId;
        this.selectedOfferId = offer.offerId();
        UICommandBuilder cmd = new UICommandBuilder();
        String oldTile = previous == null ? null : wareOf.get(CommerceText.normalize(previous));
        if (oldTile != null && !oldTile.equals(tile)) {
            TilePainter.selectWare(cmd, oldTile, false);
        }
        TilePainter.selectWare(cmd, tile, true);
        renderDetail(cmd, engine, subject, offer, System.currentTimeMillis());
        this.sendUpdate(cmd, new UIEventBuilder(), false);
    }

    /**
     * Buy what the panel is showing. The engine re-runs every check itself, so an offer that rotated
     * out or a limit that filled between render and click refuses with its own line rather than
     * charging.
     */
    private void doBuy(@Nonnull ShopEngine engine, @Nonnull Subject subject, @Nonnull ShopOffer offer) {
        rerollArm.reset();
        ShopEngine.PurchaseOutcome outcome =
                engine.purchase(subject, offer, System.currentTimeMillis());
        if (!outcome.ok()) {
            // The first of the lines the panel shows for it, so a gate names what it asks for here too.
            showToast(ToastKind.ERROR, refusalLines(engine, subject, offer, outcome.reason()).get(0));
            return;
        }
        showToast(purchaseToast(offer, outcome));
    }

    /**
     * The toast a completed purchase floats: the consumer's own when it has one, else this library's
     * line, and the queued variant when some of what was bought is waiting for the next connect -
     * because a player who paid and saw nothing arrive needs to be told why.
     *
     * <p>Under the headline, one row per thing the purchase actually handed over: the grant pass's
     * receipt, read through the same chip source the offer card previewed the payout with, so a
     * rolled table lists the items it produced and a reward still waiting for the next connect adds
     * no row. A bare headline left the player to guess what "Bought X" put in their bag. Whichever line
     * it is, it plays the purchase sound ({@link CommercePageDeps#resolvePurchaseToast}).
     */
    @Nonnull
    private ToastSpec purchaseToast(@Nonnull ShopOffer offer,
            @Nonnull ShopEngine.PurchaseOutcome outcome) {
        return deps.resolvePurchaseToast(offer.offerId(), () -> {
            List<RewardSpec> receipt = outcome.grants() == null ? List.of() : outcome.grants().receipt();
            return ToastSpec.of(ToastKind.REWARD, outcome.anyQueued()
                    ? text("shop.toast.bought_queued", offerName(offer))
                    : text("shop.toast.bought", offerName(offer)))
                    .withLines(RewardToastLines.lines(receipt, deps.rewardChips(),
                            dropped -> text("shop.toast.more", dropped)));
        });
    }

    /**
     * Swap the shown offer for another from its shelf slot, behind two clicks when it costs
     * anything: the first press arms and says the price, a second inside the window goes through.
     */
    private void doReroll(@Nonnull ShopEngine engine, @Nonnull Subject subject,
            @Nonnull ShopOffer offer) {
        ShelfPosition at = shelfOf.get(CommerceText.normalize(offer.offerId()));
        ShelfSpec shelf = at == null ? null : shelfById(at.shelfId());
        if (shelf == null || shelf.reroll() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        ShopEngine.PurchaseCheck probe = engine.canRerollShelf(subject, shelf, at.position(), now);
        if (!probe.ok()) {
            showToast(ToastKind.ERROR, refusalLine(probe.reason()));
            return;
        }
        Cost price = shelf.reroll().cost();
        if (!price.isFree() && !rerollArm.confirm(armKey(offer.offerId()), now)) {
            showToast(ToastKind.WARNING, text("shop.toast.reroll_confirm", priceLine(price, subject)));
            return;
        }
        ShopEngine.RerollResult result = engine.rerollShelf(subject, shelf, at.position(), now);
        if (!result.ok()) {
            showToast(ToastKind.ERROR, refusalLine(result.reason()));
            return;
        }
        // Keep the player looking at the slot they just paid to change, not at whatever sorts first.
        this.selectedOfferId = result.newId();
    }

    // ==================== text ====================

    /** What an offer is CALLED, with a generated row's arguments resolved through the deps seam. */
    @Nonnull
    private Message offerName(@Nonnull ShopOffer offer) {
        return CommerceText.title(textOf(offer), deps.titleArgs(), text("shop.offer.untitled"));
    }

    @Nullable
    private static ContentTextAsset textOf(@Nonnull ShopOffer offer) {
        return offer instanceof ShopEntryOffer entry ? entry.asset().getText() : null;
    }

    @Nullable
    private static String categoryOf(@Nonnull ShopOffer offer) {
        if (!(offer instanceof ShopEntryOffer entry) || entry.asset().getListing() == null) {
            return null;
        }
        String category = CommerceText.normalize(entry.asset().getListing().getCategory());
        return category.isEmpty() ? null : category;
    }

    /** One refusal token as a sentence, with whatever it named resolved in the player's language. */
    @Nonnull
    private Message refusalLine(@Nullable String reason) {
        CommerceRefusals.Refusal refusal = CommerceRefusals.of(reason);
        if (refusal.line() != null) {
            return refusal.line();
        }
        if (refusal.currencyId() != null) {
            return text(refusal.key(), CommerceChips.nameOf(CommerceDefaults.currencyEngine(),
                    refusal.currencyId(), deps.currencyNames()));
        }
        if (refusal.itemId() != null) {
            return text(refusal.key(), CommerceChips.itemName(refusal.itemId()));
        }
        return text(refusal.key());
    }

    /** A price as one readable phrase, for the line that asks a player to confirm paying it. */
    @Nonnull
    private Message priceLine(@Nonnull Cost price, @Nonnull Subject subject) {
        CurrencyEngine currencies = CommerceDefaults.currencyEngine();
        String primary = price.primaryCurrencyId();
        if (primary != null) {
            return CommerceChips.amountAndName(currencies, primary, price.amountOf(primary),
                    deps.currencyNames());
        }
        return Msg.num(price.componentCount());
    }

    /**
     * How much of a capped offer is left, as the pill on its tile: what is left in the available
     * tone, none today in the waiting tone (it comes back tomorrow), sold out in the blocked tone.
     */
    @Nullable
    private Pill limitBadge(@Nonnull ShopOffer offer, @Nonnull Subject subject, long now) {
        PurchaseLimits limits = offer.limits();
        if (limits == null || limits.isOpen()) {
            return null;
        }
        long day = ShopEngine.epochDay(now);
        long remainingDay = limits.daily() == null ? Long.MAX_VALUE
                : Math.max(0, limits.daily().intValue()
                        - CommerceStores.get().purchasesToday(subject, offer.offerId(), day));
        long remainingTotal = limits.total() == null ? Long.MAX_VALUE
                : Math.max(0, limits.total().intValue()
                        - CommerceStores.get().purchasesTotal(subject, offer.offerId()));
        long remaining = Math.min(remainingDay, remainingTotal);
        if (remaining == 0) {
            return remainingTotal == 0 ? Pill.of(text("shop.badge.sold_out"), Tone.BLOCKED)
                    : Pill.of(text("shop.badge.none_today"), Tone.WAITING);
        }
        if (remaining == Long.MAX_VALUE) {
            return null;
        }
        return Pill.of(text("shop.badge.left", remaining), Tone.AVAILABLE);
    }

    /** What stands between this buyer and this offer, as the engine's own check answers it. */
    @Nonnull
    private static Reach reachOf(@Nonnull ShopEngine.PurchaseCheck check) {
        if (check.ok()) {
            return Reach.READY;
        }
        String reason = check.reason() == null ? "" : check.reason();
        if (reason.startsWith(ShopEngine.REASON_SHORT_CURRENCY) || reason.startsWith(ShopEngine.REASON_SHORT_ITEM)) {
            return Reach.SHORT;
        }
        if (ShopEngine.REASON_NO_ROOM.equals(reason)) {
            return Reach.NO_ROOM;
        }
        if (ShopEngine.REASON_LIMIT_DAILY.equals(reason) || ShopEngine.REASON_LIMIT_TOTAL.equals(reason)) {
            return Reach.LIMITED;
        }
        return Reach.LOCKED;
    }

    /** Grey a button that will refuse, without unbinding it: the press still answers with a reason. */
    private static void paintLocked(@Nonnull UICommandBuilder cmd, @Nonnull String selector) {
        UiRetint.retintButtonStates(cmd, selector, "#2b3240", "#2b3240", "#2b3240");
        ZigRichButton.color(cmd, selector, "#7d8895");
    }

    private static void setIcon(@Nonnull UICommandBuilder cmd, @Nonnull String slotSelector,
            @Nonnull String gridSelector, @Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            cmd.set(slotSelector + ".Visible", false);
            return;
        }
        try {
            cmd.set(gridSelector + ".Slots", List.of(new ItemGridSlot(new ItemStack(itemId, 1))));
            cmd.set(slotSelector + ".Visible", true);
        } catch (Throwable ignored) {
            cmd.set(slotSelector + ".Visible", false);
        }
    }

    private static void bindAction(@Nonnull UIEventBuilder events, @Nonnull String selector,
            @Nonnull String action) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                EventData.of("Action", action), false);
    }

    @Nullable
    private ShelfSpec shelfById(@Nonnull String shelfId) {
        for (ShelfSpec shelf : StorefrontView.shelves(shopId)) {
            if (CommerceText.sameId(shelf.shelfId(), shelfId)) {
                return shelf;
            }
        }
        return null;
    }

    @Nonnull
    private static String armKey(@Nonnull String offerId) {
        return "reroll:" + offerId;
    }

    /** A dropdown's chosen value as a filter: whatever it said, and All when it said nothing. */
    @Nonnull
    private static String filterValue(@Nullable String value) {
        return value == null || value.isBlank() ? ShopSections.FILTER_ALL : value.trim();
    }

    @Nonnull
    private Message text(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr(PREFIX, DOMAIN + key, args);
    }
}
