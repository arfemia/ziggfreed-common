package com.ziggfreed.common.progress.asset;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.validation.Finding;

/**
 * The leaves EVERY authored {@code Listing} carries, whatever kind of content owns it: how it is
 * grouped, where it sorts, what it is labelled, and which chains it belongs to.
 *
 * <pre>{@code
 * "Listing": { "Category": "gathering", "SortOrder": 10, "Tags": ["daily"],
 *              "Chains": [ { "Id": "prospecting", "Tier": 2 } ] }
 * }</pre>
 *
 * <p><b>Why a shared base rather than two similar codecs.</b> Two lifecycle engines list content,
 * and the moment their field names drift an author has to remember which spelling belongs to which
 * kind of content, while a shared renderer starts needing two branches. Declaring these leaves ONCE
 * and appending them into both makes that drift impossible rather than merely discouraged. An engine
 * adds its own on top (a second grouping level belongs to the engine that has one).
 *
 * <p>Every leaf is {@code appendInherited}, so content with a {@code Parent} can be re-sorted
 * without losing the category it did not mention.
 */
public class ContentListingAsset {

    @Nullable protected String category;
    @Nullable protected Integer sortOrder;
    @Nullable protected String[] tags;
    @Nullable protected ChainMembership[] chains;
    @Nullable protected String icon;
    @Nullable protected Boolean hidden;
    @Nullable protected Boolean requirePrerequisites;

