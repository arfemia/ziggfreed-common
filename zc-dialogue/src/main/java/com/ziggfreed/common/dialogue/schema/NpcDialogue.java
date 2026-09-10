package com.ziggfreed.common.dialogue.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.CommonLog;
import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.state.DialogueMemory;

/**
 * A branching NPC dialogue: a STANDALONE tree keyed by its OWN id (never bound to
 * one NPC - NPCs attach a dialogue, so one tree can serve a whole camp).
 * {@code Start} declares WHICH screen a conversation opens on, in sections the engine
 * walks in a fixed order (see {@link DialogueStart}); {@code Nodes} maps node ids to
 * {@link DialogueNode}s. An optional top-level {@code Memories} map declares the named
 * things this conversation can remember about a player (see {@link DialogueMemory}).
 *
 * <p>A pure data POJO: its codec is assembled per-{@link DialogueEngine} (so the
 * action/condition dispatch codecs carry the consumer's registered types), which
 * is why the fields are package-private and set by the engine's codec lambdas
 * rather than via a static codec here. Build one directly with {@link #setTree}
 * (or decode a JSON body via {@link DialogueEngine#decode}).
 */
public class NpcDialogue {

    protected String id = "";
    @Nullable DialogueStart start;
    @Nullable Map<String, DialogueNode> nodes;
    @Nullable Map<String, DialogueMemory> memories;
    @Nullable Map<String, DialogueFragmentGroup> fragments;
    @Nullable List<String> headerSources;
    @Nullable DialogueChrome chrome;

    /**
     * The include cycles already reported, one line per conversation and re-entered group for the
     * life of the process: the splice runs on every read of the file, and a cycle the audit also
     * names is worth one line, not one per boot per reload.
     */
    private static final Set<String> WARNED_CYCLES = ConcurrentHashMap.newKeySet();

    public NpcDialogue() {
    }

    /**
     * The shared option groups this conversation declares, keyed by name, in the order written: for
     * screens that name one with {@code IncludeOptions}, and for the groups that name their screens
     * themselves through {@code On}. Empty when it declares none.
     */
    @Nonnull
    public Map<String, DialogueFragmentGroup> getFragments() {
        return fragments == null ? Collections.emptyMap() : fragments;
    }

    /** Direct (non-codec) construction: declare the shared option groups from Java. */
    public void setFragments(@Nullable Map<String, DialogueFragmentGroup> fragments) {
        this.fragments = fragments;
    }

    /**
     * Give each screen the shared option groups it gets, once, right after the whole conversation
     * has been read (so a screen inherited from a parent picks up the child's groups too, and an
     * unknown name is reported against the conversation that used it).
     *
     * <p>A screen gets a group two ways, and the result reads in a fixed order: the screen's own
     * {@code Options} first, then every group whose {@code On} selects the screen (in the order the
     * groups are declared), then every group the screen's {@code IncludeOptions} names (in the order
     * written). A group reaching a screen both ways is spliced once, where the screen named it. That
     * keeps the screen's own indices exactly where the file put them, and keeps a footer last. The
     * same option object is shared by every screen that gets the group; nothing about an option
     * depends on which screen it is shown from, so there is nothing to copy.
     *
     * <p>A group's lines are its own {@code Options} followed by the lines of every group its
     * {@code Include} names, recursively, in the order written. A group met again on the way down
     * from itself is dropped at that point rather than followed, with one warning; the audit reports
     * the same cycle as a finding.
     *
     * <p>A name is looked for in this conversation's own {@code Fragments} first and in the shared
     * {@code DialogueFragments} files second, so a conversation that wants its own version of a
     * server-wide footer writes one under its own {@code Fragments} and that is the one its screens
     * get. Only a name neither answers is reported. A shared file is pull-only: it has no {@code On}
     * and lands only where a screen or a group names it.
     */
    public void spliceFragments() {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        Map<String, DialogueFragmentGroup> declared = getFragments();
        Map<String, DialogueNode> spliced = null;
        for (Map.Entry<String, DialogueNode> entry : nodes.entrySet()) {
            DialogueNode node = entry.getValue();
            if (node == null) {
                continue;
            }
            String nodeId = entry.getKey();
            List<String> pulled = node.getIncludeOptions();
            List<String> pushed = pushedGroups(declared, nodeId, node, pulled);
            if (pulled.isEmpty() && pushed.isEmpty()) {
                continue;
            }
            // Start from what the screen itself AUTHORED, never from an earlier splice of it: under
            // Parent a screen the child did not restate is the parent's own object, already spliced
            // when the parent was read, and appending to that would show the shared lines twice here
            // and change what the parent conversation says.
            List<DialogueOption> merged = new ArrayList<>(node.getAuthoredOptions());
            for (String name : pushed) {
                appendGroup(declared, name, nodeId, merged);
            }
            for (String name : pulled) {
                appendGroup(declared, name, nodeId, merged);
            }
            // And write the result onto a COPY, into a map of this conversation's own, so a screen
            // (or a whole screen map) shared with the conversation it inherits from is never touched.
            if (spliced == null) {
                spliced = new LinkedHashMap<>(nodes);
            }
            spliced.put(nodeId, node.withSplicedOptions(merged.toArray(new DialogueOption[0])));
        }
        if (spliced != null) {
            nodes = spliced;
        }
    }

