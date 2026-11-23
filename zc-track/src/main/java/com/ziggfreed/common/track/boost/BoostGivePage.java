package com.ziggfreed.common.track.boost;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.permissions.PermissionValidation;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.owner.OwnerAudience;
import com.ziggfreed.common.progress.gate.FeatureLift;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.asset.TrackConfig;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.ZigFrame;
import com.ziggfreed.common.ui.kit.Hero;
import com.ziggfreed.common.ui.kit.HeroPainter;
import com.ziggfreed.common.ui.owner.OwnerMenu;
import com.ziggfreed.common.ui.owner.OwnerPageGuard;
import com.ziggfreed.common.ui.owner.OwnerText;
import com.ziggfreed.common.ui.owner.OwnerViewer;
import com.ziggfreed.common.util.SafeLog;

/**
 * The owner menu's Award Boost screen ({@code Pages/ZigBoostGivePage.ui}): pick any mod's boost and, inside what it
 * lifts, one track or all of them, with its own percent and length or the ones typed here; then give it to a player
 * online, as a Claim on their Claims tab that starts it when they collect it, or start it now for everyone on the
 * server ({@link Boosts#startServer}). It runs no command: a start it makes is any boost's start, the owner's own start
 * command from {@code boost-settings.json} included, as on every start.
 *
 * <p>It offers every switched-on timed boost but a reusable one (a reusable boost is each player's own to start). Every
 * value the client sends is read again here: the boost must be one this screen offers, the track one that boost
 * lifts, the numbers in range, the player online. Every event but Close asks the owner audience again
 * ({@link OwnerPageGuard}, which closes the page on a refusal), and every exit answers the client.
 *
 * <p><b>The permission a reusable boost asks for.</b> A reusable boost whose {@code Reusable.Requires} names a
 * permission node (its top-level {@code Permission} leaf) is one a player starts once they hold that node. The screen
 * lists each such node ({@link #grantable}: a switched-on reusable boost the game shows on this server, its node one
 * exact node, never a wildcard or a denial) and grants exactly the picked one to the player named, online, through the
 * engine's own permission module ({@code PermissionsModule.addUserPermission}), never a command; the node is read again
 * from the boosts at the press, and the line says what it granted.
 */
public final class BoostGivePage extends InteractiveCustomUIPage<BoostGivePage.Event> {

    /** The page document. */
    public static final String PAGE_TEMPLATE = "Pages/ZigBoostGivePage.ui";

    /** The track dropdown's value for every track the boost lifts. */
    static final String ALL_TRACKS = "*";

    /** The most a percent typed here may be, and the longest a length, in minutes (a year). */
    static final double MAX_PERCENT = 10000.0;
    static final int MAX_MINUTES = 525_600;

    static final String STATUS = "#BoostGiveStatus";
    static final String BOOST = "#BoostGiveBoost";
    static final String TRACK = "#BoostGiveTrack";
    static final String PERCENT = "#BoostGivePercent";
    static final String MINUTES = "#BoostGiveMinutes";
    static final String OWN = "#BoostGiveOwn";
    static final String PLAYER = "#BoostGivePlayer";
    static final String GIVE = "#BoostGiveGive";
    static final String SERVER = "#BoostGiveServer";
    static final String BACK = "#BoostGiveBack";
    static final String NODE = "#BoostGiveNode";
    static final String GRANT = "#BoostGiveGrant";

    /** Every selector this page sets or binds, which its document test walks. */
    static final List<String> SELECTORS = List.of(STATUS, BOOST, TRACK, PERCENT, MINUTES, OWN, PLAYER, GIVE, SERVER,
            BACK, NODE, GRANT);

    static final String ACTION = "Action";
    static final String A_CLOSE = "close";
    static final String A_BACK = "back";
    static final String A_BOOST = "boost";
    static final String A_GIVE = "give";
    static final String A_SERVER = "server";
    static final String A_GRANT = "grant";
    static final String K_BOOST = "@Boost";
    static final String K_TRACK = "@Track";
    static final String K_PERCENT = "@Percent";
    static final String K_MINUTES = "@Minutes";
    static final String K_PLAYER = "@Player";
    static final String K_NODE = "@Node";

    /** What the page's events round-trip: which button, and each control's live value. */
    public static final class Event {

        String action;
        String boost;
        String track;
        String percent;
        String minutes;
        String player;
        String node;

