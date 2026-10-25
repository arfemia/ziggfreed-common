package com.ziggfreed.common.track.world;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.server.core.asset.type.gameplay.GameplayConfig;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * What a world does to tracks (R699: a PvP arena that strips passive stats): whether a gain lands there and at
 * what multiplier, for every track or by track and category, whether hits on other players pay there, and which
 * grants stay on there, for every track or by track and category.
 *
 * <pre>{@code
 * { "Gain": { "Multiplier": 0.5, "DisabledTracks": [ "Gathering" ], "PlayerHits": true,
 *             "Tracks": { "Your_Swords": { "Multiplier": 2.0 } } },
 *   "Grants": { "Kinds": { "Combat": false },
 *               "Tracks": { "Your_Magic": { "Kinds": { "Ability": false } } } } }
 * }</pre>
 *
 * <p>Native first (R695): a world's own gameplay config carries it, written in its {@code Plugin} group under
 * {@value #ID} ({@code GameplayConfig.PLUGIN_CODEC}, the way the engine's own plugins hang their per-world settings
 * there, merged over a {@code Parent} config's). A world named by a pattern (every {@code Dungeon_*}), which a
 * gameplay config cannot say, takes the same group from a {@link TrackWorldRuleAsset}. Where both apply, the
 * world's own gameplay config wins whole ({@link TrackWorldRules}).
 *
 * <p>{@code Gain}: an XP multiplier ({@code Multiplier}, 0 stops every gain), a deny list ({@code DisabledTracks})
 * and an allow list ({@code EnabledTracks}: when written, only the tracks it names gain). Each list and each
 * {@code Gain.Tracks} entry names a track id or a category id, a track entry winning over its category's. Each of
 * those switches only takes away: a track any of them turns off gains nothing here. {@code PlayerHits} is the one
 * that gives (R864): a moment that happened to another player (a hit landed on one) pays no track source unless a
 * world's rules write it true, so it pays nowhere a world's rules do not name.
 *
 * <p>{@code Grants} is the same for what the picks made on a track grant (R699), read by the grant rebuild at every
 * world entry: its {@code Enabled} and {@code Kinds} take away every grant, or the grants a word names, for every
 * track, and its {@code Tracks} entries the same for one track or one category. A {@code Kinds} word is a grant's
 * kind ({@code Stat}) or a family the grant belongs to ({@code Combat}: a stat grant belongs to every family its
 * stat's {@code ZigGrantFamily} tag names, zc-entity's {@code GrantFamilies}). The most specific word decides
 * (R895): a family word written in a switch set decides for the grants of that family over their kind's word, false
 * winning among several written families, and the kind decides only where no family of the grant is written; so
 * {@code Combat} false takes only the grants on a stat tagged {@code Combat}, and {@code Stat} false takes every stat
 * grant but those a written family word keeps. Each switch set (the group's, a track's entry, a category's) only
 * takes away. A consumer's stat, combat and ability switches map onto {@code Kinds.Stat}, {@code Kinds.Combat} and
 * {@code Kinds.Ability}.
 */
public final class TrackRules {

    /** The key the rules are written under in a gameplay config's {@code Plugin} group. */
    public static final String ID = "ZigTrackRules";

    @Nullable protected GainRules gain;
    @Nullable protected GrantRules grants;

    public static final BuilderCodec<TrackRules> CODEC = BuilderCodec.builder(TrackRules.class, TrackRules::new)
            .documentation("What this world does to tracks: whether gains land and at what multiplier, and which "
                    + "grant kinds stay on.")
            .appendInherited(new KeyedCodec<>("Gain", GainRules.CODEC, false),
                    (o, v) -> o.gain = v, o -> o.gain, (o, p) -> o.gain = p.gain)
            .documentation("Whether a gain on a track lands in this world, and at what multiplier, for every track "
                    + "or by track and category, and whether hits on other players pay here. Leave it out and every "
                    + "track gains as anywhere else, and a hit on another player pays nothing.").add()
            .appendInherited(new KeyedCodec<>("Grants", GrantRules.CODEC, false),
                    (o, v) -> o.grants = v, o -> o.grants, (o, p) -> o.grants = p.grants)
            .documentation("Which of what the picks made on a track grant stay on in this world, for every track or "
                    + "by track and category: a PvP arena that strips passive stats writes { \"Kinds\": { \"Stat\": "
                    + "false } }, one that strips only the combat stats { \"Kinds\": { \"Combat\": false } }. Each "
                    + "switch only takes away, and leaving the world gives the grants back. Leave it out and every "
                    + "grant stays on.").add()
            .build();

    public TrackRules() {
    }

    /** Java-side construction (a test, a consumer building rules in code). */
    @Nonnull
    public static TrackRules of(@Nullable GainRules gain, @Nullable GrantRules grants) {
        TrackRules rules = new TrackRules();
        rules.gain = gain;
        rules.grants = grants;
        return rules;
    }

    /** The rules a gameplay config writes in its {@code Plugin} group, or null when it writes none. */
    @Nullable
    public static TrackRules of(@Nullable GameplayConfig config) {
        if (config == null) {
            return null;
        }
        Object held = config.getPluginConfig().get(TrackRules.class);
        return held instanceof TrackRules rules ? rules : null;
    }

    /**
     * What a gain on track {@code trackId} (in category {@code categoryId}, null for none) is multiplied by in this
     * world: 0 when anything here turns it off, else the most specific {@code Multiplier} written (the track's
     * entry, then its category's, then the group's), 1 when none is.
     */
    public double gainMultiplier(@Nonnull String trackId, @Nullable String categoryId) {
        GainRules g = gain;
        return g == null ? 1.0 : g.multiplier(trackId, categoryId);
    }

    /**
     * Does a hit on another player (any moment that happened to one) pay track sources in this world? False unless
     * {@code Gain.PlayerHits} is written true (R864).
     */
    public boolean paysPlayerHits() {
        GainRules g = gain;
        return g != null && g.paysPlayerHits();
    }

    /**
     * Does a grant of {@code word} (a kind, or a family a grant belongs to) stay on in this world, on every track?
     * What the rules say for all tracks alike; a track or category entry may still take it away
     * ({@link #grantsOn(String, Collection, String, String)}).
     */
    public boolean grantsOn(@Nullable String word) {
        return grantsOn(word, null, null, null);
    }

    /**
     * Does a grant of {@code kind} (belonging to {@code families}, null or empty for none) from a pick on track
     * {@code trackId} (in category {@code categoryId}) stay on in this world? No unless every switch set that applies
     * leaves it on: the group's own, the track's entry and the category's entry, each deciding by the most specific
     * word it writes (a family of the grant's over its kind, R895).
     */
    public boolean grantsOn(@Nullable String kind, @Nullable Collection<String> families, @Nullable String trackId,
            @Nullable String categoryId) {
        GrantRules g = grants;
        return g == null || g.on(kind, families, trackId, categoryId);
    }

    /** The {@code Gain} group as written, or null. */
    @Nullable
    public GainRules gain() {
        return gain;
    }

    /** The {@code Grants} group as written, or null. */
    @Nullable
    public GrantRules grants() {
        return grants;
    }

    /** One grant switch set: every grant on or off, and per grant kind or family. */
    public static class GrantRule {

        @Nullable protected Boolean enabled;
        @Nullable protected Map<String, Boolean> kinds;

        /** The kinds map lower-cased once, on first ask (a group never changes once decoded). */
        @Nullable private volatile Map<String, Boolean> folded;

        public static final BuilderCodec<GrantRule> CODEC = BuilderCodec.builder(GrantRule.class, GrantRule::new)
                .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                        (o, v) -> o.enabled = v, o -> o.enabled, (o, p) -> o.enabled = p.enabled)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether what the picks grant stays on here at all. Unauthored means true; false "
                        + "takes every grant away while a player is here.").add()
                .appendInherited(new KeyedCodec<>("Kinds", new MapCodec<>(Codec.BOOLEAN, LinkedHashMap::new, false),
                                false), (o, v) -> o.kinds = v, o -> o.kinds, (o, p) -> o.kinds = p.kinds)
                .documentation("Grants switched off here, by a grant kind's id (Stat, or one a mod registers) or a "
                        + "family a grant belongs to (Combat, Ability: a stat grant belongs to every family its "
                        + "stat's ZigGrantFamily tag names): false takes those grants away while a player is here. "
                        + "The most specific word decides: a family written here decides for its grants over their "
                        + "kind (false among several families winning), so Stat false with Combat true takes every "
                        + "stat grant but the combat ones, and a kind decides only for grants no written family "
                        + "names. A grant no word names stays on.").add()
                .build();

        public GrantRule() {
        }

        /** Java-side construction; nulls stay unauthored. */
        @Nonnull
        public static GrantRule of(@Nullable Boolean enabled, @Nullable Map<String, Boolean> kinds) {
            GrantRule rule = new GrantRule();
            rule.enabled = enabled;
            rule.kinds = kinds == null ? null : new LinkedHashMap<>(kinds);
            return rule;
        }

        /** The {@code Enabled} switch as written, or null when unauthored (which reads as on). */
        @Nullable
        public Boolean enabled() {
            return enabled;
        }

        /** The kinds map as written, or an empty one. */
        @Nonnull
        public Map<String, Boolean> kinds() {
            return kinds == null ? Map.of() : Map.copyOf(kinds);
        }

        /**
         * Does this switch set leave a grant of {@code kind}, belonging to {@code families}, on? No when the set is
         * switched off. Otherwise the most specific word written decides (R895): a family the grant belongs to,
         * when this set writes one (false among them winning), else its kind; a grant no written word names stays on.
         */
        boolean leavesOn(@Nullable String kind, @Nullable Collection<String> families) {
            if (enabled != null && !enabled) {
                return false;
            }
            Map<String, Boolean> map = foldedKinds();
            if (map.isEmpty()) {
                return true;
            }
            boolean familyWritten = false;
            if (families != null) {
                for (String family : families) {
                    Boolean word = word(map, family);
                    if (word != null) {
                        if (!word) {
                            return false;
                        }
                        familyWritten = true;
                    }
                }
            }
            if (familyWritten) {
                return true;
            }
            Boolean kindWord = word(map, kind);
            return kindWord == null || kindWord;
        }

        /** What this set writes for {@code word} (any case), or null when it writes nothing. */
        @Nullable
        private static Boolean word(@Nonnull Map<String, Boolean> map, @Nullable String word) {
            return word == null ? null : map.get(word.trim().toLowerCase(Locale.ROOT));
        }

        @Nonnull
        private Map<String, Boolean> foldedKinds() {
            Map<String, Boolean> held = folded;
            if (held == null) {
                Map<String, Boolean> out = new HashMap<>();
                if (kinds != null) {
                    for (Map.Entry<String, Boolean> e : kinds.entrySet()) {
                        if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                            out.put(e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue());
                        }
                    }
                }
                held = Map.copyOf(out);
                folded = held;
            }
            return held;
        }
    }

    /** The {@code Grants} group: the world's switches for every track, and per-track or per-category entries. */
    public static final class GrantRules extends GrantRule {

        @Nullable protected Map<String, GrantRule> tracks;

        /** The entries lower-cased once, on first ask. */
        @Nullable private volatile Map<String, GrantRule> byId;

        public static final BuilderCodec<GrantRules> CODEC = BuilderCodec.builder(GrantRules.class, GrantRules::new,
                        GrantRule.CODEC)
                .appendInherited(new KeyedCodec<>("Tracks", new MapCodec<>(GrantRule.CODEC, LinkedHashMap::new,
                                false), false), (o, v) -> o.tracks = v, o -> o.tracks, (o, p) -> o.tracks = p.tracks)
                .documentation("The same switches for one track or one category, by its id: they take away only "
                        + "what that track's picks grant, beside whatever the group's own switches take away.")
                .add()
                .build();

        public GrantRules() {
        }

        /** Java-side construction; nulls stay unauthored. */
        @Nonnull
        public static GrantRules of(@Nullable Boolean enabled, @Nullable Map<String, Boolean> kinds,
                @Nullable Map<String, GrantRule> tracks) {
            GrantRules rules = new GrantRules();
            rules.enabled = enabled;
            rules.kinds = kinds == null ? null : new LinkedHashMap<>(kinds);
            rules.tracks = tracks == null ? null : new LinkedHashMap<>(tracks);
            return rules;
        }

        /** The per-id entries as written, or an empty map. */
        @Nonnull
        public Map<String, GrantRule> tracks() {
            return tracks == null ? Map.of() : Map.copyOf(tracks);
        }

        boolean on(@Nullable String kind, @Nullable Collection<String> families, @Nullable String trackId,
                @Nullable String categoryId) {
            if (!leavesOn(kind, families)) {
                return false;
            }
            Map<String, GrantRule> entries = entries();
            if (entries.isEmpty()) {
                return true;
            }
            GrantRule byTrack = trackId == null ? null : entries.get(trackId.trim().toLowerCase(Locale.ROOT));
            GrantRule byCategory = categoryId == null || categoryId.isBlank() ? null
                    : entries.get(categoryId.trim().toLowerCase(Locale.ROOT));
            return (byTrack == null || byTrack.leavesOn(kind, families))
                    && (byCategory == null || byCategory.leavesOn(kind, families));
        }

        @Nonnull
        private Map<String, GrantRule> entries() {
            Map<String, GrantRule> held = byId;
            if (held == null) {
                Map<String, GrantRule> out = new HashMap<>();
                if (tracks != null) {
                    for (Map.Entry<String, GrantRule> e : tracks.entrySet()) {
                        if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                            out.put(e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue());
                        }
                    }
                }
                held = Map.copyOf(out);
                byId = held;
            }
            return held;
        }
    }

    /** One switch and multiplier: the group's own, or one track's or category's under {@code Gain.Tracks}. */
    public static class GainRule {

        @Nullable protected Boolean enabled;
        @Nullable protected Double multiplier;

        public static final BuilderCodec<GainRule> CODEC = BuilderCodec.builder(GainRule.class, GainRule::new)
                .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                        (o, v) -> o.enabled = v, o -> o.enabled, (o, p) -> o.enabled = p.enabled)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether a gain lands here at all. Unauthored means true.").add()
                .appendInherited(new KeyedCodec<>("Multiplier", Codec.DOUBLE, false),
                        (o, v) -> o.multiplier = v, o -> o.multiplier, (o, p) -> o.multiplier = p.multiplier)
                .metadata(EditorSchema.defaultValue(1.0))
                .documentation("What a gain is multiplied by here: 1 unchanged, 2 doubled, 0 nothing. A negative "
                        + "number reads as 0. Unauthored means 1, or the broader rule's.").add()
                .build();

        public GainRule() {
        }

        /** Java-side construction; nulls stay unauthored. */
        @Nonnull
        public static GainRule of(@Nullable Boolean enabled, @Nullable Double multiplier) {
            GainRule rule = new GainRule();
            rule.enabled = enabled;
            rule.multiplier = multiplier;
            return rule;
        }

        /** The {@code Enabled} switch as written, or null when unauthored (which reads as on). */
        @Nullable
        public Boolean enabled() {
            return enabled;
        }

        /** The {@code Multiplier} as written, or null when unauthored (a reader holds it at 0 or more). */
        @Nullable
        public Double multiplier() {
            return multiplier;
        }

        /** False only when written false. */
        boolean on() {
            return enabled == null || enabled;
        }

        /** The multiplier as written (held at 0 or more), or null when unauthored. */
        @Nullable
        Double writtenMultiplier() {
            if (multiplier == null) {
                return null;
            }
            return Double.isFinite(multiplier) ? Math.max(0.0, multiplier) : 0.0;
        }
    }

    /**
     * The {@code Gain} group: the world's switch and multiplier, its allow and deny lists, per-id entries, and
     * whether hits on other players pay.
     */
    public static final class GainRules extends GainRule {

        @Nullable protected String[] enabledTracks;
        @Nullable protected String[] disabledTracks;
        @Nullable protected Map<String, GainRule> tracks;
        @Nullable protected Boolean playerHits;

        /** The lists and the map lower-cased once, on first ask (a group never changes once decoded). */
        @Nullable private volatile Folded folded;

        private record Folded(@Nonnull Set<String> allow, @Nonnull Set<String> deny,
                              @Nonnull Map<String, GainRule> byId) {
        }

        public static final BuilderCodec<GainRules> CODEC = BuilderCodec.builder(GainRules.class, GainRules::new,
                        GainRule.CODEC)
                .appendInherited(new KeyedCodec<>("EnabledTracks", Codec.STRING_ARRAY, false),
                        (o, v) -> o.enabledTracks = v, o -> o.enabledTracks,
                        (o, p) -> o.enabledTracks = p.enabledTracks)
                .documentation("An allow list of track and category ids: when it names any, only those gain here. "
                        + "Unauthored or empty allows every track.").add()
                .appendInherited(new KeyedCodec<>("DisabledTracks", Codec.STRING_ARRAY, false),
                        (o, v) -> o.disabledTracks = v, o -> o.disabledTracks,
                        (o, p) -> o.disabledTracks = p.disabledTracks)
                .documentation("A deny list of track and category ids: those gain nothing here, whatever the allow "
                        + "list says.").add()
                .appendInherited(new KeyedCodec<>("Tracks", new MapCodec<>(GainRule.CODEC, LinkedHashMap::new, false),
                                false), (o, v) -> o.tracks = v, o -> o.tracks, (o, p) -> o.tracks = p.tracks)
                .documentation("A switch and multiplier for one track or one category, by its id: a track's entry "
                        + "wins over its category's, and either over the group's own Multiplier. Its Enabled "
                        + "false turns that track or category off here.").add()
                .appendInherited(new KeyedCodec<>("PlayerHits", Codec.BOOLEAN, false),
                        (o, v) -> o.playerHits = v, o -> o.playerHits, (o, p) -> o.playerHits = p.playerHits)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("Whether hitting another player pays XP here, as hitting a creature does (a weapon "
                        + "skill's damage XP, or any other track source paid on a hit). Unauthored means false: a "
                        + "hit on another player pays nothing, in every world no rule names too. Write true for "
                        + "a PvP arena whose fights should train; the lists and multipliers above still apply.")
                .add()
                .build();

        public GainRules() {
        }

        /** Java-side construction, {@code PlayerHits} unauthored; nulls stay unauthored. */
        @Nonnull
        public static GainRules of(@Nullable Boolean enabled, @Nullable Double multiplier,
                @Nullable String[] enabledTracks, @Nullable String[] disabledTracks,
                @Nullable Map<String, GainRule> tracks) {
            return of(enabled, multiplier, enabledTracks, disabledTracks, tracks, null);
        }

        /** Java-side construction; nulls stay unauthored. */
        @Nonnull
        public static GainRules of(@Nullable Boolean enabled, @Nullable Double multiplier,
                @Nullable String[] enabledTracks, @Nullable String[] disabledTracks,
                @Nullable Map<String, GainRule> tracks, @Nullable Boolean playerHits) {
            GainRules rules = new GainRules();
            rules.enabled = enabled;
            rules.multiplier = multiplier;
            rules.enabledTracks = enabledTracks == null ? null : enabledTracks.clone();
            rules.disabledTracks = disabledTracks == null ? null : disabledTracks.clone();
            rules.tracks = tracks == null ? null : new LinkedHashMap<>(tracks);
            rules.playerHits = playerHits;
            return rules;
        }

        /** The {@code PlayerHits} switch as written, or null when unauthored (which reads as off). */
        @Nullable
        public Boolean playerHits() {
            return playerHits;
        }

        /** True only when {@code PlayerHits} is written true. */
        boolean paysPlayerHits() {
            return Boolean.TRUE.equals(playerHits);
        }

        /** The allow list as written, or an empty array. */
        @Nonnull
        public String[] enabledTracks() {
            return enabledTracks == null ? new String[0] : enabledTracks.clone();
        }

        /** The deny list as written, or an empty array. */
        @Nonnull
        public String[] disabledTracks() {
            return disabledTracks == null ? new String[0] : disabledTracks.clone();
        }

        /** The per-id entries as written, or an empty map. */
        @Nonnull
        public Map<String, GainRule> tracks() {
            return tracks == null ? Map.of() : Map.copyOf(tracks);
        }

        double multiplier(@Nonnull String trackId, @Nullable String categoryId) {
            Folded f = folded();
            String track = trackId.trim().toLowerCase(Locale.ROOT);
            String category = categoryId == null || categoryId.isBlank() ? null
                    : categoryId.trim().toLowerCase(Locale.ROOT);
            if (!on()) {
                return 0.0;
            }
            if (!f.allow().isEmpty() && !f.allow().contains(track) && (category == null
                    || !f.allow().contains(category))) {
                return 0.0;
            }
            if (f.deny().contains(track) || (category != null && f.deny().contains(category))) {
                return 0.0;
            }
            GainRule byTrack = f.byId().get(track);
            GainRule byCategory = category == null ? null : f.byId().get(category);
            if ((byTrack != null && !byTrack.on()) || (byCategory != null && !byCategory.on())) {
                return 0.0;
            }
            Double m = byTrack == null ? null : byTrack.writtenMultiplier();
            if (m == null && byCategory != null) {
                m = byCategory.writtenMultiplier();
            }
            if (m == null) {
                m = writtenMultiplier();
            }
            return m == null ? 1.0 : m;
        }

        @Nonnull
        private Folded folded() {
            Folded held = folded;
            if (held == null) {
                Map<String, GainRule> byId = new HashMap<>();
                if (tracks != null) {
                    for (Map.Entry<String, GainRule> e : tracks.entrySet()) {
                        if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                            byId.put(e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue());
                        }
                    }
                }
                held = new Folded(lowered(enabledTracks), lowered(disabledTracks), Map.copyOf(byId));
                folded = held;
            }
            return held;
        }

        @Nonnull
        private static Set<String> lowered(@Nullable String[] ids) {
            if (ids == null || ids.length == 0) {
                return Set.of();
            }
            Set<String> out = new HashSet<>();
            for (String id : ids) {
                if (id != null && !id.isBlank()) {
                    out.add(id.trim().toLowerCase(Locale.ROOT));
                }
            }
            return Set.copyOf(out);
        }
    }
}
