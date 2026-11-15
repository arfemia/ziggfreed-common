package com.ziggfreed.common.track.boost;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.asset.SeasonLeaf;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.season.SeasonGate;
import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.asset.TrackCategoryAsset;

/**
 * One boost, at {@code Server/ZiggfreedCommon/Boosts/<Id>.json}: a percent more on every gain a player earns on the
 * tracks it names, for as long as it runs. The FILE NAME is the id (matched without regard to case); the folders
 * are the author's own grouping.
 *
 * <pre>{@code
 * { "Text": { "TitleKey": "yourpack.boost.harvest" },
 *   "Icon": "Tool_Sickle_Iron",
 *   "Tracks": { "Categories": [ "Gathering" ] },
 *   "Percent": 50,
 *   "Scope": "Personal",
 *   "Seconds": 1800,
 *   "Stack": { "Max": 2, "Cooldown": 3600 },
 *   "Effect": "Yourpack_Boost_Harvest_Tile",
 *   "Season": "Harvest_Feast" }
 * }</pre>
 *
 * <p>A TIMED boost ({@code Seconds}) runs once something starts it: a boost item's {@code ZigBoost} interaction, a
 * {@code Boost} reward when its Claim is collected, {@code /zigboost start}, or the player themselves from a page
 * that lists it ({@code Reusable}, whose own {@code Requires} says who may). A start may name one track it lifts,
 * inside what the boost lifts (a token
 * pointed at one skill of a boost that lifts every skill). It shows a tile on the buff bar
 * while it runs and keeps running across a restart and while the player is away. A STANDING boost
 * ({@code Standing: true}) is never started: it lifts every gain on its tracks whenever it is switched on, in
 * season and its {@code Requires} passes, and stays off the bar (a bonus weekend). Every boost running on a track
 * adds up, held under the server's cap ({@code BoostSettings}), and the boosts together add to a mod's own bonus
 * rather than multiplying it ({@code Gain.Additive} on the track).
 *
 * <p>Every leaf is nullable and inherited through {@code Parent}; {@code Tracks} and {@code Stack} merge leaf by
 * leaf. A top-level {@code Requires} follows the mod gate (a plain mod-presence check keeps the file out where that
 * mod is missing) and asks the rest of the player when the boost would start and at each gain it would lift. The
 * server owner retunes any of it in {@code mods/ziggfreedcommon/boosts.json}.
 */
