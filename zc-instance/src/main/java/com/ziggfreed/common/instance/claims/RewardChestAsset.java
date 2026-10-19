package com.ziggfreed.common.instance.claims;

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
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.diminish.DiminishSpec;

/**
 * One reward chest, as a file: {@code Server/ZiggfreedCommon/RewardChests/<Id>.json}, the filename its id. Every
 * reward source that stands a chest ({@link SpoilsChests#stand}: a won boss fight, a rare kill or break from a library
 * bonus row, the admin's {@code /zigclaims chest}) names one, and the chest that goes up is that file: how it looks,
 * who may open it, what it holds of its own, what it says, and when it goes. Every chest shows what it holds on one
 * page ({@link RewardChestPage}); its {@code View} group no longer changes that.
 *
 * <pre>{@code
 * {
 *   "Parent": "Zc_Reward_Chest",
 *   "Look":     { "Prop": "Furniture_Ancient_Chest_Small", "HintKey": "mypack.chest.hint" },
 *   "Open":     { "Credited": true },
 *   "Contents": { "Lootable": "MyPack_Trork_Hoard", "Diminish": { "Steps": [1, 0.5], "Window": "Daily" },
 *                 "TitleKey": "mypack.chest.trork.title" },
 *   "Banner":   { "TitleKey": "mypack.chest.trork.banner.title", "SubtitleKey": "mypack.chest.trork.banner",
 *                 "Major": false },
 *   "Text":     { "LeadKey": "mypack.chest.lead", "NoticeKey": "mypack.chest.notice" },
 *   "Despawn":  { "Lifetime": 300, "WhenEmptied": true }
 * }
 * }</pre>
 *
 * <p>Each group is an orthogonal knob and every leaf is nullable: unauthored means the default each getter names,
 * and under native {@code Parent} a child keeps every leaf it does not restate. The defaults ARE the library's boss
 * chest: a file that writes nothing stands exactly what a won fight left before this type existed, which is what the
 * shipped {@code Zc_Spoils_Chest} says out loud. {@code Zc_Reward_Chest} is the neutral chest a kill or a break
 * stands, with a banner. A server owner retunes a chest, or adds one, in {@code mods/ziggfreedcommon/reward-chests.json}
 * ({@link RewardChestOwnerLayers}).
 *
 * <p>{@code Open} lets only the players the source credited open it today; it is a group so that a later leaf (anyone
 * near, a party near) can sit beside {@code Credited} without changing a file.
 */
