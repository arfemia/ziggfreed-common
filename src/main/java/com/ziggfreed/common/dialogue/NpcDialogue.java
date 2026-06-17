package com.ziggfreed.common.dialogue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A branching NPC dialogue: a STANDALONE tree keyed by its OWN id (never bound to
 * one NPC - NPCs attach a dialogue, so one tree can serve a whole camp).
 * {@code Start} is an ordered list of entry candidates whose conditions pick the
 * greeting node (first passing candidate wins, so greeting text tracks state);
 * {@code Nodes} maps node ids to {@link DialogueNode}s.
 *
 * <p>A pure data POJO: its codec is assembled per-{@link DialogueEngine} (so the
 * action/condition dispatch codecs carry the consumer's registered types), which
 * is why the fields are package-private and set by the engine's codec lambdas
 * rather than via a static codec here. Build one directly with {@link #setTree}
 * (or decode a JSON body via {@link DialogueEngine#decode}).
 */
public class NpcDialogue {

    protected String id = "";
    @Nullable DialogueEntry[] start;
    @Nullable Map<String, DialogueNode> nodes;

    public NpcDialogue() {
    }

    /** The dialogue id (lowercased). */
    @Nonnull
    public String getId() {
        return id;
    }

    public void setId(@Nonnull String id) {
        this.id = id.toLowerCase(Locale.ROOT);
    }

    /** Direct (non-codec) construction: fill the tree from Java. */
    public void setTree(@Nullable DialogueEntry[] start, @Nullable Map<String, DialogueNode> nodes) {
        this.start = start;
        this.nodes = nodes;
    }

    @Nonnull
    public List<DialogueEntry> getStart() {
        return start == null ? Collections.emptyList() : List.of(start);
    }

    @Nonnull
    public Map<String, DialogueNode> getNodes() {
        return nodes == null ? Collections.emptyMap() : nodes;
    }

    @Nullable
    public DialogueNode getNode(@Nullable String nodeId) {
        if (nodeId == null) {
            return null;
        }
        return getNodes().get(nodeId);
    }

    @Nonnull
    static Map<String, DialogueNode> emptyNodeMap() {
        return new LinkedHashMap<>();
    }

    /** One ordered entry candidate: a node id plus optional AND-combined conditions. */
    public static class DialogueEntry {

        @Nullable String node;
        @Nullable DialogueCondition[] conditions;

        public DialogueEntry() {
        }

        @Nullable public String getNode() { return node; }

        @Nonnull
        public List<DialogueCondition> getConditions() {
            return conditions == null ? Collections.emptyList() : List.of(conditions);
        }
    }
}