public final class BoostAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, BoostAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Boosts";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Boolean enabled;
    @Nullable protected ContentTextAsset text;
    @Nullable protected String icon;
    @Nullable protected Tracks tracks;
    @Nullable protected Double percent;
    @Nullable protected String scope;
    @Nullable protected Long seconds;
    @Nullable protected Boolean standing;
    @Nullable protected Reusable reusable;
    @Nullable protected Stack stack;
    @Nullable protected String effect;
    @Nullable protected GateSpec requires;
    @Nullable protected String season;

    /** The live part of the {@code Requires} block, lifted once per instance ({@link StoreGates#live}). */
    @Nullable private volatile GateSpec[] live;

    /** The lower-cased ids {@link #lifts} reads, built on first ask (a decoded file is never changed). */
    @Nullable private volatile Matcher matcher;

    public static final AssetBuilderCodec<String, BoostAsset> CODEC = SeasonLeaf.append(AssetBuilderCodec.builder(
                    BoostAsset.class,
                    BoostAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether this boost exists on this server; unauthored means true. False makes it "
                    + "absent: nothing can start it, a standing one stops, and a running one stops lifting and "
                    + "leaves the buff bar.").add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the boost is called (TitleKey) in the line a player reads when it starts and "
                    + "on a reward, as a localization key in your own lang file. Without a TitleKey it is named by "
                    + "what it lifts and by how much. FlavorKey words what it lifts (every skill) in place of the "
                    + "first track or category it names; a start that names one track reads as that track.").add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .metadata(EditorSchema.assetRef(Item.class))
            .documentation("The item whose picture stands for the boost on a reward that starts it. Leave it out "
                    + "for no picture.").add()
            .appendInherited(new KeyedCodec<>("Tracks", Tracks.CODEC, false),
                    (a, v) -> a.tracks = v, a -> a.tracks, (a, p) -> a.tracks = p.tracks)
            .documentation("The tracks the boost lifts: by id, by category, or All. A boost naming none lifts "
                    + "nothing.").add()
            .appendInherited(new KeyedCodec<>("Percent", Codec.DOUBLE, false),
                    (a, v) -> a.percent = v, a -> a.percent, (a, p) -> a.percent = p.percent)
            .metadata(EditorSchema.defaultValue(0.0))
            .documentation("How much more every gain on those tracks earns while the boost runs, as a percent: "
                    + "50 is half as much again, 100 doubles it. Every boost running on a track adds up, under "
                    + "the server's cap. A start can ask for its own percent (a reward's Percent).").add()
            .appendInherited(new KeyedCodec<>("Scope", Codec.STRING, false),
                    (a, v) -> a.scope = v, a -> a.scope, (a, p) -> a.scope = p.scope)
            .metadata(BoostScope.editorHint())
            .metadata(EditorSchema.defaultValue(BoostScope.PERSONAL.word()))
            .documentation(BoostScope.DOCUMENTATION).add()
            .appendInherited(new KeyedCodec<>("Seconds", Codec.LONG, false),
                    (a, v) -> a.seconds = v, a -> a.seconds, (a, p) -> a.seconds = p.seconds)
            .documentation("How long the boost runs once started, in seconds, counted on the clock: it keeps "
                    + "running while the player is away and across a restart. A start can ask for its own length "
                    + "(a reward's Seconds). Leave it out only on a Standing boost.").add()
            .appendInherited(new KeyedCodec<>("Standing", Codec.BOOLEAN, false),
                    (a, v) -> a.standing = v, a -> a.standing, (a, p) -> a.standing = p.standing)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("True makes this a standing boost: nobody starts it and it keeps no timer, it lifts "
                    + "every gain on its tracks for every player whenever it is switched on, in season and its "
                    + "Requires passes (a bonus weekend, with a Season), and it shows no tile. It counts as a "
                    + "server boost whatever its Scope says. Unauthored means false.").add()
            .appendInherited(new KeyedCodec<>("Reusable", Reusable.CODEC, false),
                    (a, v) -> a.reusable = v, a -> a.reusable, (a, p) -> a.reusable = p.reusable)
            .documentation("Lets a player start this timed boost themselves, as often as its Stack.Cooldown allows: "
                    + "a mod's boosts page lists it, with a button that starts it, for every player who meets "
                    + "Reusable.Requires (a permission, say), asked only when they start it, so a server boost one "
                    + "player starts still lifts everyone. Leave it out and only an item, a reward or a command "
                    + "starts it.").add()
            .appendInherited(new KeyedCodec<>("Stack", Stack.CODEC, false),
                    (a, v) -> a.stack = v, a -> a.stack, (a, p) -> a.stack = p.stack)
            .documentation("How this boost stacks with itself: how many may run at once and how long a player "
                    + "waits between starts. Leave it out for no limit of its own (the server's limits on how "
                    + "many boosts run still hold) and no wait.").add()
            .appendInherited(new KeyedCodec<>("Effect", Codec.STRING, false),
                    (a, v) -> a.effect = v, a -> a.effect, (a, p) -> a.effect = p.effect)
            .metadata(EditorSchema.assetRef(EntityEffect.class))
            .documentation("The EntityEffect whose tile shows the boost on the buff bar while it runs: give it an "
                    + "effect of its own whose Name says what it lifts and by how much (Gathering +50%), and its "
                    + "ring counts down the boost's real time. Nothing else may apply that effect: the bar takes "
                    + "it off whenever no boost names it. Leave it out, or name one no pack ships, for the shared "
                    + "Personal Boost or Server Boost tile.").add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.requires = v, a -> a.requires, (a, p) -> a.requires = p.requires)
            .documentation(StoreGates.DOCUMENTATION + " Asked of the player who starts the boost, and of each "
                    + "player at every gain it would lift.").add(),
                    (a, v) -> a.season = v, a -> a.season)
            .build();

    public BoostAsset() {
    }

    /** The boost id, exactly as the filename spells it. */
    @Override
    public String getId() {
        return id;
    }

    /** Is the boost switched on in its own file? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** Is it here right now: switched on, and its {@code Season} running (or none named)? */
    public boolean isPresent() {
        return isEnabled() && SeasonGate.live(season);
    }

    /** The localization key the boost is named by, or null when the file names none. */
    @Nullable
    public String titleKey() {
        return text == null ? null : blankToNull(text.getTitleKey());
    }

    /** The localization key wording what the boost lifts ({@code Text.FlavorKey}), or null when the file names none. */
    @Nullable
    public String liftsKey() {
        return text == null ? null : blankToNull(text.getFlavorKey());
    }

    /** The item standing for it, or null. */
    @Nullable
    public String icon() {
        return blankToNull(icon);
    }

    /** The percent it lifts by; 0 when unauthored or not a number. */
    public double percent() {
        return percent == null || !Double.isFinite(percent) ? 0.0 : percent;
    }

    /** Whose it is. */
    @Nonnull
    public BoostScope scope() {
        return BoostScope.of(scope);
    }

    /** How long a start runs, in seconds; 0 when unauthored or below 1. */
    public long seconds() {
        return seconds == null ? 0L : Math.max(0L, seconds);
    }

    /** Is this a standing boost (never started, on while present)? */
    public boolean isStanding() {
        return Boolean.TRUE.equals(standing);
    }

    /** May a player start this boost themselves, from a page that lists it ({@code Reusable})? Never a standing one. */
    public boolean isReusable() {
        return reusable != null && !isStanding();
    }

    /**
     * Who may start it themselves ({@code Reusable.Requires}), asked only at such a start; null when anyone may, or it
     * is not reusable.
     */
    @Nullable
    public GateSpec reusableRequires() {
        return reusable == null ? null : reusable.requires;
    }

    /** How many of it may run at once for one owner; 0 for no limit of its own. */
    public int stackMax() {
        Integer max = stack == null ? null : stack.max;
        return max == null ? 0 : Math.max(0, max);
    }

    /** How long a player waits between two starts of it, in seconds; 0 for none. */
    public long cooldownSeconds() {
        Long cooldown = stack == null ? null : stack.cooldown;
        return cooldown == null ? 0L : Math.max(0L, cooldown);
    }

    /** The tile's effect as written, trimmed, or null for the scope's shared tile. */
    @Nullable
    public String effect() {
        return blankToNull(effect);
    }

    /** The calendar event it belongs to, trimmed, or null for all year. */
    @Nullable
    public String season() {
        return SeasonGate.normalize(season);
    }

    /** The top-level {@code Requires}, or null when none is written. */
    @Nullable
    public GateSpec requires() {
        return requires;
    }

    /** The part of {@link #requires()} asked of the player, lifted once; null when nothing is. */
    @Nullable
    public GateSpec liveRequires() {
        GateSpec[] held = live;
        if (held == null) {
            held = new GateSpec[] {StoreGates.live(requires)};
            live = held;
        }
        return held[0];
    }

    /** Does this boost lift every track? */
    public boolean liftsAll() {
        return tracks != null && Boolean.TRUE.equals(tracks.all);
    }

    /** The track ids it names, trimmed, in authored order. */
    @Nonnull
    public List<String> trackIds() {
        return tracks == null ? List.of() : words(tracks.ids);
    }

    /** The category ids it names, trimmed, in authored order. */
    @Nonnull
    public List<String> categoryIds() {
        return tracks == null ? List.of() : words(tracks.categories);
    }

    /** Does it name any track at all (All, an id or a category)? */
    public boolean namesTracks() {
        return liftsAll() || !trackIds().isEmpty() || !categoryIds().isEmpty();
    }

    /** Does it lift the track {@code trackId}, whose category is {@code categoryId} (null for none)? */
    public boolean lifts(@Nonnull String trackId, @Nullable String categoryId) {
        if (liftsAll()) {
            return true;
        }
        Matcher matcher = matcher();
        return matcher.tracks.contains(trackId.toLowerCase(Locale.ROOT))
                || (categoryId != null && matcher.categories.contains(categoryId.trim().toLowerCase(Locale.ROOT)));
    }

    /** The lower-cased ids {@link #lifts} reads. */
    private record Matcher(@Nonnull Set<String> tracks, @Nonnull Set<String> categories) {
    }

    @Nonnull
    private Matcher matcher() {
        Matcher held = matcher;
        if (held == null) {
            held = new Matcher(lowered(trackIds()), lowered(categoryIds()));
            matcher = held;
        }
        return held;
    }

    @Nonnull
    private static Set<String> lowered(@Nonnull List<String> words) {
        Set<String> out = new TreeSet<>();
        for (String word : words) {
            out.add(word.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(out);
    }

    @Nonnull
    private static List<String> words(@Nullable String[] written) {
        if (written == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(written.length);
        for (String word : written) {
            if (word != null && !word.isBlank()) {
                out.add(word.trim());
            }
        }
        return List.copyOf(out);
    }

    @Nullable
    static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The Tracks group: what the boost lifts. */
    public static final class Tracks {

        @Nullable protected Boolean all;
        @Nullable protected String[] ids;
        @Nullable protected String[] categories;

        public static final BuilderCodec<Tracks> CODEC = BuilderCodec.builder(Tracks.class, Tracks::new)
                .appendInherited(new KeyedCodec<>("All", Codec.BOOLEAN, false),
                        (o, v) -> o.all = v, o -> o.all, (o, p) -> o.all = p.all)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True lifts every track, whatever Ids and Categories say.").add()
                .appendInherited(new KeyedCodec<>("Ids", Codec.STRING_ARRAY, false),
                        (o, v) -> o.ids = v, o -> o.ids, (o, p) -> o.ids = p.ids)
                .metadata(EditorSchema.assetRef(TrackAsset.class))
                .documentation("The tracks it lifts, by id (files under Server/ZiggfreedCommon/Tracks), in any "
                        + "case.").add()
                .appendInherited(new KeyedCodec<>("Categories", Codec.STRING_ARRAY, false),
                        (o, v) -> o.categories = v, o -> o.categories, (o, p) -> o.categories = p.categories)
                .metadata(EditorSchema.assetRef(TrackCategoryAsset.class))
                .documentation("The categories whose every track it lifts, by id (Combat, Gathering, Crafting, or "
                        + "a pack's own).").add()
                .build();

        public Tracks() {
        }
    }

    /** The Reusable group: a player may start the boost themselves, and who may. */
    public static final class Reusable {

        @Nullable protected GateSpec requires;

        public static final BuilderCodec<Reusable> CODEC = BuilderCodec.builder(Reusable.class, Reusable::new)
                .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                        (o, v) -> o.requires = v, o -> o.requires, (o, p) -> o.requires = p.requires)
                .documentation("Who may start it themselves, in the shared Requires grammar (a Permission node, a "
                        + "level), asked only when a player starts it from a page: never at a gain, and never of "
                        + "the players a server boost lifts. Leave it out for anyone.").add()
                .build();

        public Reusable() {
        }
    }

    /** The Stack group: how a boost stacks with itself. */
    public static final class Stack {

        @Nullable protected Integer max;
        @Nullable protected Long cooldown;

        public static final BuilderCodec<Stack> CODEC = BuilderCodec.builder(Stack.class, Stack::new)
                .appendInherited(new KeyedCodec<>("Max", Codec.INTEGER, false),
                        (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("How many of this boost may run at once for one player (a Server boost: for the "
                        + "server); one more is refused, and a boost item is kept. Each one running adds its "
                        + "percent. 0, or unauthored, sets no limit of its own.").add()
                .appendInherited(new KeyedCodec<>("Cooldown", Codec.LONG, false),
                        (o, v) -> o.cooldown = v, o -> o.cooldown, (o, p) -> o.cooldown = p.cooldown)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("How long, in seconds, a player waits after starting this boost before they can "
                        + "start it again, counted on the clock and kept across a restart. 0, or unauthored, means "
                        + "no wait.").add()
                .build();

        public Stack() {
        }
    }
}
