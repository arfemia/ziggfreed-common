package com.ziggfreed.common.ui.hud.statbar;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * One bar on the player's screen showing how full one stat is: the game draws only its own bars (health, stamina,
 * mana, oxygen, the Signature meter), so a pool a mod adds (Energy, a pack's own) is drawn by a file here. A second
 * bar, for any stat and by any mod, is one more file and no code. The FILE NAME is the bar's id.
 *
 * <pre>{@code
 * // Server/ZiggfreedCommon/StatBars/YourMod/Your_Focus_Bar.json
 * { "Stat": "Focus", "Label": "yourmod.stat.focus", "Color": "#5fb7e8",
 *   "Placement": { "Position": { "Preset": "BottomCenter", "OffsetY": 140 }, "Width": 260 },
 *   "ShowWhen": { "Factors": [ { "Factor": "hytale:stat_percent", "Param": "Focus", "Max": 99.5 } ] } }
 * }</pre>
 *
 * <p>The bar reads the stat live a few times a second, as a cost reads it, and shows its fill and its whole value.
 * {@code ShowWhen} is a {@code Requires} block asked of the player each time (left out, the bar shows whenever its
 * place does). A server owner switches a bar off, moves it or recolours it on the Stat Bars screen of the owner menu,
 * saved in {@code mods/ziggfreedcommon/stat-bars.json} by id.
 */
public final class StatBarAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, StatBarAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/StatBars";

    /** Where bars sharing a place stack, when the file names no order: after every one that did. */
    public static final int DEFAULT_ORDER = 1000;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private String stat;
    @Nullable private String label;
    @Nullable private String color;
    @Nullable private StatBarPlacement placement;
    @Nullable private GateSpec showWhen;
    @Nullable private Integer order;

    public static final AssetBuilderCodec<String, StatBarAsset> CODEC = AssetBuilderCodec.builder(
                    StatBarAsset.class,
                    StatBarAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether the bar is drawn at all, for everyone. Unwritten reads true.")
            .add()
            .appendInherited(new KeyedCodec<>("Stat", Codec.STRING, false),
                    (a, v) -> a.stat = v, a -> a.stat, (a, p) -> a.stat = p.stat)
            .metadata(EditorSchema.assetRef(EntityStatType.class))
            .documentation("The stat the bar shows, by id (an EntityStatType: Energy, Mana, or one a pack ships). "
                    + "The bar fills from the stat's minimum to its maximum as the player carries it. Left out, or "
                    + "naming a stat the server has not loaded, the bar is not drawn.")
            .add()
            .appendInherited(new KeyedCodec<>("Label", Codec.STRING, false),
                    (a, v) -> a.label = v, a -> a.label, (a, p) -> a.label = p.label)
            .documentation("A localization key naming the bar, drawn at its left end in the player's own language "
                    + "(a full registered key passes through as written). Left out, the bar has no name.")
            .add()
            .appendInherited(new KeyedCodec<>("Color", Codec.STRING, false),
                    (a, v) -> a.color = v, a -> a.color, (a, p) -> a.color = p.color)
            .documentation("The fill's colour: six hex digits (#f2c94c), or the name of a colour of the family's kit "
                    + "(ZigToneActiveFill, ZigAccent). Left out, or anything else, the kit's active blue, with one line "
                    + "in the log.")
            .add()
            .appendInherited(new KeyedCodec<>("Placement", StatBarPlacement.CODEC, false),
                    (a, v) -> a.placement = v, a -> a.placement, (a, p) -> a.placement = p.placement)
            .documentation("Where the bar sits: beside another HUD (Beside, Side, Gap), or standing on its own "
                    + "(Position, Width). Left out, the bar is not drawn.")
            .add()
            .appendInherited(new KeyedCodec<>("ShowWhen", GateSpec.CODEC, false),
                    (a, v) -> a.showWhen = v, a -> a.showWhen, (a, p) -> a.showWhen = p.showWhen)
            .documentation("When the bar shows, in the Requires shape quests use (Factors, Permission, Quests, "
                    + "Custom, AllOf, AnyOf, Not), asked of the player a few times a second, so a bar appears only "
                    + "while it means something (a pool being spent: hytale:stat_percent with a Max under 100). Left "
                    + "out, it shows whenever its place does.")
            .add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .metadata(EditorSchema.defaultValue((long) DEFAULT_ORDER))
            .documentation("Where the bar stacks among bars beside the same HUD on the same side: lower numbers "
                    + "nearest it, equal numbers by id. " + DEFAULT_ORDER + " when unwritten.")
            .add()
            .build();

    public StatBarAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** Whether the bar is drawn at all; true unless the file says otherwise. */
    public boolean enabled() {
        return !Boolean.FALSE.equals(enabled);
    }

    /** The stat id, trimmed, or null when unwritten. */
    @Nullable
    public String stat() {
        return stat == null || stat.isBlank() ? null : stat.trim();
    }

    /** The bar's name key as written, trimmed, or null for a bar that names none. */
    @Nullable
    public String labelKey() {
        return label == null || label.isBlank() ? null : label.trim();
    }

    /** The bar's name, resolved on the client; an empty raw message for a bar that names none. */
    @Nonnull
    public Message label() {
        String key = labelKey();
        return key == null ? Msg.raw("") : ContentKeys.tr(key);
    }

    /** The authored colour (a hex or a kit colour's name), as written, or null. */
    @Nullable
    public String color() {
        return color;
    }

    /** Where the bar sits, or null when the file says nothing. */
    @Nullable
    public StatBarPlacement placement() {
        return placement;
    }

    /** When the bar shows, or null to show whenever its place does. */
    @Nullable
    public GateSpec showWhen() {
        return showWhen == null || showWhen.isEmpty() ? null : showWhen;
    }

    /** Where the bar stacks among its neighbours; {@value #DEFAULT_ORDER} when unwritten. */
    public int order() {
        return order == null ? DEFAULT_ORDER : order;
    }
}
