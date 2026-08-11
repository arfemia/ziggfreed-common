package com.ziggfreed.common.dialogue.validate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.dialogue.DialogueAction;
import com.ziggfreed.common.dialogue.DialogueCondition;
import com.ziggfreed.common.dialogue.DialogueMemory;
import com.ziggfreed.common.dialogue.DialogueNode;
import com.ziggfreed.common.dialogue.DialogueOnce;
import com.ziggfreed.common.dialogue.DialogueOption;
import com.ziggfreed.common.dialogue.DialogueStateKeys;
import com.ziggfreed.common.dialogue.NpcDialogue;
import com.ziggfreed.common.factor.FactorRegistry;

/**
 * Domain-agnostic STRUCTURAL audit of a decoded dialogue tree: missing/dangling
 * start, dangling {@code Goto}, unreachable nodes, and a dialogue that resolved to
 * zero nodes. It knows nothing about a consumer's action/condition types - a
 * consumer that registers domain actions (quest accept, reward grant) runs its own
 * pass over the tree for those refs and merges the findings with these. Returns
 * neutral {@link Issue}s the consumer maps into its own reporting framework.
 *
 * <p>It also audits the GENERIC state surface - {@code World} conditions, {@code Once} knobs and
 * declared {@code Memories} - because every way those can be wrong is otherwise SILENT: a
 * {@code World} condition with no positive axis never passes (its content is permanently
 * invisible), a {@code Once} naming a selector nothing contributes never retires its beat (so a
 * first-visit greeting repeats forever), and a memory used without a declaration has no scope or
 * lifetime behind it. Pass the loaded selector vocabulary -
 * {@code DialogueWorlds.knownSelectorNames()} - to the {@code knownSelectorNames} overloads to
 * enable the unknown-name checks; a null or EMPTY set means "cannot tell" and skips them, so
 * validating before assets load never produces a false alarm.
 *
 * <p>Memory findings that need more than one dialogue (a {@code Shared} name declared differently
 * in two of them) only run in {@link #validateAll}, which sees the whole set.
 */
public final class DialogueStructureValidator {

    /** INFO is advisory: nothing is broken, but the content probably does not do what it says. */
    public enum Severity { ERROR, WARNING, INFO }

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

        @Nonnull
        public static Issue info(@Nonnull String code, @Nonnull String message, @Nonnull String id) {
            return new Issue(Severity.INFO, code, message, id);
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
        return validateAll(dialogues, knownSelectorNames, null);
    }

    /**
     * {@link #validateAll(Collection, Set)} plus the factor-vocabulary check. Pass the engine's
     * own {@code factors()} registry so a {@code Factor} condition naming an id nobody registered
     * is reported; {@code null} skips the check, exactly as an empty selector pool does.
     */
    @Nonnull
    public static List<Issue> validateAll(@Nonnull Collection<NpcDialogue> dialogues,
                                          @Nullable Set<String> knownSelectorNames,
                                          @Nullable FactorRegistry factors) {
        List<Issue> out = new ArrayList<>();
        for (NpcDialogue dialogue : dialogues) {
            validate(dialogue, out, knownSelectorNames, factors);
        }
        checkSharedMemoriesAgree(dialogues, out);
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
        return validate(dialogue, knownSelectorNames, null);
    }

    /** {@link #validate(NpcDialogue, Set)} plus the factor-vocabulary check. */
    @Nonnull
    public static List<Issue> validate(@Nonnull NpcDialogue dialogue,
                                       @Nullable Set<String> knownSelectorNames,
                                       @Nullable FactorRegistry factors) {
        List<Issue> out = new ArrayList<>();
        validate(dialogue, out, knownSelectorNames, factors);
        return out;
    }

