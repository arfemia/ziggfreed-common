package com.ziggfreed.common.ui.hud;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * What one player has said about their own HUD, persisted by the library so every mod drawing a
 * shared overlay reads the same answer: for each panel, the spot they picked for it and whether
 * they hid it, plus one switch hiding every panel at once. Nothing here knows what a panel shows;
 * a panel is an id, a spot is an id, and the mod drawing the panel is the one that reads them.
 *
 * <p><b>Why the library holds it.</b> A HUD placement is generic presentation: no product
 * vocabulary, no skill, no XP, no level, just where a thing sits on a screen and whether it shows.
 * A preference a player sets against a shared panel has to survive whichever consumer mods are
 * installed, and a consumer that stored it would leave every other consumer's panel deaf to it.
 * So it sits beside the panels themselves, the way {@code ZigFlairComponent} keeps unlocked flair
 * for whoever renders it.
 *
 * <p><b>Three orthogonal knobs, never a mode.</b> A placement pick and a hide are independent per
 * panel, and the hide-all switch is a third bit over both: a hidden panel keeps its pick, and
 * switching hide-all off shows every panel the player did not hide on its own.
 *
 * <p>Ids are lower-cased at write time so a pick and a lookup can never miss each other on case.
 * The persisted format packs the picks as {@code panel=spot|panel=spot} and the hidden set as
 * {@code panel|panel} with no escaping, so an id carrying {@code '|'} or {@code '='} is REFUSED at
 * the write path ({@link #usesReservedDelimiter}) rather than corrupting every entry after it.
 *
 * <p>{@link #register} is called once from the library's setup, before any world loads, and
 * {@link #install} hangs the connect hook that attaches one to every player. Every read and write
 * site guards on {@code TYPE != null} and treats a missing component as "no preference".
 */
public class HudPreferenceComponent implements Component<EntityStore> {

    /** The engine registry id; the persisted save key for this component. */
    public static final String REGISTRY_ID = "ZiggfreedCommon:HudPreferences";

    /** The registered type, or null until {@link #register} runs. */
    @Nullable
    public static ComponentType<EntityStore, HudPreferenceComponent> TYPE;

    private static final char ENTRY_SEPARATOR = '|';
    private static final char PAIR_SEPARATOR = '=';

    /** Lower-cased panel id to the lower-cased spot the player picked for it. */
    public Map<String, String> placements;

    /** Lower-cased ids of the panels the player hid for themselves. */
    public Set<String> hidden;

    /** Whether every panel is hidden for this player, whatever the per-panel set says. */
    public volatile boolean hideAll;

    public static final BuilderCodec<HudPreferenceComponent> CODEC;

    static {
        var builder = BuilderCodec.builder(HudPreferenceComponent.class, HudPreferenceComponent::new);
        builder.append(new KeyedCodec<>("Placements", Codec.STRING),
                (c, v, info) -> c.placements = deserializePlacements(v),
                (c, info) -> serializePlacements(c.placements)).add();
        builder.append(new KeyedCodec<>("Hidden", Codec.STRING),
                (c, v, info) -> c.hidden = deserializeSet(v),
                (c, info) -> serializeSet(c.hidden)).add();
        builder.append(new KeyedCodec<>("HideAll", Codec.BOOLEAN),
                (c, v, info) -> c.hideAll = Boolean.TRUE.equals(v),
                (c, info) -> c.hideAll).add();
        CODEC = builder.build();
    }

    public HudPreferenceComponent() {
        this.placements = new ConcurrentHashMap<>();
        this.hidden = ConcurrentHashMap.newKeySet();
    }

    /**
     * True when {@code id} carries a character the persisted format reserves ({@code '|'} between
     * entries, {@code '='} between a panel and its spot), or is nothing at all.
     */
    public static boolean usesReservedDelimiter(@Nullable String id) {
        return id == null || id.isBlank() || id.indexOf(ENTRY_SEPARATOR) >= 0 || id.indexOf(PAIR_SEPARATOR) >= 0;
    }

    @Nonnull
    static String serializePlacements(@Nullable Map<String, String> picks) {
        if (picks == null || picks.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> pick : new TreeMap<>(picks).entrySet()) {
            if (usesReservedDelimiter(pick.getKey()) || usesReservedDelimiter(pick.getValue())) {
                // A write is already refused, so this only catches a direct edit of the map; the
                // entry is dropped LOUDLY rather than corrupting every one packed after it.
                SafeLog.warn("[hud] the placement pick '" + pick.getKey() + "=" + pick.getValue()
                        + "' was NOT saved: an id carries '|' or '=', which the save format reserves");
                continue;
            }
            if (out.length() > 0) {
                out.append(ENTRY_SEPARATOR);
            }
            out.append(pick.getKey()).append(PAIR_SEPARATOR).append(pick.getValue());
        }
        return out.toString();
    }

    @Nonnull
    static Map<String, String> deserializePlacements(@Nullable String packed) {
        Map<String, String> picks = new ConcurrentHashMap<>();
        if (packed == null || packed.isEmpty()) {
            return picks;
        }
        for (String entry : packed.split("\\" + ENTRY_SEPARATOR)) {
            int at = entry.indexOf(PAIR_SEPARATOR);
            if (at <= 0 || at == entry.length() - 1) {
                continue;
            }
            picks.put(entry.substring(0, at), entry.substring(at + 1));
        }
        return picks;
    }

    @Nonnull
    static String serializeSet(@Nullable Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String id : new TreeSet<>(ids)) {
            if (usesReservedDelimiter(id)) {
                SafeLog.warn("[hud] the hidden panel id '" + id + "' was NOT saved: it carries '|' or "
                        + "'=', which the save format reserves");
                continue;
            }
            if (out.length() > 0) {
                out.append(ENTRY_SEPARATOR);
            }
            out.append(id);
        }
        return out.toString();
    }

    @Nonnull
    static Set<String> deserializeSet(@Nullable String packed) {
        Set<String> ids = ConcurrentHashMap.newKeySet();
        if (packed == null || packed.isEmpty()) {
            return ids;
        }
        for (String id : packed.split("\\" + ENTRY_SEPARATOR)) {
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Register the component type with the entity-store registry. Called once at library setup,
     * BEFORE any world loads. Never throws: a failure logs and leaves {@link #TYPE} unset, and
     * every consumer guards on that.
     */
    @Nullable
    public static ComponentType<EntityStore, HudPreferenceComponent> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            TYPE = registry.registerComponent(HudPreferenceComponent.class, REGISTRY_ID, CODEC);
            return TYPE;
        } catch (Throwable t) {
            SafeLog.warn("[hud] could not register HudPreferenceComponent", t);
            return null;
        }
    }

    /** Hang the connect hook that attaches one of these to every player. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().register(PlayerConnectEvent.class, HudPreferenceComponent::onPlayerConnect);
    }

    private static void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        try {
            if (TYPE == null) {
                return;
            }
            event.getHolder().ensureAndGetComponent(TYPE);
        } catch (Throwable t) {
            SafeLog.warn("[hud] could not ensure the HUD preference component", t);
        }
    }

    /** The spot the player picked for {@code panelId} (any case), or null for the server's own. */
    @Nullable
    public String placementOf(@Nullable String panelId) {
        return panelId == null ? null : placements.get(panelId.toLowerCase(Locale.ROOT));
    }

    /**
     * Pick {@code placementId} for {@code panelId}, or clear the pick with null. True when
     * something changed; an id the save format cannot hold is refused with one warning.
     */
    public boolean setPlacement(@Nullable String panelId, @Nullable String placementId) {
        if (usesReservedDelimiter(panelId)) {
            return false;
        }
        String panel = panelId.toLowerCase(Locale.ROOT);
        if (placementId == null || placementId.isBlank()) {
            return placements.remove(panel) != null;
        }
        if (usesReservedDelimiter(placementId)) {
            SafeLog.warn("[hud] the placement id '" + placementId + "' is REFUSED: it carries '|' or '=',"
                    + " which the per-player save format reserves. Rename the placement.");
            return false;
        }
        String spot = placementId.toLowerCase(Locale.ROOT);
        return !spot.equals(placements.put(panel, spot));
    }

    /** Whether {@code panelId} is hidden for this player: by its own switch, or by hide-all. */
    public boolean isHidden(@Nullable String panelId) {
        return hideAll || (panelId != null && hidden.contains(panelId.toLowerCase(Locale.ROOT)));
    }

    /** Whether the player hid {@code panelId} on its own, apart from hide-all. */
    public boolean isHiddenAlone(@Nullable String panelId) {
        return panelId != null && hidden.contains(panelId.toLowerCase(Locale.ROOT));
    }

    /** Hide or show {@code panelId} for this player. True when something changed. */
    public boolean setHidden(@Nullable String panelId, boolean hide) {
        if (usesReservedDelimiter(panelId)) {
            return false;
        }
        String panel = panelId.toLowerCase(Locale.ROOT);
        return hide ? hidden.add(panel) : hidden.remove(panel);
    }

    /** Whether every panel is hidden for this player. */
    public boolean hideAll() {
        return hideAll;
    }

    /** Hide or show every panel at once. True when the switch moved. */
    public boolean setHideAll(boolean hide) {
        if (hideAll == hide) {
            return false;
        }
        hideAll = hide;
        return true;
    }

    @Override
    @SuppressWarnings("CloneDeclaresCloneNotSupported")
    public HudPreferenceComponent clone() {
        HudPreferenceComponent c = new HudPreferenceComponent();
        c.placements.putAll(this.placements);
        c.hidden.addAll(this.hidden);
        c.hideAll = this.hideAll;
        return c;
    }
}