        public static final BuilderCodec<Event> CODEC = BuilderCodec.builder(Event.class, Event::new)
                .append(new KeyedCodec<>(ACTION, Codec.STRING),
                        (e, v, info) -> e.action = v, (e, info) -> e.action).add()
                .append(new KeyedCodec<>(K_BOOST, Codec.STRING),
                        (e, v, info) -> e.boost = v, (e, info) -> e.boost).add()
                .append(new KeyedCodec<>(K_TRACK, Codec.STRING),
                        (e, v, info) -> e.track = v, (e, info) -> e.track).add()
                .append(new KeyedCodec<>(K_PERCENT, Codec.STRING),
                        (e, v, info) -> e.percent = v, (e, info) -> e.percent).add()
                .append(new KeyedCodec<>(K_MINUTES, Codec.STRING),
                        (e, v, info) -> e.minutes = v, (e, info) -> e.minutes).add()
                .append(new KeyedCodec<>(K_PLAYER, Codec.STRING),
                        (e, v, info) -> e.player = v, (e, info) -> e.player).add()
                .append(new KeyedCodec<>(K_NODE, Codec.STRING),
                        (e, v, info) -> e.node = v, (e, info) -> e.node).add()
                .build();
    }

    @Nullable private final String menuFirst;
    @Nullable private String selected;

