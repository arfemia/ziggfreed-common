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
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.commerce.CommerceStores;
import com.ziggfreed.common.commerce.fold.CommerceCatalogs;
import com.ziggfreed.common.commerce.fold.CommerceDefaults;
import com.ziggfreed.common.commerce.fold.CommerceEngines;
import com.ziggfreed.common.commerce.fold.ShelfSpec;
import com.ziggfreed.common.commerce.fold.ShopEntryOffer;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.shop.PurchaseLimits;
import com.ziggfreed.common.shop.ShopEngine;
import com.ziggfreed.common.shop.ShopOffer;
import com.ziggfreed.common.shop.asset.StorefrontAsset;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.NumberFormatter;
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
 * <p>A storefront's rotating SHELVES read first, each under a header carrying the countdown to its
 * next turnover, because they are the part that will not be there tomorrow. Its standing catalogue
 * reads below, in the category order the storefront itself declared. Both are the same row template,
 * and the section headings share it too, because a list mixing two templates gives two different
 * child sets at one index and a partial update addresses the wrong one.
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
    private static final String ROW_TEMPLATE = "Pages/ZigCommerceRow.ui";

    /** What a theme is offered to repaint: the panel carrying the catalogue. */
    private static final String FRAME_SELECTOR = "#LeftPanel";

    /** This library's own lang namespace; {@link Msg#tr} concatenates it with the key verbatim. */
    private static final String PREFIX = "ziggfreedcommon.";

    /** The domain segment every key on this page carries (the {@code ziggfreedcommon.commerce.lang} file). */
    private static final String DOMAIN = "commerce.";

    /** A hard ceiling on list rows, so a very large catalogue cannot build an unbounded page. */
    private static final int MAX_ROWS = 200;

    /** A ceiling on the lines inside one detail section. */
    private static final int MAX_LINES = 24;

    /** A ceiling on the chips in one strip, so a wide price cannot push a row off the panel. */
    private static final int MAX_CHIPS = 6;

    /** The marker {@link #builtRowOrder} carries where a section heading was drawn. */
    private static final String HEADER_ROW = "";

    // The selected row's accent, and the shared row style's own per-state colours to revert to.
    private static final String ROW_SELECTED_TINT = "#1a2d44";
    private static final String ROW_SELECTED_TEXT = "#ffffff";
    private static final String ROW_TINT = "#41506a";
    private static final String ROW_HOVER_TINT = "#5b6f8c";
    private static final String ROW_PRESSED_TINT = "#344156";
    private static final String ROW_TEXT = "#b6c9de";

    /**
     * A section heading is a HEADING: it reads at least as brightly as the rows under it, or a
     * player takes a shelf's name for a greyed-out entry rather than for the run it opens. Kept in
     * step with the row template's own default for the same element.
     */
    private static final String HEADER_TEXT = "#c2d4e8";

    // The status dot: what stands between this buyer and this offer, at a glance.
    private static final String DOT_READY = "#7affa0";
    private static final String DOT_SHORT = "#ffcc4a";
    private static final String DOT_LIMITED = "#c8a86a";
    private static final String DOT_LOCKED = "#96a9be";

    @Nonnull private final String shopId;

    @Nonnull private final CommercePageDeps deps;

    @Nullable private String selectedOfferId;

    /** Two clicks before a reroll charges anything. Page-instance state, never persisted. */
    private final ConfirmArm rerollArm = new ConfirmArm();

    /**
     * The exact row order the last full build rendered, section headings included as
     * {@link #HEADER_ROW} markers so an offer's index is the one the client DOM actually holds.
     */
    private final List<String> builtRowOrder = new ArrayList<>();

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

        builtRowOrder.clear();
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
                        asset == null ? List.of() : asset.currencyIds(), deps.currencyNames()),
                MAX_CHIPS);

        if (asset != null && !asset.isEnabled()) {
            showEmpty(cmd, text("shop.empty.closed"));
            renderToastInto(cmd);
            return;
        }

        long now = System.currentTimeMillis();
        ShopEngine engine = CommerceEngines.shops();
        List<Run> runs = runsOf(engine, subject, asset, now);
        List<String> orderedIds = new ArrayList<>();
        for (Run run : runs) {
            for (ShopOffer offer : run.offers()) {
                orderedIds.add(offer.offerId());
            }
        }
        if (orderedIds.isEmpty()) {
            showEmpty(cmd, text("shop.empty.nothing"));
            renderToastInto(cmd);
            return;
        }
        this.selectedOfferId = ShopSections.select(orderedIds, selectedOfferId);
        cmd.set("#OfferCount.TextSpans", text("shop.count", orderedIds.size()));

        appendRuns(cmd, events, engine, subject, runs, now);

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
            return ProgressionRuntime.subjects().questSubject(store, ref, playerRef);
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

    /** One run of rows: its heading, the clock under it when it has one, and what it holds. */
    private record Run(@Nonnull Message heading, @Nullable Message meta,
                       @Nonnull List<ShopOffer> offers, @Nullable String shelfId) {
    }

    /**
     * What this storefront shows right now: its rotating shelves, each drawn for THIS buyer (their
     * own re-rolled positions laid over the shared draw), then its standing catalogue grouped the way
     * the storefront asked for.
     */
    @Nonnull
    private List<Run> runsOf(@Nonnull ShopEngine engine, @Nonnull Subject subject,
            @Nullable StorefrontAsset asset, long now) {
        List<Run> runs = new ArrayList<>();
        List<String> shelved = new ArrayList<>();
        for (ShelfSpec shelf : CommerceCatalogs.shelvesOf(shopId)) {
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
                    drawn, shelf.shelfId()));
        }

        List<ShopSections.Entry> standing = new ArrayList<>();
        Map<String, ShopOffer> byId = new LinkedHashMap<>();
        for (ShopEntryOffer offer : CommerceCatalogs.shopContent().offersOf(shopId)) {
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
        List<String> categoryOrder = asset == null ? List.of() : asset.categoryOrder();
        for (ShopSections.Section section : ShopSections.standing(standing, categoryOrder)) {
            List<ShopOffer> offers = new ArrayList<>();
            for (String id : section.offerIds()) {
                ShopOffer offer = byId.get(CommerceText.normalize(id));
                if (offer != null) {
                    offers.add(offer);
                }
            }
            if (!offers.isEmpty()) {
                runs.add(new Run(categoryHeading(asset, section.id()), null, offers, null));
            }
        }
        return runs;
    }

    /**
     * What a category run is called, on the one ladder both screens use: what the storefront wrote
     * beside that category, then what a consumer ships for it, then this library's own word for the
     * common shelves, then the category itself. The bucket carrying no category at all is not a
     * shelf, so it reads as the generic catalogue line instead.
     */
    @Nonnull
    private Message categoryHeading(@Nullable StorefrontAsset shop, @Nonnull String categoryId) {
        if (categoryId.isEmpty()) {
            return text("shop.section.catalogue");
        }
        return CommerceLabels.category(shop, categoryId, deps.titleArgs());
    }

    private void appendRuns(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull ShopEngine engine, @Nonnull Subject subject, @Nonnull List<Run> runs, long now) {
        int index = 0;
        for (Run run : runs) {
            // A heading and the first row under it are budgeted TOGETHER, so a list cut short by the
            // row ceiling never ends on a heading with nothing under it.
            if (index + 1 >= MAX_ROWS) {
                break;
            }
            index = appendHeader(cmd, index, run.heading(), run.meta());
            for (ShopOffer offer : run.offers()) {
                if (index >= MAX_ROWS) {
                    break;
                }
                index = appendOfferRow(cmd, events, engine, subject, index, offer, now);
            }
        }
    }

    /** A heading, drawn as a row whose button is hidden and whose label carries the run's name. */
    private int appendHeader(@Nonnull UICommandBuilder cmd, int index, @Nonnull Message label,
            @Nullable Message meta) {
        String sel = appendRow(cmd, index);
        builtRowOrder.add(HEADER_ROW);
        cmd.set(sel + " #RowBtn.Visible", false);
        cmd.set(sel + " #StatusDot.Visible", false);
        cmd.set(sel + " #SectionLabel.TextSpans", label);
        cmd.set(sel + " #SectionLabel.Style.TextColor", HEADER_TEXT);
        cmd.set(sel + " #SectionLabel.Visible", true);
        if (meta != null) {
            cmd.set(sel + " #SectionMeta.TextSpans", meta);
            cmd.set(sel + " #SectionMeta.Visible", true);
        }
        return index + 1;
    }

    private int appendOfferRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull ShopEngine engine, @Nonnull Subject subject, int index, @Nonnull ShopOffer offer,
            long now) {
        String sel = appendRow(cmd, index);
        builtRowOrder.add(offer.offerId());
        ZigRichButton.text(cmd, sel + " #RowBtn", offerName(offer));
        cmd.set(sel + " #StatusDot.Background", dotFor(engine, subject, offer, now));
        Message badge = limitBadge(engine, subject, offer, now);
        if (badge != null) {
            cmd.set(sel + " #RowBadge.TextSpans", badge);
            cmd.set(sel + " #RowBadge.Visible", true);
        }
        if (CommerceText.sameId(offer.offerId(), selectedOfferId)) {
            paintRowSelected(cmd, sel, true);
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #RowBtn",
                EventData.of("Action", "select").append("OfferId", offer.offerId()), false);
        return index + 1;
    }

    @Nonnull
    private static String appendRow(@Nonnull UICommandBuilder cmd, int index) {
        cmd.append("#OfferList", ROW_TEMPLATE);
        return "#OfferList[" + index + "]";
    }

    /**
     * The selected row's accent, and the exact per-state colours of the shared row style to revert
     * to. Tinting the button's states is what a partial update can do; replacing its background with
     * a bare string is what red-Xes a patch style.
     */
    private static void paintRowSelected(@Nonnull UICommandBuilder cmd, @Nonnull String rowSel,
            boolean selected) {
        if (selected) {
            UiRetint.retintButtonStates(cmd, rowSel + " #RowBtn",
                    ROW_SELECTED_TINT, ROW_SELECTED_TINT, ROW_SELECTED_TINT);
            ZigRichButton.color(cmd, rowSel + " #RowBtn", ROW_SELECTED_TEXT);
            cmd.set(rowSel + " #RowBtn #Label.Style.RenderBold", true);
        } else {
            UiRetint.retintButtonStates(cmd, rowSel + " #RowBtn",
                    ROW_TINT, ROW_HOVER_TINT, ROW_PRESSED_TINT);
            ZigRichButton.color(cmd, rowSel + " #RowBtn", ROW_TEXT);
            cmd.set(rowSel + " #RowBtn #Label.Style.RenderBold", false);
        }
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

        ContentTextAsset text = textOf(offer);
        cmd.set("#DetailTitle.TextSpans", offerName(offer));
        Message flavor = CommerceText.flavor(text, deps.titleArgs());
        if (flavor != null) {
            cmd.set("#Flavor.TextSpans", flavor);
            cmd.set("#Flavor.Visible", true);
        }
        String category = categoryOf(offer);
        if (category != null) {
            cmd.set("#DetailCategory.TextSpans", categoryHeading(shopAsset(), category));
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
        // The status list is APPENDED to, so its next index is counted rather than assumed: a
        // selector naming a row the container does not hold disconnects the player outright.
        int statusLines = 0;
        if (!check.ok()) {
            // The button stays BOUND: the press re-checks and answers with the same line as a toast,
            // so a stale screen can never silently do nothing.
            paintLocked(cmd, "#BuyBtn");
            renderStatus(cmd, currencies, check.reason(), statusLines);
            statusLines++;
        }
        renderReroll(cmd, engine, subject, offer, now, statusLines);
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
            CommerceChips.setLine(cmd, CommerceChips.appendLine(cmd, "#RewardsList", i), chip.label(),
                    CommerceChips.COLOR_LINE, chip.iconItemId());
        }
    }

    /**
     * Why this offer is out of reach, in one line the player can act on.
     *
     * <p>A shortfall names the wallet or the item it is short of, resolved HERE so it reads in the
     * player's own language; every other refusal is the engine's token turned into its own sentence,
     * and a gate refusal reads as the generic locked line rather than leaking whatever an author
     * gated on.
     */
    private void renderStatus(@Nonnull UICommandBuilder cmd, @Nonnull CurrencyEngine currencies,
            @Nullable String reason, int index) {
        CommerceRefusals.Refusal refusal = CommerceRefusals.of(reason);
        Message line;
        if (refusal.currencyId() != null) {
            line = text(refusal.key(),
                    CommerceChips.nameOf(currencies, refusal.currencyId(), deps.currencyNames()));
        } else if (refusal.itemId() != null) {
            line = text(refusal.key(), CommerceChips.itemName(refusal.itemId()));
        } else {
            line = text(refusal.key());
        }
        CommerceChips.setLine(cmd, CommerceChips.appendLine(cmd, "#StatusList", index), line,
                CommerceChips.COLOR_REFUSAL, null);
    }

    /**
     * The reroll footer, for an offer that came off a rotating shelf. Shown only when the shelf
     * offers rerolls at all; when the period's rerolls are spent the button is left visible and
     * LOCKED with the reason beside it, so the limit is legible rather than the control simply
     * vanishing.
     */
    private void renderReroll(@Nonnull UICommandBuilder cmd, @Nonnull ShopEngine engine,
            @Nonnull Subject subject, @Nonnull ShopOffer offer, long now, int statusIndex) {
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
        ZigRichButton.color(cmd, "#RerollBtn", armed ? CommerceChips.COLOR_SHORT : ROW_TEXT);

        ShopEngine.PurchaseCheck check = engine.canRerollShelf(subject, shelf, at.position(), now);
        if (!check.ok()) {
            paintLocked(cmd, "#RerollBtn");
            CommerceChips.setLine(cmd, CommerceChips.appendLine(cmd, "#StatusList", statusIndex),
                    text(CommerceRefusals.keyOf(check.reason())), CommerceChips.COLOR_REFUSAL, null);
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

        Subject subject = subjectOf(store, ref);
        ShopEngine engine = CommerceEngines.shops();
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
     * Swap the highlighted row and re-render the detail panel in place, so the list keeps its scroll
     * position. Falls back to a full reopen when the clicked row is not one the last build recorded,
     * because a recomputed index can address a different row entirely - a section heading has no
     * status dot, and an unresolved selector disconnects the player.
     */
    private void selectOffer(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Player player, @Nullable String offerId) {
        // An arm left standing behind a different row would charge for a click that was never about
        // it, so changing what the panel shows forgets every arm.
        rerollArm.reset();
        ShopEngine engine = CommerceEngines.shops();
        ShopOffer offer = offerId == null ? null : engine.catalog().offer(offerId);
        int row = offerId == null ? -1 : indexOfRow(offerId);
        Subject subject = subjectOf(store, ref);
        if (offer == null || row < 0 || subject == null) {
            this.selectedOfferId = offerId;
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }
        String previous = this.selectedOfferId;
        this.selectedOfferId = offer.offerId();
        UICommandBuilder cmd = new UICommandBuilder();
        int oldRow = previous == null ? -1 : indexOfRow(previous);
        if (oldRow >= 0 && oldRow != row) {
            paintRowSelected(cmd, "#OfferList[" + oldRow + "]", false);
        }
        paintRowSelected(cmd, "#OfferList[" + row + "]", true);
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
            showToast(ToastKind.ERROR, refusalLine(outcome.reason()));
            return;
        }
        showToast(purchaseToast(offer, outcome));
    }

    /**
     * The toast a completed purchase floats: the consumer's own when it has one, else this library's
     * line, and the queued variant when some of what was bought is waiting for the next connect -
     * because a player who paid and saw nothing arrive needs to be told why.
     */
    @Nonnull
    private ToastSpec purchaseToast(@Nonnull ShopOffer offer,
            @Nonnull ShopEngine.PurchaseOutcome outcome) {
        try {
            ToastSpec spec = deps.purchaseToast().forPurchase(offer.offerId());
            if (spec != null) {
                return spec;
            }
        } catch (Throwable ignored) {
            // A consumer's toast failing costs its own line, never the purchase that earned it.
        }
        return ToastSpec.of(ToastKind.REWARD, outcome.anyQueued()
                ? text("shop.toast.bought_queued", offerName(offer))
                : text("shop.toast.bought", offerName(offer)));
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
        return Msg.raw(NumberFormatter.grouped(price.componentCount()));
    }

    /** How much of a capped offer is left, as the badge on its row. */
    @Nullable
    private Message limitBadge(@Nonnull ShopEngine engine, @Nonnull Subject subject,
            @Nonnull ShopOffer offer, long now) {
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
            return text(remainingTotal == 0 ? "shop.badge.sold_out" : "shop.badge.none_today");
        }
        if (remaining == Long.MAX_VALUE) {
            return null;
        }
        return text("shop.badge.left", remaining);
    }

    /** What stands between this buyer and this offer, at a glance. */
    @Nonnull
    private static String dotFor(@Nonnull ShopEngine engine, @Nonnull Subject subject,
            @Nonnull ShopOffer offer, long now) {
        ShopEngine.PurchaseCheck check = engine.canPurchase(subject, offer, now);
        if (check.ok()) {
            return DOT_READY;
        }
        String reason = check.reason() == null ? "" : check.reason();
        if (reason.startsWith(ShopEngine.REASON_SHORT_CURRENCY)
                || reason.startsWith(ShopEngine.REASON_SHORT_ITEM)
                || ShopEngine.REASON_NO_ROOM.equals(reason)) {
            return DOT_SHORT;
        }
        if (ShopEngine.REASON_LIMIT_DAILY.equals(reason) || ShopEngine.REASON_LIMIT_TOTAL.equals(reason)) {
            return DOT_LIMITED;
        }
        return DOT_LOCKED;
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
        for (ShelfSpec shelf : CommerceCatalogs.shelvesOf(shopId)) {
            if (CommerceText.sameId(shelf.shelfId(), shelfId)) {
                return shelf;
            }
        }
        return null;
    }

    private int indexOfRow(@Nonnull String offerId) {
        for (int i = 0; i < builtRowOrder.size(); i++) {
            if (CommerceText.sameId(builtRowOrder.get(i), offerId)) {
                return i;
            }
        }
        return -1;
    }

    @Nonnull
    private static String armKey(@Nonnull String offerId) {
        return "reroll:" + offerId;
    }

    @Nonnull
    private Message text(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr(PREFIX, DOMAIN + key, args);
    }
}
