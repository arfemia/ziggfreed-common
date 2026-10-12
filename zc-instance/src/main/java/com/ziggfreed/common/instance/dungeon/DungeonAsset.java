package com.ziggfreed.common.instance.dungeon;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.asset.SeasonLeaf;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.season.SeasonGate;

/**
 * One dungeon, as a file: {@code Server/ZiggfreedCommon/Dungeons/<Id>.json}. The filename is the id, and
 * every copy of the dungeon is a world named {@code <id>-<n>}.
 *
 * <pre>{@code
 * {
 *   "Instance": "ZiggfreedCommon_Dungeon_Void",
 *   "Share": { "PartyLocked": true, "MaxPlayers": 5, "AreasPerCopy": 4 },
 *   "Area": { "Prefab": "MyPack/Crypt/Crypt_Main.prefab.json", "Spacing": 2048, "EntryOffset": { "Y": 1 } },
 *   "Run": { "TimeLimitSeconds": 900, "FailOnWipe": false },
 *   "Complete": { "Bosses": ["MyPack_Crypt_Lich"], "AllBosses": false },
 *   "Copy": { "EmptyCloseSeconds": 60 },
 *   "Difficulty": "",
 *   "Season": "Hallows_Eve",
 *   "Text": { "NameKey": "mypack.dungeon.crypt.name", "DescriptionKey": "mypack.dungeon.crypt.description" }
 * }
 * }</pre>
 *
 * <p>A dungeon is a prefab pasted into an area of a copy of a native instance template (zc's own void
 * template unless {@code Instance} names another). Several parties share one copy, each in its own area,
 * {@code Area.Spacing} blocks apart, so one world thread serves them all ({@link CopyRouter}).
 *
 * <p>Every leaf is nullable: unauthored means the default each getter names, and under native
 * {@code Parent} a child keeps every leaf it does not restate. A server owner retunes a dungeon, or adds
 * one, in {@code mods/ziggfreedcommon/dungeons.json} ({@link DungeonOwnerLayers}). A dungeon is present
 * while it is {@code Enabled} and its {@code Season} runs ({@link #isOpen()}); out of season it is absent,
 * never shown locked. {@link DungeonValidator} checks what a file names once every pack has loaded.
 */
