package com.ziggfreed.common.match;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One {@code Match} or {@code Qualifier} as authored: a {@link NamePattern} over the id itself, or a SET key that
 * names what the id's own asset says it is (R858: which things count is data on the asset, never a list in code).
 *
 * <ul>
 *   <li>{@code ItemTag:<tag>} ({@link Form#ITEM_TAG}): an item, or a block through the item it lives in, whose
 *       native {@code Tags} carry {@code <tag>} as the engine expands them, its {@code Parent}'s merged in:
 *       {@code ItemTag:Type=Ore} the value {@code Ore} under the key {@code Type}, {@code ItemTag:Ore} the value
 *       (or key) {@code Ore} under any key. A weapon qualifier reads the same way ({@code ItemTag:Family=Sword}).</li>
 *   <li>{@code BlockFamily:<id>} ({@link Form#BLOCK_FAMILY}): a block in that block family, as the family asset and
 *       the block's own {@code ZigBlockFamily} tag decide (zc-world fills the reader).</li>
 *   <li>{@code NpcGroup:<id>} ({@link Form#NPC_GROUP}): a creature whose role is in that native NPC group
 *       ({@code Server/NPC/Groups/}).</li>
 * </ul>
 *
 * <p>A prefix is matched without regard to case and the set id after it is trimmed; the set id is exact (a
 * {@code *} in it is a literal character, never a wildcard). Anything else is a name pattern, read exactly as
 * {@link NamePattern} always has, so {@code tag:Hit_Id} or {@code cause:Fire} stay the literal names they were.
 *
 * <p><b>Which wins.</b> A set names many things, so it ranks under every pattern that pins down characters of the
 * name and over the bare {@code *} ({@link NameMatchRank#SET_BAND}): an exact id first, then the pattern with the
 * longest literal core, then any set key, then {@code *}. Two set keys that both match are equally specific, and
 * the one folded first keeps the moment, as two equal patterns do.
 *
 * <p>Parse once at load, match many times: a name key answers from its parsed pattern, a set key asks
 * {@link MatchSets} (a table keeps the answer per target until {@link MatchSets#generation()} moves).
 */
public final class MatchKey {

    /** What a key reads. Closed: a new form is a new reader in {@link MatchSets}. */
    public enum Form {
        /** A name pattern over the id itself. */
        NAME("", "a name pattern"),
        /** A native item tag on the item, or on the item a block lives in. */
        ITEM_TAG("ItemTag:", "an item tag"),
        /** A block family. */
        BLOCK_FAMILY("BlockFamily:", "a block family"),
        /** A native NPC group. */
        NPC_GROUP("NpcGroup:", "an NPC group");

        private final String prefix;
        private final String lowerPrefix;
        private final String noun;

        Form(@Nonnull String prefix, @Nonnull String noun) {
            this.prefix = prefix;
            this.lowerPrefix = prefix.toLowerCase(Locale.ROOT);
            this.noun = noun;
        }

        /** How a key of this form starts, as documented ({@code ItemTag:}); empty for a name pattern. */
        @Nonnull
        public String prefix() {
            return prefix;
        }

        /** What the form names, for a message ("a block family"). */
        @Nonnull
        public String noun() {
            return noun;
        }
    }

    /** The key as authored, trimmed; empty when nothing was written (which covers everything). */
    @Nonnull
    public final String raw;

    @Nonnull
    private final Form form;

    /** The parsed pattern of a name key ({@code *} for a blank one); null for a set key. */
    @Nullable
    private final NamePattern name;

    /** A set key's set id, trimmed as written; empty for a name key or a set key naming nothing. */
    @Nonnull
    private final String set;

    /** The key rows collapse on: the name as written, lower-cased, or the form's prefix and the set id. */
    @Nonnull
    private final String foldKey;

    private MatchKey(@Nonnull String raw, @Nonnull Form form, @Nullable NamePattern name, @Nonnull String set,
            @Nonnull String foldKey) {
        this.raw = raw;
        this.form = form;
        this.name = name;
        this.set = set;
        this.foldKey = foldKey;
    }

    /** {@code authored} as a key; blank or null is the name pattern {@code *}, covering everything. */
    @Nonnull
    public static MatchKey parse(@Nullable String authored) {
        String trimmed = authored == null ? "" : authored.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (Form form : Form.values()) {
            if (form != Form.NAME && lower.startsWith(form.lowerPrefix)) {
                String set = trimmed.substring(form.prefix.length()).trim();
                return new MatchKey(trimmed, form, null, set,
                        form.lowerPrefix + set.toLowerCase(Locale.ROOT));
            }
        }
        return new MatchKey(trimmed, Form.NAME, NamePattern.parse(trimmed.isEmpty() ? "*" : trimmed), "", lower);
    }

    @Nonnull
    public Form form() {
        return form;
    }

    /** Does this key name a set (an item tag, a block family, an NPC group) rather than a name pattern? */
    public boolean isSet() {
        return form != Form.NAME;
    }

    /** A name key's pattern; null for a set key. */
    @Nullable
    public NamePattern name() {
        return name;
    }

    /** A set key's set id, trimmed as written; empty for a name key, or a set key that names nothing. */
    @Nonnull
    public String set() {
        return set;
    }

    /** The key two rows claiming one key collapse on (case ignored, a set key's prefix spelt one way). */
    @Nonnull
    public String foldKey() {
        return foldKey;
    }

    /** Is this the bare {@code *} catch-all (or a blank key)? */
    public boolean isDefaultRule() {
        return name != null && name.isDefaultRule();
    }

    /** Where this key sits on the shared ladder once it has matched ({@link NameMatchRank}). */
    @Nonnull
    public NameMatchRank rank() {
        return name != null ? NameMatchRank.ofPattern(name) : NameMatchRank.ofSet();
    }

    /**
     * Does this key cover {@code id}? A name key reads {@code idLower} (the caller has lower-cased it once for every
     * key it tries); a set key asks {@link MatchSets} about {@code id} as the moment spelt it. A set key naming
     * nothing covers nothing.
     */
    public boolean matches(@Nonnull String id, @Nonnull String idLower) {
        if (name != null) {
            return name.matches(idLower);
        }
        return !set.isEmpty() && MatchSets.inSet(form, id, set);
    }

    @Override
    public String toString() {
        return raw.isEmpty() ? "*" : raw;
    }
}
