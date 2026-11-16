package com.ziggfreed.common.instance.dungeon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.asset.SeasonLeaf;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.instance.dungeon.layout.Cell;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan;
import com.ziggfreed.common.instance.dungeon.layout.asset.DungeonLayoutAsset;
import com.ziggfreed.common.instance.dungeon.layout.asset.DungeonLayoutConfig;
import com.ziggfreed.common.instance.dungeon.omen.DungeonOmenAsset;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.diminish.DiminishSpec;
import com.ziggfreed.common.season.SeasonGate;

/**
 * One dungeon, as a file: {@code Server/ZiggfreedCommon/Dungeons/<Id>.json}. The filename is the id, and
 * every copy of the dungeon is a world named {@code <id>-<n>}.
 *
 * <pre>{@code
 * {
 *   "Instance": "ZiggfreedCommon_Dungeon_Void",
 *   "Share": { "PartyLocked": true, "MaxPlayers": 5, "AreasPerCopy": 4 },
 *   "Area": { "Prefab": "MyPack/Crypt/Crypt_Main.prefab.json", "Spacing": 2048, "EntryOffset": { "Y": 1 }, "EntryYaw": 180,
 *             "IntroCamera": "MyPack_Crypt_Intro" },
 *   "Run": { "TimeLimitSeconds": 900, "FailOnWipe": false },
 *   "Complete": { "Bosses": ["MyPack_Crypt_Lich"], "AllBosses": false,
 *                 "Objective": { "Kind": "PICKUP_ITEM", "Target": "MyPack_Crypt_Relic", "Amount": 3 },
 *                 "SurviveTimer": 300 },
 *   "Rewards": { "Lootable": "MyPack_Crypt_Clear", "Diminish": { "Steps": [1, 0.5], "Window": "Daily" } },
 *   "EntryCost": { "Items": [ { "Item": "MyPack_Crypt_Key", "Count": 1 } ], "Keys": ["MyPack_Crypt_Master_Key"],
 *                  "HintKey": "mypack.dungeon.crypt.key_hint" },
 *   "Copy": { "EmptyCloseSeconds": 60 },
 *   "Difficulty": "",
 *   "Tier": { "Rank": 2, "Opens": "MyPack_Crypt_T1" },
 *   "Omens": { "Count": 1, "Pool": ["MyPack_Bolstering", "MyPack_Blight"], "PerWeek": 2 },
 *   "Seed": { "Daily": false },
 *   "Season": "Hallows_Eve",
 *   "Text": { "NameKey": "mypack.dungeon.crypt.name", "DescriptionKey": "mypack.dungeon.crypt.description",
 *             "GoalKey": "mypack.dungeon.crypt.goal" }
 * }
 * }</pre>
 *
 * <p>A dungeon is a prefab pasted into an area of a copy of a native instance template (zc's own void
 * template unless {@code Instance} names another). Several parties share one copy, each in its own area,
 * {@code Area.Spacing} blocks apart, so one world thread serves them all ({@link CopyRouter}).
 *
 * <p>A dungeon with {@code Area.Layout} is generated instead: each party's run gets a fresh layout of rooms from that
 * plan and its own seed, painted into the copy's chunks by zc's world generator off the world thread
 * ({@code dungeon/gen/}), each run on ground of its own along its area's lane ({@code Area.RunStride},
 * {@code Area.MaxRunsPerLane}, {@link RunLanes}); its {@code Area.Prefab} is never pasted.
 *
 * <p>Every leaf is nullable: unauthored means the default each getter names, and under native
 * {@code Parent} a child keeps every leaf it does not restate. A server owner retunes a dungeon, or adds
 * one, in {@code mods/ziggfreedcommon/dungeons.json} ({@link DungeonOwnerLayers}). A dungeon is open while it
 * is {@code Enabled} and its {@code Season} runs ({@link #isOpen()}); otherwise it still resolves, and its way
 * in refuses entry with a line saying it is closed, or that it only opens during its season
 * ({@link DungeonRefusals}). {@link DungeonValidator} checks what a file names once every pack has loaded.
 *
 * <p>{@code EntryCost} prices the way in ({@link DungeonEntryCost}): the player who opens an area for their party
 * pays it, a partymate who follows into that area pays nothing, a refused entry spends nothing, and the admin's
 * {@code /zigdungeon enter} never pays. A key ({@code EntryCost.Keys}) the opener holds pays it without being spent.
 *
 * <p>{@code Tier}, {@code Omens} and {@code Seed} are the replay hooks ({@code tier/}, {@code omen/}, {@link RunSeeds}):
 * a tier is a child file of the tier below with its own rank and the dungeon whose clear opens it, so each tier keeps
 * its own runs, daily rewards and Records line; a week's omens are drawn from a pool in order and each tier meets
 * the first {@code Omens.Count} of them (R1006: exact per tier per week, a higher tier's including a lower one's); and
 * a daily is a child file whose runs all share the day's seed.
 */