public final class RewardChestAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, RewardChestAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/RewardChests";

    /** The library's boss chest: what a won fight leaves when its binding row names no chest. */
    public static final String SPOILS_CHEST = EncounterBindingAsset.Spoils.DEFAULT_CHEST;

    /** The library's neutral reward chest, with a banner, which a kill or a break stands when its reward names none. */
    public static final String REWARD_CHEST = "Zc_Reward_Chest";

    /** The chest's look when a file names none: the base game's small dungeon chest. */
    public static final String DEFAULT_PROP = EncounterBindingAsset.Spoils.DEFAULT_PROP;

    /** How long a chest stands when a file names no lifetime: five minutes. */
    public static final int DEFAULT_LIFETIME_SECONDS = EncounterBindingAsset.Spoils.DEFAULT_LIFETIME_SECONDS;

    /** The prompt over a chest when a file names none. */
    public static final String DEFAULT_HINT_KEY = ClaimsText.PREFIX + "spoils.hint";

    /** The chest page's line when a file names none. */
    public static final String DEFAULT_LEAD_KEY = ClaimsText.PREFIX + "spoils.lead";

    /** The toast's line for a reward waiting in the chest when a file names none ({@code .named} for a titled one). */
    public static final String DEFAULT_NOTICE_KEY = ClaimsText.PREFIX + "notice.added.spoils";

    /** What a notice key's titled form adds to it: {@code <NoticeKey>.named}, its {@code {0}} the reward's title. */
    public static final String NAMED_SUFFIX = ".named";

    /** A chest that writes nothing: the structural default every getter reads, the library's boss chest. */
    static final RewardChestAsset BARE = new RewardChestAsset();

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Look look;
    @Nullable private Open open;
    @Nullable private Contents contents;
    @Nullable private View view;
    @Nullable private Banner banner;
    @Nullable private Text text;
    @Nullable private Despawn despawn;

    public static final AssetBuilderCodec<String, RewardChestAsset> CODEC = AssetBuilderCodec.builder(
                    RewardChestAsset.class,
                    RewardChestAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Look", Look.CODEC, false),
                    (a, v) -> a.look = v, a -> a.look, (a, p) -> a.look = p.look)
            .documentation("How the chest looks and what its prompt says.").add()
            .appendInherited(new KeyedCodec<>("Open", Open.CODEC, false),
                    (a, v) -> a.open = v, a -> a.open, (a, p) -> a.open = p.open)
            .documentation("Who may open the chest. Each player who opens it finds only their own share.").add()
            .appendInherited(new KeyedCodec<>("Contents", Contents.CODEC, false),
                    (a, v) -> a.contents = v, a -> a.contents, (a, p) -> a.contents = p.contents)
            .documentation("What the chest holds of its own, rolled for each player who may open it as it goes up, "
                    + "beside whatever its source pays into it (a boss fight's own rewards). Unauthored, it holds "
                    + "only what its source pays.").add()
            .appendInherited(new KeyedCodec<>("View", View.CODEC, false),
                    (a, v) -> a.view = v, a -> a.view, (a, p) -> a.view = p.view)
            .documentation("How opening the chest shows what it holds. Every chest opens one page with the "
                    + "player's whole share on it, what is not an item first and then the items, each taken with "
                    + "a click or all at once with Take All.").add()
            .appendInherited(new KeyedCodec<>("Banner", Banner.CODEC, false),
                    (a, v) -> a.banner = v, a -> a.banner, (a, p) -> a.banner = p.banner)
            .documentation("The banner each player with a share sees once as the chest goes up. Unauthored, none. "
                    + "A source that shows its own banner for the chest (a dungeon clear whose reward joins it) "
                    + "shows that one instead.").add()
            .appendInherited(new KeyedCodec<>("Text", Text.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("The chest's own words: the line on its page and the toast when a reward lands in it.")
            .add()
            .appendInherited(new KeyedCodec<>("Despawn", Despawn.CODEC, false),
                    (a, v) -> a.despawn = v, a -> a.despawn, (a, p) -> a.despawn = p.despawn)
            .documentation("When the chest goes. Whatever is left in it then still waits under Claims.").add()
            .build();

    public RewardChestAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    // ==================== the reads ====================

    /** {@code Look.Prop}: the block item the chest looks like, trimmed, else {@link #DEFAULT_PROP}. */
    @Nonnull
    public String prop() {
        return orDefault(look == null ? null : look.prop, DEFAULT_PROP);
    }

    /** {@code Look.HintKey}: the prompt over the chest, trimmed, else {@link #DEFAULT_HINT_KEY}. */
    @Nonnull
    public String hintKey() {
        return orDefault(look == null ? null : look.hintKey, DEFAULT_HINT_KEY);
    }

    /** {@code Open.Credited}: whether the players the source credited may open it; unauthored means yes. */
    public boolean openCredited() {
        Boolean v = open == null ? null : open.credited;
        return v == null || v;
    }

    /** {@code Contents.Lootable}: the loot table rolled for each opener as the chest goes up, or null for none. */
    @Nullable
    public String lootable() {
        return blankToNull(contents == null ? null : contents.lootable);
    }

    /** {@code Contents.Diminish} as written, or null: every chest pays its roll in full. */
    @Nullable
    public DiminishSpec diminish() {
        return contents == null ? null : contents.diminish;
    }

    /** {@code Contents.TitleKey}: the title of the Claim the roll writes, or null for the tab's own word. */
    @Nullable
    public String contentsTitleKey() {
        return blankToNull(contents == null ? null : contents.titleKey);
    }

    /**
     * {@code View.ItemsWindow} as the file writes it, unauthored true. It no longer changes the view: every chest
     * opens its one page ({@link RewardChestPage}), items and all, whichever way a file writes it.
     */
    public boolean itemsWindow() {
        Boolean v = view == null ? null : view.itemsWindow;
        return v == null || v;
    }

    /** {@code Banner.TitleKey}, trimmed, or null: no banner when the file names no title. */
    @Nullable
    public String bannerTitleKey() {
        return blankToNull(banner == null ? null : banner.titleKey);
    }

    /** {@code Banner.SubtitleKey}, trimmed, or null for a banner with no line under it. */
    @Nullable
    public String bannerSubtitleKey() {
        return blankToNull(banner == null ? null : banner.subtitleKey);
    }

    /** {@code Banner.Major}: the larger banner; unauthored means the smaller one. */
    public boolean bannerMajor() {
        return banner != null && Boolean.TRUE.equals(banner.major);
    }

    /** {@code Text.LeadKey}: the chest page's line, trimmed, else {@link #DEFAULT_LEAD_KEY}. */
    @Nonnull
    public String leadKey() {
        return orDefault(text == null ? null : text.leadKey, DEFAULT_LEAD_KEY);
    }

    /** {@code Text.NoticeKey}: the toast's line for a reward waiting in it, trimmed, else {@link #DEFAULT_NOTICE_KEY}. */
    @Nonnull
    public String noticeKey() {
        return orDefault(text == null ? null : text.noticeKey, DEFAULT_NOTICE_KEY);
    }

    /** {@code Despawn.Lifetime}: how many seconds it stands at most, when positive, else {@link #DEFAULT_LIFETIME_SECONDS}. */
    public int lifetimeSeconds() {
        Integer v = despawn == null ? null : despawn.lifetime;
        return v == null || v <= 0 ? DEFAULT_LIFETIME_SECONDS : v;
    }

    /** {@code Despawn.WhenEmptied}: whether it goes once its players hold nothing from it; unauthored means yes. */
    public boolean goesWhenEmptied() {
        Boolean v = despawn == null ? null : despawn.whenEmptied;
        return v == null || v;
    }

    @Nonnull
    private static String orDefault(@Nullable String value, @Nonnull String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ==================== Look ====================

    /** How the chest looks and what its prompt says. */
    public static final class Look {

        @Nullable protected String prop;
        @Nullable protected String hintKey;

        public static final BuilderCodec<Look> CODEC = BuilderCodec.builder(Look.class, Look::new)
                .appendInherited(new KeyedCodec<>("Prop", Codec.STRING, false),
                        (o, v) -> o.prop = v, o -> o.prop, (o, p) -> o.prop = p.prop)
                .metadata(EditorSchema.assetRef(Item.class))
                .metadata(EditorSchema.defaultValue(DEFAULT_PROP))
                .documentation("The block item whose look the chest takes, by id: any chest block works. An id that "
                        + "names no block item on this server falls back to the default, with a warning.").add()
                .appendInherited(new KeyedCodec<>("HintKey", Codec.STRING, false),
                        (o, v) -> o.hintKey = v, o -> o.hintKey, (o, p) -> o.hintKey = p.hintKey)
                .metadata(EditorSchema.defaultValue(DEFAULT_HINT_KEY))
                .documentation("The localization key of the prompt a player looking at the chest reads; the game "
                        + "fills {key} with the button to press.").add()
                .build();

        public Look() {
        }
    }

    // ==================== Open ====================

    /** Who may open the chest. */
    public static final class Open {

        @Nullable protected Boolean credited;

        public static final BuilderCodec<Open> CODEC = BuilderCodec.builder(Open.class, Open::new)
                .appendInherited(new KeyedCodec<>("Credited", Codec.BOOLEAN, false),
                        (o, v) -> o.credited = v, o -> o.credited, (o, p) -> o.credited = p.credited)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether the players its source credited (a boss fight's credited players, the player "
                        + "a kill or a break credited) may open the chest, each finding only their own share. "
                        + "Unauthored means true. Set false and nobody can, so no chest goes up.").add()
                .build();

        public Open() {
        }
    }

    // ==================== Contents ====================

    /** What the chest holds of its own. */
    public static final class Contents {

        @Nullable protected String lootable;
        @Nullable protected DiminishSpec diminish;
        @Nullable protected String titleKey;

        public static final BuilderCodec<Contents> CODEC = BuilderCodec.builder(Contents.class, Contents::new)
                .appendInherited(new KeyedCodec<>("Lootable", Codec.STRING, false),
                        (o, v) -> o.lootable = v, o -> o.lootable, (o, p) -> o.lootable = p.lootable)
                .metadata(EditorSchema.assetRef(LootableAsset.class))
                .documentation("The loot table rolled once for each player who may open the chest, as it goes up, by "
                        + "id (a file under Server/ZiggfreedCommon/Lootables/). What it lands is one Claim, found in "
                        + "the chest and under Claims. A roll that lands nothing writes nothing, and a chest nothing "
                        + "pays into never goes up. Unauthored, the chest holds only what its source pays.").add()
                .appendInherited(new KeyedCodec<>("Diminish", DiminishSpec.CODEC, false),
                        (o, v) -> o.diminish = v, o -> o.diminish, (o, p) -> o.diminish = p.diminish)
                .documentation("How much a repeat roll earns in one window, counted per player for this chest: Steps "
                        + "[1, 0.5] with Window Daily pays half for every chest after the first that day. Unauthored, "
                        + "every roll pays in full.").add()
                .appendInherited(new KeyedCodec<>("TitleKey", Codec.STRING, false),
                        (o, v) -> o.titleKey = v, o -> o.titleKey, (o, p) -> o.titleKey = p.titleKey)
                .documentation("The localization key of the title the rolled Claim reads, in the chest and under "
                        + "Claims. Unauthored, the tab's own word for a reward.").add()
                .build();

        public Contents() {
        }
    }

    // ==================== View ====================

    /** How opening the chest shows what it holds. */
    public static final class View {

        @Nullable protected Boolean itemsWindow;

        public static final BuilderCodec<View> CODEC = BuilderCodec.builder(View.class, View::new)
                .appendInherited(new KeyedCodec<>("ItemsWindow", Codec.BOOLEAN, false),
                        (o, v) -> o.itemsWindow = v, o -> o.itemsWindow, (o, p) -> o.itemsWindow = p.itemsWindow)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("No longer changes anything: every chest opens one page with the player's whole "
                        + "share on it, items and all, whether this is true or false. Kept so a file that writes "
                        + "it still reads.").add()
                .build();

        public View() {
        }
    }

    // ==================== Banner ====================

    /** The banner as the chest goes up. */
    public static final class Banner {

        @Nullable protected String titleKey;
        @Nullable protected String subtitleKey;
        @Nullable protected Boolean major;

        public static final BuilderCodec<Banner> CODEC = BuilderCodec.builder(Banner.class, Banner::new)
                .appendInherited(new KeyedCodec<>("TitleKey", Codec.STRING, false),
                        (o, v) -> o.titleKey = v, o -> o.titleKey, (o, p) -> o.titleKey = p.titleKey)
                .documentation("The localization key of the banner's title, {0} what left the chest (the boss, the "
                        + "mob, the block). Unauthored, no banner shows.").add()
                .appendInherited(new KeyedCodec<>("SubtitleKey", Codec.STRING, false),
                        (o, v) -> o.subtitleKey = v, o -> o.subtitleKey, (o, p) -> o.subtitleKey = p.subtitleKey)
                .documentation("The localization key of the line under the banner's title. Unauthored, none.").add()
                .appendInherited(new KeyedCodec<>("Major", Codec.BOOLEAN, false),
                        (o, v) -> o.major = v, o -> o.major, (o, p) -> o.major = p.major)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True shows the game's larger banner. Unauthored means false.").add()
                .build();

        public Banner() {
        }
    }

    // ==================== Text ====================

    /** The chest's own words. */
    public static final class Text {

        @Nullable protected String leadKey;
        @Nullable protected String noticeKey;

        public static final BuilderCodec<Text> CODEC = BuilderCodec.builder(Text.class, Text::new)
                .appendInherited(new KeyedCodec<>("LeadKey", Codec.STRING, false),
                        (o, v) -> o.leadKey = v, o -> o.leadKey, (o, p) -> o.leadKey = p.leadKey)
                .metadata(EditorSchema.defaultValue(DEFAULT_LEAD_KEY))
                .documentation("The localization key of the line under the chest page's title.").add()
                .appendInherited(new KeyedCodec<>("NoticeKey", Codec.STRING, false),
                        (o, v) -> o.noticeKey = v, o -> o.noticeKey, (o, p) -> o.noticeKey = p.noticeKey)
                .metadata(EditorSchema.defaultValue(DEFAULT_NOTICE_KEY))
                .documentation("The localization key of the toast when a reward lands in the chest, saying where it "
                        + "waits. Ship <NoticeKey>.named beside it, {0} the reward's own title, for a reward that "
                        + "has one.").add()
                .build();

        public Text() {
        }
    }

    // ==================== Despawn ====================

    /** When the chest goes. */
    public static final class Despawn {

        @Nullable protected Integer lifetime;
        @Nullable protected Boolean whenEmptied;

        public static final BuilderCodec<Despawn> CODEC = BuilderCodec.builder(Despawn.class, Despawn::new)
                .appendInherited(new KeyedCodec<>("Lifetime", Codec.INTEGER, false),
                        (o, v) -> o.lifetime = v, o -> o.lifetime, (o, p) -> o.lifetime = p.lifetime)
                .metadata(EditorSchema.defaultValue(DEFAULT_LIFETIME_SECONDS))
                .documentation("How many seconds the chest stands at most. Zero or less reads as the default.").add()
                .appendInherited(new KeyedCodec<>("WhenEmptied", Codec.BOOLEAN, false),
                        (o, v) -> o.whenEmptied = v, o -> o.whenEmptied, (o, p) -> o.whenEmptied = p.whenEmptied)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether the chest goes once every player it serves holds nothing more from it (taken "
                        + "from the chest or from Claims). False keeps it standing for its whole lifetime. "
                        + "Unauthored means true.").add()
                .build();

        public Despawn() {
        }
    }
}