public final class DungeonAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, DungeonAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Dungeons";

    /** The native instance template a dungeon copies when it names none: zc's own empty void world. */
    public static final String DEFAULT_TEMPLATE = "ZiggfreedCommon_Dungeon_Void";

    /** How many players one area holds when a file says nothing: the size of zc's own party. */
    public static final int DEFAULT_MAX_PLAYERS = 5;

    /** How many parties share one copy when a file says nothing (lead's call). */
    public static final int DEFAULT_AREAS_PER_COPY = 4;

    /**
     * How far apart two areas of one copy sit, in blocks, when a file says nothing: twice the largest view
     * radius the engine allows (32 chunks of 32 blocks), so no player ever sees into another party's area.
     */
    public static final int DEFAULT_SPACING = 2048;

    /** How long an emptied copy stands before it closes, in seconds, when a file says nothing. */
    public static final int DEFAULT_EMPTY_CLOSE_SECONDS = 60;

    /** The folder a prefab path is written under; a path that starts with it reads the same without it. */
    public static final String PREFABS_ROOT = "Server/Prefabs/";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String instance;
    @Nullable private Boolean enabled;
    @Nullable private Share share;
    @Nullable private Area area;
    @Nullable private Run run;
    @Nullable private Complete complete;
    @Nullable private Copy copy;
    @Nullable private String difficulty;
    @Nullable private String season;
    @Nullable private Text text;

    public static final AssetBuilderCodec<String, DungeonAsset> CODEC = SeasonLeaf.append(AssetBuilderCodec.builder(
                    DungeonAsset.class,
                    DungeonAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Instance", Codec.STRING, false),
                    (a, v) -> a.instance = v, a -> a.instance, (a, p) -> a.instance = p.instance)
            .metadata(EditorSchema.defaultValue(DEFAULT_TEMPLATE))
            .documentation("The native instance template every copy of this dungeon starts from, by its folder "
                    + "under Server/Instances/. Unauthored means ZiggfreedCommon_Dungeon_Void, an empty world that "
                    + "saves nothing. A template that pins its world with an InstanceKey cannot be copied, and one "
                    + "with a portal timer or world events cannot be shared by several parties.").add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether the dungeon can be entered at all. Unauthored means true. Set false to "
                    + "close it without deleting the file.").add()
            .appendInherited(new KeyedCodec<>("Share", Share.CODEC, false),
                    (a, v) -> a.share = v, a -> a.share, (a, p) -> a.share = p.share)
            .documentation("Who goes in together, and how many parties one copy holds.").add()
            .appendInherited(new KeyedCodec<>("Area", Area.CODEC, false),
                    (a, v) -> a.area = v, a -> a.area, (a, p) -> a.area = p.area)
            .documentation("What one party's area is: the prefab pasted there, how far apart areas sit, and "
                    + "where an arriving player lands.").add()
            .appendInherited(new KeyedCodec<>("Run", Run.CODEC, false),
                    (a, v) -> a.run = v, a -> a.run, (a, p) -> a.run = p.run)
            .documentation("How a party's run ends besides finishing it.").add()
            .appendInherited(new KeyedCodec<>("Complete", Complete.CODEC, false),
                    (a, v) -> a.complete = v, a -> a.complete, (a, p) -> a.complete = p.complete)
            .documentation("What finishes a party's run, clearing it. Unauthored, nothing clears a run: it "
                    + "ends only by failing (its time limit, every member leaving, or a wipe where FailOnWipe "
                    + "is set).").add()
            .appendInherited(new KeyedCodec<>("Copy", Copy.CODEC, false),
                    (a, v) -> a.copy = v, a -> a.copy, (a, p) -> a.copy = p.copy)
            .documentation("How long a copy stays open.").add()
            .appendInherited(new KeyedCodec<>("Difficulty", Codec.STRING, false),
                    (a, v) -> a.difficulty = v, a -> a.difficulty, (a, p) -> a.difficulty = p.difficulty)
            .metadata(EditorSchema.defaultValue(""))
            .documentation("A label for a harder version of a dungeon, such as Hard, which its runs are counted "
                    + "under. Unauthored or empty means normal.").add()
            .appendInherited(new KeyedCodec<>("Text", Text.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What players read the dungeon as, by localization key.").add(),
                    (a, v) -> a.season = v, a -> a.season)
            .build();

    public DungeonAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The instance template every copy starts from, trimmed; zc's void template when unauthored. */
    @Nonnull
    public String templateId() {
        return instance == null || instance.isBlank() ? DEFAULT_TEMPLATE : instance.trim();
    }

    /** {@code Enabled}, unauthored true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** The calendar event the dungeon belongs to, trimmed, or null when it is open all year. */
    @Nullable
    public String getSeason() {
        return SeasonGate.normalize(season);
    }

    /** Can the dungeon be entered right now: switched on, and its season (if any) running? */
    public boolean isOpen() {
        return isEnabled() && SeasonGate.live(getSeason());
    }

    /** {@code Share.PartyLocked}, unauthored true: an area holds one party, and a stranger gets one of their own. */
    public boolean partyLocked() {
        Boolean v = share == null ? null : share.partyLocked;
        return v == null || v;
    }

    /** {@code Share.MaxPlayers}: how many players one area holds, at least one. */
    public int maxPlayers() {
        Integer v = share == null ? null : share.maxPlayers;
        return Math.max(1, v == null ? DEFAULT_MAX_PLAYERS : v);
    }

    /** {@code Share.AreasPerCopy}: how many parties one copy holds, at least one. */
    public int areasPerCopy() {
        Integer v = share == null ? null : share.areasPerCopy;
        return Math.max(1, v == null ? DEFAULT_AREAS_PER_COPY : v);
    }

    /**
     * {@code Area.Prefab} as a path under {@code Server/Prefabs/} with its extension, slashes forward and a
     * leading {@code Server/Prefabs/} dropped; null when the file names none.
     */
    @Nullable
    public String prefabPath() {
        String raw = area == null ? null : area.prefab;
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String path = raw.trim().replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.toLowerCase(Locale.ROOT).startsWith(PREFABS_ROOT.toLowerCase(Locale.ROOT))) {
            path = path.substring(PREFABS_ROOT.length());
        }
        return path.isBlank() ? null : path;
    }

    /** {@code Area.Spacing} in blocks, as written; {@link #DEFAULT_SPACING} when unauthored. */
    public int spacing() {
        Integer v = area == null ? null : area.spacing;
        return v == null ? DEFAULT_SPACING : v;
    }

    /** {@code Area.EntryOffset}: where an arriving player lands, from the area's origin, in blocks; zero unauthored. */
    @Nonnull
    public Vec3 entryOffset() {
        Vec3 v = area == null ? null : area.entryOffset;
        return v == null ? Vec3.of(null, null, null) : v;
    }

    /** {@code Run.TimeLimitSeconds}, or null when the run has no time limit (unauthored, zero or less). */
    @Nullable
    public Integer timeLimitSeconds() {
        Integer v = run == null ? null : run.timeLimitSeconds;
        return v == null || v <= 0 ? null : v;
    }

    /** {@code Run.FailOnWipe}, unauthored false: a run fails when every member is dead at once only when set. */
    public boolean failOnWipe() {
        Boolean v = run == null ? null : run.failOnWipe;
        return v != null && v;
    }

    /**
     * {@code Complete.Bosses}: the encounter script ids whose defeat clears a run, trimmed, blanks and repeats
     * dropped, in the order written; empty when the file names none, and then no boss clears a run.
     */
    @Nonnull
    public List<String> completeBosses() {
        String[] raw = complete == null ? null : complete.bosses;
        if (raw == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String id : raw) {
            if (id != null && !id.isBlank()) {
                out.add(id.trim());
            }
        }
        return List.copyOf(out);
    }

    /**
     * {@code Complete.AllBosses}, unauthored false: a run clears once every boss {@link #completeBosses()} names
     * has fallen in it, rather than at the first of them.
     */
    public boolean completeAllBosses() {
        Boolean v = complete == null ? null : complete.allBosses;
        return v != null && v;
    }

    /** {@code Copy.EmptyCloseSeconds}: how long an emptied copy stands before it closes, zero or more. */
    public int emptyCloseSeconds() {
        Integer v = copy == null ? null : copy.emptyCloseSeconds;
        return Math.max(0, v == null ? DEFAULT_EMPTY_CLOSE_SECONDS : v);
    }

    /** {@code Difficulty}, trimmed; empty for normal. */
    @Nonnull
    public String difficulty() {
        return difficulty == null ? "" : difficulty.trim();
    }

    /** {@code Text.NameKey}, trimmed, or null when the file names none. */
    @Nullable
    public String nameKey() {
        return blankToNull(text == null ? null : text.nameKey);
    }

    /** {@code Text.DescriptionKey}, trimmed, or null when the file names none. */
    @Nullable
    public String descriptionKey() {
        return blankToNull(text == null ? null : text.descriptionKey);
    }

    /** What the router needs of this dungeon, filed under {@code dungeonId} (the fold's lower-cased id). */
    @Nonnull
    public CopyRouter.Rules routerRules(@Nonnull String dungeonId) {
        return new CopyRouter.Rules(dungeonId, areasPerCopy(), maxPlayers(), partyLocked(),
                emptyCloseSeconds() * 1000L);
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ==================== Share ====================

    /** Who goes in together, and how many parties one copy holds. */
    public static final class Share {

        @Nullable protected Boolean partyLocked;
        @Nullable protected Integer maxPlayers;
        @Nullable protected Integer areasPerCopy;

        public static final BuilderCodec<Share> CODEC = BuilderCodec.builder(Share.class, Share::new)
                .appendInherited(new KeyedCodec<>("PartyLocked", Codec.BOOLEAN, false),
                        (o, v) -> o.partyLocked = v, o -> o.partyLocked, (o, p) -> o.partyLocked = p.partyLocked)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether an area holds one party only. Unauthored means true: a player outside "
                        + "the party gets an area of their own. False lets players who are not in a party "
                        + "together share an area while it has room.").add()
                .appendInherited(new KeyedCodec<>("MaxPlayers", Codec.INTEGER, false),
                        (o, v) -> o.maxPlayers = v, o -> o.maxPlayers, (o, p) -> o.maxPlayers = p.maxPlayers)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_MAX_PLAYERS))
                .documentation("How many players one area holds, counting those still on their way in. "
                        + "Unauthored means 5; less than 1 means 1.").add()
                .appendInherited(new KeyedCodec<>("AreasPerCopy", Codec.INTEGER, false),
                        (o, v) -> o.areasPerCopy = v, o -> o.areasPerCopy, (o, p) -> o.areasPerCopy = p.areasPerCopy)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_AREAS_PER_COPY))
                .documentation("How many parties share one copy, each in its own area. A party fills a free "
                        + "area of an open copy before a new copy opens. Unauthored means 4; 1 gives every party "
                        + "a world of its own.").add()
                .build();

        public Share() {
        }
    }

    // ==================== Area ====================

    /** What one party's area is. */
    public static final class Area {

        @Nullable protected String prefab;
        @Nullable protected Integer spacing;
        @Nullable protected Vec3 entryOffset;

        public static final BuilderCodec<Area> CODEC = BuilderCodec.builder(Area.class, Area::new)
                .appendInherited(new KeyedCodec<>("Prefab", Codec.STRING, false),
                        (o, v) -> o.prefab = v, o -> o.prefab, (o, p) -> o.prefab = p.prefab)
                .documentation("The prefab pasted into each area, as its path under Server/Prefabs/ with its "
                        + "extension, such as MyPack/Crypt/Crypt_Main.prefab.json. A path no loaded pack ships "
                        + "is reported, and the area stays empty.").add()
                .appendInherited(new KeyedCodec<>("Spacing", Codec.INTEGER, false),
                        (o, v) -> o.spacing = v, o -> o.spacing, (o, p) -> o.spacing = p.spacing)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_SPACING))
                .documentation("How far apart two areas of one copy sit, in blocks. It must be at least twice "
                        + "the server's view distance, so no party sees another. Unauthored means 2048, enough "
                        + "for any view distance.").add()
                .appendInherited(new KeyedCodec<>("EntryOffset", Vec3.CODEC, false),
                        (o, v) -> o.entryOffset = v, o -> o.entryOffset, (o, p) -> o.entryOffset = p.entryOffset)
                .documentation("Where an arriving player lands, in blocks from the spot the prefab is pasted "
                        + "at. Unauthored means that spot itself.").add()
                .build();

        public Area() {
        }
    }

    // ==================== Run ====================

    /** How a party's run ends besides finishing it. */
    public static final class Run {

        @Nullable protected Integer timeLimitSeconds;
        @Nullable protected Boolean failOnWipe;

        public static final BuilderCodec<Run> CODEC = BuilderCodec.builder(Run.class, Run::new)
                .appendInherited(new KeyedCodec<>("TimeLimitSeconds", Codec.INTEGER, false),
                        (o, v) -> o.timeLimitSeconds = v, o -> o.timeLimitSeconds,
                        (o, p) -> o.timeLimitSeconds = p.timeLimitSeconds)
                .documentation("How long a run may last, in seconds, before it fails. Unauthored, or zero or "
                        + "less, means no time limit.").add()
                .appendInherited(new KeyedCodec<>("FailOnWipe", Codec.BOOLEAN, false),
                        (o, v) -> o.failOnWipe = v, o -> o.failOnWipe, (o, p) -> o.failOnWipe = p.failOnWipe)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("Whether a run fails the moment every member is dead at once. Unauthored means "
                        + "false: the party respawns and carries on.").add()
                .build();

        public Run() {
        }
    }

    // ==================== Complete ====================

    /** What finishes a party's run, clearing it. */
    public static final class Complete {

        @Nullable protected String[] bosses;
        @Nullable protected Boolean allBosses;

        public static final BuilderCodec<Complete> CODEC = BuilderCodec.builder(Complete.class, Complete::new)
                .appendInherited(new KeyedCodec<>("Bosses", Codec.STRING_ARRAY, false),
                        (o, v) -> o.bosses = v, o -> o.bosses, (o, p) -> o.bosses = p.bosses)
                .documentation("The boss fights that clear a run, by their encounter script ids (the file names "
                        + "under Server/EncounterManager). A fight counts for the run of the area its players "
                        + "are in. Unauthored or empty, no boss clears a run. This is ONE leaf: authoring it "
                        + "replaces an inherited list whole.").add()
                .appendInherited(new KeyedCodec<>("AllBosses", Codec.BOOLEAN, false),
                        (o, v) -> o.allBosses = v, o -> o.allBosses, (o, p) -> o.allBosses = p.allBosses)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("Whether every boss Bosses names must fall in the run before it clears. "
                        + "Unauthored means false: the first of them to fall clears it.").add()
                .build();

        public Complete() {
        }
    }

    // ==================== Copy ====================

    /** How long a copy stays open. */
    public static final class Copy {

        @Nullable protected Integer emptyCloseSeconds;

        public static final BuilderCodec<Copy> CODEC = BuilderCodec.builder(Copy.class, Copy::new)
                .appendInherited(new KeyedCodec<>("EmptyCloseSeconds", Codec.INTEGER, false),
                        (o, v) -> o.emptyCloseSeconds = v, o -> o.emptyCloseSeconds,
                        (o, p) -> o.emptyCloseSeconds = p.emptyCloseSeconds)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_EMPTY_CLOSE_SECONDS))
                .documentation("How long a copy stands once every area in it is empty, in seconds, before it "
                        + "closes. Unauthored means 60.").add()
                .build();

        public Copy() {
        }
    }

    // ==================== Text ====================

    /** What players read the dungeon as. */
    public static final class Text {

        @Nullable protected String nameKey;
        @Nullable protected String descriptionKey;

        public static final BuilderCodec<Text> CODEC = BuilderCodec.builder(Text.class, Text::new)
                .appendInherited(new KeyedCodec<>("NameKey", Codec.STRING, false),
                        (o, v) -> o.nameKey = v, o -> o.nameKey, (o, p) -> o.nameKey = p.nameKey)
                .documentation("The localization key the dungeon's name reads from. Unauthored shows its id.")
                .add()
                .appendInherited(new KeyedCodec<>("DescriptionKey", Codec.STRING, false),
                        (o, v) -> o.descriptionKey = v, o -> o.descriptionKey,
                        (o, p) -> o.descriptionKey = p.descriptionKey)
                .documentation("The localization key of a line or two saying what the dungeon is.").add()
                .build();

        public Text() {
        }
    }
}
