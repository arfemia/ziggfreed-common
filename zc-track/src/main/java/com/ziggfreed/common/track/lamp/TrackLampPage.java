package com.ziggfreed.common.track.lamp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.ZigFrame;
import com.ziggfreed.common.ui.kit.Hero;
import com.ziggfreed.common.ui.kit.HeroPainter;
import com.ziggfreed.common.util.SafeLog;

/**
 * A lamp's picker ({@code Pages/ZigTrackLampPage.ui}), opened by using a lamp ({@code ZigTrackLamp}): one row per pick
 * the lamp asks for, each a dropdown of the tracks it may go to for this player now ({@link TrackLamps#choices}) with
 * what that pick pays there (minutes of play priced at the player's level on that track), and Use, which spends the
 * lamp and pays every pick ({@link TrackLamps#rub}, which reads the choice again and takes the lamp first). Every exit
 * answers the client; a refusal says why on the status line, a use closes the page and says what each pick paid.
 */
public final class TrackLampPage extends InteractiveCustomUIPage<TrackLampPage.Event> {

    /** The page document. */
    public static final String PAGE_TEMPLATE = "Pages/ZigTrackLampPage.ui";

    static final String STATUS = "#TrackLampStatus";
    static final String USE = "#TrackLampUse";
    static final String[] ROWS = {"#TrackLampRow1", "#TrackLampRow2", "#TrackLampRow3", "#TrackLampRow4",
        "#TrackLampRow5"};
    static final String[] PICKS = {"#TrackLampPick1", "#TrackLampPick2", "#TrackLampPick3", "#TrackLampPick4",
        "#TrackLampPick5"};
    static final String[] PAYS = {"#TrackLampPay1", "#TrackLampPay2", "#TrackLampPay3", "#TrackLampPay4",
        "#TrackLampPay5"};

    static final String ACTION = "Action";
    static final String A_CLOSE = "close";
    static final String A_PICK = "pick";
    static final String A_USE = "use";
    static final String[] K_PICKS = {"@Pick1", "@Pick2", "@Pick3", "@Pick4", "@Pick5"};

    /** What the page's events round-trip: which button, and each pick's live value. */
    public static final class Event {

        String action;
        final String[] picks = new String[TrackLamp.MAX_PICKS];

        public static final BuilderCodec<Event> CODEC = BuilderCodec.builder(Event.class, Event::new)
                .append(new KeyedCodec<>(ACTION, Codec.STRING),
                        (e, v, info) -> e.action = v, (e, info) -> e.action).add()
                .append(new KeyedCodec<>(K_PICKS[0], Codec.STRING),
                        (e, v, info) -> e.picks[0] = v, (e, info) -> e.picks[0]).add()
                .append(new KeyedCodec<>(K_PICKS[1], Codec.STRING),
                        (e, v, info) -> e.picks[1] = v, (e, info) -> e.picks[1]).add()
                .append(new KeyedCodec<>(K_PICKS[2], Codec.STRING),
                        (e, v, info) -> e.picks[2] = v, (e, info) -> e.picks[2]).add()
                .append(new KeyedCodec<>(K_PICKS[3], Codec.STRING),
                        (e, v, info) -> e.picks[3] = v, (e, info) -> e.picks[3]).add()
                .append(new KeyedCodec<>(K_PICKS[4], Codec.STRING),
                        (e, v, info) -> e.picks[4] = v, (e, info) -> e.picks[4]).add()
                .build();
    }

    @Nonnull private final TrackLamp lamp;