public final class DungeonAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, DungeonAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Dungeons";

    /** The native instance template a dungeon copies when it names none: zc's own empty void world. */
    public static final String DEFAULT_TEMPLATE = "ZiggfreedCommon_Dungeon_Void";

    /**
     * The native instance template a generated dungeon ({@code Area.Layout}) copies when it names none: zc's empty
     * world whose chunks zc's own generator paints the runs' rooms into.
     */
    public static final String GENERATED_TEMPLATE = "ZiggfreedCommon_Dungeon_Generated";

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

    /** How many omens make a week's set when a file says nothing (lead's call). */
    public static final int DEFAULT_OMENS_PER_WEEK = 4;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String instance;
    @Nullable private Boolean enabled;
    @Nullable private Share share;
    @Nullable private Area area;
    @Nullable private Run run;
    @Nullable private Complete complete;
    @Nullable private Rewards rewards;
    @Nullable private EntryCost entryCost;
    @Nullable private Copy copy;
    @Nullable private String difficulty;
    @Nullable private Tier tier;
    @Nullable private Omens omens;
    @Nullable private Seed seed;
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
                    + "saves nothing, or ZiggfreedCommon_Dungeon_Generated for a dungeon with an Area.Layout. A "
                    + "template that pins its world with an InstanceKey cannot be copied, and one with a portal timer "
                    + "or world events cannot be shared by several parties.").add()
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
            .documentation("What one party's area is: the prefab pasted there or the layout plan its rooms are "
                    + "generated from, how far apart areas sit, and where an arriving player lands.").add()
            .appendInherited(new KeyedCodec<>("Run", Run.CODEC, false),
                    (a, v) -> a.run = v, a -> a.run, (a, p) -> a.run = p.run)
            .documentation("How a party's run ends besides finishing it.").add()
            .appendInherited(new KeyedCodec<>("Complete", Complete.CODEC, false),
                    (a, v) -> a.complete = v, a -> a.complete, (a, p) -> a.complete = p.complete)
            .documentation("What finishes a party's run, clearing it: every leaf written here must hold (the "
                    + "bosses fallen, the objective met and the survive timer run out), and the run clears the "
                    + "moment the last of them does. Unauthored, nothing clears a run: it ends only by failing "
                    + "(its time limit, every member leaving, or a wipe where FailOnWipe is set).").add()
            .appendInherited(new KeyedCodec<>("Rewards", Rewards.CODEC, false),
                    (a, v) -> a.rewards = v, a -> a.rewards, (a, p) -> a.rewards = p.rewards)
            .documentation("What each member present at a clear earns: one Claim they collect on the results "
                    + "screen, or later on the Claims tab. Unauthored, a clear pays nothing.").add()
            .appendInherited(new KeyedCodec<>("EntryCost", EntryCost.CODEC, false),
                    (a, v) -> a.entryCost = v, a -> a.entryCost, (a, p) -> a.entryCost = p.entryCost)
            .documentation("What going in costs: items taken from the player who opens an area for their party, "
                    + "on every way in a player takes (a block, a character, a conversation). A partymate who "
                    + "follows into the area the party already holds pays nothing, a player who cannot pay is "
                    + "refused and keeps everything, and the admin's /zigdungeon enter never pays. A player "
                    + "holding one of its Keys pays nothing and keeps the key. Unauthored, going in is free.").add()
            .appendInherited(new KeyedCodec<>("Copy", Copy.CODEC, false),
                    (a, v) -> a.copy = v, a -> a.copy, (a, p) -> a.copy = p.copy)
            .documentation("How long a copy stays open.").add()
            .appendInherited(new KeyedCodec<>("Difficulty", Codec.STRING, false),
                    (a, v) -> a.difficulty = v, a -> a.difficulty, (a, p) -> a.difficulty = p.difficulty)
            .metadata(EditorSchema.defaultValue(""))
            .documentation("A label for a harder version of a dungeon, such as Hard, which its runs are counted "
                    + "under. Unauthored or empty means normal.").add()
            .appendInherited(new KeyedCodec<>("Tier", Tier.CODEC, false),
                    (a, v) -> a.tier = v, a -> a.tier, (a, p) -> a.tier = p.tier)
            .documentation("Makes this file one tier of a dungeon: a file that names the tier below as its Parent "
                    + "and sets its own Difficulty, layout plan, loot and Omens.Count, so each tier keeps its own "
                    + "runs, daily rewards and Records line. A gate naming any tier shows a party the tiers it may "
                    + "pick. Unauthored, the dungeon is a tier of rank 1 that is always open.").add()
            .appendInherited(new KeyedCodec<>("Omens", Omens.CODEC, false),
                    (a, v) -> a.omens = v, a -> a.omens, (a, p) -> a.omens = p.omens)
            .documentation("The omens this tier meets: each week the pool is put in a new order, the same for every "
                    + "tier sharing it, and the tier meets the first Count of it, so every run of the tier that week "
                    + "meets the same omens and a higher tier meets every omen of a lower one. The gate lists each "
                    + "tier's omens and a player arriving reads them. Unauthored, runs meet no omens.").add()
            .appendInherited(new KeyedCodec<>("Seed", Seed.CODEC, false),
                    (a, v) -> a.seed = v, a -> a.seed, (a, p) -> a.seed = p.seed)
            .documentation("Where a run's seed comes from, which picks its rooms and their variants. Unauthored, "
                    + "every run takes a fresh random seed.").add()
            .appendInherited(new KeyedCodec<>("Text", Text.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What players read the dungeon as, and what they are told to do there, by "
                    + "localization key.").add(),
                    (a, v) -> a.season = v, a -> a.season)
            .build();

    public DungeonAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * The instance template every copy starts from, trimmed; when unauthored, zc's generated template for a dungeon
     * with an {@code Area.Layout}, else zc's void template.
     */
    @Nonnull
    public String templateId() {
        if (instance != null && !instance.isBlank()) {
            return instance.trim();
        }
        return generated() ? GENERATED_TEMPLATE : DEFAULT_TEMPLATE;
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
     * How many areas one copy really holds for a layout plan of size {@code bounds}: {@link #areasPerCopy()}, and for
     * a generated dungeon no more lanes than fit within the far limit at its lanes' spacing ({@link RunLanes#lanes}).
     */
    public int areasPerCopy(@Nonnull Cell bounds) {
        if (!generated()) {
            return areasPerCopy();
        }
        return RunLanes.lanes(areasPerCopy(), RunLanes.spacing(spacing(), bounds.x()), bounds.x());
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

    /**
     * {@code Area.Layout}, the layout plan ({@code DungeonLayouts}) a run's rooms are generated from, trimmed, or null
     * when the file names none and the dungeon pastes {@code Area.Prefab}.
     */
    @Nullable
    public String layoutId() {
        return blankToNull(area == null ? null : area.layout);
    }

    /** Whether the dungeon is generated: it names a layout plan, so each run's rooms are written by zc's generator. */
    public boolean generated() {
        return layoutId() != null;
    }

    /** The layout plan {@code Area.Layout} names as loaded now, or null when it names none or none is loaded. */
    @Nullable
    public LayoutPlan layoutPlan() {
        String plan = layoutId();
        return plan == null ? null : DungeonLayoutConfig.getInstance().plan(plan);
    }

    /** The size of the layout plan as loaded now; the plan's default bounds when it names none loaded. */
    @Nonnull
    public Cell layoutBounds() {
        LayoutPlan plan = layoutPlan();
        return plan == null ? LayoutPlan.DEFAULT_BOUNDS : plan.bounds();
    }

    /** {@code Area.RunStride} in blocks, as written; {@link RunLanes#DEFAULT_RUN_STRIDE} when unauthored. */
    public int runStride() {
        Integer v = area == null ? null : area.runStride;
        return v == null ? RunLanes.DEFAULT_RUN_STRIDE : v;
    }

    /** {@code Area.MaxRunsPerLane}, as written; {@link RunLanes#DEFAULT_MAX_RUNS_PER_LANE} when unauthored. */
    public int maxRunsPerLane() {
        Integer v = area == null ? null : area.maxRunsPerLane;
        return v == null ? RunLanes.DEFAULT_MAX_RUNS_PER_LANE : v;
    }

    /** {@code Area.EntryOffset}: where an arriving player lands, from the area's origin, in blocks; zero unauthored. */
    @Nonnull
    public Vec3 entryOffset() {
        Vec3 v = area == null ? null : area.entryOffset;
        return v == null ? Vec3.of(null, null, null) : v;
    }

    /** {@code Area.EntryYaw}: which way an arriving player faces, in degrees (0 faces north); 0 unauthored. */
    public float entryYaw() {
        Float v = area == null ? null : area.entryYaw;
        return v == null ? 0f : v;
    }

    /** Whether the file writes {@code Area.EntryYaw}; a generated run's arrival faces its first joined door unless it does. */
    public boolean entryYawAuthored() {
        return area != null && area.entryYaw != null;
    }

    /** {@code Area.IntroCamera}: the camera sequence an arriving player watches, or null for none. */
    @Nullable
    public String introCamera() {
        String v = area == null ? null : area.introCamera;
        return v == null || v.isBlank() ? null : v.trim();
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

    /** {@code Run.Endless}, or null when the dungeon is no endless wave run. */
    @Nullable
    public DungeonEndless endless() {
        return run == null ? null : run.endless;
    }

    /** Whether the dungeon is an endless wave run ({@code Run.Endless}). */
    public boolean isEndless() {
        return endless() != null;
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

    /**
     * {@code Complete.Objective}: the moment a member must meet in the area before a run clears, or null when the
     * file names none, or names one with no {@code Kind}, which nothing could ever meet.
     */
    @Nullable
    public DungeonObjective completeObjective() {
        Objective o = complete == null ? null : complete.objective;
        if (o == null || o.kind == null || o.kind.isBlank()) {
            return null;
        }
        return new DungeonObjective(o.kind.trim(), o.target == null ? "" : o.target.trim(),
                blankToNull(o.matchMode), o.qualifier == null ? null : o.qualifier.trim(),
                o.amount == null ? 1L : Math.max(1L, o.amount));
    }

    /** {@code Complete.SurviveTimer} in seconds, or null when the run has none (unauthored, zero or less). */
    @Nullable
    public Integer surviveTimerSeconds() {
        Integer v = complete == null ? null : complete.surviveTimer;
        return v == null || v <= 0 ? null : v;
    }

    /** Whether anything in {@code Complete} can clear a run: a boss, an objective or a survive timer. */
    public boolean hasClear() {
        return !completeBosses().isEmpty() || completeObjective() != null || surviveTimerSeconds() != null;
    }

    /** {@code Rewards.Lootable}, the loot table a clear rolls for each member, trimmed, or null when none. */
    @Nullable
    public String rewardsLootable() {
        return blankToNull(rewards == null ? null : rewards.lootable);
    }

    /** {@code Rewards.Diminish} as written, or null: every clear pays in full. */
    @Nullable
    public DiminishSpec rewardsDiminish() {
        return rewards == null ? null : rewards.diminish;
    }

    /** {@code Rewards.InChest}: whether a clear's Claim goes into the clearing fight's chest; false unauthored. */
    public boolean rewardsInChest() {
        return rewards != null && Boolean.TRUE.equals(rewards.inChest);
    }

    /**
     * {@code EntryCost.Items}: what opening an area costs, each item id trimmed with its count (unauthored or less
     * than 1 means 1), an entry naming no item dropped and repeats of one id added together, in the order written;
     * empty when going in is free.
     */
    @Nonnull
    public List<DungeonEntryCost.Item> entryCostItems() {
        EntryItem[] raw = entryCost == null ? null : entryCost.items;
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (EntryItem entry : raw) {
            String item = entry == null ? null : blankToNull(entry.item);
            if (item != null) {
                counts.merge(item, entry.countOrOne(), Integer::sum);
            }
        }
        List<DungeonEntryCost.Item> out = new ArrayList<>(counts.size());
        counts.forEach((item, count) -> out.add(new DungeonEntryCost.Item(item, count)));
        return List.copyOf(out);
    }

    /**
     * {@code EntryCost.Keys}: the item ids any one of which, held, opens an area without anything being spent
     * ({@link DungeonEntryCost#heldKey}), each trimmed, blanks and repeats dropped, in the order written; empty when
     * the file names none. Read only where {@link #entryCostItems()} is not empty: a free way in needs no key.
     */
    @Nonnull
    public List<String> entryCostKeys() {
        String[] raw = entryCost == null ? null : entryCost.keys;
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String key : raw) {
            String id = blankToNull(key);
            if (id != null) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    /** {@code EntryCost.HintKey}, trimmed, or null when the file names none. */
    @Nullable
    public String entryCostHintKey() {
        return blankToNull(entryCost == null ? null : entryCost.hintKey);
    }

    /**
     * {@code EntryCost.Confirm}, unauthored true: a player who would pay the cost first sees the confirm screen
     * ({@link DungeonEntryPrompt}). Read only where a cost is set.
     */
    public boolean entryCostConfirm() {
        Boolean v = entryCost == null ? null : entryCost.confirm;
        return v == null || v;
    }

    /** {@code EntryCost.ConfirmKey}, trimmed, or null: the confirm screen's button then reads the library's Confirm. */
    @Nullable
    public String entryCostConfirmKey() {
        return blankToNull(entryCost == null ? null : entryCost.confirmKey);
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

    /** Whether the file writes {@code Tier}, itself or through its {@code Parent}: it is one tier of a ladder. */
    public boolean isTier() {
        return tier != null;
    }

    /** {@code Tier.Rank}, at least 1; 1 for a dungeon that writes no {@code Tier}. */
    public int tierRank() {
        Integer v = tier == null ? null : tier.rank;
        return v == null ? 1 : Math.max(1, v);
    }

    /**
     * {@code Tier.Opens}, trimmed: the dungeon whose clear opens this tier; null when it names none, and then the tier
     * is open to everyone.
     */
    @Nullable
    public String tierOpens() {
        return blankToNull(tier == null ? null : tier.opens);
    }

    /** {@code Omens.Count}: how many of the week's set a run draws, zero or more; zero unauthored. */
    public int omenCount() {
        Integer v = omens == null ? null : omens.count;
        return v == null ? 0 : Math.max(0, v);
    }

    /**
     * {@code Omens.Pool}: the omen ids the week's set is drawn from, trimmed, blanks and repeats dropped, in the order
     * written; empty when the file names none, and then every loaded omen is in the pool.
     */
    @Nonnull
    public List<String> omenPool() {
        String[] raw = omens == null ? null : omens.pool;
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String omen : raw) {
            String id = blankToNull(omen);
            if (id != null) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    /** {@code Omens.PerWeek}: how many omens make the week's set; {@link #DEFAULT_OMENS_PER_WEEK} unauthored. */
    public int omensPerWeek() {
        Integer v = omens == null ? null : omens.perWeek;
        return v == null ? DEFAULT_OMENS_PER_WEEK : Math.max(1, v);
    }

    /** {@code Seed.Daily}, unauthored false: every run that day shares one seed ({@link RunSeeds#daily}). */
    public boolean seedDaily() {
        return seed != null && Boolean.TRUE.equals(seed.daily);
    }

    /** The id this file names as its {@code Parent}, trimmed, or null when it names none or it cannot be read. */
    @Nullable
    public String parentId() {
        Object key = data == null ? null : data.getParentKey();
        return key == null ? null : blankToNull(key.toString());
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

    /**
     * {@code Text.GoalKey}, trimmed, or null when the file names none: then a player arriving reads lines built
     * from {@code Complete} instead ({@link DungeonGoals}).
     */
    @Nullable
    public String goalKey() {
        return blankToNull(text == null ? null : text.goalKey);
    }

    /**
     * {@code Text.ChestKey}, trimmed, or null when the file names none: then the banner of a clear whose Claim went
     * into the chest ({@code Rewards.InChest}) reads the library's own line.
     */
    @Nullable
    public String chestKey() {
        return blankToNull(text == null ? null : text.chestKey);
    }

    /**
     * What the router needs of this dungeon, filed under {@code dungeonId} (the fold's lower-cased id); a generated
     * dungeon's lanes are sized for its layout plan as loaded now.
     */
    @Nonnull
    public CopyRouter.Rules routerRules(@Nonnull String dungeonId) {
        return routerRules(dungeonId, generated() ? layoutBounds() : LayoutPlan.DEFAULT_BOUNDS);
    }

    /**
     * What the router needs of this dungeon, filed under {@code dungeonId}; {@code bounds} is the size of a generated
     * dungeon's layout plan, which caps how many lanes a copy holds and how many runs one lane serves
     * ({@link RunLanes#lanes}, {@link RunLanes#maxRuns}). A pasted dungeon's areas serve parties without limit.
     */
    @Nonnull
    public CopyRouter.Rules routerRules(@Nonnull String dungeonId, @Nonnull Cell bounds) {
        int maxRuns = 0;
        if (generated()) {
            maxRuns = RunLanes.maxRuns(maxRunsPerLane(), RunLanes.runStride(runStride(), bounds.z()), bounds.z());
        }
        return new CopyRouter.Rules(dungeonId, areasPerCopy(bounds), maxPlayers(), partyLocked(),
                emptyCloseSeconds() * 1000L, maxRuns);
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
                        + "a world of its own. A dungeon with an Area.Layout holds no more areas than fit within "
                        + "131072 blocks of the world's centre at its spacing, and the audit says when it holds "
                        + "fewer.").add()
                .build();

        public Share() {
        }
    }

    // ==================== Area ====================

    /** What one party's area is. */
    public static final class Area {

        @Nullable protected String prefab;
        @Nullable protected String layout;
        @Nullable protected Integer spacing;
        @Nullable protected Integer runStride;
        @Nullable protected Integer maxRunsPerLane;
        @Nullable protected Vec3 entryOffset;
        @Nullable protected Float entryYaw;
        @Nullable protected String introCamera;

        public static final BuilderCodec<Area> CODEC = BuilderCodec.builder(Area.class, Area::new)
                .appendInherited(new KeyedCodec<>("Prefab", Codec.STRING, false),
                        (o, v) -> o.prefab = v, o -> o.prefab, (o, p) -> o.prefab = p.prefab)
                .documentation("The prefab pasted into each area, as its path under Server/Prefabs/ with its "
                        + "extension, such as MyPack/Crypt/Crypt_Main.prefab.json. A path no loaded pack ships "
                        + "is reported, and the area stays empty. A dungeon with a Layout never pastes it.").add()
                .appendInherited(new KeyedCodec<>("Layout", Codec.STRING, false),
                        (o, v) -> o.layout = v, o -> o.layout, (o, p) -> o.layout = p.layout)
                .metadata(EditorSchema.assetRef(DungeonLayoutAsset.class))
                .documentation("A layout plan (a file under Server/ZiggfreedCommon/DungeonLayouts/) each run's rooms "
                        + "are generated from: every party gets a fresh layout from its own seed, its rooms written "
                        + "into the world as the ground around the party loads. A dungeon with a Layout ignores "
                        + "Prefab. Its plan's Bounds.Y stands from row 64, so keep it at 256 or less, or its top "
                        + "rows pass the world's top. Unauthored, the dungeon pastes Prefab.").add()
                .appendInherited(new KeyedCodec<>("Spacing", Codec.INTEGER, false),
                        (o, v) -> o.spacing = v, o -> o.spacing, (o, p) -> o.spacing = p.spacing)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_SPACING))
                .documentation("How far apart two areas of one copy sit, in blocks. It must be at least twice "
                        + "the server's view distance, so no party sees another. Unauthored means 2048, enough "
                        + "for any view distance. A dungeon with a Layout spaces its areas at least its plan's "
                        + "width plus 1056 apart.").add()
                .appendInherited(new KeyedCodec<>("RunStride", Codec.INTEGER, false),
                        (o, v) -> o.runStride = v, o -> o.runStride, (o, p) -> o.runStride = p.runStride)
                .metadata(EditorSchema.defaultValue((long) RunLanes.DEFAULT_RUN_STRIDE))
                .documentation("For a dungeon with a Layout: how far apart, in blocks, two parties' runs in one area "
                        + "stand, each on fresh ground further along the area. Unauthored means 2048; it is never "
                        + "less than the plan's depth plus 1056, so no party sees the last one's rooms.").add()
                .appendInherited(new KeyedCodec<>("MaxRunsPerLane", Codec.INTEGER, false),
                        (o, v) -> o.maxRunsPerLane = v, o -> o.maxRunsPerLane,
                        (o, p) -> o.maxRunsPerLane = p.maxRunsPerLane)
                .metadata(EditorSchema.defaultValue((long) RunLanes.DEFAULT_MAX_RUNS_PER_LANE))
                .documentation("For a dungeon with a Layout: how many parties one area of a copy serves before it "
                        + "takes no more, so the copy closes once it empties and a fresh one opens. Unauthored, or "
                        + "less than 1, means 32; it is capped so the furthest run stays within 131072 blocks of "
                        + "the world's centre.").add()
                .appendInherited(new KeyedCodec<>("EntryOffset", Vec3.CODEC, false),
                        (o, v) -> o.entryOffset = v, o -> o.entryOffset, (o, p) -> o.entryOffset = p.entryOffset)
                .documentation("Where an arriving player lands, in blocks from the spot the prefab is pasted "
                        + "at (with a Layout, from the entry room's anchor, turned with the room). Unauthored means "
                        + "that spot itself.").add()
                .appendInherited(new KeyedCodec<>("EntryYaw", Codec.FLOAT, false),
                        (o, v) -> o.entryYaw = v, o -> o.entryYaw, (o, p) -> o.entryYaw = p.entryYaw)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("Which way an arriving player faces, in degrees: 0 faces north (toward negative Z), "
                        + "90 faces west (negative X), 180 faces south (positive Z) and 270 faces east. Pitch is "
                        + "always level. With a Layout it turns with the entry room. Unauthored means 0, how a "
                        + "player arrives with no facing written; with a Layout, facing the entry room's first "
                        + "door that leads on.").add()
                .appendInherited(new KeyedCodec<>("IntroCamera", Codec.STRING, false),
                        (o, v) -> o.introCamera = v, o -> o.introCamera, (o, p) -> o.introCamera = p.introCamera)
                .documentation("A camera sequence (Server/ZiggfreedCommon/CameraSequences/<Id>.json) each player "
                        + "watches as they arrive, its positions measured from the spot the prefab is pasted at. "
                        + "Their movement is held while it plays and the view returns to them at its end. "
                        + "Unauthored means no intro.").add()
                .build();

        public Area() {
        }
    }

    // ==================== Run ====================

    /** How a party's run ends besides finishing it. */
    public static final class Run {

        @Nullable protected Integer timeLimitSeconds;
        @Nullable protected Boolean failOnWipe;
        @Nullable protected DungeonEndless endless;

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
                .appendInherited(new KeyedCodec<>("Endless", DungeonEndless.CODEC, false),
                        (o, v) -> o.endless = v, o -> o.endless, (o, p) -> o.endless = p.endless)
                .documentation("Makes the dungeon an endless wave run: one run per visit, waves that never run out "
                        + "and grow tougher, a boss wave every few, and a chest that grows with every wave cleared. "
                        + "Its run lasts until every member in the area is down at once (as FailOnWipe reads it, "
                        + "whatever FailOnWipe says), its time limit passes, or everyone leaves, and every way it "
                        + "ends pays each member what they banked; nothing in Complete clears it. The prefab's "
                        + "encounter script is a Variant of Zc_Encounter_Endless. A dungeon with an Area.Layout cannot "
                        + "be endless yet: its audit refuses the pair and nobody can enter. Unauthored, the dungeon "
                        + "runs as Complete says.").add()
                .build();

        public Run() {
        }
    }

    // ==================== Complete ====================

    /** What finishes a party's run, clearing it. */
    public static final class Complete {

        @Nullable protected String[] bosses;
        @Nullable protected Boolean allBosses;
        @Nullable protected Objective objective;
        @Nullable protected Integer surviveTimer;

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
                .appendInherited(new KeyedCodec<>("Objective", Objective.CODEC, false),
                        (o, v) -> o.objective = v, o -> o.objective, (o, p) -> o.objective = p.objective)
                .documentation("Something a member must do inside the area before the run clears, written the "
                        + "way a quest step is (Kind, Target, MatchMode, Qualifier, Amount): picking up a relic, "
                        + "killing a number of a mob, talking to someone. Every member's moments count toward one "
                        + "total for the party. Unauthored, or with no Kind, no objective is needed.").add()
                .appendInherited(new KeyedCodec<>("SurviveTimer", Codec.INTEGER, false),
                        (o, v) -> o.surviveTimer = v, o -> o.surviveTimer, (o, p) -> o.surviveTimer = p.surviveTimer)
                .documentation("How long a run must last, in seconds from its first arrival, before it clears: "
                        + "the party survives that long without the run failing. Keep it under "
                        + "Run.TimeLimitSeconds, or the time limit fails the run first. Unauthored, or zero or "
                        + "less, means no timer.").add()
                .build();

        public Complete() {
        }
    }

    // ==================== Objective ====================

    /** Something a member must do inside the area, written the way a quest step is. */
    public static final class Objective {

        @Nullable protected String kind;
        @Nullable protected String target;
        @Nullable protected String matchMode;
        @Nullable protected String qualifier;
        @Nullable protected Long amount;

        public static final BuilderCodec<Objective> CODEC = BuilderCodec.builder(Objective.class, Objective::new)
                .appendInherited(new KeyedCodec<>("Kind", Codec.STRING, false),
                        (o, v) -> o.kind = v, o -> o.kind, (o, p) -> o.kind = p.kind)
                .documentation("Which kind of moment counts, the same kinds a quest step names, such as "
                        + "KILL_ENTITY, PICKUP_ITEM or BREAK_BLOCK. Unauthored, no objective is needed.").add()
                .appendInherited(new KeyedCodec<>("Target", Codec.STRING, false),
                        (o, v) -> o.target = v, o -> o.target, (o, p) -> o.target = p.target)
                .documentation("Which one specifically (an entity id, an item id, a block id). Leave it out for "
                        + "any.").add()
                .appendInherited(new KeyedCodec<>("MatchMode", Codec.STRING, false),
                        (o, v) -> o.matchMode = v, o -> o.matchMode, (o, p) -> o.matchMode = p.matchMode)
                .metadata(EditorSchema.oneOfDocumented(
                        "EXACT", "The whole identifier must equal the target",
                        "CONTAINS", "The identifier must contain the target anywhere inside it",
                        "PREFIX", "The identifier must start with the target"))
                .metadata(EditorSchema.defaultValue("CONTAINS"))
                .documentation("How Target is compared: EXACT, CONTAINS or PREFIX. Unauthored means CONTAINS, "
                        + "as a quest step reads it.").add()
                .appendInherited(new KeyedCodec<>("Qualifier", Codec.STRING, false),
                        (o, v) -> o.qualifier = v, o -> o.qualifier, (o, p) -> o.qualifier = p.qualifier)
                .documentation("The kind's own secondary filter, compared whole, as a quest step reads it. "
                        + "Unauthored means any.").add()
                .appendInherited(new KeyedCodec<>("Amount", Codec.LONG, false),
                        (o, v) -> o.amount = v, o -> o.amount, (o, p) -> o.amount = p.amount)
                .metadata(EditorSchema.defaultValue(1L))
                .documentation("How many the party needs in all. Unauthored, or less than 1, means 1.").add()
                .build();

        public Objective() {
        }
    }

    // ==================== Rewards ====================

    /** What each member present at a clear earns. */
    public static final class Rewards {

        @Nullable protected String lootable;
        @Nullable protected DiminishSpec diminish;
        @Nullable protected Boolean inChest;

        public static final BuilderCodec<Rewards> CODEC = BuilderCodec.builder(Rewards.class, Rewards::new)
                .appendInherited(new KeyedCodec<>("Lootable", Codec.STRING, false),
                        (o, v) -> o.lootable = v, o -> o.lootable, (o, p) -> o.lootable = p.lootable)
                .metadata(EditorSchema.assetRef(LootableAsset.class))
                .documentation("The loot table a clear rolls once for each member present, by id (a file under "
                        + "Server/ZiggfreedCommon/Lootables/). What it lands is one Claim the member collects on "
                        + "the results screen or the Claims tab. Unauthored, a clear pays nothing.").add()
                .appendInherited(new KeyedCodec<>("Diminish", DiminishSpec.CODEC, false),
                        (o, v) -> o.diminish = v, o -> o.diminish, (o, p) -> o.diminish = p.diminish)
                .documentation("How much a repeat clear earns in one window, counted per player for this dungeon "
                        + "and difficulty: Steps [1, 0.5] with Window Daily pays half for every clear after the "
                        + "first that day. Unauthored, every clear pays in full.").add()
                .appendInherited(new KeyedCodec<>("InChest", Codec.BOOLEAN, false),
                        (o, v) -> o.inChest = v, o -> o.inChest, (o, p) -> o.inChest = p.inChest)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("Whether a clear won by a boss fight that leaves a spoils chest puts each credited "
                        + "member's Claim in that chest: no results screen opens, the member sees a banner naming "
                        + "the clear with Text.ChestKey's line under it, and opening the chest offers the Claim with "
                        + "the fight's own. Unauthored means false: the results screen offers it. A member the "
                        + "fight's chest does not hold a share for sees the results screen either way.").add()
                .build();

        public Rewards() {
        }
    }

    // ==================== EntryCost ====================

    /** What going in costs, the keys that open it for nothing, and the line a player who cannot pay reads. */
    public static final class EntryCost {

        @Nullable protected EntryItem[] items;
        @Nullable protected String[] keys;
        @Nullable protected String hintKey;
        @Nullable protected Boolean confirm;
        @Nullable protected String confirmKey;

        public static final BuilderCodec<EntryCost> CODEC = BuilderCodec.builder(EntryCost.class, EntryCost::new)
                .appendInherited(new KeyedCodec<>("Items", new ArrayCodec<>(EntryItem.CODEC, EntryItem[]::new), false),
                        (o, v) -> o.items = v, o -> o.items, (o, p) -> o.items = p.items)
                .documentation("The items taken on the way in, each with its count, from the bag, storage and "
                        + "hotbar (never what is worn) of the player who opens an area for their party; one who holds "
                        + "too few of any is refused, with a line naming the item and the count, and keeps all of "
                        + "them. This is ONE leaf: authoring it replaces an inherited list whole. Unauthored or empty, "
                        + "going in is free.").add()
                .appendInherited(new KeyedCodec<>("Keys", Codec.STRING_ARRAY, false),
                        (o, v) -> o.keys = v, o -> o.keys, (o, p) -> o.keys = p.keys)
                .metadata(EditorSchema.assetRef(Item.class))
                .documentation("Items that open the way without being spent, by item id, written exactly as each "
                        + "item's file is named: a player who opens an area for their party while holding any one of "
                        + "them in the bag, storage or hotbar (never what is worn) goes in, keeps the key, and pays "
                        + "nothing from Items, even holding those too. The confirm screen then shows the key and says "
                        + "it is not used up. Keys only waive Items: with Items unauthored or empty, going in is free "
                        + "and Keys do nothing. This is ONE leaf: authoring it replaces an inherited list whole. "
                        + "Unauthored or empty, only Items pays the way in.").add()
                .appendInherited(new KeyedCodec<>("HintKey", Codec.STRING, false),
                        (o, v) -> o.hintKey = v, o -> o.hintKey, (o, p) -> o.hintKey = p.hintKey)
                .documentation("The localization key of a line saying where the items are found, such as where "
                        + "they are sold: the confirm screen shows it to a player who holds too few, and a refusal "
                        + "line shows it under the item and the count. Unauthored, they name the item and the count "
                        + "alone.").add()
                .appendInherited(new KeyedCodec<>("Confirm", Codec.BOOLEAN, false),
                        (o, v) -> o.confirm = v, o -> o.confirm, (o, p) -> o.confirm = p.confirm)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether a player who would pay first sees a confirm screen, built like the game's "
                        + "portal summon screen: the dungeon's name and description, each item it takes with how "
                        + "many they hold and how many it needs (or, holding one of Keys, that key alone, kept), and "
                        + "a button that spends the cost and sends them in "
                        + "with their party, with the game's portal sound and sparkle; closing it spends nothing. "
                        + "With too few, the button is off and the HintKey line shows. Unauthored means true; false "
                        + "takes the cost on the way in with no screen.").add()
                .appendInherited(new KeyedCodec<>("ConfirmKey", Codec.STRING, false),
                        (o, v) -> o.confirmKey = v, o -> o.confirmKey, (o, p) -> o.confirmKey = p.confirmKey)
                .documentation("The localization key of the words on the confirm screen's button, such as \"Light "
                        + "the Candle\". Unauthored, the button reads Confirm.").add()
                .build();

        public EntryCost() {
        }
    }

    /** One item the way in takes, and how many. */
    public static final class EntryItem {

        @Nullable protected String item;
        @Nullable protected Integer count;

        public static final BuilderCodec<EntryItem> CODEC = BuilderCodec.builder(EntryItem.class, EntryItem::new)
                .appendInherited(new KeyedCodec<>("Item", Codec.STRING, false),
                        (o, v) -> o.item = v, o -> o.item, (o, p) -> o.item = p.item)
                .metadata(EditorSchema.assetRef(Item.class))
                .documentation("The item id taken, written exactly as the item's file is named. An id no loaded "
                        + "item answers to is reported, and nobody could pay it.").add()
                .appendInherited(new KeyedCodec<>("Count", Codec.INTEGER, false),
                        (o, v) -> o.count = v, o -> o.count, (o, p) -> o.count = p.count)
                .metadata(EditorSchema.defaultValue(1L))
                .documentation("How many. Unauthored, or less than 1, means 1.").add()
                .build();

        public EntryItem() {
        }

        /** {@code Count}, at least 1. */
        int countOrOne() {
            return count == null || count < 1 ? 1 : count;
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

    // ==================== Tier ====================

    /** Which tier of a dungeon this file is, and what opens it. */
    public static final class Tier {

        @Nullable protected Integer rank;
        @Nullable protected String opens;

        public static final BuilderCodec<Tier> CODEC = BuilderCodec.builder(Tier.class, Tier::new)
                .appendInherited(new KeyedCodec<>("Rank", Codec.INTEGER, false),
                        (o, v) -> o.rank = v, o -> o.rank, (o, p) -> o.rank = p.rank)
                .metadata(EditorSchema.defaultValue(1L))
                .documentation("Which tier this is, from 1: the gate lists tiers from the lowest. It only orders them; "
                        + "what opens a tier is its own Opens. Unauthored, or less than 1, means 1.").add()
                .appendInherited(new KeyedCodec<>("Opens", Codec.STRING, false),
                        (o, v) -> o.opens = v, o -> o.opens, (o, p) -> o.opens = p.opens)
                .metadata(EditorSchema.assetRef(DungeonAsset.class))
                .documentation("The dungeon whose clear opens this tier, by its id (a file under "
                        + "Server/ZiggfreedCommon/Dungeons/), as a rule the tier below. A player who has cleared it once "
                        + "has this tier open for good, read from their Dungeon_Runs_Completed record, and a party may go "
                        + "in only when every member standing with them has. Unauthored, the tier is open to "
                        + "everyone.").add()
                .build();

        public Tier() {
        }
    }

    // ==================== Omens ====================

    /** The omens a run meets. */
    public static final class Omens {

        @Nullable protected Integer count;
        @Nullable protected String[] pool;
        @Nullable protected Integer perWeek;

        public static final BuilderCodec<Omens> CODEC = BuilderCodec.builder(Omens.class, Omens::new)
                .appendInherited(new KeyedCodec<>("Count", Codec.INTEGER, false),
                        (o, v) -> o.count = v, o -> o.count, (o, p) -> o.count = p.count)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("How many of the week's omens this tier meets: the first Count of the week's order, so "
                        + "a higher tier with a larger Count meets every omen a lower one does, and more. Unauthored, or "
                        + "0, means none.").add()
                .appendInherited(new KeyedCodec<>("Pool", Codec.STRING_ARRAY, false),
                        (o, v) -> o.pool = v, o -> o.pool, (o, p) -> o.pool = p.pool)
                .metadata(EditorSchema.assetRef(DungeonOmenAsset.class))
                .documentation("The omens the week's set is drawn from, by id (files under "
                        + "Server/ZiggfreedCommon/DungeonOmens/). Tiers sharing a pool share the week's set. This is ONE "
                        + "leaf: authoring it replaces an inherited list whole. Unauthored, every loaded omen.").add()
                .appendInherited(new KeyedCodec<>("PerWeek", Codec.INTEGER, false),
                        (o, v) -> o.perWeek = v, o -> o.perWeek, (o, p) -> o.perWeek = p.perWeek)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_OMENS_PER_WEEK))
                .documentation("How many omens make the week's set, drawn afresh every Monday at 00:00 UTC. Unauthored "
                        + "means 4; a pool no bigger than this is the whole set every week, in a new order, so the "
                        + "tiers' omens still change.").add()
                .build();

        public Omens() {
        }
    }

    // ==================== Seed ====================

    /** Where a run's seed comes from. */
    public static final class Seed {

        @Nullable protected Boolean daily;

        public static final BuilderCodec<Seed> CODEC = BuilderCodec.builder(Seed.class, Seed::new)
                .appendInherited(new KeyedCodec<>("Daily", Codec.BOOLEAN, false),
                        (o, v) -> o.daily = v, o -> o.daily, (o, p) -> o.daily = p.daily)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("Whether every run that day shares one seed, made from the date and this file's id, so "
                        + "every party gets the same rooms in the same order and the same omens, and a new layout "
                        + "comes at midnight UTC. Write it on a child file of the normal dungeon (the daily), so its "
                        + "runs, rewards and Records line are its own and its clear times rank on Records' Today tab. "
                        + "Unauthored means false: every run takes a fresh random seed.").add()
                .build();

        public Seed() {
        }
    }

    // ==================== Text ====================

    /** What players read the dungeon as, and what they are told to do there. */
    public static final class Text {

        @Nullable protected String nameKey;
        @Nullable protected String descriptionKey;
        @Nullable protected String goalKey;
        @Nullable protected String chestKey;

        public static final BuilderCodec<Text> CODEC = BuilderCodec.builder(Text.class, Text::new)
                .appendInherited(new KeyedCodec<>("NameKey", Codec.STRING, false),
                        (o, v) -> o.nameKey = v, o -> o.nameKey, (o, p) -> o.nameKey = p.nameKey)
                .documentation("The localization key the dungeon's name reads from. Unauthored shows its id.")
                .add()
                .appendInherited(new KeyedCodec<>("DescriptionKey", Codec.STRING, false),
                        (o, v) -> o.descriptionKey = v, o -> o.descriptionKey,
                        (o, p) -> o.descriptionKey = p.descriptionKey)
                .documentation("The localization key of a line or two saying what the dungeon is.").add()
                .appendInherited(new KeyedCodec<>("GoalKey", Codec.STRING, false),
                        (o, v) -> o.goalKey = v, o -> o.goalKey, (o, p) -> o.goalKey = p.goalKey)
                .documentation("The localization key of a line or two telling a player what to do there, such as "
                        + "which boss to beat and how. Each player reads it as they arrive in an area while its run "
                        + "is going, with the run's time limit and wipe rule under it. Unauthored, the line is built "
                        + "from Complete: defeat the boss (or every boss), finish the task, hold out for the "
                        + "survive timer.").add()
                .appendInherited(new KeyedCodec<>("ChestKey", Codec.STRING, false),
                        (o, v) -> o.chestKey = v, o -> o.chestKey, (o, p) -> o.chestKey = p.chestKey)
                .documentation("The localization key of the line under a clear's banner when Rewards.InChest puts "
                        + "the Claim in the boss's chest, saying where it waits. Unauthored, it reads the library's "
                        + "own line: the reward waits in the chest where the boss fell.").add()
                .build();

        public Text() {
        }
    }
}
