package com.ziggfreed.common.match;

import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * WHICH moments something answers for: the one source matcher every moment-keyed table shares (a track's
 * sources, the bonus rows). Three nullable leaves, authored as a nested {@code When} group:
 *
 * <pre>{@code
 * "When": { "Kind": "BREAK_BLOCK", "Match": "Ore_*" }
 * "When": { "Kind": "BREAK_BLOCK", "Match": "BlockFamily:Ore" }
 * "When": { "Kind": "DEAL_DAMAGE", "Qualifier": "ItemTag:Family=Sword" }
 * "When": { "Kind": "KILL_ENTITY", "Match": "NpcGroup:Goblin" }
 * }</pre>
 *
 * <ul>
 *   <li>{@code Kind} is an objective kind id, the vocabulary quests and achievements author
 *       ({@code BREAK_BLOCK}, {@code KILL_ENTITY}, {@code DEAL_DAMAGE}, ...), matched without regard to case. The
 *       bonus rows' first spellings ({@code BreakBlock}, {@code KillMob}, {@code PickupItem}) are aliases of
 *       {@code BREAK_BLOCK}, {@code KILL_ENTITY} and {@code PICKUP_ITEM} ({@link #normalizeKind}), so a file
 *       written either way reads the same.</li>
 *   <li>{@code Match} is a {@link MatchKey} over the moment's target (the block, mob or item id): a
 *       {@link NamePattern}, or a set key naming what the target's own asset carries ({@code ItemTag:},
 *       {@code BlockFamily:}, {@code NpcGroup:}); blank covers every target.</li>
 *   <li>{@code Qualifier} is a {@link MatchKey} over the moment's qualifier (a hit's weapon, a kill's tier), in
 *       the same forms; blank asks nothing of it.</li>
 * </ul>
 *
 * <p>Selection among several matching rows is {@link MomentTable}'s: the most specific {@code Match} wins (an
 * exact id, then the longest pattern, then any set key, then {@code *}), then the most specific {@code Qualifier},
 * then the first folded. A consumer embeds this group under its own field (its codec, its documentation); it
 * carries no defaults, so each read site applies its own. {@link #MATCH_FORMS} is the one sentence every field
 * taking a key quotes, so the Asset Editor tells authors the forms the same way everywhere.
 */
public class When {

    /**
     * The bonus rows' first spellings of three kinds, squashed (no {@code _}) and upper-cased, to the kind each
     * names. Frozen: a shipped or owner file may still write any of them.
     */
    private static final Map<String, String> FIRST_SPELLINGS = Map.of(
            "BREAKBLOCK", "BREAK_BLOCK",
            "KILLMOB", "KILL_ENTITY",
            "PICKUPITEM", "PICKUP_ITEM");

    /**
     * How a key may name its targets beyond a name pattern, and which key wins: quoted by the documentation of
     * every field that takes a {@link MatchKey} (this group's {@code Match} and {@code Qualifier}, a track source's
     * {@code Rows}, a bonus row's {@code Match}).
     */
    public static final String MATCH_FORMS = "Or by what the thing's own files say: \"ItemTag:Type=Ore\" an item, "
            + "or a block's item, carrying that native tag (\"ItemTag:Ore\" the value Ore under any tag), "
            + "\"BlockFamily:Ore\" a block in that block family, \"NpcGroup:Goblin\" a creature in that NPC group. "
            + "Case is ignored. An exact name wins over everything, any pattern over an ItemTag, BlockFamily or "
            + "NpcGroup key, and those over \"*\".";

    @Nullable protected String kind;
    @Nullable protected String match;
    @Nullable protected String qualifier;

    public static final BuilderCodec<When> CODEC = BuilderCodec.builder(When.class, When::new)
            .appendInherited(new KeyedCodec<>("Kind", Codec.STRING, false),
                    (o, v) -> o.kind = v, o -> o.kind, (o, p) -> o.kind = p.kind)
            .documentation("Which moment this answers for, as an objective kind id: BREAK_BLOCK, KILL_ENTITY, "
                    + "PICKUP_ITEM, DEAL_DAMAGE, CRAFT_ITEM and any kind a mod adds. Case is ignored, and "
                    + "BreakBlock, KillMob and PickupItem read as BREAK_BLOCK, KILL_ENTITY and PICKUP_ITEM. "
                    + "Naming no kind answers for nothing.").add()
            .appendInherited(new KeyedCodec<>("Match", Codec.STRING, false),
                    (o, v) -> o.match = v, o -> o.match, (o, p) -> o.match = p.match)
            .documentation("Which block, mob or item, as a name pattern: \"Rock_Stone\" exactly, \"Rock_*\" "
                    + "starting with, \"*_Cracked\" ending with, \"*Geode*\" anywhere in the name, \"*\" "
                    + "everything. " + MATCH_FORMS + " Omit it to cover everything in the moment.").add()
            .appendInherited(new KeyedCodec<>("Qualifier", Codec.STRING, false),
                    (o, v) -> o.qualifier = v, o -> o.qualifier, (o, p) -> o.qualifier = p.qualifier)
            .documentation("What else the moment must carry, as a name pattern over its qualifier: for "
                    + "DEAL_DAMAGE the weapon that hit (\"Weapon_Sword*\", or \"ItemTag:Family=Sword\" for every "
                    + "weapon tagged a sword), for KILL_ENTITY whatever a mod qualifies a kill with. It takes the "
                    + "same ItemTag, BlockFamily and NpcGroup keys as Match. Omit it to ask nothing more.").add()
            .build();

    public When() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static When of(@Nullable String kind, @Nullable String match, @Nullable String qualifier) {
        When when = new When();
        when.kind = kind;
        when.match = match;
        when.qualifier = qualifier;
        return when;
    }

    /** The kind as an objective kind id ({@link #normalizeKind}), or null when none is named. */
    @Nullable
    public String kindId() {
        return normalizeKind(kind);
    }

    /** The kind exactly as authored, trimmed, for a message that has to quote it. */
    @Nonnull
    public String rawKind() {
        return kind == null ? "" : kind.trim();
    }

    /** The target pattern, trimmed; blank covers every target. */
    @Nonnull
    public String match() {
        return match == null ? "" : match.trim();
    }

    /** The qualifier pattern, trimmed, or null when none is asked. */
    @Nullable
    public String qualifier() {
        return qualifier == null || qualifier.isBlank() ? null : qualifier.trim();
    }

    /**
     * {@code raw} as an objective kind id: trimmed and upper-cased, a first spelling ({@code BreakBlock},
     * {@code KillMob}, {@code PickupItem}, any case, underscores forgiven) as the kind it names; null when blank.
     */
    @Nullable
    public static String normalizeKind(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        String first = FIRST_SPELLINGS.get(upper.replace("_", ""));
        return first != null ? first : upper;
    }
}
