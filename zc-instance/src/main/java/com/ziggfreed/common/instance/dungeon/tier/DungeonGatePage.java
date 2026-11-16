package com.ziggfreed.common.instance.dungeon.tier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.dungeon.DungeonAsset;
import com.ziggfreed.common.instance.dungeon.DungeonConfig;
import com.ziggfreed.common.instance.dungeon.DungeonEntry;
import com.ziggfreed.common.instance.dungeon.DungeonRefusals;
import com.ziggfreed.common.instance.dungeon.omen.RunOmens;
import com.ziggfreed.common.ui.kit.LedgerBindings;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.RowSize;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * A dungeon's gate when it has more than one way in ({@link DungeonGate}): the tiers the player's party may pick
 * ({@link DungeonTiers#partyRank}: up to the lowest rank any member standing with them has opened), each with the exact
 * omens it meets this week ({@link RunOmens#draw}, R1006), then the daily the party may pick with its own, then every
 * omen met this week by name with what each does,
 * and under the list which dungeon to clear to open the next tier. A row's click goes in through the one entry
 * ({@link DungeonEntry#enterWithParty}), which checks the tier again; a refusal reads as the usual line over the open
 * page, and a dungeon with an entry cost opens its confirm screen in this page's place.
 *
 * <p>Its document is {@code Pages/ZigDungeonGatePage.ui}; the list is the kit's ledger ({@link LedgerPainter}), which
 * owns every row's ids. Its words are {@code ziggfreedcommon.dungeon.lang}'s {@code gate.*} and {@code tier.*}. Every
 * exit of {@link #handleDataEvent} answers the client.
 */
public final class DungeonGatePage extends ToastablePage<DungeonGateEventData> {

    static final String PAGE_TEMPLATE = "Pages/ZigDungeonGatePage.ui";
    static final String LIST = "#GateList";

    private static final String PREFIX = "ziggfreedcommon.dungeon.";
    private static final String ENTER = "enter";
    private static final String CLOSE = "close";
    private static final String TIERS = "tiers";
    private static final String DAILY = "daily";
    private static final String OMENS = "omens";
    private static final String LOG = "[dungeon] ";

    /** A tier's or a daily's click goes in; an omen's row and every head are only read. */
    private static final LedgerBindings ROWS = new LedgerBindings() {
        @Override
        public EventData row(LedgerSection s, LedgerRow r) {
            return OMENS.equals(s.id()) ? null : EventData.of("Action", ENTER).append("Dungeon", r.id());
        }

        @Override
        public EventData section(LedgerSection s) {
            return null;
        }

        @Override
        public EventData showMore(LedgerSection s) {
            return null;
        }
    };

    @Nonnull private final String dungeonId;

    public DungeonGatePage(@Nonnull PlayerRef playerRef, @Nonnull String dungeonId) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, DungeonGateEventData.CODEC);
        this.dungeonId = dungeonId;
    }

    /**
     * Open the gate of {@code dungeonId} for the player at {@code ref}, replacing whatever page they have open, on
     * their world thread from a world task, never from inside a running system. False when the entity is no player.
     */
    public static boolean open(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef player, @Nonnull String dungeonId) {
        Player playerComponent = store.getComponent(ref, Player.getComponentType());
        if (playerComponent == null) {
            return false;
        }
        playerComponent.getPageManager().openCustomPage(ref, store, new DungeonGatePage(player, dungeonId));
        return true;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        cmd.append(PAGE_TEMPLATE);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", CLOSE));
        try {
            paint(ref, store, cmd, events);
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the gate of '" + dungeonId + "' could not paint everything", t);
        }
        renderToastInto(cmd);
    }

    private void paint(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        DungeonAsset named = DungeonConfig.getInstance().resolve(dungeonId);
        if (named == null) {
            cmd.set("#Lead.TextSpans", key("entry.unavailable.unnamed"));
            return;
        }
        cmd.set("#Title.TextSpans", DungeonRefusals.dungeonName(named));
        String description = named.descriptionKey();
        cmd.set("#Lead.TextSpans", description == null ? key("gate.lead") : ContentKeys.tr(description));

        List<TierLadder.Rung> ladder = DungeonTiers.ladderOf(dungeonId);
        int rank = DungeonTiers.partyRank(ladder, ref, store, this.playerRef.getUuid());
        List<LedgerRow> tiers = new ArrayList<>();
        List<LedgerRow> dailies = new ArrayList<>();
        for (TierLadder.Rung rung : TierLadder.pickable(ladder, rank)) {
            DungeonAsset dungeon = DungeonConfig.getInstance().resolve(rung.id());
            if (dungeon == null || !dungeon.isOpen()) {
                continue;
            }
            (rung.daily() ? dailies : tiers).add(row(rung, dungeon));
        }
        List<LedgerSection> sections = new ArrayList<>();
        if (!tiers.isEmpty()) {
            sections.add(new LedgerSection(TIERS, key("gate.tiers"), tiers, true));
        }
        if (!dailies.isEmpty()) {
            sections.add(new LedgerSection(DAILY, key("gate.daily"), dailies, true));
        }
        List<LedgerRow> omens = omenRows(ladder);
        if (!omens.isEmpty()) {
            sections.add(new LedgerSection(OMENS, key("gate.omens"), omens, true));
        }
        LedgerPainter.paint(cmd, events, LIST, LedgerModel.of(sections), Set.of(), null, ROWS, RowSize.STANDARD,
                this.playerRef);

        Message hint = hint(ladder, rank, tiers.isEmpty() && dailies.isEmpty());
        if (hint != null) {
            cmd.set("#Hint.Visible", true);
            cmd.set("#Hint.TextSpans", hint);
        }
    }

    /** A tier's or a daily's row: its name with its rank, and this week's omens it meets, by name. */
    @Nonnull
    private static LedgerRow row(@Nonnull TierLadder.Rung rung, @Nonnull DungeonAsset dungeon) {
        Message omens = RunOmens.names(RunOmens.draw(dungeon, System.currentTimeMillis()));
        Message meta = rung.daily()
                ? (omens == null ? key("gate.daily.meta") : key("gate.daily.omens", omens))
                : (omens == null ? key("gate.tier.no_omens") : key("gate.tier.omens", omens));
        return new LedgerRow(rung.id(), DungeonRefusals.tierName(dungeon), meta, Picture.NONE, Tone.NEUTRAL, null,
                null, null, Mark.NONE, false);
    }

    /**
     * This week's omens, by name over what each does: every omen some rung of the ladder meets this week, in the week's
     * order; none when no rung meets any.
     */
    @Nonnull
    private static List<LedgerRow> omenRows(@Nonnull List<TierLadder.Rung> ladder) {
        long now = System.currentTimeMillis();
        List<String> met = new ArrayList<>();
        for (TierLadder.Rung rung : ladder) {
            DungeonAsset dungeon = DungeonConfig.getInstance().resolve(rung.id());
            for (String omen : dungeon == null ? List.<String>of() : RunOmens.draw(dungeon, now)) {
                if (!met.contains(omen)) {
                    met.add(omen);
                }
            }
        }
        List<LedgerRow> rows = new ArrayList<>();
        for (String omen : met) {
            rows.add(new LedgerRow(OMENS + ":" + omen, RunOmens.name(omen), RunOmens.description(omen), Picture.NONE,
                    Tone.NEUTRAL, null, null, null, Mark.NONE, false));
        }
        return rows;
    }

    /**
     * The line under the list: which dungeon to clear to open the next tier; that nothing is open to the party when
     * {@code nothing} is; else none.
     */
    @Nullable
    private static Message hint(@Nonnull List<TierLadder.Rung> ladder, int rank, boolean nothing) {
        if (nothing) {
            return key("gate.none");
        }
        TierLadder.Rung next = TierLadder.next(ladder, rank);
        DungeonAsset tier = next == null ? null : DungeonConfig.getInstance().resolve(next.id());
        DungeonAsset opener = next == null || next.opens() == null ? null
                : DungeonConfig.getInstance().resolve(next.opens());
        if (tier == null || opener == null) {
            return null;
        }
        return key("gate.next", DungeonRefusals.tierName(opener), DungeonRefusals.tierName(tier));
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull DungeonGateEventData data) {
        String action = data.action == null ? "" : data.action;
        String target = data.dungeon == null ? null : data.dungeon.trim();
        if (!ENTER.equals(action) || target == null || target.isEmpty()) {
            close(ref, store);
            return;
        }
        try {
            World world = store.getExternalData().getWorld();
            DungeonEntry.Outcome outcome = DungeonEntry.enterWithParty(ref, store, this.playerRef, world, target);
            if (outcome.result() == DungeonEntry.Result.SENT) {
                close(ref, store);
                return;
            }
            // A confirm screen takes this page's place on a task of its own; a refusal reads over the open page.
            DungeonRefusals.tell(this.playerRef, target, outcome);
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the gate of '" + dungeonId + "' could not send " + this.playerRef.getUuid()
                    + " into '" + target + "'", t);
        }
        this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }

    /** Close the page; a player who is gone is answered with an empty update. */
    private void close(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        Player playerComponent = ref.isValid() ? store.getComponent(ref, Player.getComponentType()) : null;
        if (playerComponent == null) {
            this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
            return;
        }
        playerComponent.getPageManager().setPage(ref, store, Page.None);
    }

    @Nonnull
    private static Message key(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }
}
