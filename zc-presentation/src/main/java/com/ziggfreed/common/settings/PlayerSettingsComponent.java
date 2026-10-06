package com.ziggfreed.common.settings;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * What one player chose for their own screen, kept by the library so every mod reads the same answer: per
 * surface (a HUD panel id or {@link PlayerSettings#QUEST_TRACKER}) the spot they picked and whether they
 * show it, one switch hiding every bar panel at once, and the level of their quest and achievement
 * notices. Every choice is nullable: null means "the owner's default", which {@link PlayerSettings} folds
 * in, so an owner default of hidden still lets a player turn a surface on.
 *
 * <p><b>One record under 2.1.0's save key.</b> It replaces {@code HudPreferenceComponent} and keeps its
 * registry id {@value #REGISTRY_ID}: the engine matches a saved component by that string, so every ECS save
 * and every consumer database blob loads into this class unchanged. It READS 2.1.0's flat
 * {@code Placements}, {@code Hidden} and {@code HideAll} and writes only {@code Hud} and
 * {@code Notifications}, because a getter answering null leaves its key out of the save: the first save
 * after the upgrade is the move. A document never carries both shapes.
 *
 * <p>Ids are folded lower-case at write time. {@link #register} runs once from the library's setup,
 * before any world loads, and {@link #install} attaches one to every player at connect. Every reader
 * guards on {@code TYPE != null}.
 */
public class PlayerSettingsComponent implements Component<EntityStore> {

    /** The engine registry id and save key, kept from 2.1.0. */
    public static final String REGISTRY_ID = "ZiggfreedCommon:HudPreferences";

    /** The registered type, or null until {@link #register} runs. */
    @Nullable
    public static ComponentType<EntityStore, PlayerSettingsComponent> TYPE;

    private final Map<String, String> spots = new ConcurrentHashMap<>();
    private final Map<String, Boolean> shown = new ConcurrentHashMap<>();
    private volatile boolean hideAll;
    @Nullable private volatile NotificationLevel level;

    /** The {@code Hud} group as saved. */
    static final class HudGroup {

        private static final Codec<Map<String, String>> SPOTS =
                new MapCodec<String, HashMap<String, String>>(Codec.STRING, HashMap::new, false);
        private static final Codec<Map<String, Boolean>> SHOWN =
                new MapCodec<Boolean, HashMap<String, Boolean>>(Codec.BOOLEAN, HashMap::new, false);

        @Nullable Map<String, String> spots;
        @Nullable Map<String, Boolean> shown;
        @Nullable Boolean hideAll;

        static final BuilderCodec<HudGroup> CODEC = BuilderCodec.builder(HudGroup.class, HudGroup::new)
                .append(new KeyedCodec<>("Spots", SPOTS), (g, v, info) -> g.spots = v, (g, info) -> g.spots)
                .add()
                .append(new KeyedCodec<>("Shown", SHOWN), (g, v, info) -> g.shown = v, (g, info) -> g.shown)
                .add()
                .append(new KeyedCodec<>("HideAll", Codec.BOOLEAN),
                        (g, v, info) -> g.hideAll = v, (g, info) -> g.hideAll)
                .add()
                .build();
    }

    /** The {@code Notifications} group as saved. */
    static final class NotificationsGroup {

        @Nullable String level;

        static final BuilderCodec<NotificationsGroup> CODEC = BuilderCodec
                .builder(NotificationsGroup.class, NotificationsGroup::new)
                .append(new KeyedCodec<>("Level", Codec.STRING), (g, v, info) -> g.level = v, (g, info) -> g.level)
                .add()
                .build();
    }

    public static final BuilderCodec<PlayerSettingsComponent> CODEC;

    static {
        var builder = BuilderCodec.builder(PlayerSettingsComponent.class, PlayerSettingsComponent::new);
        builder.append(new KeyedCodec<>("Hud", HudGroup.CODEC),
                (c, g, info) -> c.readHud(g), (c, info) -> c.hudGroup()).add();
        builder.append(new KeyedCodec<>("Notifications", NotificationsGroup.CODEC),
                (c, g, info) -> c.readNotifications(g), (c, info) -> c.notificationsGroup()).add();
        // 2.1.0's three flat leaves: READ so an old save keeps its picks and hides, never WRITTEN (a
        // getter answering null leaves the key out), so the first save after the upgrade is the move.
        builder.append(new KeyedCodec<>("Placements", Codec.STRING),
                (c, v, info) -> c.readLegacyPlacements(v), (c, info) -> null).add();
        builder.append(new KeyedCodec<>("Hidden", Codec.STRING),
                (c, v, info) -> c.readLegacyHidden(v), (c, info) -> null).add();
        builder.append(new KeyedCodec<>("HideAll", Codec.BOOLEAN),
                (c, v, info) -> c.readLegacyHideAll(v), (c, info) -> null).add();
        CODEC = builder.build();
    }

    public PlayerSettingsComponent() {
    }

    // ==================== registration ====================

    /**
     * Register the component type. Called once at library setup, BEFORE any world loads. Never throws: a
     * failure logs and leaves {@link #TYPE} unset, and every reader guards on that.
     */
    @Nullable
    public static ComponentType<EntityStore, PlayerSettingsComponent> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            TYPE = registry.registerComponent(PlayerSettingsComponent.class, REGISTRY_ID, CODEC);
            return TYPE;
        } catch (Throwable t) {
            SafeLog.warn("[settings] could not register PlayerSettingsComponent", t);
            return null;
        }
    }

    /** Hang the connect hook that attaches one of these to every player. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().register(PlayerConnectEvent.class, PlayerSettingsComponent::onPlayerConnect);
    }

    private static void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        try {
            if (TYPE == null) {
                return;
            }
            event.getHolder().ensureAndGetComponent(TYPE);
        } catch (Throwable t) {
            SafeLog.warn("[settings] could not ensure the player settings component", t);
        }
    }

    // ==================== reads and writes ====================

    /** The spot the player picked for {@code surface} (any case), lower-cased, or null for none. */
    @Nullable
    public String spot(@Nullable String surface) {
        return blank(surface) ? null : spots.get(fold(surface));
    }

    /** Pick {@code spotId} for {@code surface}, or clear the pick with null or a blank. True when something changed. */
    public boolean setSpot(@Nullable String surface, @Nullable String spotId) {
        if (blank(surface)) {
            return false;
        }
        String key = fold(surface);
        if (blank(spotId)) {
            return spots.remove(key) != null;
        }
        String value = fold(spotId);
        return !value.equals(spots.put(key, value));
    }

    /** Whether the player chose to show {@code surface}: true, false, or null when they never chose. */
    @Nullable
    public Boolean shown(@Nullable String surface) {
        return blank(surface) ? null : shown.get(fold(surface));
    }

    /** Choose to show or hide {@code surface}, or clear the choice with null. True when something changed. */
    public boolean setShown(@Nullable String surface, @Nullable Boolean value) {
        if (blank(surface)) {
            return false;
        }
        String key = fold(surface);
        if (value == null) {
            return shown.remove(key) != null;
        }
        return !value.equals(shown.put(key, value));
    }

    /** Whether every bar panel is hidden for this player. */
    public boolean hideAll() {
        return hideAll;
    }

    /** Hide or show every bar panel at once. True when the switch moved. */
    public boolean setHideAll(boolean hide) {
        if (hideAll == hide) {
            return false;
        }
        hideAll = hide;
        return true;
    }

    /** The level the player chose, or null when they never chose. */
    @Nullable
    public NotificationLevel level() {
        return level;
    }

    /** Choose a level, or clear the choice with null. True when something changed. */
    public boolean setLevel(@Nullable NotificationLevel value) {
        if (level == value) {
            return false;
        }
        level = value;
        return true;
    }

    // ==================== the save ====================

    private void readHud(@Nullable HudGroup group) {
        if (group == null) {
            return;
        }
        spots.clear();
        shown.clear();
        if (group.spots != null) {
            for (Map.Entry<String, String> pick : group.spots.entrySet()) {
                setSpot(pick.getKey(), pick.getValue());
            }
        }
        if (group.shown != null) {
            for (Map.Entry<String, Boolean> choice : group.shown.entrySet()) {
                setShown(choice.getKey(), choice.getValue());
            }
        }
        hideAll = Boolean.TRUE.equals(group.hideAll);
    }

    @Nonnull
    private HudGroup hudGroup() {
        HudGroup group = new HudGroup();
        group.spots = new HashMap<>(spots);
        group.shown = new HashMap<>(shown);
        group.hideAll = hideAll;
        return group;
    }

    private void readNotifications(@Nullable NotificationsGroup group) {
        if (group != null) {
            level = NotificationLevel.parse(group.level);
        }
    }

    @Nonnull
    private NotificationsGroup notificationsGroup() {
        NotificationsGroup group = new NotificationsGroup();
        NotificationLevel chosen = level;
        group.level = chosen == null ? null : chosen.id();
        return group;
    }

    /** 2.1.0's {@code panel=spot|panel=spot}; an entry with no panel or no spot is skipped, never half-read. */
    private void readLegacyPlacements(@Nullable String packed) {
        spots.clear();
        for (String entry : split(packed)) {
            int at = entry.indexOf('=');
            if (at > 0 && at < entry.length() - 1) {
                setSpot(entry.substring(0, at), entry.substring(at + 1));
            }
        }
    }

    /** 2.1.0's {@code panel|panel}: each one a choice to hide. */
    private void readLegacyHidden(@Nullable String packed) {
        shown.clear();
        for (String id : split(packed)) {
            setShown(id, Boolean.FALSE);
        }
    }

    private void readLegacyHideAll(@Nullable Boolean value) {
        hideAll = Boolean.TRUE.equals(value);
    }

    @Nonnull
    private static String[] split(@Nullable String packed) {
        return packed == null || packed.isEmpty() ? new String[0] : packed.split("\\|");
    }

    private static boolean blank(@Nullable String id) {
        return id == null || id.isBlank();
    }

    @Nonnull
    private static String fold(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    @SuppressWarnings("CloneDeclaresCloneNotSupported")
    public PlayerSettingsComponent clone() {
        PlayerSettingsComponent copy = new PlayerSettingsComponent();
        copy.spots.putAll(this.spots);
        copy.shown.putAll(this.shown);
        copy.hideAll = this.hideAll;
        copy.level = this.level;
        return copy;
    }
}
