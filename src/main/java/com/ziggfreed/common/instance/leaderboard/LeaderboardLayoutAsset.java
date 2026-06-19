package com.ziggfreed.common.instance.leaderboard;

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
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.instance.result.ColumnFormat;

/**
 * A pack-authorable, mod-agnostic leaderboard LAYOUT, loaded from a consumer's
 * {@code Server/<Mod>/Leaderboard/*.json} (the path is supplied by the consumer at
 * register time, so common hard-codes no mod name). It captures the tab axes + the
 * Stats-view columns a {@link LeaderboardPageDeps} is built from - the data that was
 * previously Java literals in a consumer's startup (Kweebec's {@code KweebecExperience.init}:
 * the difficulty / party-size tab lists + the stunned / moonbloom / shrines stat columns).
 *
 * <p><b>Pattern A - full structured asset</b> (mirrors common's {@code InstancePresetAsset}
 * / Kweebec's {@code RoundPresetAsset} / hyMMO's {@code QuestGiverAsset}). The engine decodes
 * it DIRECTLY into typed fields via {@link #CODEC} - the codec IS the single schema authority
 * on both the pack layer and the owner layer (the same CODEC decodes an owner override). Every
 * {@code KeyedCodec} field name is PascalCase (the constructor rejects a lower-case first letter
 * at static init); the {@code AssetCodecInitTest}-style guard enforces it.
 *
 * <p>The two tab axes are arrays of nested {@link TabEntry} objects (a {@code Codec} array over a
 * nested {@link BuilderCodec}, the list-of-objects form the fixed {@code QueueModes} object in
 * {@code InstancePresetAsset} does not need); the Stats columns are an array of {@link StatEntry}.
 * <b>The PRIMARY (difficulty) tab {@code Id} IS the leaderboard bucket prefix</b> - the recorder +
 * CTA build the bucket key as {@code "<preset>_<partySize>"}, so a difficulty tab id MUST equal the
 * preset id it ranks.
 *
 * <p>Display labels are authored as localization KEYS; {@link #toLayout(String)} resolves each to a
 * client-resolved {@link Message} via {@link Message#translation(String)} (the key is a full,
 * already-prefixed key - common does not own a {@code .lang} namespace), so the resolved
 * {@link LeaderboardLayout} hands the page ready {@link LeaderboardBucketTab}s + {@link StatColumnDef}s.
 *
 * <p>Pack JSON shape (all fields optional; absent = the documented default):
 * <pre>{@code
 * { "Name": "chase",
 *   "BoardId": "leaderboard-chase",
 *   "PrimaryAxisLabelKey": "kweebecnightmare.leaderboard.axis.difficulty",
 *   "SecondaryAxisLabelKey": "kweebecnightmare.leaderboard.axis.players",
 *   "DifficultyTabs": [
 *     { "Id": "amateur",   "LabelKey": "kweebecnightmare.preset.amateur.name" },
 *     { "Id": "nightmare", "LabelKey": "kweebecnightmare.preset.nightmare.name" },
 *     { "Id": "hardcore",  "LabelKey": "kweebecnightmare.preset.hardcore.name" } ],
 *   "PartySizeTabs": [
 *     { "Id": "1", "LabelKey": "kweebecnightmare.leaderboard.tab.solo" },
 *     { "Id": "2", "LabelKey": "kweebecnightmare.leaderboard.tab.duo" },
 *     { "Id": "3", "LabelKey": "kweebecnightmare.leaderboard.tab.trio" },
 *     { "Id": "4", "LabelKey": "kweebecnightmare.leaderboard.tab.squad" } ],
 *   "StatColumns": [
 *     { "StatKey": "stunned",   "LabelKey": "kweebecnightmare.leaderboard.stat.stunned",   "Grouped": true },
 *     { "StatKey": "moonbloom", "LabelKey": "kweebecnightmare.leaderboard.stat.moonbloom", "Grouped": true },
 *     { "StatKey": "shrines",   "LabelKey": "kweebecnightmare.leaderboard.stat.shrines",   "Grouped": true } ] }
 * }</pre>
 */
