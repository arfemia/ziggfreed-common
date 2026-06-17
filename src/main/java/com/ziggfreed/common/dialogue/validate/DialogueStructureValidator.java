package com.ziggfreed.common.dialogue.validate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import com.ziggfreed.common.dialogue.DialogueAction;
import com.ziggfreed.common.dialogue.DialogueNode;
import com.ziggfreed.common.dialogue.DialogueOption;
import com.ziggfreed.common.dialogue.NpcDialogue;

/**
 * Domain-agnostic STRUCTURAL audit of a decoded dialogue tree: missing/dangling
 * start, dangling {@code Goto}, unreachable nodes, and a dialogue that resolved to
 * zero nodes. It knows nothing about a consumer's action/condition types - a
 * consumer that registers domain actions (quest accept, reward grant) runs its own
 * pass over the tree for those refs and merges the findings with these. Returns
 * neutral {@link Issue}s the consumer maps into its own reporting framework.
 */
public final class DialogueStructureValidator {

    public enum Severity { ERROR, WARNING }

    /** One structural finding: a severity, a stable code, a message, and the owning dialogue id. */
    public record Issue(@Nonnull Severity severity, @Nonnull String code,
                        @Nonnull String message, @Nonnull String dialogueId) {

        @Nonnull
        public static Issue error(@Nonnull String code, @Nonnull String message, @Nonnull String id) {
            return new Issue(Severity.ERROR, code, message, id);
        }

        @Nonnull
        public static Issue warning(@Nonnull String code, @Nonnull String message, @Nonnull String id) {
            return new Issue(Severity.WARNING, code, message, id);
        }
    }

    private DialogueStructureValidator() {
    }

    @Nonnull
    public static List<Issue> validateAll(@Nonnull Collection<NpcDialogue> dialogues) {
        List<Issue> out = new ArrayList<>();
        for (NpcDialogue dialogue : dialogues) {
            validate(dialogue, out);
        }
        return out;
    }

    @Nonnull
    public static List<Issue> validate(@Nonnull NpcDialogue dialogue) {
        List<Issue> out = new ArrayList<>();
        validate(dialogue, out);
        return out;
    }

    private static void validate(@Nonnull NpcDialogue dialogue, @Nonnull List<Issue> out) {
        String id = dialogue.getId();

        if (dialogue.getNodes().isEmpty()) {
            out.add(Issue.error("EMPTY_AFTER_RESOLVE",
                    "Dialogue '" + id + "' resolved to zero nodes (template pruned everything?)", id));
            return;
        }

        Set<String> entryNodes = new HashSet<>();
        if (dialogue.getStart().isEmpty()) {
            out.add(Issue.warning("MISSING_START",
                    "Dialogue '" + id + "' has no Start candidates (falls back to the first node)", id));
            entryNodes.add(dialogue.getNodes().keySet().iterator().next());
        }
        for (NpcDialogue.DialogueEntry entry : dialogue.getStart()) {
            String node = entry.getNode();
            if (node == null || node.isBlank()) {
                out.add(Issue.error("START_MISSING_NODE",
                        "Dialogue '" + id + "' has a Start candidate without a Node", id));
                continue;
            }
            if (dialogue.getNode(node) == null) {
                out.add(Issue.error("START_MISSING_NODE",
                        "Dialogue '" + id + "' Start references missing node '" + node + "'", id));
            } else {
                entryNodes.add(node);
            }
        }

        // Validate every Goto target regardless of reachability.
        for (var nodeEntry : dialogue.getNodes().entrySet()) {
            String nodeId = nodeEntry.getKey();
            DialogueNode node = nodeEntry.getValue();
            for (int i = 0; i < node.getOptions().size(); i++) {
                DialogueOption option = node.getOptions().get(i);
                for (DialogueAction action : option.getActions()) {
                    if (action instanceof DialogueAction.Goto go) {
                        String target = go.getNode();
                        String where = "node '" + nodeId + "' option " + i;
                        if (target == null || target.isBlank()) {
                            out.add(Issue.error("GOTO_MISSING_NODE",
                                    "Dialogue '" + id + "' " + where + " has a Goto without a Node", id));
                        } else if (dialogue.getNode(target) == null) {
                            out.add(Issue.error("GOTO_MISSING_NODE",
                                    "Dialogue '" + id + "' " + where + " Goto references missing node '"
                                            + target + "'", id));
                        }
                    }
                }
            }
        }

        // Reachability BFS over Goto edges from the entry set.
        Set<String> reachable = new HashSet<>(entryNodes);
        Deque<String> frontier = new ArrayDeque<>(entryNodes);
        while (!frontier.isEmpty()) {
            DialogueNode node = dialogue.getNode(frontier.poll());
            if (node == null) {
                continue;
            }
            for (DialogueOption option : node.getOptions()) {
                for (DialogueAction action : option.getActions()) {
                    if (action instanceof DialogueAction.Goto go && go.getNode() != null
                            && dialogue.getNode(go.getNode()) != null
                            && reachable.add(go.getNode())) {
                        frontier.add(go.getNode());
                    }
                }
            }
        }
        for (String nodeId : dialogue.getNodes().keySet()) {
            if (!reachable.contains(nodeId)) {
                out.add(Issue.warning("UNREACHABLE_NODE",
                        "Dialogue '" + id + "' node '" + nodeId + "' is unreachable from Start", id));
            }
        }
    }
}