    private BoostGivePage(@Nonnull PlayerRef playerRef, @Nullable String menuFirst) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Event.CODEC);
        this.menuFirst = menuFirst;
    }

    /** Open it for the owner looking at the owner menu; its Back opens the menu again. */
    public static boolean open(@Nonnull OwnerViewer viewer) {
        return open(viewer.store(), viewer.ref(), viewer.player(), null);
    }

    /**
     * Open it for {@code player}, its Back returning to the owner menu with {@code menuFirst}'s section at the front.
     * False when the owner audience refuses or the open threw; the caller answers then.
     */
    public static boolean open(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player, @Nullable String menuFirst) {
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        if (playerRef == null || !OwnerAudience.mayAdminister(store, ref, player)) {
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store, new BoostGivePage(playerRef, menuFirst));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[boost] the Award Boost screen failed to open", t);
            return false;
        }
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        cmd.append(PAGE_TEMPLATE);
        bind(events);
        try {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null || !OwnerAudience.mayAdminister(store, ref, player)) {
                HeroPainter.paint(cmd, new Hero(null, BoostText.line("give.title"),
                        OwnerText.line("menu.refused"), null));
                cmd.set(GIVE + ".Disabled", true);
                cmd.set(SERVER + ".Disabled", true);
                cmd.set(GRANT + ".Disabled", true);
                return;
            }
            HeroPainter.paint(cmd, new Hero(null, BoostText.line("give.title"), BoostText.line("give.line"), null));
            paintNodes(cmd);
            List<BoostAsset> offered = offered();
            BoostAsset boost = pick(offered, selected);
            if (boost == null) {
                SettingsUiUtil.setStatus(cmd, STATUS, BoostText.line("give.none"), true);
                cmd.set(GIVE + ".Disabled", true);
                cmd.set(SERVER + ".Disabled", true);
                return;
            }
            selected = boost.getId();
            List<DropdownEntryInfo> boosts = new ArrayList<>();
            for (BoostAsset each : offered) {
                boosts.add(new DropdownEntryInfo(nameOf(each), each.getId()));
            }
            cmd.set(BOOST + ".Entries", boosts);
            cmd.set(BOOST + ".Value", boost.getId());
            paintBoost(cmd, boost);
        } catch (Throwable t) {
            SafeLog.warn("[boost] the Award Boost screen could not paint everything", t);
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Event data) {
        Player player = ref.isValid() ? store.getComponent(ref, Player.getComponentType()) : null;
        if (player == null || !route(ref, store, player, data)) {
            this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
        }
    }

    /** Closes, opens or updates for one event; false when it sent nothing. */
    private boolean route(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player,
            @Nonnull Event data) {
        String action = data.action == null ? "" : data.action;
        if (A_CLOSE.equals(action)) {
            player.getPageManager().setPage(ref, store, Page.None);
            return true;
        }
        if (!OwnerPageGuard.stillAllowed(store, ref, player, this.playerRef, OwnerAudience::mayAdminister)) {
            return true;
        }
        if (A_BACK.equals(action)) {
            return OwnerMenu.open(store, ref, player, menuFirst);
        }
        List<BoostAsset> offered = offered();
        BoostAsset boost = offeredById(offered, data.boost);
        return switch (action) {
            case A_BOOST -> {
                if (boost == null) {
                    yield false;
                }
                selected = boost.getId();
                UICommandBuilder cmd = new UICommandBuilder();
                paintBoost(cmd, boost);
                SettingsUiUtil.setStatus(cmd, STATUS, null, false);
                this.sendUpdate(cmd, new UIEventBuilder(), false);
                yield true;
            }
            case A_GIVE -> give(boost, data);
            case A_SERVER -> startServer(boost, data);
            case A_GRANT -> grant(data);
            default -> false;
        };
    }

    /**
     * Grant the permission node picked in {@code data} to the player named there, online: only a node some switched-on
     * reusable boost names right now ({@link #grantable}), read again here, through the engine's own permission module.
     * A player who already holds it is told so and nothing is written.
     */
    private boolean grant(@Nonnull Event data) {
        String node = data.node == null ? "" : data.node.trim();
        BoostAsset boost = node.isEmpty() ? null : grantable().get(node);
        if (boost == null) {
            return say(BoostText.line(node.isEmpty() || !grantableNode(node) ? "give.node.bad" : "give.node.unknown"),
                    true);
        }
        String name = data.player == null ? "" : data.player.trim();
        if (name.isEmpty()) {
            return say(BoostText.line("give.player_needed"), true);
        }
        PlayerRef target = Universe.get().getPlayerByUsername(name, NameMatching.EXACT_IGNORE_CASE);
        if (target == null) {
            return say(BoostText.line("give.player_offline", name), true);
        }
        PermissionsModule permissions = PermissionsModule.get();
        UUID uuid = target.getUuid();
        try {
            if (permissions == null) {
                return say(BoostText.line("give.node.failed", target.getUsername()), true);
            }
            if (permissions.hasPermission(uuid, node)) {
                return say(BoostText.line("give.node.held", target.getUsername(), node), false);
            }
            permissions.addUserPermission(uuid, Set.of(node));
        } catch (Throwable t) {
            SafeLog.warn("[boost] the permission " + node + " could not be granted to " + target.getUsername()
                    + " from the Award Boost screen: " + t.getMessage());
            return say(BoostText.line("give.node.failed", target.getUsername()), true);
        }
        target.sendMessage(BoostText.line("give.node.received", BoostText.name(boost)));
        SafeLog.info("[boost] " + this.playerRef.getUsername() + " granted " + target.getUsername() + " the permission "
                + node + " (the reusable boost " + boost.getId() + ") from the Award Boost screen");
        return say(BoostText.line("give.granted", node, target.getUsername(), BoostText.name(boost)), false);
    }

    /** Give {@code boost} to the player named in {@code data}, as a Claim they collect. */
    private boolean give(@Nullable BoostAsset boost, @Nonnull Event data) {
        Start start = startOf(boost, data);
        if (start.refusal() != null) {
            return say(start.refusal(), true);
        }
        String name = data.player == null ? "" : data.player.trim();
        if (name.isEmpty()) {
            return say(BoostText.line("give.player_needed"), true);
        }
        PlayerRef target = Universe.get().getPlayerByUsername(name, NameMatching.EXACT_IGNORE_CASE);
        if (target == null) {
            return say(BoostText.line("give.player_offline", name), true);
        }
        if (!BoostRewardKind.give(target.getUuid(), start.boost(), start.percent(), start.seconds(), start.track())) {
            return say(BoostText.line("give.failed", target.getUsername()), true);
        }
        target.sendMessage(BoostText.line("give.received", BoostText.name(start.boost())));
        SafeLog.info("[boost] " + this.playerRef.getUsername() + " gave " + target.getUsername() + " the boost "
                + start.boost().getId() + " from the Award Boost screen");
        return say(BoostText.line("give.done", BoostText.name(start.boost()), target.getUsername()), false);
    }

    /** Start {@code boost} now for everyone on the server, at the numbers in {@code data}. */
    private boolean startServer(@Nullable BoostAsset boost, @Nonnull Event data) {
        Start start = startOf(boost, data);
        if (start.refusal() != null) {
            return say(start.refusal(), true);
        }
        Boosts.Start started = Boosts.startServer(start.boost().getId(), start.percent(), start.seconds(),
                start.track(), this.playerRef.getUsername());
        if (!started.started()) {
            Message why = BoostText.refusal(started);
            return say(why == null ? BoostText.line("give.server_refused") : why, true);
        }
        return say(BoostText.line("give.server_done", BoostText.name(start.boost())), false);
    }

    /** A start the page may make: the boost, and its percent, seconds and track (null for the boost's own), or why not. */
    private record Start(@Nullable BoostAsset boost, @Nullable Double percent, @Nullable Long seconds,
            @Nullable String track, @Nullable Message refusal) {

        static Start refused(@Nonnull Message why) {
            return new Start(null, null, null, null, why);
        }
    }

    /** Read {@code data}'s numbers and track for {@code boost}, each held to what this screen allows. */
    @Nonnull
    private static Start startOf(@Nullable BoostAsset boost, @Nonnull Event data) {
        if (boost == null) {
            return Start.refused(BoostText.line("give.unknown"));
        }
        Double percent = null;
        String typedPercent = data.percent == null ? "" : data.percent.trim();
        if (!typedPercent.isEmpty()) {
            try {
                percent = Double.parseDouble(typedPercent);
            } catch (NumberFormatException e) {
                percent = Double.NaN;
            }
            if (!Double.isFinite(percent) || percent <= 0.0 || percent > MAX_PERCENT) {
                return Start.refused(BoostText.line("give.bad_percent", (long) MAX_PERCENT));
            }
        }
        Long seconds = null;
        String typedMinutes = data.minutes == null ? "" : data.minutes.trim();
        if (!typedMinutes.isEmpty()) {
            int minutes;
            try {
                minutes = Integer.parseInt(typedMinutes);
            } catch (NumberFormatException e) {
                minutes = -1;
            }
            if (minutes < 1 || minutes > MAX_MINUTES) {
                return Start.refused(BoostText.line("give.bad_minutes", MAX_MINUTES));
            }
            seconds = minutes * 60L;
        }
        String track = data.track == null ? "" : data.track.trim();
        if (track.isEmpty() || ALL_TRACKS.equals(track)) {
            track = null;
        } else {
            String known = null;
            for (TrackAsset each : liftedTracks(boost)) {
                if (each.getId().equalsIgnoreCase(track)) {
                    known = each.getId();
                }
            }
            if (known == null) {
                return Start.refused(BoostText.line("give.bad_track"));
            }
            track = known;
        }
        return new Start(boost, percent, seconds, track, null);
    }

    /** The boost's track list and the line saying what it gives when nothing is typed. */
    private static void paintBoost(@Nonnull UICommandBuilder cmd, @Nonnull BoostAsset boost) {
        List<DropdownEntryInfo> tracks = new ArrayList<>();
        tracks.add(new DropdownEntryInfo(LocalizableString.fromMessageId(BoostText.PREFIX + "give.track.all"),
                ALL_TRACKS));
        for (TrackAsset track : liftedTracks(boost)) {
            String key = track.titleKey();
            tracks.add(new DropdownEntryInfo(key != null ? LocalizableString.fromMessageId(ContentKeys.resolved(key))
                    : LocalizableString.fromString(track.getId()), track.getId()));
        }
        cmd.set(TRACK + ".Entries", tracks);
        cmd.set(TRACK + ".Value", ALL_TRACKS);
        cmd.set(OWN + ".TextSpans", BoostText.line(boost.scope() == BoostScope.SERVER ? "give.own.server"
                : "give.own.personal", BoostText.rewardLine(boost, boost.percent(), boost.seconds())));
    }

    /** The page's own bindings: close, Back, a boost picked, and the two buttons with every control's live value. */
    private static void bind(@Nonnull UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, ZigFrame.CLOSE, EventData.of(ACTION, A_CLOSE));
        events.addEventBinding(CustomUIEventBindingType.Activating, BACK, EventData.of(ACTION, A_BACK));
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, BOOST,
                EventData.of(ACTION, A_BOOST).append(K_BOOST, BOOST + ".Value"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, GIVE, values(A_GIVE).append(K_PLAYER,
                PLAYER + ".Value"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, SERVER, values(A_SERVER), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, GRANT, EventData.of(ACTION, A_GRANT)
                .append(K_NODE, NODE + ".Value").append(K_PLAYER, PLAYER + ".Value"), false);
    }

    /**
     * The node dropdown: every node {@link #grantable} lists, by node; with none, one empty entry saying so and Grant
     * switched off.
     */
    private static void paintNodes(@Nonnull UICommandBuilder cmd) {
        Map<String, BoostAsset> nodes = grantable();
        List<DropdownEntryInfo> entries = new ArrayList<>();
        for (String node : nodes.keySet()) {
            // TEXT-OK: a permission node is an id the owner grants by its exact spelling, in no language.
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(node), node));
        }
        if (entries.isEmpty()) {
            entries.add(new DropdownEntryInfo(LocalizableString.fromMessageId(BoostText.PREFIX + "give.node.none"), ""));
        }
        cmd.set(NODE + ".Entries", entries);
        cmd.set(NODE + ".Value", nodes.isEmpty() ? "" : nodes.keySet().iterator().next());
        cmd.set(GRANT + ".Disabled", nodes.isEmpty());
    }

    /**
     * Every permission node a player may be granted here, to the first boost (by id) that asks for it: each switched-on
     * reusable boost whose {@code Reusable.Requires} names a node in its top-level {@code Permission} leaf, that the
     * game shows on this server (the plain feature and mod conditions of that block on, as the Boost Templates form
     * reads them), and whose node is one exact node ({@link #grantableNode}). Keyed by the node exactly as the boost
     * spells it, so a lookup matches that spelling alone; listed in node order without regard to case.
     */
    @Nonnull
    static Map<String, BoostAsset> grantable() {
        List<BoostAsset> boosts = new ArrayList<>(BoostConfig.getInstance().all().values());
        boosts.removeIf(boost -> boost == null);
        boosts.sort(Comparator.comparing(BoostAsset::getId, String.CASE_INSENSITIVE_ORDER));
        // Keyed by the node exactly as the boost spells it (the engine matches a node exactly), listed without regard to
        // case: a node typed in another case is no node a boost names, and two nodes differing in case stay two.
        Comparator<String> order = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());
        Map<String, BoostAsset> out = new TreeMap<>(order);
        for (BoostAsset boost : boosts) {
            GateSpec requires = boost.reusableRequires();
            String node = requires == null || requires.getPermission() == null ? null : requires.getPermission().trim();
            if (!boost.isEnabled() || !boost.isReusable() || node == null || !grantableNode(node)) {
                continue;
            }
            try {
                if (!FeatureLift.allOn(FeatureLift.liftKnown(requires).lifted())) {
                    continue;
                }
            } catch (Throwable t) {
                continue;
            }
            out.putIfAbsent(node, boost);
        }
        return out;
    }

    /**
     * May {@code node} be granted from this screen: one valid, exact node, never a wildcard ({@code *}, which would
     * grant more than the boost asks for) or a denial ({@code -node})?
     */
    static boolean grantableNode(@Nullable String node) {
        return node != null && !node.isBlank() && !node.contains("*") && !node.startsWith("-")
                && PermissionValidation.isValidPermissionNode(node);
    }

    /** {@code action} with the boost, track, percent and length controls' live values. */
    @Nonnull
    private static EventData values(@Nonnull String action) {
        return EventData.of(ACTION, action).append(K_BOOST, BOOST + ".Value").append(K_TRACK, TRACK + ".Value")
                .append(K_PERCENT, PERCENT + ".Value").append(K_MINUTES, MINUTES + ".Value");
    }

    /** Every boost this screen offers: switched on, timed, not standing and not reusable, by id. */
    @Nonnull
    static List<BoostAsset> offered() {
        List<BoostAsset> out = new ArrayList<>();
        for (BoostAsset boost : BoostConfig.getInstance().all().values()) {
            if (boost != null && boost.isEnabled() && !boost.isStanding() && !boost.isReusable()
                    && boost.seconds() > 0L) {
                out.add(boost);
            }
        }
        out.sort(Comparator.comparing(BoostAsset::getId, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** The offered boost {@code id} names (any case), or null. */
    @Nullable
    private static BoostAsset offeredById(@Nonnull List<BoostAsset> offered, @Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (BoostAsset boost : offered) {
            if (boost.getId().equalsIgnoreCase(id.trim())) {
                return boost;
            }
        }
        return null;
    }

    /** The boost {@code id} names among {@code offered}, else the first offered, else null. */
    @Nullable
    private static BoostAsset pick(@Nonnull List<BoostAsset> offered, @Nullable String id) {
        BoostAsset named = offeredById(offered, id);
        return named != null ? named : offered.isEmpty() ? null : offered.get(0);
    }

    /** Every switched-on track {@code boost} lifts that a gain lands on (no derived track), in the tracks' order. */
    @Nonnull
    static List<TrackAsset> liftedTracks(@Nonnull BoostAsset boost) {
        List<TrackAsset> out = new ArrayList<>();
        for (TrackAsset track : TrackConfig.getInstance().liveTracks()) {
            if (!track.isDerived() && boost.lifts(track.getId(), track.category())) {
                out.add(track);
            }
        }
        return out;
    }

    /** A boost on the dropdown: its own title, else its id. */
    @Nonnull
    private static LocalizableString nameOf(@Nonnull BoostAsset boost) {
        String key = boost.titleKey();
        return key != null ? LocalizableString.fromMessageId(ContentKeys.resolved(key))
                : LocalizableString.fromString(boost.getId());
    }

    /** Show {@code line} on the status line (red for an error); true, the answer sent. */
    private boolean say(@Nonnull Message line, boolean error) {
        UICommandBuilder cmd = new UICommandBuilder();
        SettingsUiUtil.setStatus(cmd, STATUS, line, error);
        this.sendUpdate(cmd, new UIEventBuilder(), false);
        return true;
    }
}