public final class LeaderboardLayoutAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, LeaderboardLayoutAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String boardId;
    @Nullable private String primaryAxisLabelKey;
    @Nullable private String secondaryAxisLabelKey;
    @Nullable private TabEntry[] difficultyTabs;
    @Nullable private TabEntry[] partySizeTabs;
    @Nullable private StatEntry[] statColumns;

    public static final AssetBuilderCodec<String, LeaderboardLayoutAsset> CODEC = AssetBuilderCodec.builder(
                    LeaderboardLayoutAsset.class,
                    LeaderboardLayoutAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            // Name is an optional human-readable echo of the asset key (the authoritative
            // key is the filename) - a no-op setter so it doesn't trip "Unused key(s)".
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op - id comes from the filename */ },
                    a -> a.id)
            .add()
            .append(new KeyedCodec<>("BoardId", Codec.STRING, false), (a, v) -> a.boardId = v, a -> a.boardId)
            .add()
            .append(new KeyedCodec<>("PrimaryAxisLabelKey", Codec.STRING, false),
                    (a, v) -> a.primaryAxisLabelKey = v, a -> a.primaryAxisLabelKey)
            .add()
            .append(new KeyedCodec<>("SecondaryAxisLabelKey", Codec.STRING, false),
                    (a, v) -> a.secondaryAxisLabelKey = v, a -> a.secondaryAxisLabelKey)
            .add()
            .append(new KeyedCodec<>("DifficultyTabs", new ArrayCodec<>(TabEntry.CODEC, TabEntry[]::new), false),
                    (a, v) -> a.difficultyTabs = v, a -> a.difficultyTabs)
            .add()
            .append(new KeyedCodec<>("PartySizeTabs", new ArrayCodec<>(TabEntry.CODEC, TabEntry[]::new), false),
                    (a, v) -> a.partySizeTabs = v, a -> a.partySizeTabs)
            .add()
            .append(new KeyedCodec<>("StatColumns", new ArrayCodec<>(StatEntry.CODEC, StatEntry[]::new), false),
                    (a, v) -> a.statColumns = v, a -> a.statColumns)
            .add()
            .build();

    public LeaderboardLayoutAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Build the normalized runtime {@link LeaderboardLayout}: resolve every authored label
     * KEY to a client-resolved {@link Message} (so the page deps are ready), and keep each
     * difficulty tab id as the leaderboard bucket key (it must equal the preset id). Any
     * absent list yields an empty list (the page degrades to the original single-axis,
     * score-only board).
     *
     * @param layoutId the layout id (asset key on the pack layer, map key on the owner layer)
     */
    @Nonnull
    public LeaderboardLayout toLayout(@Nonnull String layoutId) {
        String board = (boardId != null && !boardId.isBlank()) ? boardId : layoutId.toLowerCase(Locale.ROOT);
        Message primaryAxis = primaryAxisLabelKey != null ? Message.translation(primaryAxisLabelKey) : null;
        Message secondaryAxis = secondaryAxisLabelKey != null ? Message.translation(secondaryAxisLabelKey) : null;
        return new LeaderboardLayout(
                layoutId.toLowerCase(Locale.ROOT),
                board,
                primaryAxis,
                secondaryAxis,
                toBucketTabs(difficultyTabs),
                toBucketTabs(partySizeTabs),
                toStatColumns(statColumns));
    }

    @Nonnull
    private static List<LeaderboardBucketTab> toBucketTabs(@Nullable TabEntry[] entries) {
        if (entries == null || entries.length == 0) {
            return List.of();
        }
        List<LeaderboardBucketTab> out = new ArrayList<>(entries.length);
        for (TabEntry e : entries) {
            if (e == null || e.id == null || e.id.isBlank()) {
                continue;
            }
            Message label = e.labelKey != null ? Message.translation(e.labelKey) : Message.raw(e.id);
            out.add(new LeaderboardBucketTab(e.id, label));
        }
        return List.copyOf(out);
    }

    @Nonnull
    private static List<StatColumnDef> toStatColumns(@Nullable StatEntry[] entries) {
        if (entries == null || entries.length == 0) {
            return List.of();
        }
        List<StatColumnDef> out = new ArrayList<>(entries.length);
        for (StatEntry e : entries) {
            if (e == null || e.statKey == null || e.statKey.isBlank()) {
                continue;
            }
            Message label = e.labelKey != null ? Message.translation(e.labelKey) : Message.raw(e.statKey);
            out.add(new StatColumnDef(e.statKey, label, e.resolveFormat()));
        }
        return List.copyOf(out);
    }

    /**
     * One tab on a leaderboard axis: a bucket key + an authored label localization key. PascalCase
     * keys (the codec rejects a lower-case first letter at static init). {@code Id} is NOT the
     * reserved asset {@code Id} - this is a nested {@link BuilderCodec}, not the asset key, so the
     * field name is free here; it carries the bucket key (a difficulty tab's {@code Id} must equal
     * the preset id it ranks).
     */
    public static final class TabEntry {
        public static final BuilderCodec<TabEntry> CODEC = BuilderCodec.builder(TabEntry.class, TabEntry::new)
                .append(new KeyedCodec<>("Id", Codec.STRING, false), (e, v) -> e.id = v, e -> e.id).add()
                .append(new KeyedCodec<>("LabelKey", Codec.STRING, false), (e, v) -> e.labelKey = v, e -> e.labelKey).add()
                .build();

        @Nullable protected String id;
        @Nullable protected String labelKey;

        public TabEntry() {
        }
    }

    /**
     * One Stats-view column: a stat key, an authored label localization key, and how to format the
     * value. {@code Format} is the explicit {@link ColumnFormat} name (case-insensitive); the
     * convenience {@code Grouped} boolean is a shorthand for {@link ColumnFormat#GROUPED} when
     * {@code Format} is absent. PascalCase keys.
     */
    public static final class StatEntry {
        public static final BuilderCodec<StatEntry> CODEC = BuilderCodec.builder(StatEntry.class, StatEntry::new)
                .append(new KeyedCodec<>("StatKey", Codec.STRING, false), (e, v) -> e.statKey = v, e -> e.statKey).add()
                .append(new KeyedCodec<>("LabelKey", Codec.STRING, false), (e, v) -> e.labelKey = v, e -> e.labelKey).add()
                .append(new KeyedCodec<>("Format", Codec.STRING, false), (e, v) -> e.format = v, e -> e.format).add()
                .append(new KeyedCodec<>("Grouped", Codec.BOOLEAN, false), (e, v) -> e.grouped = v, e -> e.grouped).add()
                .build();

        @Nullable protected String statKey;
        @Nullable protected String labelKey;
        @Nullable protected String format;
        @Nullable protected Boolean grouped;

        public StatEntry() {
        }

        /** Resolve the column format: explicit {@code Format} name, else {@code Grouped}'s shorthand, else GROUPED. */
        @Nonnull
        ColumnFormat resolveFormat() {
            if (format != null && !format.isBlank()) {
                try {
                    return ColumnFormat.valueOf(format.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // unknown format name -> fall through to the default
                }
            }
            return ColumnFormat.GROUPED;
        }
    }
}
