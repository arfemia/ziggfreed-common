package com.ziggfreed.common.settings;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;

/**
 * The server owner's defaults and locks over every player's own choices that are not a HUD panel's (a
 * panel carries its own {@code Player} group in its file): the quest tracker's show and spot, and the
 * quest and achievement notification level. The FILE NAME is the record's id, and the record every read
 * uses is {@value #SHARED_ID}.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/PlayerSettings/Default.json}, which this library ships with
 * every leaf at its unauthored answer; a consumer's same-id file wins by pack order, and the owner layer
 * is {@code mods/ziggfreedcommon/player-settings.json}:
 * <pre>{@code
 * // mods/ziggfreedcommon/player-settings.json
 * { "Default": { "Notifications": { "Level": { "Default": "Milestones", "Locked": true } } } }
 * }</pre>
 */
public final class PlayerSettingsAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, PlayerSettingsAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/PlayerSettings";

    /** The id of the record every read uses (the file is {@code Default.json}; ids fold lower-case). */
    public static final String SHARED_ID = "default";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private SurfaceRules questTracker;
    @Nullable private NotificationRules notifications;

    public static final AssetBuilderCodec<String, PlayerSettingsAsset> CODEC = AssetBuilderCodec.builder(
                    PlayerSettingsAsset.class,
                    PlayerSettingsAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("QuestTracker", SurfaceRules.CODEC, false),
                    (a, v) -> a.questTracker = v, a -> a.questTracker, (a, p) -> a.questTracker = p.questTracker)
            .documentation("The quest tracker, the panel of a player's pinned quests: whether it shows for a "
                    + "player who has not chosen, and whether players may change that or where it sits. The "
                    + "spots a player may pick for it are the HUD spot files whose Panels list Quest_Tracker.")
            .add()
            .appendInherited(new KeyedCodec<>("Notifications", NotificationRules.CODEC, false),
                    (a, v) -> a.notifications = v, a -> a.notifications,
                    (a, p) -> a.notifications = p.notifications)
            .documentation("Quest and achievement notices: the level a player who has not chosen gets, and "
                    + "whether players may change it.")
            .add()
            .build();

    public PlayerSettingsAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The quest tracker's rules, never null. */
    @Nonnull
    public SurfaceRules questTracker() {
        return questTracker != null ? questTracker : SurfaceRules.NONE;
    }

    /** The notification rules, never null. */
    @Nonnull
    public NotificationRules notifications() {
        return notifications != null ? notifications : NotificationRules.NONE;
    }
}