    /**
     * Register the seven shared listing leaves on {@code builder}. Every engine's own listing codec
     * starts from this call, which is what keeps the field names from drifting apart.
     */
    @Nonnull
    protected static <T extends ContentListingAsset, S extends BuilderCodec.BuilderBase<T, S>> S appendLeaves(
            @Nonnull S builder) {
        return builder
                .appendInherited(new KeyedCodec<>("Category", Codec.STRING, false),
                        (o, v) -> o.category = v, o -> o.category, (o, p) -> o.category = p.category)
                .documentation("Free grouping label, interpreted by whatever renders the list.").add()
                .appendInherited(new KeyedCodec<>("SortOrder", Codec.INTEGER, false),
                        (o, v) -> o.sortOrder = v, o -> o.sortOrder, (o, p) -> o.sortOrder = p.sortOrder)
                .documentation("Lower sorts first within a category; unauthored means 0. Leave gaps (10, 20, "
                        + "30) so a later one can be slotted between two without renumbering.").add()
                .appendInherited(new KeyedCodec<>("Tags", Codec.STRING_ARRAY, false),
                        (o, v) -> o.tags = v, o -> o.tags, (o, p) -> o.tags = p.tags)
                .documentation("Free classification carried through to anything listening for this content's "
                        + "events, so a mod can count or filter by its own vocabulary. Nothing here interprets "
                        + "them.").add()
                .appendInherited(new KeyedCodec<>("Chains",
                                new ArrayCodec<>(ChainMembership.CODEC, ChainMembership[]::new), false),
                        (o, v) -> o.chains = v, o -> o.chains, (o, p) -> o.chains = p.chains)
                .documentation("The tiered ladders this is a rung of, so a surface can show a whole climb as "
                        + "one entry instead of a row of near-identical ones. A piece of content may be a rung "
                        + "of SEVERAL ladders at once, each with its own tier - a mining achievement can be "
                        + "rung 3 of the copper ladder and rung 1 of the whole-game one. This is ONE leaf: "
                        + "author it and an inherited list is replaced whole.").add()
                .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                        (o, v) -> o.icon = v, o -> o.icon, (o, p) -> o.icon = p.icon)
                .documentation("An item id to illustrate it with. Unauthored leaves the choice to whatever "
                        + "renders it.").add()
                .appendInherited(new KeyedCodec<>("Hidden", Codec.BOOLEAN, false),
                        (o, v) -> o.hidden = v, o -> o.hidden, (o, p) -> o.hidden = p.hidden)
                .documentation("Keep it off open listings, for content reached some other way (a chain step, "
                        + "an event, a surprise). It still progresses; only the listing is affected, and "
                        + "anything a player already holds or has earned always shows.").add()
                .appendInherited(new KeyedCodec<>("RequirePrerequisites", Codec.BOOLEAN, false),
                        (o, v) -> o.requirePrerequisites = v, o -> o.requirePrerequisites,
                        (o, p) -> o.requirePrerequisites = p.requirePrerequisites)
                .documentation("Hide it until its Requires block passes, instead of showing it locked. "
                        + "Unauthored means shown locked, which is usually kinder: a player can see what to "
                        + "work towards.").add();
    }

    public ContentListingAsset() {
    }

    @Nullable
    public String getCategory() {
        return category;
    }

    @Nullable
    public Integer getSortOrder() {
        return sortOrder;
    }

    public int sortOrderOrZero() {
        return sortOrder == null ? 0 : sortOrder;
    }

    @Nullable
    public String[] getTags() {
        return tags == null ? null : tags.clone();
    }

    /** The labels, blanks dropped, in authored order. */
    @Nonnull
    public List<String> tagList() {
        String[] authored = tags;
        if (authored == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(authored.length);
        for (String tag : authored) {
            if (tag != null && !tag.isBlank()) {
                out.add(tag.trim());
            }
        }
        return out;
    }

    /** The item id that illustrates this, or null when it authors none. */
    @Nullable
    public String getIcon() {
        return icon == null || icon.isBlank() ? null : icon.trim();
    }

    /** Kept off open listings? Unauthored means listed. */
    public boolean isHidden() {
        return hidden != null && hidden;
    }

    /** Hidden until its Requires block passes? Unauthored means shown locked. */
    public boolean isRequirePrerequisites() {
        return requirePrerequisites != null && requirePrerequisites;
    }

    /** The ladders this is a rung of, in authored order; the FIRST is the primary one. */
    @Nonnull
    public List<ChainMembership> chainList() {
        ChainMembership[] authored = chains;
        if (authored == null) {
            return List.of();
        }
        List<ChainMembership> out = new ArrayList<>(authored.length);
        for (ChainMembership chain : authored) {
            if (chain != null && chain.getId() != null) {
                out.add(chain);
            }
        }
        return out;
    }

    /**
     * What is wrong with a set of ladder memberships, if anything: an id named twice by one piece of
     * content, and a rung that is not a rung. Both are otherwise silent - a duplicate simply loses
     * to itself, and a zero rung sorts as though it were unnumbered - so they are worth saying out
     * loud at load.
     *
     * <p>Declared here beside the leaf rather than in either engine's validator, for the same reason
     * the leaf itself is: two copies of one rule drift.
     */
    @Nonnull
    public static List<Finding> chainFindings(@Nonnull List<ChainMembership> chains,
            @Nonnull String domain, @Nonnull String contentId) {
        List<Finding> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ChainMembership chain : chains) {
            String chainId = chain.getId();
            if (chainId == null) {
                continue;
            }
            if (!seen.add(chainId)) {
                out.add(Finding.warning(domain, "DUPLICATE_CHAIN",
                        "it is listed twice as a rung of the ladder '" + chainId + "'; only the first "
                                + "membership is read, so the second says nothing", contentId));
            }
            if (chain.tierOrZero() <= 0) {
                out.add(Finding.warning(domain, "NON_POSITIVE_CHAIN_TIER",
                        "its rung on the ladder '" + chainId + "' is " + chain.tierOrZero()
                                + "; rungs are counted from 1, and anything lower cannot be placed on the "
                                + "climb", contentId));
            }
        }
        return out;
    }

    // ==================== ChainMembership ====================

    /**
     * One ladder this content is a rung of, and which rung it is.
     *
     * <p>Ids are free strings and deliberately unnamespaced: a ladder is a grouping an author
     * invents for their own content, and two mods that happen to pick the same word have simply
     * built one ladder together, which is usually what they wanted.
     */
    public static final class ChainMembership {

        @Nullable protected String id;
        @Nullable protected Integer tier;

        public static final BuilderCodec<ChainMembership> CODEC =
                BuilderCodec.builder(ChainMembership.class, ChainMembership::new)
                        .appendInherited(new KeyedCodec<>("Id", Codec.STRING, false),
                                (o, v) -> o.id = v, o -> o.id, (o, p) -> o.id = p.id)
                        .documentation("The ladder's own id, shared by every rung of it. Give each ladder its "
                                + "own: two unrelated ladders sharing one are shown as a single climb.").add()
                        .appendInherited(new KeyedCodec<>("Tier", Codec.INTEGER, false),
                                (o, v) -> o.tier = v, o -> o.tier, (o, p) -> o.tier = p.tier)
                        .documentation("Which rung this is, counting from 1. Number them in the order a player "
                                + "climbs them, since a listing shows the highest one reached.").add()
                        .build();

        public ChainMembership() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static ChainMembership of(@Nullable String id, @Nullable Integer tier) {
            ChainMembership c = new ChainMembership();
            c.id = id;
            c.tier = tier;
            return c;
        }

        /** The ladder id, lower-cased, or null when the entry names none. */
        @Nullable
        public String getId() {
            return id == null || id.isBlank() ? null : id.trim().toLowerCase(Locale.ROOT);
        }

        /** The authored rung, or null when unauthored. */
        @Nullable
        public Integer getTier() {
            return tier;
        }

        /** Which rung, or 0 when unauthored. */
        public int tierOrZero() {
            return tier == null ? 0 : tier;
        }
    }
}
