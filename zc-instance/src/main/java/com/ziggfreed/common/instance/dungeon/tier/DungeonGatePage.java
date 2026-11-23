package com.ziggfreed.common.instance.dungeon.tier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

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
import com.ziggfreed.common.instance.dungeon.DungeonResults;
import com.ziggfreed.common.instance.dungeon.omen.RunOmens;
import com.ziggfreed.common.instance.result.ColumnFormat;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.ui.ZigFrame;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailPainter;
import com.ziggfreed.common.ui.kit.Hero;
import com.ziggfreed.common.ui.kit.HeroPainter;
import com.ziggfreed.common.ui.kit.PayMark;
import com.ziggfreed.common.ui.kit.TierCard;
import com.ziggfreed.common.ui.kit.TierCardPainter;
import com.ziggfreed.common.ui.kit.TilePainter;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * A dungeon's gate when it has more than one way in ({@link DungeonGate}), and its week board: the game's Chapter
 * screen on the band frame, in two columns.
 *
 * <p>The left column is the frame's hero ({@link HeroPainter}): the dungeon's {@code Emblem}, its name, a copper line
 * and the line under the rule, then its description, this week's omens by name over what each does, and the chests its
 * {@code Gate.Chests} names, each waiting or taken by the player ({@link GateChests}). The copper line says where the
 * player's party already is ("Your party is in Depth 2", {@link GateParty}) with the line under the rule saying which
 * card joins them; on a week board it is the board's own line ({@code LineKey}) with the party's line under it.
 *
 * <p>The right column is the depths, each a card that is ONE button (the kit's {@link TierCardPainter}, R1180): every
 * rung open to the player's whole party ({@link DungeonTiers#party}: every member standing with them has cleared that
 * rung's own {@code Opens}), then the next rung the party opens ({@link TierLadder#next}) as a locked card saying what
 * opens it or which member has not opened it yet, then the daily with its target time. Each card shows the omens it
 * meets this week ({@link RunOmens#draw}, R1006), what a clear pays as pictures and, as its tooltip, in words
 * ({@link TierPays}), and what the player's next clear of it today pays ({@link PayMark}, read from the clear's own
 * {@code Rewards.Diminish} count through {@link ClaimStore#nextShare}); a card a member of the party stands inside names
 * them. A card's click goes in through the one entry ({@link DungeonEntry#enterWithParty}), which checks the rung again;
 * a refusal reads as the usual line over the open page, and a dungeon with an entry cost opens its confirm screen in
 * this page's place. The week board ({@code enter} false) is the same page with no card to click and no go-in plate.
 *
 * <p>Its documents are {@code Pages/ZigDungeonGatePage.ui} and {@code Pages/ZigDungeonGateChest.ui}; the cards, lines,
 * slots and hero are the kit's, whose painters own their ids. Its words are {@code ziggfreedcommon.dungeon.lang}'s
 * {@code gate.*} and {@code tier.*}, and a pack's own through its dungeon file. Every exit of
 * {@link #handleDataEvent} answers the client.
 */
public final class DungeonGatePage extends ToastablePage<DungeonGateEventData> {

    static final String PAGE_TEMPLATE = "Pages/ZigDungeonGatePage.ui";
    static final String CHEST_TEMPLATE = "Pages/ZigDungeonGateChest.ui";

    static final String CARDS = "#GateCards";
    static final String OMENS = "#GateOmens";
    static final String CHESTS = "#GateChests";

    private static final String PREFIX = "ziggfreedcommon.dungeon.";
    private static final String ENTER = "enter";
    private static final String CLOSE = "close";
    private static final String LOG = "[dungeon] ";

    @Nonnull private final String dungeonId;
    private final boolean enter;
    @Nullable private final String lineKey;

    public DungeonGatePage(@Nonnull PlayerRef playerRef, @Nonnull String dungeonId) {
        this(playerRef, dungeonId, true, null);
    }

    /**
     * A gate whose cards go in when {@code enter}, else only to be read (a week board), with {@code lineKey}'s line as
     * its copper line under the dungeon's name (null for none).
     */
    public DungeonGatePage(@Nonnull PlayerRef playerRef, @Nonnull String dungeonId, boolean enter,
            @Nullable String lineKey) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, DungeonGateEventData.CODEC);
        this.dungeonId = dungeonId;
        this.enter = enter;
        this.lineKey = lineKey == null || lineKey.isBlank() ? null : lineKey.trim();
    }

    /**
     * Open the gate of {@code dungeonId} for the player at {@code ref}, replacing whatever page they have open, on
     * their world thread from a world task, never from inside a running system. False when the entity is no player.
     */
    public static boolean open(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef player, @Nonnull String dungeonId) {
        return open(ref, store, player, dungeonId, true, null);
    }

    /**
     * {@link #open(Ref, Store, PlayerRef, String)} as a gate whose cards go in when {@code enter}, else as a week board
     * with nothing to click, with {@code lineKey}'s line under the dungeon's name (null for none).
     */
    public static boolean open(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef player, @Nonnull String dungeonId, boolean enter, @Nullable String lineKey) {
        Player playerComponent = store.getComponent(ref, Player.getComponentType());
        if (playerComponent == null) {
            return false;
        }
        playerComponent.getPageManager().openCustomPage(ref, store,
                new DungeonGatePage(player, dungeonId, enter, lineKey));
        return true;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        cmd.append(PAGE_TEMPLATE);
        events.addEventBinding(CustomUIEventBindingType.Activating, ZigFrame.CLOSE, EventData.of("Action", CLOSE));
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
            HeroPainter.paint(cmd, Hero.of(key("entry.unavailable.unnamed")));
            return;
        }
        UUID me = this.playerRef.getUuid();
        long now = System.currentTimeMillis();
        List<TierLadder.Rung> ladder = DungeonTiers.ladderOf(dungeonId);
        List<DungeonTiers.Member> members = DungeonTiers.members(ref, store, me);
        List<Predicate<String>> party = new ArrayList<>(members.size());
        for (DungeonTiers.Member member : members) {
            party.add(member.cleared());
        }
        Map<String, List<UUID>> inside = GateParty.inside(me, ladder);
        List<TierLadder.Rung> pickable = TierLadder.pickable(ladder, party);
        // The hero first: it is the left column, so a later part that cannot paint never hides it.
        hero(cmd, named, ladder, pickable, inside);
        String description = named.descriptionKey();
        cmd.set("#GateLead.TextSpans", description == null ? key("gate.lead") : ContentKeys.tr(description));

        // The cards: the rungs open to the whole party, the next one locked, then the dailies.
        List<TierCard> open = new ArrayList<>();
        List<TierCard> dailies = new ArrayList<>();
        for (TierLadder.Rung rung : pickable) {
            DungeonAsset dungeon = DungeonConfig.getInstance().resolve(rung.id());
            if (dungeon == null || !dungeon.isOpen()) {
                continue;
            }
            (rung.daily() ? dailies : open).add(openCard(rung, dungeon, GateParty.in(inside, rung.id()), me, now));
        }
        List<TierCard> cards = new ArrayList<>(open);
        TierCard locked = lockedCard(ladder, party, members, inside);
        if (locked != null) {
            cards.add(locked);
        }
        cards.addAll(dailies);
        Function<TierCard, EventData> binding = enter
                ? card -> EventData.of("Action", ENTER).append("Dungeon", card.id())
                : card -> null;
        TierCardPainter.paint(cmd, events, CARDS, cards, binding);
        cmd.set("#GateHead.Visible", !cards.isEmpty());
        if (!cards.isEmpty()) {
            cmd.set("#GateHead.TextSpans", key(GateParty.withOthers(me) ? "gate.open.party" : "gate.open.you"));
        }
        boolean nothing = open.isEmpty() && dailies.isEmpty();
        cmd.set("#GateHint.Visible", nothing);
        if (nothing) {
            cmd.set("#GateHint.TextSpans", key("gate.none"));
        }
        omens(cmd, events, ladder);
        chests(cmd, GateChests.of(named, me, now));
    }

    /**
     * The hero: the emblem, the dungeon's name, and its copper line and the line under the rule. On a gate the copper
     * line says where the party already is and the line under the rule which card joins them (when the player may pick
     * it); with a board's own line, that is the copper line and the party's line goes under the rule.
     */
    private void hero(@Nonnull UICommandBuilder cmd, @Nonnull DungeonAsset named,
            @Nonnull List<TierLadder.Rung> ladder, @Nonnull List<TierLadder.Rung> pickable,
            @Nonnull Map<String, List<UUID>> inside) {
        Message partyLine = null;
        Message joinLine = null;
        for (TierLadder.Rung rung : ladder) {
            DungeonAsset dungeon = GateParty.in(inside, rung.id()) == null ? null
                    : DungeonConfig.getInstance().resolve(rung.id());
            if (dungeon == null) {
                continue;
            }
            Message word = TierNames.word(dungeon);
            partyLine = key("gate.party.in", word);
            if (enter && pickable.contains(rung) && dungeon.isOpen()) {
                joinLine = key("gate.party.join", word);
            }
            break;
        }
        Message board = lineKey == null ? null : ContentKeys.tr(lineKey);
        Message tagline = board != null ? board : partyLine;
        Message sub = board != null ? partyLine : joinLine;
        HeroPainter.paint(cmd, new Hero(named.emblem(), DungeonRefusals.dungeonName(named), tagline, sub));
    }

    /** An open rung's card: what it meets, what it pays and what the player's next clear of it pays. */
    @Nonnull
    private TierCard openCard(@Nonnull TierLadder.Rung rung, @Nonnull DungeonAsset dungeon,
            @Nullable List<UUID> in, @Nonnull UUID me, long now) {
        Message sub = in == null ? null : key("gate.party.inside", GateParty.names(in));
        Integer par = rung.daily() ? dungeon.completeParSeconds() : null;
        if (sub == null && par != null) {
            sub = key("gate.daily.target", ColumnFormat.TIME.render(par));
        }
        List<TierPays.Pay> pays = TierPays.of(dungeon);
        return new TierCard(rung.id(), rung.daily() ? key("gate.daily.tag") : null, TierNames.word(dungeon), sub,
                week(rung, dungeon), pays.isEmpty() ? null : key("gate.pays"), TierPays.slots(pays),
                TierPays.note(pays, TierCardPainter.PAYS_SHOWN), pays.isEmpty() ? null : mark(dungeon, me, now),
                enter ? key("gate.go") : null, TierPays.line(pays), null);
    }

    /**
     * The next rung the party opens, as a locked card: what it meets and pays, and what keeps it shut. When the player
     * has cleared what opens it and a member standing with them has not, the card names that member; else it says what
     * to clear. Null when every rung is open to the party, or the next one cannot be read.
     */
    @Nullable
    private static TierCard lockedCard(@Nonnull List<TierLadder.Rung> ladder, @Nonnull List<Predicate<String>> party,
            @Nonnull List<DungeonTiers.Member> members, @Nonnull Map<String, List<UUID>> inside) {
        TierLadder.Rung next = TierLadder.next(ladder, party);
        String opens = next == null ? null : next.opens();
        DungeonAsset tier = next == null ? null : DungeonConfig.getInstance().resolve(next.id());
        if (opens == null || tier == null || !tier.isOpen()) {
            return null;
        }
        DungeonAsset opener = DungeonConfig.getInstance().resolve(opens);
        Message word = TierNames.word(tier);
        Message openerWord = opener == null ? Msg.raw(opens) : TierNames.word(opener);
        UUID behind = null;
        if (!members.isEmpty() && members.get(0).cleared().test(opens)) {
            for (DungeonTiers.Member member : members.subList(1, members.size())) {
                if (!member.cleared().test(opens)) {
                    behind = member.id();
                    break;
                }
            }
        }
        TierCard.Lock lock;
        if (behind != null) {
            Message who = GateParty.name(behind);
            lock = new TierCard.Lock(key("gate.party.unopened", who, word),
                    key("gate.party.unopened.how", word, who, openerWord));
        } else {
            lock = new TierCard.Lock(key("gate.next", openerWord, word), null);
        }
        List<UUID> in = GateParty.in(inside, next.id());
        List<TierPays.Pay> pays = TierPays.of(tier);
        return new TierCard(next.id(), null, word, in == null ? null : key("gate.party.inside", GateParty.names(in)),
                week(next, tier), pays.isEmpty() ? null : key("gate.pays"), TierPays.slots(pays),
                TierPays.note(pays, TierCardPainter.PAYS_SHOWN), null, null, null, lock);
    }

    /** What a rung meets this week, by name; a daily's line says its rooms are the same for every party today. */
    @Nullable
    private static Message week(@Nonnull TierLadder.Rung rung, @Nonnull DungeonAsset dungeon) {
        Message omens = RunOmens.names(RunOmens.draw(dungeon, System.currentTimeMillis()));
        if (rung.daily()) {
            return omens == null ? key("gate.daily.meta") : key("gate.daily.omens", omens);
        }
        return omens == null ? null : key("gate.tier.omens", omens);
    }

    /**
     * What the player's next clear of {@code dungeon} today pays, by its {@code Rewards.Diminish} count: four diamonds
     * for a full share, two for half, one for a quarter, none for nothing.
     */
    @Nonnull
    private static PayMark mark(@Nonnull DungeonAsset dungeon, @Nonnull UUID me, long now) {
        String id = dungeon.getId() == null ? "" : dungeon.getId();
        double share = ClaimStore.shared().nextShare(me, dungeon.rewardsDiminish(),
                DungeonResults.diminishKey(id, dungeon.difficulty()), now);
        int lit = PayMark.litFor(share);
        String word = switch (lit) {
            case PayMark.PIPS -> "gate.mark.full";
            case 3 -> "gate.mark.most";
            case 2 -> "gate.mark.half";
            case 1 -> "gate.mark.quarter";
            default -> "gate.mark.none";
        };
        return new PayMark(key("gate.mark.head"), key(word), lit);
    }

    /**
     * This week's omens, by name over what each does: every omen some rung of the ladder meets this week, in the week's
     * order; the head and its line hidden when no rung meets any.
     */
    private static void omens(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull List<TierLadder.Rung> ladder) {
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
        List<DetailLine> lines = new ArrayList<>(met.size());
        for (String omen : met) {
            lines.add(DetailLine.bullet(RunOmens.name(omen), RunOmens.description(omen)));
        }
        boolean any = !lines.isEmpty();
        cmd.set("#GateOmensHead.Visible", any);
        cmd.set("#GateOmensSub.Visible", any);
        if (any) {
            cmd.set("#GateOmensHead.TextSpans", key("gate.omens.head"));
            cmd.set("#GateOmensSub.TextSpans", key("gate.omens.sub"));
        }
        DetailPainter.lines(cmd, events, OMENS, lines, line -> null);
    }

    /** The dungeon's chests: each its slot, its name, its line and whether it waits or is taken. */
    private static void chests(@Nonnull UICommandBuilder cmd, @Nonnull List<GateChests.Row> rows) {
        cmd.set("#GateChestsHead.Visible", !rows.isEmpty());
        if (!rows.isEmpty()) {
            cmd.set("#GateChestsHead.TextSpans", key("gate.chests"));
        }
        cmd.clear(CHESTS);
        for (int i = 0; i < rows.size(); i++) {
            GateChests.Row row = rows.get(i);
            cmd.append(CHESTS, CHEST_TEMPLATE);
            String r = CHESTS + "[" + i + "]";
            TilePainter.chestSlots(cmd, r + " #ChestSlot", List.of(row.slot()));
            cmd.set(r + " #ChestName.TextSpans", row.name());
            cmd.set(r + " #ChestLine.TextSpans", row.line());
            cmd.set(r + " #ChestWaiting.Visible", !row.taken());
            cmd.set(r + " #ChestTaken.Visible", row.taken());
            cmd.set(r + (row.taken() ? " #ChestTaken.TextSpans" : " #ChestWaiting.TextSpans"),
                    key(row.taken() ? "gate.chest.taken" : "gate.chest.waiting"));
        }
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
        if (!enter) {
            // A week board's cards are bound to nothing; a stray press reads nothing and moves nobody.
            this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
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
