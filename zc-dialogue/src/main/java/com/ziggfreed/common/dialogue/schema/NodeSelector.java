package com.ziggfreed.common.dialogue.schema;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Which screens of a conversation a shared option group lands on, written on the group's own
 * {@code On} leaf: {@code Nodes} names screens by their exact id, {@code Tags} names every screen
 * carrying any of those tags, and {@code Exclude} takes named screens back out of whatever the two
 * positive axes selected.
 *
 * <pre>{@code
 * "On": { "Tags": ["Temple_Landing"] }
 * "On": { "Nodes": ["greet", "menu"], "Exclude": ["menu"] }
 * }</pre>
 *
 * <p>Three independent nullable leaves in the shape of the family's {@code Where} selector, so an
 * author who has written one has already learned this one. A selector with no positive axis selects
 * nothing at all, which is a validator finding rather than a quiet no-op, exactly as a {@code Where}
 * with only an {@code ExcludeMatch} is. Every id and tag is matched without regard to case, like
 * every other id in this family, and authored {@code Is_Like_This}.
 *
 * <p>The leaves inherit one by one under {@code Parent}, so a child conversation that restates a
 * group's {@code On} with only {@code Exclude} keeps the parent's {@code Tags}.
 */
public final class NodeSelector {

    public static final BuilderCodec<NodeSelector> CODEC = BuilderCodec.builder(NodeSelector.class, NodeSelector::new)
            .appendInherited(new KeyedCodec<>("Nodes", Codec.STRING_ARRAY, false),
                    (s, v) -> s.nodes = v, s -> s.nodes, (child, parent) -> child.nodes = parent.nodes)
            .documentation("Screens named by their exact id. Write this for a handful of screens you can "
                    + "list; write Tags for a kind of screen that will be added to later.").add()
            .appendInherited(new KeyedCodec<>("Tags", Codec.STRING_ARRAY, false),
                    (s, v) -> s.tags = v, s -> s.tags, (child, parent) -> child.tags = parent.tags)
            .documentation("Every screen carrying any of these tags gets the group. A screen declares its "
                    + "tags on its own Tags leaf, so a screen written next month is tagged by whoever "
                    + "writes it and picks the group up without anybody editing the group.").add()
            .appendInherited(new KeyedCodec<>("Exclude", Codec.STRING_ARRAY, false),
                    (s, v) -> s.exclude = v, s -> s.exclude,
                    (child, parent) -> child.exclude = parent.exclude)
            .documentation("Screens by exact id that Nodes or Tags selected and should not get the group "
                    + "after all. A filter over the two above, never a selector on its own.").add()
            .build();

    @Nullable String[] nodes;
    @Nullable String[] tags;
    @Nullable String[] exclude;

    public NodeSelector() {
    }

    /** Java-side construction (tests, a consumer building a tree in code). */
    @Nonnull
    public static NodeSelector of(@Nullable String[] nodes, @Nullable String[] tags,
                                  @Nullable String[] exclude) {
        NodeSelector selector = new NodeSelector();
        selector.nodes = nodes;
        selector.tags = tags;
        selector.exclude = exclude;
        return selector;
    }

    /** The exact screen ids this selector names, in the order written. Empty when it names none. */
    @Nonnull
    public List<String> getNodes() {
        return nodes == null ? Collections.emptyList() : List.of(nodes);
    }

    /** The tags this selector names, in the order written. Empty when it names none. */
    @Nonnull
    public List<String> getTags() {
        return tags == null ? Collections.emptyList() : List.of(tags);
    }

    /** The exact screen ids taken back out, in the order written. Empty when there are none. */
    @Nonnull
    public List<String> getExclude() {
        return exclude == null ? Collections.emptyList() : List.of(exclude);
    }

    /** True when neither {@code Nodes} nor {@code Tags} is authored, so this selects no screen at all. */
    public boolean hasNoPositiveAxis() {
        return getNodes().isEmpty() && getTags().isEmpty();
    }

    /**
     * Whether the screen {@code nodeId}, carrying {@code nodeTags}, is one this selector lands on:
     * named by id or by any tag it carries, and not taken back out by {@code Exclude}. Every
     * comparison ignores case.
     */
    public boolean selects(@Nonnull String nodeId, @Nonnull Collection<String> nodeTags) {
        if (containsIgnoreCase(getExclude(), nodeId)) {
            return false;
        }
        if (containsIgnoreCase(getNodes(), nodeId)) {
            return true;
        }
        for (String tag : nodeTags) {
            if (tag != null && containsIgnoreCase(getTags(), tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code values} holds {@code wanted} under any casing (blank entries never match):
     * the one rule every group name, screen id and tag comparison in the splice and the audit runs
     * through.
     */
    public static boolean containsIgnoreCase(@Nonnull Collection<String> values, @Nullable String wanted) {
        if (wanted == null || wanted.isBlank()) {
            return false;
        }
        String folded = fold(wanted);
        for (String value : values) {
            if (value != null && fold(value).equals(folded)) {
                return true;
            }
        }
        return false;
    }

    /** The one case fold every id and tag comparison here runs through. */
    @Nonnull
    public static String fold(@Nonnull String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