    /**
     * The names of this conversation's own groups whose {@code On} selects the screen, in
     * declaration order, leaving out any the screen already names itself (those are spliced where the
     * screen put them).
     */
    @Nonnull
    private static List<String> pushedGroups(@Nonnull Map<String, DialogueFragmentGroup> declared,
                                             @Nonnull String nodeId, @Nonnull DialogueNode node,
                                             @Nonnull List<String> pulled) {
        List<String> pushed = new ArrayList<>();
        for (Map.Entry<String, DialogueFragmentGroup> group : declared.entrySet()) {
            DialogueFragmentGroup value = group.getValue();
            if (value == null || value.getOn() == null || !value.getOn().selects(nodeId, node.getTags())) {
                continue;
            }
            if (NodeSelector.containsIgnoreCase(pulled, group.getKey())) {
                continue;
            }
            pushed.add(group.getKey());
        }
        return pushed;
    }

    /** Append the lines of the group {@code name} (own lines, then its includes) onto {@code into}. */
    private void appendGroup(@Nonnull Map<String, DialogueFragmentGroup> declared, @Nullable String name,
                             @Nonnull String nodeId, @Nonnull List<DialogueOption> into) {
        DialogueFragmentGroup group = resolveFragment(declared, name);
        if (group == null) {
            unknownFragment(nodeId, name);
            return;
        }
        Set<String> onTheWayDown = new LinkedHashSet<>();
        onTheWayDown.add(NodeSelector.fold(name));
        expand(declared, group, nodeId, onTheWayDown, into);
    }

    /**
     * The lines of {@code group}, then the lines of each group it includes, in order. A name already
     * on the way down from itself is a cycle: it is dropped here, once, rather than followed, so a
     * file that closes the loop still loads with every line before the loop in place.
     */
    private void expand(@Nonnull Map<String, DialogueFragmentGroup> declared,
                        @Nonnull DialogueFragmentGroup group, @Nonnull String nodeId,
                        @Nonnull Set<String> onTheWayDown, @Nonnull List<DialogueOption> into) {
        into.addAll(group.getOptions());
        for (String included : group.getInclude()) {
            if (included == null) {
                continue;
            }
            String folded = NodeSelector.fold(included);
            if (onTheWayDown.contains(folded)) {
                includeCycle(included, onTheWayDown);
                continue;
            }
            DialogueFragmentGroup next = resolveFragment(declared, included);
            if (next == null) {
                unknownFragment(nodeId, included);
                continue;
            }
            onTheWayDown.add(folded);
            expand(declared, next, nodeId, onTheWayDown, into);
            onTheWayDown.remove(folded);
        }
    }

    /**
     * This conversation's own group of that name, else the shared file of that name, else null. A
     * local name is matched as written first and then without regard to case, so the two lookups
     * answer the same spelling rule.
     */
    @Nullable
    private static DialogueFragmentGroup resolveFragment(@Nonnull Map<String, DialogueFragmentGroup> declared,
                                                         @Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        DialogueFragmentGroup local = declared.get(name);
        if (local != null) {
            return local;
        }
        String folded = NodeSelector.fold(name);
        for (Map.Entry<String, DialogueFragmentGroup> entry : declared.entrySet()) {
            if (entry.getValue() != null && NodeSelector.fold(entry.getKey()).equals(folded)) {
                return entry.getValue();
            }
        }
        return DialogueFragmentConfig.getInstance().group(name);
    }

    private void unknownFragment(@Nonnull String nodeId, @Nullable String name) {
        try {
            CommonLog.LOGGER.atWarning().log(
                    "[Dialogue] '%s' screen '%s' pulls in shared options '%s', which neither this"
                            + " conversation's Fragments nor any DialogueFragments file provides",
                    id, nodeId, String.valueOf(name));
        } catch (Throwable ignored) {
            // a unit JVM with no log manager throws an Error from the fluent logger; swallow it.
        }
    }

    private void includeCycle(@Nonnull String reentered, @Nonnull Set<String> onTheWayDown) {
        if (!WARNED_CYCLES.add(id + ":" + NodeSelector.fold(reentered))) {
            return;
        }
        try {
            CommonLog.LOGGER.atWarning().log(
                    "[Dialogue] '%s' shared option group '%s' includes itself by way of %s; the"
                            + " re-entry is dropped so the file still loads, but remove the loop",
                    id, reentered, onTheWayDown);
        } catch (Throwable ignored) {
            // a unit JVM with no log manager throws an Error from the fluent logger; swallow it.
        }
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
    public void setTree(@Nullable DialogueStart start, @Nullable Map<String, DialogueNode> nodes) {
        this.start = start;
        this.nodes = nodes;
    }

    /**
     * Which screen this conversation opens on, in the sections the engine walks. Never null: a
     * conversation that authored none gets {@link DialogueStart#EMPTY}, and the engine then opens on
     * the first screen whose own conditions pass.
     */
    @Nonnull
    public DialogueStart getStart() {
        return start == null ? DialogueStart.EMPTY : start;
    }

    /** True when this conversation authored a {@code Start} that decides something. */
    public boolean hasStart() {
        return start != null && !start.isEmpty();
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

    /**
     * The header sources this conversation shows under the speaker's name, in order; the first that
     * has something to say is the line drawn. Empty means no header, which is the default.
     */
    @Nonnull
    public List<String> getHeaderSources() {
        return headerSources == null ? List.of() : headerSources;
    }

    /** Direct (non-codec) construction: declare the header sources from Java. */
    public void setHeaderSources(@Nullable List<String> sources) {
        this.headerSources = sources == null || sources.isEmpty() ? null : List.copyOf(sources);
    }

    /**
     * This conversation's own wording for the page's built-in lines, or null for the library's. Only
     * a character with a voice of their own needs it; see {@link DialogueChrome}.
     */
    @Nullable
    public DialogueChrome getChrome() {
        return chrome;
    }

    /** Direct (non-codec) construction: declare the chrome wording from Java. */
    public void setChrome(@Nullable DialogueChrome chrome) {
        this.chrome = chrome;
    }

    @Nonnull
    static Map<String, DialogueNode> emptyNodeMap() {
        return new LinkedHashMap<>();
    }
}
