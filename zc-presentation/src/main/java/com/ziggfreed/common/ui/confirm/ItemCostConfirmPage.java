package com.ziggfreed.common.ui.confirm;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cast.ModelParticleService;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.i18n.NativeNames;
import com.ziggfreed.common.sound.Sound3D;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.icon.IconRenderer;
import com.ziggfreed.common.ui.kit.KitText;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * The library's item-cost confirm page: before anything that spends a player's items (a dungeon's way in, a summon, a
 * shrine), the page shows what it is and what it takes, and asks. Built as a copy of the game's own portal summon
 * screen, the one a Fragment Key opens at a portal device (the client's {@code Portals/PortalSummonPanel.xaml}), in the
 * library's words: the title, a rule, the description, then each item it takes in a slot with its picture, its own
 * name and how many the player holds over how many it needs (green when they hold enough, red when not), a line
 * saying what the button uses from their bag, and the button beside Close. While the player holds too few the button
 * is off and the caller's hint shows under it (where the items are found).
 *
 * <p><b>What it shows comes in, what it does goes out.</b> The caller hands over an {@link ItemCostConfirm} (plain
 * values: the caller counts what the player holds) and an {@link OnConfirm}, run on the player's world thread when
 * they press the button. The callback spends and acts, answering whether it went through; then the page plays the
 * caller's sound and particle where the player stands and closes. One that did not go through leaves the page open,
 * where the caller's own refusal line draws as a toast ({@link ToastablePage}). Closing spends nothing.
 *
 * <p>Its documents are {@code Pages/ZigItemCostConfirmPage.ui} and its slot, {@code Pages/ZigItemCostRow.ui}; its
 * words are {@code ziggfreedcommon.ui.lang}'s {@code confirm.*} and the kit's count. Every exit of
 * {@link #handleDataEvent} answers the client.
 */
public final class ItemCostConfirmPage extends ToastablePage<ItemCostConfirmEventData> {

    /** The key family the page's own words resolve under. */
    static final String PREFIX = "ziggfreedcommon.ui.confirm.";

    /** How long the confirm particle may play, in seconds, so a system that never ends on its own stops anyway. */
    static final float PARTICLE_SECONDS = 3f;

    private static final String PAGE_TEMPLATE = "Pages/ZigItemCostConfirmPage.ui";
    private static final String ROW_TEMPLATE = "Pages/ZigItemCostRow.ui";
    private static final String CONFIRM = "confirm";
    private static final String CLOSE = "close";
    private static final String LOG = "[confirm] ";

    /** What pressing the button does, on the player's world thread; answers whether it went through. */
    @FunctionalInterface
    public interface OnConfirm {

        boolean confirmed(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                @Nonnull PlayerRef player);
    }

    private final ItemCostConfirm spec;
    private final OnConfirm onConfirm;

    public ItemCostConfirmPage(@Nonnull PlayerRef playerRef, @Nonnull ItemCostConfirm spec,
            @Nonnull OnConfirm onConfirm) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ItemCostConfirmEventData.CODEC);
        this.spec = spec;
        this.onConfirm = onConfirm;
    }

    /**
     * Open the page for the player at {@code ref}, replacing whatever page they have open (the conversation that
     * asked), on their world thread from a world task, never from inside a running system. Answers false when the
     * entity is no player.
     */
    public static boolean open(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef player, @Nonnull ItemCostConfirm spec, @Nonnull OnConfirm onConfirm) {
        Player playerComponent = store.getComponent(ref, Player.getComponentType());
        if (playerComponent == null) {
            return false;
        }
        playerComponent.getPageManager().openCustomPage(ref, store, new ItemCostConfirmPage(player, spec, onConfirm));
        return true;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        cmd.append(PAGE_TEMPLATE);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", CLOSE));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseAction", EventData.of("Action", CLOSE));
        try {
            paint(cmd, events);
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the item-cost confirm page could not paint everything", t);
        }
        renderToastInto(cmd);
    }

    private void paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        cmd.set("#Title.TextSpans", spec.title());
        Message description = spec.description();
        if (description != null) {
            cmd.set("#Description.TextSpans", description);
        } else {
            cmd.set("#Description.Visible", false);
        }

        List<ItemCostConfirm.Row> rows = spec.rows();
        for (int i = 0; i < rows.size(); i++) {
            paintRow(cmd, i, rows.get(i));
        }
        cmd.set("#Uses.TextSpans", rows.size() == 1
                ? Msg.key(PREFIX + "uses.one", NativeNames.itemNameMsg(rows.get(0).itemId()), rows.get(0).needed())
                : Msg.key(PREFIX + "uses.many"));

        boolean affordable = spec.affordable();
        Message hint = spec.hint();
        if (!affordable && hint != null) {
            cmd.set("#Hint.Visible", true);
            cmd.set("#Hint.TextSpans", hint);
        }

        ZigRichButton.text(cmd, "#CloseAction", Msg.key(PREFIX + "close"));
        ZigRichButton.text(cmd, "#ConfirmAction", confirmLabel());
        if (affordable) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmAction",
                    EventData.of("Action", CONFIRM));
        } else {
            cmd.set("#ConfirmAction.Disabled", true);
        }
    }

    /** One item's slot: its picture, its own name, and held over needed in green, or in red while short. */
    private static void paintRow(@Nonnull UICommandBuilder cmd, int index, @Nonnull ItemCostConfirm.Row row) {
        cmd.append("#CostList", ROW_TEMPLATE);
        String sel = "#CostList[" + index + "]";
        IconRenderer.applyPlainIcon(cmd, sel + " #Pic", row.itemId(), null);
        cmd.set(sel + " #Name.TextSpans", NativeNames.itemNameMsg(row.itemId()));
        Message count = KitText.count(row.held(), row.needed());
        if (row.isShort()) {
            cmd.set(sel + " #Have.Visible", false);
            cmd.set(sel + " #Short.Visible", true);
            cmd.set(sel + " #Short.TextSpans", count);
        } else {
            cmd.set(sel + " #Have.TextSpans", count);
            cmd.set(sel + " #SlotShort.Visible", false);
            cmd.set(sel + " #SlotReady.Visible", true);
        }
    }

    /** The button's words: the caller's content key, else the library's "Confirm". */
    @Nonnull
    private Message confirmLabel() {
        String key = spec.confirmKey();
        return key == null || key.isBlank() ? Msg.key(PREFIX + "button") : ContentKeys.tr(key.trim());
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull ItemCostConfirmEventData data) {
        String action = data.action == null ? "" : data.action;
        if (!CONFIRM.equals(action)) {
            close(ref, store);
            return;
        }
        if (!spec.affordable()) {
            this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
            return;
        }
        boolean went;
        try {
            went = onConfirm.confirmed(ref, store, this.playerRef);
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the item-cost confirm page's action failed for " + this.playerRef.getUuid(), t);
            went = false;
        }
        if (!went) {
            this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
            return;
        }
        cue(ref, store, spec.sound(), spec.particle());
        close(ref, store);
    }

    /** The caller's sound and particle where the player stands. */
    private static void cue(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nullable String sound,
            @Nullable String particle) {
        if (!ref.isValid()) {
            return;
        }
        TransformComponent at = store.getComponent(ref, TransformComponent.getComponentType());
        if (at == null) {
            return;
        }
        Vector3d position = new Vector3d(at.getPosition());
        Sound3D.play(sound, SoundCategory.SFX, position, store, LOG, false);
        ModelParticleService.spawnAt(store, particle, new Vector3d(position.x, position.y + 1.0, position.z),
                PARTICLE_SECONDS);
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
}
