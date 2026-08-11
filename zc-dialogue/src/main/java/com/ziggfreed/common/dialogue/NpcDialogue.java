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
 * {@code Nodes} maps node ids to {@link DialogueNode}s. An optional top-level
 * {@code Memories} map declares the named things this conversation can remember
 * about a player (see {@link DialogueMemory}).
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
    @Nullable Map<String, DialogueMemory> memories;

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

    /**
     * The named things this conversation can remember about a player, declared once at the top
     * level and referred to by bare name by the {@code Remember}/{@code Forget} actions and the
     * {@code Remembered}/{@code NotRemembered} conditions. Empty when the dialogue declares none.
     */
    @Nonnull
    public Map<String, DialogueMemory> getMemories() {
        return memories == null ? Collections.emptyMap() : memories;
    }

    /** The declaration for {@code name} (case-insensitive), or null when it was never declared. */
    @Nullable
    public DialogueMemory getMemory(@Nullable String name) {
        if (name == null || name.isBlank() || memories == null) {
            return null;
        }
        DialogueMemory direct = memories.get(name);
        if (direct != null) {
            return direct;
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, DialogueMemory> entry : memories.entrySet()) {
            if (entry.getKey().trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Direct (non-codec) construction: declare the memories from Java. */
    public void setMemories(@Nullable Map<String, DialogueMemory> memories) {
        this.memories = memories;
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

    /**
     * One ordered entry candidate: a node id, optional AND-combined conditions, and an optional
     * {@link DialogueOnce} that retires the entry after the player has played that beat through.
     */
    public static class DialogueEntry {

        @Nullable String node;
        @Nullable DialogueCondition[] conditions;
        @Nullable DialogueOnce once;

        public DialogueEntry() {
        }

        @Nullable public String getNode() { return node; }

        @Nonnull
        public List<DialogueCondition> getConditions() {
            return conditions == null ? Collections.emptyList() : List.of(conditions);
        }

        /**
         * The first-visit knob, or null when this entry may be picked any number of times. The
         * entry stops matching once the player finishes the beat it routed to (chooses any option
         * on that node, the implicit Farewell included); leaving with Escape shows it again.
         */
        @Nullable public DialogueOnce getOnce() { return once; }
    }
}
