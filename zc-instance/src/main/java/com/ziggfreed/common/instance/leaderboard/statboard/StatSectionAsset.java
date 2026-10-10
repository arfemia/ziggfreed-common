package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.instance.leaderboard.StatColumnDef;

/**
 * One section of the stat board, as a file: {@code Server/ZiggfreedCommon/StatSections/<Id>.json}. A
 * section is a tab on Records and the columns under it: the stats it names, at most
 * {@value StatColumnDef#MAX_STAT_COLUMNS}, ranked over every period.
 *
 * <pre>{@code
 * {"NameKey":"ziggfreedcommon.statboard.section.combat","Order":10,"Stats":["Mob_Kills","Deaths"]}
 * }</pre>
 *
 * <p>A line is a stat id, or {@code Stat/Thing} for one line of a stat's breakdown
 * ({@code Mob_Kills/Trork_Warrior}). A line no store on this server counts shows no column, and a
 * section left with none shows no tab, so a section can name a stat a mod adds and still read right
 * on a server without that mod. Only a line some section names is ranked at all.
 */
public final class StatSectionAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, StatSectionAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/StatSections";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String nameKey;
    @Nullable private Integer order;
    @Nullable private String[] stats;

    public static final AssetBuilderCodec<String, StatSectionAsset> CODEC = AssetBuilderCodec.builder(
                    StatSectionAsset.class,
                    StatSectionAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("NameKey", Codec.STRING, false),
                    (a, v) -> a.nameKey = v, a -> a.nameKey, (a, parent) -> a.nameKey = parent.nameKey)
            .documentation("The full translation key the section's tab is named by. Omit it and the tab "
                    + "shows the file's id.").add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, parent) -> a.order = parent.order)
            .metadata(EditorSchema.defaultValue(0L))
            .documentation("Where the section's tab sits among the others, lowest first.").add()
            .appendInherited(new KeyedCodec<>("Stats", Codec.STRING_ARRAY, false),
                    (a, v) -> a.stats = v, a -> a.stats, (a, parent) -> a.stats = parent.stats)
            .documentation("The stats the section ranks, one column each, at most four: a stat id such as "
                    + "Mob_Kills, or Stat/Thing for one line of its breakdown, such as Mob_Kills/Trork_Warrior. "
                    + "A stat nothing on this server counts shows no column.").add()
            .build();

    public StatSectionAsset() {
    }

    /** The section id, lower-cased from the filename. */
    @Override
    public String getId() {
        return id;
    }

    /** The section as the board shows it. */
    @Nonnull
    public StatSection toSection(@Nonnull String sectionId) {
        List<String> lines = new ArrayList<>();
        if (stats != null) {
            for (String line : stats) {
                if (line != null && !line.isBlank()) {
                    lines.add(line.trim());
                }
            }
        }
        String name = nameKey == null || nameKey.isBlank() ? null : nameKey.trim();
        return new StatSection(sectionId.toLowerCase(Locale.ROOT), name, order == null ? 0 : order, lines);
    }
}