    private TrackLampPage(@Nonnull PlayerRef playerRef, @Nonnull TrackLamp lamp) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Event.CODEC);
        this.lamp = lamp;
    }

    /** Open the picker of {@code lamp} for the player at {@code ref}; false when it could not open. World thread. */
    public static boolean open(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull TrackLamp lamp) {
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = player == null ? null : PlayerAccess.playerRef(player);
        if (playerRef == null) {
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store, new TrackLampPage(playerRef, lamp));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[track] a lamp's picker failed to open", t);
            return false;
        }
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        cmd.append(PAGE_TEMPLATE);
        bind(events);
        try {
            int picks = lamp.picks();
            HeroPainter.paint(cmd, new Hero(null, TrackText.line("lamp.title"), picks > 1
                    ? TrackText.line("lamp.line.many", lamp.priced().minutes(), picks)
                    : TrackText.line("lamp.line.one", lamp.priced().minutes()), null));
            paintChoices(cmd, choices(store, ref), null);
        } catch (Throwable t) {
            SafeLog.warn("[track] a lamp's picker could not paint everything", t);
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

    /** Closes or updates for one event; false when it sent nothing. */
    private boolean route(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player,
            @Nonnull Event data) {
        String action = data.action == null ? "" : data.action;
        if (A_CLOSE.equals(action)) {
            player.getPageManager().setPage(ref, store, Page.None);
            return true;
        }
        List<TrackLamps.Choice> choices = choices(store, ref);
        if (A_PICK.equals(action)) {
            UICommandBuilder cmd = new UICommandBuilder();
            paintPays(cmd, choices, data.picks);
            this.sendUpdate(cmd, new UIEventBuilder(), false);
            return true;
        }
        if (!A_USE.equals(action)) {
            return false;
        }
        int required = TrackLamps.required(lamp, choices.size());
        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < required; i++) {
            chosen.add(data.picks[i] == null ? "" : data.picks[i]);
        }
        TrackLamps.Rub rub = TrackLamps.rub(store, ref, this.playerRef.getUuid(), lamp, chosen);
        if (rub.outcome() == TrackLamps.Outcome.PAID) {
            player.getPageManager().setPage(ref, store, Page.None);
            for (Map.Entry<TrackDef, Long> paid : rub.paid().entrySet()) {
                this.playerRef.sendMessage(TrackText.line("lamp.paid", paid.getValue(),
                        TrackText.name(paid.getKey().asset())));
            }
            return true;
        }
        UICommandBuilder cmd = new UICommandBuilder();
        if (rub.outcome() == TrackLamps.Outcome.NOT_CHOOSABLE) {
            paintChoices(cmd, choices, null);
        }
        SettingsUiUtil.setStatus(cmd, STATUS, refusal(rub.outcome()), true);
        this.sendUpdate(cmd, new UIEventBuilder(), false);
        return true;
    }

    /** What a refused use says. */
    @Nonnull
    static Message refusal(@Nonnull TrackLamps.Outcome outcome) {
        return switch (outcome) {
            case NO_LAMP -> TrackText.line("lamp.refused.gone");
            case WRONG_COUNT -> TrackText.line("lamp.refused.same");
            case NOT_CHOOSABLE -> TrackText.line("lamp.refused.changed");
            default -> TrackText.line("lamp.refused.none");
        };
    }

    @Nonnull
    private List<TrackLamps.Choice> choices(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        UUID player = this.playerRef.getUuid();
        return player == null ? List.of() : TrackLamps.choices(store, ref, player, lamp);
    }

    /**
     * The rows: one per pick the lamp asks for (never more than there are choices), each offering every choice and
     * starting on a different one, the rest hidden; with no choice at all, the line saying so and Use off.
     */
    private void paintChoices(@Nonnull UICommandBuilder cmd, @Nonnull List<TrackLamps.Choice> choices,
            @Nullable String[] picked) {
        int rows = TrackLamps.required(lamp, choices.size());
        List<DropdownEntryInfo> entries = new ArrayList<>();
        for (TrackLamps.Choice choice : choices) {
            String key = choice.def().asset().titleKey();
            entries.add(new DropdownEntryInfo(key != null ? LocalizableString.fromMessageId(ContentKeys.resolved(key))
                    : LocalizableString.fromString(choice.def().asset().getId()), choice.def().id()));
        }
        String[] values = new String[TrackLamp.MAX_PICKS];
        for (int i = 0; i < TrackLamp.MAX_PICKS; i++) {
            boolean shown = i < rows;
            cmd.set(ROWS[i] + ".Visible", shown);
            if (shown) {
                values[i] = picked != null && picked[i] != null ? picked[i] : choices.get(i).def().id();
                cmd.set(PICKS[i] + ".Entries", entries);
                cmd.set(PICKS[i] + ".Value", values[i]);
            }
        }
        cmd.set(USE + ".Disabled", rows == 0);
        SettingsUiUtil.setStatus(cmd, STATUS, rows == 0 ? TrackText.line("lamp.refused.none") : null, rows == 0);
        paintPays(cmd, choices, values);
    }

    /** What each shown pick pays now, beside it. */
    private void paintPays(@Nonnull UICommandBuilder cmd, @Nonnull List<TrackLamps.Choice> choices,
            @Nonnull String[] picked) {
        int rows = TrackLamps.required(lamp, choices.size());
        for (int i = 0; i < rows; i++) {
            TrackLamps.Choice choice = find(choices, picked[i]);
            cmd.set(PAYS[i] + ".TextSpans", choice == null ? Msg.raw("")
                    : TrackText.line("lamp.pays", choice.pays()));
        }
    }

    @Nullable
    private static TrackLamps.Choice find(@Nonnull List<TrackLamps.Choice> choices, @Nullable String id) {
        if (id == null) {
            return null;
        }
        for (TrackLamps.Choice choice : choices) {
            if (choice.def().id().equalsIgnoreCase(id.trim())) {
                return choice;
            }
        }
        return null;
    }

    /** The page's bindings: close, each pick changed, and Use with every pick's live value. */
    private static void bind(@Nonnull UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, ZigFrame.CLOSE, EventData.of(ACTION, A_CLOSE));
        for (String pick : PICKS) {
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, pick, values(A_PICK), false);
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, USE, values(A_USE), false);
    }

    @Nonnull
    private static EventData values(@Nonnull String action) {
        EventData data = EventData.of(ACTION, action);
        for (int i = 0; i < TrackLamp.MAX_PICKS; i++) {
            data = data.append(K_PICKS[i], PICKS[i] + ".Value");
        }
        return data;
    }
}
