package com.ziggfreed.common.ui.hud.panel;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.JsonOverrideWriter;

/**
 * The ONE path a server owner's panel settings are written by from inside the game: the HUD
 * settings page's Server tab and {@code /zighud default} both come through here, so the two can
 * never disagree about what a change looks like on disk.
 *
 * <p>Every write lands in {@code mods/ziggfreedcommon/hud-panels.json} in exactly the shape an
 * owner would type by hand (a bare map from panel id to restated leaves, every other entry and
 * comment preserved), is re-read through the same codec the file is always read through, and is
 * pushed to every online player's panels at once. A panel already keyed in the file keeps its key's
 * spelling, so an owner's hand-written {@code "world_bars"} and this writer's {@code World_Bars}
 * never become two entries folding onto one id.
 *
 * <p>Picking a spot CLEARS the inline {@code Position}, {@code Columns}, {@code RowsPerColumn},
 * {@code Gap}, {@code Cutout} and {@code MinHeight} leaves the entry carried: those were nudges over
 * the previous spot, and an offset measured from one corner, or a band counted from one edge, or a
 * cut at one column's end, is nonsense against another. The inline {@code Color} is left alone: a
 * tint is measured from no corner and means the same at the new spot.
 */
public final class HudPanelOwnerWriter {

    private static final String LOG_TAG = "hud";

    /** The leaves a spot pick clears, because they were written against the spot before it. */
    private static final String[] INLINE_LEAVES =
            {"Position", "Columns", "RowsPerColumn", "Gap", "Cutout", "MinHeight"};

    private HudPanelOwnerWriter() {
    }

    /**
     * Point {@code panelId} at the spot {@code placementId}, or back at whatever its shipped file
     * says when null, clearing the inline nudges either way. True when the file was written.
     */
    public static boolean setPlacement(@Nonnull String panelId, @Nullable String placementId) {
        Map<String, Object> leaves = new LinkedHashMap<>();
        leaves.put("Placement", placementId == null || placementId.isBlank() ? null : placementId.trim());
        for (String inline : INLINE_LEAVES) {
            leaves.put(inline, null);
        }
        return setLeaves(panelId, leaves);
    }

    /** Switch {@code panelId} on or off for everyone. True when the file was written. */
    public static boolean setEnabled(@Nonnull String panelId, boolean enabled) {
        Map<String, Object> leaves = new LinkedHashMap<>();
        leaves.put("Enabled", enabled);
        return setLeaves(panelId, leaves);
    }

    /**
     * Write {@code leaves} (dotted PascalCase paths RELATIVE to the panel's entry, e.g.
     * {@code Position.OffsetX}; a null value removes that leaf) under {@code panelId}, then re-read
     * the file and repaint every online panel. True when the file was written.
     */
    public static boolean setLeaves(@Nonnull String panelId, @Nonnull Map<String, Object> leaves) {
        Path file = HudOwnerLayers.panelsFile();
        String key = entryKey(file, panelId);
        Map<String, Object> absolute = new LinkedHashMap<>();
        for (Map.Entry<String, Object> leaf : leaves.entrySet()) {
            absolute.put(key + "." + leaf.getKey(), leaf.getValue());
        }
        boolean written = JsonOverrideWriter.setLeaves(file, absolute);
        if (written) {
            HudOwnerLayers.reloadPanels();
            HudPanels.repaintAllOnline();
        }
        return written;
    }

    /** The key the panel is already filed under in the owner file, in its own spelling, else the panel's id. */
    @Nonnull
    private static String entryKey(@Nonnull Path file, @Nonnull String panelId) {
        JsonObject root = OwnerLayerReader.readObject(LOG_TAG, file);
        if (root != null) {
            for (String existing : root.keySet()) {
                if (existing.trim().equalsIgnoreCase(panelId.trim())) {
                    return existing;
                }
            }
        }
        return panelId;
    }
}
