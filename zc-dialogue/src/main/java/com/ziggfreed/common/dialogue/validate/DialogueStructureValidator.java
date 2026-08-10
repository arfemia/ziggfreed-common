package com.ziggfreed.common.dialogue.validate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.dialogue.DialogueAction;
import com.ziggfreed.common.dialogue.DialogueCondition;
import com.ziggfreed.common.dialogue.DialogueFlagScope;
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
 *
 * <p>It also audits the GENERIC world-identity references, because every way they can be wrong is
 * otherwise SILENT: a {@code World} condition with no positive axis never passes (its content is
 * permanently invisible), and a {@code Scope} naming a selector nothing contributes makes a scoped
 * flag unwritable and unreadable forever (so a first-visit beat re-fires on every single visit).
 * Pass the loaded selector vocabulary - {@code DialogueWorlds.knownSelectorNames()} - to the
 * {@code knownSelectorNames} overloads to enable the unknown-name checks; a null or EMPTY set
 * means "cannot tell" and skips them, so validating before assets load never produces a false
 * alarm.
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
        return validateAll(dialogues, null);
    }

    /** {@link #validateAll(Collection)} plus the world-selector vocabulary checks. */
    @Nonnull
    public static List<Issue> validateAll(@Nonnull Collection<NpcDialogue> dialogues,
                                          @Nullable Set<String> knownSelectorNames) {
        List<Issue> out = new ArrayList<>();
        for (NpcDialogue dialogue : dialogues) {
            validate(dialogue, out, knownSelectorNames);
        }
        return out;
    }

    @Nonnull
    public static List<Issue> validate(@Nonnull NpcDialogue dialogue) {
        return validate(dialogue, (Set<String>) null);
    }

    /** {@link #validate(NpcDialogue)} plus the world-selector vocabulary checks. */
    @Nonnull
    public static List<Issue> validate(@Nonnull NpcDialogue dialogue,
                                       @Nullable Set<String> knownSelectorNames) {
        List<Issue> out = new ArrayList<>();
        validate(dialogue, out, knownSelectorNames);
        return out;
    }

    private static void validate(@Nonnull NpcDialogue dialogue, @Nonnull List<Issue> out,
                                 @Nullable Set<String> knownSelectorNames) {
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
        int startIndex = 0;
        for (NpcDialogue.DialogueEntry entry : dialogue.getStart()) {
            checkConditions(entry.getConditions(), "Start candidate " + startIndex++, id, out,
                    knownSelectorNames);
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

        // Validate every Goto target regardless of reachability, plus the world-identity refs.
        for (var nodeEntry : dialogue.getNodes().entrySet()) {
            String nodeId = nodeEntry.getKey();
            DialogueNode node = nodeEntry.getValue();
            checkConditions(node.getConditions(), "node '" + nodeId + "'", id, out, knownSelectorNames);
            for (int i = 0; i < node.getOptions().size(); i++) {
                DialogueOption option = node.getOptions().get(i);
                String where = "node '" + nodeId + "' option " + i;
                checkConditions(option.getConditions(), where, id, out, knownSelectorNames);
                for (DialogueAction action : option.getActions()) {
                    if (action instanceof DialogueAction.Goto go) {
                        String target = go.getNode();
                        if (target == null || target.isBlank()) {
                            out.add(Issue.error("GOTO_MISSING_NODE",
                                    "Dialogue '" + id + "' " + where + " has a Goto without a Node", id));
                        } else if (dialogue.getNode(target) == null) {
                            out.add(Issue.error("GOTO_MISSING_NODE",
                                    "Dialogue '" + id + "' " + where + " Goto references missing node '"
                                            + target + "'", id));
                        }
                    } else if (action instanceof DialogueAction.SetFlag setFlag) {
                        checkScope(setFlag.getScope(), where + " SetFlag", id, out, knownSelectorNames);
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

    // ==================== World-identity references ====================

    /**
     * Audit a condition list (recursing through the boolean combinators, which is where a
     * {@code World} condition or a scoped {@code Flag} most often hides) for the generic
     * world-identity references.
     */
    private static void checkConditions(@Nonnull List<DialogueCondition> conditions,
                                        @Nonnull String where, @Nonnull String id,
                                        @Nonnull List<Issue> out,
                                        @Nullable Set<String> knownSelectorNames) {
        for (DialogueCondition condition : conditions) {
            if (condition instanceof DialogueCondition.Combinator combinator) {
                checkConditions(combinator.getChildren(), where, id, out, knownSelectorNames);
            } else if (condition instanceof DialogueCondition.World world) {
                checkWorldCondition(world, where, id, out, knownSelectorNames);
            } else if (condition instanceof DialogueCondition.Flag flag) {
                checkScope(flag.getScope(), where + " Flag", id, out, knownSelectorNames);
            } else if (condition instanceof DialogueCondition.NotFlag notFlag) {
                checkScope(notFlag.getScope(), where + " NotFlag", id, out, knownSelectorNames);
            }
        }
    }

    private static void checkWorldCondition(@Nonnull DialogueCondition.World condition,
                                            @Nonnull String where, @Nonnull String id,
                                            @Nonnull List<Issue> out,
                                            @Nullable Set<String> knownSelectorNames) {
        // A selector with only ExcludeNames (or nothing at all) matches NOTHING, so the gated
        // content can never appear. That reads as the opposite of the author's intent.
        if (condition.getSelector().hasNoPositiveAxis()) {
            out.add(Issue.error("WORLD_CONDITION_NO_AXIS",
                    "Dialogue '" + id + "' " + where + " has a World condition with no Names/Match/"
                            + "GameplayConfig, so it can never pass and its content is invisible", id));
            return;
        }
        String[] names = condition.getNames();
        if (names == null || !hasKnownNames(knownSelectorNames)) {
            return;
        }
        for (String name : names) {
            if (isUnknown(name, knownSelectorNames)) {
                out.add(Issue.error("WORLD_CONDITION_UNKNOWN_SELECTOR",
                        "Dialogue '" + id + "' " + where + " has a World condition naming selector '"
                                + name + "', which no loaded WorldSelector contributes", id));
            }
        }
    }

    private static void checkScope(@Nullable DialogueFlagScope scope, @Nonnull String where,
                                   @Nonnull String id, @Nonnull List<Issue> out,
                                   @Nullable Set<String> knownSelectorNames) {
        if (scope == null) {
            return;
        }
        if (scope.isBlank()) {
            out.add(Issue.error("FLAG_SCOPE_BLANK",
                    "Dialogue '" + id + "' " + where + " has a Scope with no WorldSelector, so the"
                            + " flag stays global - author the selector name or drop the Scope", id));
            return;
        }
        String name = scope.getWorldSelector();
        if (hasKnownNames(knownSelectorNames) && isUnknown(name, knownSelectorNames)) {
            // The soft-lock case: an unmatched scope makes the write a no-op AND the read unset,
            // so a first-visit beat gated on a scoped NotFlag re-fires on every single visit.
            out.add(Issue.error("FLAG_SCOPE_UNKNOWN_SELECTOR",
                    "Dialogue '" + id + "' " + where + " is scoped to world selector '" + name
                            + "', which no loaded WorldSelector contributes - the flag will never be"
                            + " written or read", id));
        }
    }

    /** An absent or EMPTY vocabulary means "cannot tell" (assets may not have loaded yet). */
    private static boolean hasKnownNames(@Nullable Set<String> knownSelectorNames) {
        return knownSelectorNames != null && !knownSelectorNames.isEmpty();
    }

    private static boolean isUnknown(@Nullable String name, @Nullable Set<String> knownSelectorNames) {
        return knownSelectorNames != null && name != null && !name.isBlank()
                && !knownSelectorNames.contains(name.trim().toLowerCase(Locale.ROOT));
    }
}
