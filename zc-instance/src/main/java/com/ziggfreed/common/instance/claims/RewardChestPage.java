package com.ziggfreed.common.instance.claims;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.feedback.moment.FeedbackEngine;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailBindings;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailPainter;
import com.ziggfreed.common.ui.kit.DetailToggle;
import com.ziggfreed.common.ui.kit.EmptyStatePainter;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * A reward chest's one page: everything of the player's own filed under the chest (its key, {@link Claim#fight}),
 * and nothing of anyone else's, on the kit's reading page in a decorated frame with no menu rail. {@link ClaimsView}
 * decides what it shows ({@link ClaimsView#chestDetail}): under the chest's heading, one list of cards across all its
 * Claims in the style of vanilla's Memories page, what is not an item first on a warm plate (alike rewards summed into
 * one card), a divider, then the items best quality first, each on the game's own rarity slot with its own tooltip. A
 * click on a card takes it ({@link ClaimGrants#claimReward}: an item only when all of it fits; a summed card each of
 * its rewards in turn, {@link ClaimsView#takesOf}), and the gold Take All takes everything
 * ({@link ClaimGrants#claimFight}); each press repaints the page under one toast naming what landed and how much
 * ({@link ClaimsView#pressToast}). What does not fit stays listed here and waits under Claims once the page closes.
 * With nothing left the page says the player took everything and stays open, so the toast is seen; the chest goes
 * at its next upkeep ({@link SpoilsChestSystems}). {@link SpoilsView#open} is the one place that opens it.
 *
 * <p>Its first build is the chest opening: a gold flash behind its picture that the client plays once on its own, and
 * one sound ({@link #OPENED_MOMENT}) picked by the rarest reward in it. That is the whole of what an open sends beyond
 * the page itself: the cards' sheen and the flash run on the client, and a press's rebuild repeats neither.
 *
 * <p>Every event is answered exactly once through {@link RewardChestActions#event}: a press repaints, the close
 * button closes, anything else gets an empty update.
 */
public final class RewardChestPage extends ToastablePage<ClaimsEventData> {

    static final String PAGE_TEMPLATE = "Pages/ZigRewardChestPage.ui";
    static final String DETAIL = "#Page";
    static final String EMPTY = "#ChestEmpty";

    /** The gold flash behind the chest's picture, which the client plays once; shown on the page's first build only. */
    static final String BURST = "#OpenBurst";

    /**
     * The feedback moment the chest's opening fires, once per open, carrying {@link #RARITY_ARG}: its shipped file
     * plays one sound picked by the rarest reward in the chest, and a pack's same-named file changes it.
     */
    public static final String OPENED_MOMENT = "Reward_Chest_Opened";

    /** The rarest reward's rarity as its drop's chance reads ({@code Legendary}); absent when none carries a chance. */
    public static final String RARITY_ARG = "rarity";

    /**
     * A card's click takes it: its select id names its first reward's Claim and key ({@link ClaimsView.Taken}), sent
     * apart as {@code Claim} and {@code Reward}; the page takes the rest of a summed card on the server.
     */
    private static final DetailBindings LINES = new DetailBindings() {
        @Override
        public EventData line(DetailBlock b, DetailLine l) {
            ClaimsView.Taken take = ClaimsView.Taken.of(l.selectId());
            return take == null ? null : EventData.of("Action", RewardChestActions.TAKE).append("Claim", take.claim())
                    .append("Reward", take.reward());
        }

        @Override
        public EventData toggle(DetailToggle t) {
            return null;
        }
    };

    @Nonnull private final ClaimsPageDeps deps;

    /** The chest's key: what its Claims are filed under. */
    @Nonnull private final String fight;

    /** True once this page handed something over, so a page with nothing left says the player took everything. */
    private boolean took;

    /** True once the page has been built: its opening (the flash and the sound) happens on the first build alone. */
    private boolean opened;

    /**
     * @param fight the chest's key ({@link Claim#fight}): a boss fight's run id, or a {@code chest:} key
     */
    public RewardChestPage(@Nonnull PlayerRef playerRef, @Nonnull ClaimsPageDeps deps, @Nonnull String fight) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ClaimsEventData.CODEC);
        this.deps = Objects.requireNonNull(deps, "deps");
        this.fight = Objects.requireNonNull(fight, "fight").trim();
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        cmd.append(PAGE_TEMPLATE);
        boolean first = !opened;
        opened = true;
        try {
            List<Claim> shown = paint(cmd, events);
            if (first && !shown.isEmpty()) {
                open(cmd, ref, store, shown);
            }
        } catch (Throwable t) {
            // build() must not throw: a reading that fails leaves the page as far as it got.
            SafeLog.warn(SpoilsChests.LOG + "the chest's page could not be built", t);
        }
        // Last, so the toast is the top sibling and draws over the page.
        renderToastInto(cmd);
    }

    /** Paint the page; the Claims whose cards it shows (none when it shows the empty state). */
    @Nonnull
    private List<Claim> paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                EventData.of("Action", RewardChestActions.CLOSE));
        // The action bar's Take All is a live element, bound once per build.
        DetailPainter.bindActionsOnce(events, DETAIL,
                slot -> slot == ActionSlot.PRIMARY ? EventData.of("Action", RewardChestActions.TAKE_ALL) : null, null);
        ClaimsText text = deps.text();
        List<Claim> claims = waiting();
        if (claims.isEmpty()) {
            cmd.set(DETAIL + ".Visible", false);
            cmd.set(EMPTY + ".Visible", true);
            EmptyStatePainter.paint(cmd, events, EMPTY, ClaimsView.chestEmpty(text, took), null);
            return List.of();
        }
        DetailPainter.paint(cmd, events, DETAIL, ClaimsView.chestDetail(claims, text, SpoilsChests.leadOf(fight)),
                LINES, playerRef);
        return claims;
    }

    /**
     * The chest opening, on the page's first build: the gold flash behind its picture, which the client plays once
     * on its own, and the {@link #OPENED_MOMENT} sound, picked by the rarest reward in {@code claims}. Nothing here
     * runs again while the page stays open: a press's rebuild leaves the flash hidden and plays no sound.
     */
    private void open(@Nonnull UICommandBuilder cmd, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull List<Claim> claims) {
        cmd.set(BURST + ".Visible", true);
        try {
            Subject subject = Subject.of(playerRef, store.getComponent(ref, Player.getComponentType()));
            if (subject == null) {
                return;
            }
            Map<String, Object> args = new HashMap<>();
            String rarity = ClaimsView.chestRarity(claims);
            if (rarity != null) {
                args.put(RARITY_ARG, rarity);
            }
            FeedbackEngine.fire(OPENED_MOMENT, subject.withFacets(playerRef), args);
        } catch (Throwable t) {
            SafeLog.fine(SpoilsChests.LOG + "the chest's opening sound could not play: " + t.getMessage());
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull ClaimsEventData data) {
        RewardChestActions.event(new RewardChestActions.Host<Player>() {
            @Nullable
            @Override
            public Player player() {
                return store.getComponent(ref, Player.getComponentType());
            }

            @Override
            public void take(@Nonnull Player player, @Nonnull String claim, @Nonnull String reward) {
                UUID uuid = playerRef.getUuid();
                if (uuid == null) {
                    return;
                }
                // A summed card stands for alike rewards across the chest's Claims: each is taken in turn, under one
                // toast whose rows add them up, as the card did.
                List<ClaimsView.Taken> takes = ClaimsView.takesOf(waiting(), claim, reward);
                ClaimGrants.Result total = ClaimGrants.Result.NOTHING;
                for (ClaimsView.Taken each : takes) {
                    total = total.plus(ClaimGrants.claimReward(deps.claims(), uuid, each.claim(), each.reward(), ref,
                            store, deps.sink()));
                }
                told(total, false, takes.size() > 1);
            }

            @Override
            public void takeAll(@Nonnull Player player) {
                UUID uuid = playerRef.getUuid();
                if (uuid != null) {
                    told(ClaimGrants.claimFight(deps.claims(), uuid, fight, ref, store, deps.sink()), true, false);
                }
            }

            @Override
            public void reopen(@Nonnull Player player) {
                player.getPageManager().openCustomPage(ref, store, RewardChestPage.this);
            }

            @Override
            public void close(@Nonnull Player player) {
                player.getPageManager().setPage(ref, store, Page.None);
            }

            @Override
            public void answer() {
                sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
            }
        }, data.action, data.claim, data.reward);
    }

    /**
     * A press's one toast, over the repaint that follows ({@code summed}: a summed card's, its rows added up);
     * nothing for a press that took nothing (a stale line).
     */
    private void told(@Nonnull ClaimGrants.Result result, boolean all, boolean summed) {
        if (!result.nothing()) {
            took = true;
        }
        ToastSpec toast = ClaimsView.pressToast(result, deps.text(), all, summed);
        if (toast != null) {
            showToast(toast);
        }
    }

    /** The player's own Claims filed under the chest, oldest first, as the store lists them; none on a failed read. */
    @Nonnull
    private List<Claim> waiting() {
        try {
            UUID player = playerRef.getUuid();
            return player == null ? List.of()
                    : deps.claims().list(player).stream().filter(claim -> claim.fromFight(fight)).toList();
        } catch (Throwable t) {
            SafeLog.warn(SpoilsChests.LOG + "the chest's page could not read what waits in it", t);
            return List.of();
        }
    }
}
