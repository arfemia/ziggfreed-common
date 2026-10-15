package com.ziggfreed.common.instance.portal;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * One of the game's own portal dungeons the library keeps run records for, as a file:
 * {@code Server/ZiggfreedCommon/PortalDungeons/<PortalType id>.json}, its filename the id of the game's
 * {@code PortalType} (such as {@code The_Deepworks}). The library never opens or runs such a dungeon: it watches the
 * worlds the game opens for it ({@link PortalDungeonRuns}) and counts each visit as a dungeon run, so the dungeon
 * stats, quests and achievements count it and Records ranks it.
 *
 * <pre>{@code
 * {
 *   "Enabled": true,
 *   "NameKey": "server.portals.theDeepworks",
 *   "Icon": "PortalKey_The_Deepworks",
 *   "Complete": { "Encounters": ["Goblin_Gobliterator_Encounter", "Goblin_Gobliterator_Encounter_Hard"] }
 * }
 * }</pre>
 *
 * <p>Every leaf is nullable and inherited under {@code Parent}.
 */
public final class PortalDungeonAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, PortalDungeonAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/PortalDungeons";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private String nameKey;
    @Nullable private String icon;
    @Nullable private Complete complete;

    public static final AssetBuilderCodec<String, PortalDungeonAsset> CODEC = AssetBuilderCodec.builder(
                    PortalDungeonAsset.class,
                    PortalDungeonAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Set false and the library counts no run of this dungeon. Unauthored means true.").add()
            .appendInherited(new KeyedCodec<>("NameKey", Codec.STRING, false),
                    (a, v) -> a.nameKey = v, a -> a.nameKey, (a, p) -> a.nameKey = p.nameKey)
            .documentation("The translation key the dungeon is named by on Records. Omit it and the dungeon takes "
                    + "the name the game gives its portal.").add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .metadata(EditorSchema.assetRef(Item.class))
            .documentation("The item whose picture stands for the dungeon where a page pictures it. Omit it for "
                    + "none.").add()
            .appendInherited(new KeyedCodec<>("Complete", Complete.CODEC, false),
                    (a, v) -> a.complete = v, a -> a.complete, (a, p) -> a.complete = p.complete)
            .documentation("What clears a run of the dungeon. Unauthored, nothing does: every run fails when its "
                    + "last member leaves.").add()
            .build();

    public PortalDungeonAsset() {
    }

    /** The PortalType id this file follows, as its filename spells it. */
    @Override
    public String getId() {
        return id;
    }

    /** Whether the library counts this dungeon's runs; unauthored means yes. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** The key the dungeon is named by, or null to take the game's portal name. */
    @Nullable
    public String nameKey() {
        return nameKey == null || nameKey.isBlank() ? null : nameKey.trim();
    }

    /** The item the dungeon is pictured by, or null for none. */
    @Nullable
    public String icon() {
        return icon == null || icon.isBlank() ? null : icon.trim();
    }

    /** The encounter script ids whose defeat clears a run, trimmed, blanks and repeats left out; empty for none. */
    @Nonnull
    public List<String> completeEncounters() {
        String[] written = complete == null ? null : complete.encounters;
        List<String> out = new ArrayList<>();
        if (written != null) {
            for (String encounter : written) {
                if (encounter != null && !encounter.isBlank() && !containsIgnoringCase(out, encounter.trim())) {
                    out.add(encounter.trim());
                }
            }
        }
        return out;
    }

    private static boolean containsIgnoringCase(@Nonnull List<String> list, @Nonnull String value) {
        for (String held : list) {
            if (held.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    // ==================== Complete ====================

    /** What clears a run. */
    public static final class Complete {

        @Nullable protected String[] encounters;

        public static final BuilderCodec<Complete> CODEC = BuilderCodec.builder(Complete.class, Complete::new)
                .appendInherited(new KeyedCodec<>("Encounters", Codec.STRING_ARRAY, false),
                        (o, v) -> o.encounters = v, o -> o.encounters, (o, p) -> o.encounters = p.encounters)
                .documentation("The boss fights that clear a run, by their encounter script ids (the file names "
                        + "under Server/EncounterManager): the first of them won in the dungeon's world clears the "
                        + "run for every player in that world then. This is ONE leaf: authoring it replaces an "
                        + "inherited list whole.").add()
                .build();

        public Complete() {
        }
    }
}
