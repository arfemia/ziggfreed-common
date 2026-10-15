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
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
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
 * <p>A line is a stat id, {@code Stat/Thing} for one line of a stat's breakdown
 * ({@code Mob_Kills/Trork_Warrior}), or {@code Stat/*} for every line of a stat: on the sections row one
 * column per line the stat's source names now ({@code Reputation/*}, each reputation on the server), and in
 * a section whose split takes its columns from it (the {@code boss_fights} and {@code dungeons} sections) one
 * column per stat read one picked thing at a time ({@code Encounters_Won/*}), its plain stat ids then the first
 * choice's and each group's columns. A line no store on this server counts shows no column, and a section left
 * with none shows no tab, so a section can name a stat a mod adds and still read right on a server without that
 * mod. Only a line some section names is ranked at all.
 */
public final class StatSectionAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, StatSectionAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/StatSections";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String nameKey;
    @Nullable private Integer order;
    @Nullable private String[] stats;
    @Nullable private String icon;
    @Nullable private String hintKey;

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
                    + "Mob_Kills, Stat/Thing for one line of its breakdown, such as Mob_Kills/Trork_Warrior, or "
                    + "Stat/* for every line of a stat: a column per line a read stat names, such as Reputation/* "
                    + "for each reputation on the server. In a section split into things that takes its columns "
                    + "from here (Boss_Fights, Dungeons), the stat ids are the columns of the first choice and of "
                    + "each group, and the Stat/* lines a picked thing's, at most four of each. A stat nothing on "
                    + "this server counts shows no column.").add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, parent) -> a.icon = parent.icon)
            .metadata(EditorSchema.assetRef(Item.class))
            .documentation("The item whose picture the section's tab shows, and the picture over the board while "
                    + "nobody is on it. Omit it and the tab shows the library's own picture.").add()
            .appendInherited(new KeyedCodec<>("HintKey", Codec.STRING, false),
                    (a, v) -> a.hintKey = v, a -> a.hintKey, (a, parent) -> a.hintKey = parent.hintKey)
            .documentation("The full translation key of one line saying what counts on this tab and how to get on "
                    + "it, shown to a player who has no place there yet and under the board while nobody is on it. "
                    + "Omit it and the tab says that anything in its columns counts.").add()
            .build();

    public StatSectionAsset() {
    }

    /** The section id, lower-cased from the filename. */
    @Override
    public String getId() {
        return id;
    }

    /** The item the section's tab is pictured by, or null when the file names none. */
    @Nullable
    public String icon() {
        return icon == null || icon.isBlank() ? null : icon.trim();
    }

    /** The key of the line saying how to get on the section's board, or null when the file names none. */
    @Nullable
    public String hintKey() {
        return hintKey == null || hintKey.isBlank() ? null : hintKey.trim();
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