    private static void validate(@Nonnull NpcDialogue dialogue, @Nonnull List<Issue> out,
                                 @Nullable Set<String> knownSelectorNames,
                                 @Nullable FactorRegistry factors) {
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
            String where = "Start candidate " + startIndex++;
            checkConditions(entry.getConditions(), where, id, out, knownSelectorNames, factors);
            checkOnce(entry.getOnce(), null, where, id, out, knownSelectorNames);
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

        // Validate every Goto target regardless of reachability, plus the generic state refs.
        for (var nodeEntry : dialogue.getNodes().entrySet()) {
            String nodeId = nodeEntry.getKey();
            DialogueNode node = nodeEntry.getValue();
            checkConditions(node.getConditions(), "node '" + nodeId + "'", id, out, knownSelectorNames, factors);
            // One Once identity per node: two options resolving to the same key share one flag,
            // so spending either retires both.
            Map<String, Integer> onceIdentities = new HashMap<>();
            for (int i = 0; i < node.getOptions().size(); i++) {
                DialogueOption option = node.getOptions().get(i);
                String where = "node '" + nodeId + "' option " + i;
                checkConditions(option.getConditions(), where, id, out, knownSelectorNames, factors);
                checkOnce(option.getOnce(), option, where, id, out, knownSelectorNames);
                checkOnceIdentity(option, i, nodeId, id, onceIdentities, out);
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
                    }
                }
            }
        }

        checkMemories(dialogue, out, knownSelectorNames);

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
     * {@code World} condition or a memory read most often hides) for the generic references.
     */
    private static void checkConditions(@Nonnull List<DialogueCondition> conditions,
                                        @Nonnull String where, @Nonnull String id,
                                        @Nonnull List<Issue> out,
                                        @Nullable Set<String> knownSelectorNames,
                                        @Nullable FactorRegistry factors) {
        for (DialogueCondition condition : conditions) {
            if (condition instanceof DialogueCondition.Combinator combinator) {
                checkConditions(combinator.getChildren(), where, id, out, knownSelectorNames, factors);
            } else if (condition instanceof DialogueCondition.World world) {
                checkWorldCondition(world, where, id, out, knownSelectorNames);
            } else if (condition instanceof DialogueCondition.Factor factor) {
                checkFactorCondition(factor, where, id, out, factors);
            }
        }
    }

    /**
     * A {@code Factor} condition naming an id nobody registered can never resolve, so its content
     * is permanently invisible - and unlike a missing node it produces no error at all at runtime,
     * which is exactly why it is worth a finding. WARNING rather than ERROR because the id may
     * legitimately belong to an optional mod: on a server that installs it the same file is
     * correct. Skipped entirely when no registry was passed, the same "cannot tell" rule the
     * selector-pool checks keep.
     */
    private static void checkFactorCondition(@Nonnull DialogueCondition.Factor condition,
                                             @Nonnull String where, @Nonnull String id,
                                             @Nonnull List<Issue> out,
                                             @Nullable FactorRegistry factors) {
        if (factors == null) {
            return;
        }
        String factorId = condition.getFactor();
        if (factorId == null || factorId.isBlank()) {
            out.add(Issue.error("FACTOR_CONDITION_NO_ID",
                    "Dialogue '" + id + "' " + where + " has a Factor condition with no Factor id, so it"
                            + " can never pass and its content is invisible", id));
            return;
        }
        if (!factors.isRegistered(factorId)) {
            out.add(Issue.warning("FACTOR_CONDITION_UNKNOWN_FACTOR",
                    "Dialogue '" + id + "' " + where + " gates on factor '" + factorId + "', which nothing"
                            + " has registered - it fails closed, so this content stays hidden until the"
                            + " mod that owns the factor is installed", id));
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

    // ==================== Once ====================

    /**
     * Audit a {@code Once} knob: the world family it is kept per must exist, and (for an option)
     * the option must offer something stable to identify it by.
     */
    private static void checkOnce(@Nullable DialogueOnce once, @Nullable DialogueOption option,
                                  @Nonnull String where, @Nonnull String id,
                                  @Nonnull List<Issue> out,
                                  @Nullable Set<String> knownSelectorNames) {
        if (once == null) {
            return;
        }
        String name = once.getWorldSelector();
        if (hasKnownNames(knownSelectorNames) && isUnknown(name, knownSelectorNames)) {
            // The re-show case: an unmatched selector makes the write a no-op AND the read unset,
            // so a first-visit beat plays again on every single visit.
            out.add(Issue.error("ONCE_UNKNOWN_SELECTOR",
                    "Dialogue '" + id + "' " + where + " has a Once kept per world selector '" + name
                            + "', which no loaded WorldSelector contributes - it will never be"
                            + " remembered", id));
        }
        if (option != null && option.onceDiscriminator().isBlank()) {
            out.add(Issue.warning("ONCE_NO_IDENTITY",
                    "Dialogue '" + id + "' " + where + " has a Once but no LabelKey or OnceId to"
                            + " identify it, so it stays repeatable - author an OnceId", id));
        }
    }

    /**
     * Record this option's {@code Once} identity for its node and report a COLLISION: an option
     * Once is remembered under {@code OnceId}, else {@code LabelKey}, and nothing else separates
     * two options inside one node - so two Once-bearing options sharing that discriminator share
     * one flag, and taking either retires both. Comparison runs over the composed state key, so
     * case- and whitespace-variant labels (which the runtime folds together) are caught too.
     */
    private static void checkOnceIdentity(@Nonnull DialogueOption option, int index,
                                          @Nonnull String nodeId, @Nonnull String id,
                                          @Nonnull Map<String, Integer> onceIdentities,
                                          @Nonnull List<Issue> out) {
        if (option.getOnce() == null) {
            return;
        }
        String discriminator = option.onceDiscriminator();
        if (discriminator.isBlank()) {
            return; // already reported as ONCE_NO_IDENTITY
        }
        String key = DialogueStateKeys.optionOnce(id, nodeId, discriminator);
        Integer first = onceIdentities.putIfAbsent(key, index);
        if (first != null) {
            out.add(Issue.error("ONCE_DUPLICATE_IDENTITY",
                    "Dialogue '" + id + "' node '" + nodeId + "' options " + first + " and " + index
                            + " both carry a Once but resolve to the same identity '" + discriminator
                            + "', so spending either retires both - author a distinct OnceId on one",
                    id));
        }
    }

    // ==================== Memories ====================

    /**
     * Audit the declared {@code Memories} against their use sites: every name used must be
     * declared (that is where its scope and lifetime live), every declaration should be both
     * written and read, and a world family a memory is kept per must exist.
     */
    private static void checkMemories(@Nonnull NpcDialogue dialogue, @Nonnull List<Issue> out,
                                      @Nullable Set<String> knownSelectorNames) {
        String id = dialogue.getId();
        Map<String, DialogueMemory> declared = new LinkedHashMap<>();
        for (Map.Entry<String, DialogueMemory> entry : dialogue.getMemories().entrySet()) {
            String name = normalize(entry.getKey());
            if (name.isEmpty()) {
                out.add(Issue.error("MEMORY_BLANK_NAME",
                        "Dialogue '" + id + "' declares a memory with no name", id));
                continue;
            }
            declared.put(name, entry.getValue());
            String selector = entry.getValue() == null ? null : entry.getValue().getWorldSelector();
            if (hasKnownNames(knownSelectorNames) && isUnknown(selector, knownSelectorNames)) {
                out.add(Issue.error("MEMORY_UNKNOWN_SELECTOR",
                        "Dialogue '" + id + "' declares memory '" + name + "' per world selector '"
                                + selector + "', which no loaded WorldSelector contributes - it will"
                                + " never be remembered", id));
            }
        }

        Set<String> written = new LinkedHashSet<>();
        Set<String> read = new LinkedHashSet<>();
        boolean blankUse = false;
        for (NpcDialogue.DialogueEntry entry : dialogue.getStart()) {
            blankUse |= collectReads(entry.getConditions(), read);
        }
        for (DialogueNode node : dialogue.getNodes().values()) {
            blankUse |= collectReads(node.getConditions(), read);
            for (DialogueOption option : node.getOptions()) {
                blankUse |= collectReads(option.getConditions(), read);
                for (DialogueAction action : option.getActions()) {
                    if (action instanceof DialogueAction.MemoryAction memory) {
                        blankUse |= !collect(memory.getMemory(), written);
                    }
                }
            }
        }
        if (blankUse) {
            out.add(Issue.error("MEMORY_BLANK_NAME",
                    "Dialogue '" + id + "' has a Remember/Forget/Remembered/NotRemembered with no"
                            + " Memory name", id));
        }

        Set<String> used = new LinkedHashSet<>(written);
        used.addAll(read);
        for (String name : used) {
            if (!declared.containsKey(name)) {
                out.add(Issue.error("MEMORY_UNDECLARED",
                        "Dialogue '" + id + "' uses memory '" + name + "' without declaring it in"
                                + " Memories, so it has no scope or reset behind it", id));
            }
        }
        for (String name : declared.keySet()) {
            if (!written.contains(name)) {
                out.add(Issue.warning("MEMORY_NEVER_WRITTEN",
                        "Dialogue '" + id + "' declares memory '" + name + "' but no option ever"
                                + " Remembers it, so it can never become true here", id));
            }
            if (!read.contains(name)) {
                out.add(Issue.info("MEMORY_NEVER_READ",
                        "Dialogue '" + id + "' declares memory '" + name + "' but nothing reads it"
                                + " with Remembered/NotRemembered", id));
            }
        }
    }

    /** Collect memory reads from a condition list, recursing through combinators. */
    private static boolean collectReads(@Nonnull List<DialogueCondition> conditions,
                                        @Nonnull Set<String> into) {
        boolean blank = false;
        for (DialogueCondition condition : conditions) {
            if (condition instanceof DialogueCondition.Combinator combinator) {
                blank |= collectReads(combinator.getChildren(), into);
            } else if (condition instanceof DialogueCondition.MemoryCondition memory) {
                blank |= !collect(memory.getMemory(), into);
            }
        }
        return blank;
    }

    /** Add a normalized name, or report false when it was blank. */
    private static boolean collect(@Nullable String name, @Nonnull Set<String> into) {
        String normalized = normalize(name);
        if (normalized.isEmpty()) {
            return false;
        }
        into.add(normalized);
        return true;
    }

    /**
     * A {@code Shared} memory is ONE piece of state across every dialogue that declares it, so the
     * declarations must agree: a mismatch means two conversations silently disagree about how long
     * the same memory lasts or which worlds it applies to.
     */
    private static void checkSharedMemoriesAgree(@Nonnull Collection<NpcDialogue> dialogues,
                                                 @Nonnull List<Issue> out) {
        Map<String, NpcDialogue> firstOwner = new HashMap<>();
        Map<String, DialogueMemory> firstDeclaration = new HashMap<>();
        for (NpcDialogue dialogue : dialogues) {
            for (Map.Entry<String, DialogueMemory> entry : dialogue.getMemories().entrySet()) {
                DialogueMemory declaration = entry.getValue();
                String name = normalize(entry.getKey());
                if (declaration == null || name.isEmpty() || !declaration.isShared()) {
                    continue;
                }
                DialogueMemory previous = firstDeclaration.putIfAbsent(name, declaration);
                if (previous == null) {
                    firstOwner.put(name, dialogue);
                    continue;
                }
                if (!previous.sameDeclarationAs(declaration)) {
                    out.add(Issue.error("MEMORY_SHARED_MISMATCH",
                            "Dialogue '" + dialogue.getId() + "' declares shared memory '" + name
                                    + "' differently from '" + firstOwner.get(name).getId()
                                    + "' - every dialogue sharing a memory must declare the same"
                                    + " WorldSelector and ResetWithQuest", dialogue.getId()));
                }
            }
        }
    }

    // ==================== shared helpers ====================

    /** An absent or EMPTY vocabulary means "cannot tell" (assets may not have loaded yet). */
    private static boolean hasKnownNames(@Nullable Set<String> knownSelectorNames) {
        return knownSelectorNames != null && !knownSelectorNames.isEmpty();
    }

    private static boolean isUnknown(@Nullable String name, @Nullable Set<String> knownSelectorNames) {
        return knownSelectorNames != null && name != null && !name.isBlank()
                && !knownSelectorNames.contains(normalize(name));
    }

    @Nonnull
    private static String normalize(@Nullable String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
