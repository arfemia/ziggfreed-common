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
import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.DialogueMemory;
import com.ziggfreed.common.dialogue.DialogueNode;
import com.ziggfreed.common.dialogue.DialogueOnce;
import com.ziggfreed.common.dialogue.DialogueOption;
import com.ziggfreed.common.dialogue.DialogueStateKeys;
import com.ziggfreed.common.dialogue.NpcDialogue;
import com.ziggfreed.common.dialogue.quest.QuestDialogueActions;
import com.ziggfreed.common.dialogue.quest.QuestDialogueConditions;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.world.WhereValidator;
import com.ziggfreed.common.world.WorldNameMatcher.Pattern;

/**
 * The content audit for a decoded conversation: everything that is wrong in a way the server would
 * otherwise never mention.
 *
 * <p>Most of what can go wrong in a dialogue is SILENT. A greeting pointing at a screen that does not
 * exist, a line gated on a world nothing is part of, a first-visit beat scoped to every world at once
 * so it is not really per world at all, a memory used without being declared, a shorthand quietly
 * ignored because the option also spelled its order out - none of them throw, and every one of them
 * shows up in game as "that line never appears" weeks later. This turns each into a startup finding
 * naming the file.
 *
 * <p>Structure is checked on its own; the vocabulary checks need to be told what the server actually
 * has. Pass the engine's factor registry and the engine itself to enable them; leaving either out
 * means "cannot tell" and skips that check, so validating before assets have loaded never cries
 * wolf.
 *
 * <p>Findings that need more than one conversation (a shared memory declared differently in two of
 * them) only run in {@link #validateAll}, which sees the whole set.
 */
public final class DialogueStructureValidator {

    /** The domain every finding here is stamped with. */
    public static final String DOMAIN = "dialogue";

    private DialogueStructureValidator() {
    }

    @Nonnull
    public static List<Finding> validateAll(@Nonnull Collection<NpcDialogue> dialogues) {
        return validateAll(dialogues, null, null);
    }

    /**
     * {@link #validateAll(Collection)} plus the factor-vocabulary check. Pass the engine's own
     * {@code factors()} registry so a {@code Factor} condition naming an id nobody registered is
     * reported; {@code null} skips the check.
     */
    @Nonnull
    public static List<Finding> validateAll(@Nonnull Collection<NpcDialogue> dialogues,
                                            @Nullable FactorRegistry factors) {
        return validateAll(dialogues, factors, null);
    }

    /**
     * {@link #validateAll(Collection, FactorRegistry)} plus the check that THIS engine can actually
     * answer every condition and run every action in the files it is about to serve.
     */
    @Nonnull
    public static List<Finding> validateAll(@Nonnull Collection<NpcDialogue> dialogues,
                                            @Nullable FactorRegistry factors,
                                            @Nullable DialogueEngine engine) {
        List<Finding> out = new ArrayList<>();
        for (NpcDialogue dialogue : dialogues) {
            validate(dialogue, out, factors, engine);
        }
        checkSharedMemoriesAgree(dialogues, out);
        return out;
    }

    @Nonnull
    public static List<Finding> validate(@Nonnull NpcDialogue dialogue) {
        return validate(dialogue, null, null);
    }

    /** {@link #validate(NpcDialogue)} plus the factor-vocabulary check. */
    @Nonnull
    public static List<Finding> validate(@Nonnull NpcDialogue dialogue,
                                         @Nullable FactorRegistry factors) {
        return validate(dialogue, factors, null);
    }

    /** {@link #validate(NpcDialogue, FactorRegistry)} plus the engine-vocabulary check. */
    @Nonnull
    public static List<Finding> validate(@Nonnull NpcDialogue dialogue,
                                         @Nullable FactorRegistry factors,
                                         @Nullable DialogueEngine engine) {
        List<Finding> out = new ArrayList<>();
        validate(dialogue, out, factors, engine);
        return out;
    }

    private static void validate(@Nonnull NpcDialogue dialogue, @Nonnull List<Finding> out,
                                 @Nullable FactorRegistry factors,
                                 @Nullable DialogueEngine engine) {
        String id = dialogue.getId();

        if (dialogue.getNodes().isEmpty()) {
            out.add(error("EMPTY_DIALOGUE", "Dialogue '" + id + "' has no screens at all", id));
            return;
        }

        Set<String> entryNodes = new HashSet<>();
        if (dialogue.getStart().isEmpty()) {
            out.add(warning("MISSING_START",
                    "Dialogue '" + id + "' has no Start candidates (falls back to the first node)", id));
            entryNodes.add(dialogue.getNodes().keySet().iterator().next());
        }
        int startIndex = 0;
        for (NpcDialogue.DialogueEntry entry : dialogue.getStart()) {
            String where = "Start candidate " + startIndex++;
            checkConditions(entry.getConditions(), where, id, out, factors, engine);
            checkOnce(entry.getOnce(), null, where, id, out);
            String node = entry.getNode();
            if (node == null || node.isBlank()) {
                out.add(error("START_MISSING_NODE",
                        "Dialogue '" + id + "' has a Start candidate without a Node", id));
                continue;
            }
            if (dialogue.getNode(node) == null) {
                out.add(error("START_MISSING_NODE",
                        "Dialogue '" + id + "' Start references missing node '" + node + "'", id));
            } else {
                entryNodes.add(node);
            }
        }

        Set<String> declaredFragments = dialogue.getFragments().keySet();

        // Validate every Goto target regardless of reachability, plus the generic state refs.
        for (var nodeEntry : dialogue.getNodes().entrySet()) {
            String nodeId = nodeEntry.getKey();
            DialogueNode node = nodeEntry.getValue();
            checkConditions(node.getConditions(), "node '" + nodeId + "'", id, out,
                    factors, engine);
            for (String fragment : node.getIncludeOptions()) {
                if (fragment == null || !declaredFragments.contains(fragment)) {
                    out.add(error("UNKNOWN_FRAGMENT",
                            "Dialogue '" + id + "' node '" + nodeId + "' pulls in shared options '"
                                    + fragment + "', which it does not declare under Fragments", id));
                }
            }
            // One Once identity per node: two options resolving to the same key share one flag,
            // so spending either retires both.
            Map<String, Integer> onceIdentities = new HashMap<>();
            for (int i = 0; i < node.getOptions().size(); i++) {
                DialogueOption option = node.getOptions().get(i);
                String where = "node '" + nodeId + "' option " + i;
                checkConditions(option.getConditions(), where, id, out,
                        factors, engine);
                checkOnce(option.getOnce(), option, where, id, out);
                checkOnceIdentity(option, i, nodeId, id, onceIdentities, out);
                checkSugar(option, where, id, out);
                for (DialogueAction action : option.getActions()) {
                    checkActionKnown(action, where, id, out, engine);
                    checkQuestAction(action, where, id, out);
                    if (action instanceof DialogueAction.Goto go) {
                        String target = go.getNode();
                        if (target == null || target.isBlank()) {
                            out.add(error("GOTO_MISSING_NODE",
                                    "Dialogue '" + id + "' " + where + " has a Goto without a Node", id));
                        } else if (dialogue.getNode(target) == null) {
                            out.add(error("GOTO_MISSING_NODE",
                                    "Dialogue '" + id + "' " + where + " Goto references missing node '"
                                            + target + "'", id));
                        }
                    }
                }
            }
        }

        checkMemories(dialogue, out);

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
                out.add(warning("UNREACHABLE_NODE",
                        "Dialogue '" + id + "' node '" + nodeId + "' is unreachable from Start", id));
            }
        }
    }

    // ==================== shorthand ====================

    /**
     * Two ways a shorthand quietly does nothing, both of which read as correct on the page.
     *
     * <p>An option that spells its order out with {@code Do} runs ONLY those atoms, so a bare
     * shorthand sitting beside it never happens - which looks exactly like an option that forgot to
     * do half its job. And two actions of the same kind on one option (a {@code Goto} shorthand
     * beside a hand-written {@code Goto}, say) means one of them decides and the other is dead
     * weight; which one wins is not something an author should have to know.
     */
    private static void checkSugar(@Nonnull DialogueOption option, @Nonnull String where,
                                   @Nonnull String id, @Nonnull List<Finding> out) {
        Set<String> bare = option.bareSugarKeys();
        if (option.hasDoAtoms() && !bare.isEmpty()) {
            out.add(warning("SUGAR_SHADOWED_BY_DO",
                    "Dialogue '" + id + "' " + where + " authors Do AND " + bare
                            + " beside it; Do decides the whole order, so those never run - move them"
                            + " into the Do array", id));
        }
        Set<Class<?>> seen = new HashSet<>();
        for (DialogueAction action : option.getActions()) {
            if (!seen.add(action.getClass()) && isSingular(action)) {
                out.add(warning("DUPLICATE_ACTION",
                        "Dialogue '" + id + "' " + where + " ends up with two "
                                + action.getClass().getSimpleName() + " actions, so only one of them"
                                + " decides what happens - drop the one you did not mean", id));
            }
        }
    }

    /** The actions where a second one is genuinely dead: only one jump and one close can win. */
    private static boolean isSingular(@Nonnull DialogueAction action) {
        return action instanceof DialogueAction.Goto || action instanceof DialogueAction.Close;
    }

    // ==================== vocabulary this engine can serve ====================

    /**
     * An action the shared schema could READ but this engine has no handler for: the option renders
     * and then does nothing. Only checkable when an engine was passed, and a WARNING rather than an
     * error because the mod that owns it may simply not be installed on this server.
     */
    private static void checkActionKnown(@Nonnull DialogueAction action, @Nonnull String where,
                                         @Nonnull String id, @Nonnull List<Finding> out,
                                         @Nullable DialogueEngine engine) {
        if (engine != null && !engine.executor().handles(action.getClass())) {
            out.add(warning("UNKNOWN_ACTION_TYPE",
                    "Dialogue '" + id + "' " + where + " uses the action "
                            + action.getClass().getSimpleName() + ", which this game has no handler"
                            + " for - the option is offered and then does nothing", id));
        }
    }

    private static void checkConditionKnown(@Nonnull DialogueCondition condition, @Nonnull String where,
                                            @Nonnull String id, @Nonnull List<Finding> out,
                                            @Nullable DialogueEngine engine) {
        if (engine != null && !engine.evaluates(condition.getClass())) {
            out.add(warning("UNKNOWN_CONDITION_TYPE",
                    "Dialogue '" + id + "' " + where + " gates on the condition "
                            + condition.getClass().getSimpleName() + ", which this game cannot answer"
                            + " - it fails closed, so that content stays hidden", id));
        }
    }

    // ==================== quest references ====================

    /** A quest line with no quest id, or naming a state that does not exist, can never be right. */
    private static void checkQuestCondition(@Nonnull DialogueCondition condition, @Nonnull String where,
                                            @Nonnull String id, @Nonnull List<Finding> out) {
        if (condition instanceof QuestDialogueConditions.QuestRef ref
                && (ref.getQuest() == null || ref.getQuest().isBlank())) {
            out.add(error("QUEST_CONDITION_NO_ID",
                    "Dialogue '" + id + "' " + where + " has a quest condition with no Quest id, so it"
                            + " can never pass and its content is invisible", id));
        }
        if (!(condition instanceof QuestDialogueConditions.QuestState state)) {
            return;
        }
        String[] states = state.getStates();
        if (states != null && states.length > 0) {
            for (String wanted : states) {
                reportUnknownState(wanted, where, id, out);
            }
            return;
        }
        if (state.getState() == null || state.getState().isBlank()) {
            out.add(error("QUEST_STATE_MISSING",
                    "Dialogue '" + id + "' " + where + " names a quest but no State or States, so it"
                            + " can never pass", id));
            return;
        }
        reportUnknownState(state.getState(), where, id, out);
    }

    private static void reportUnknownState(@Nullable String name, @Nonnull String where,
                                           @Nonnull String id, @Nonnull List<Finding> out) {
        if (QuestDialogueConditions.QuestState.parse(name) == null) {
            out.add(error("QUEST_STATE_UNKNOWN",
                    "Dialogue '" + id + "' " + where + " waits for quest state '" + name
                            + "', which is not a state a quest can be in - the line never appears", id));
        }
    }

    /** An accept / hand-in line with no quest id does nothing at all when it is chosen. */
    private static void checkQuestAction(@Nonnull DialogueAction action, @Nonnull String where,
                                         @Nonnull String id, @Nonnull List<Finding> out) {
        if (action instanceof QuestDialogueActions.QuestRef ref
                && (ref.getQuest() == null || ref.getQuest().isBlank())) {
            out.add(error("QUEST_ACTION_NO_ID",
                    "Dialogue '" + id + "' " + where + " has a quest action with no Quest id, so"
                            + " choosing that line does nothing", id));
        }
    }

    // ==================== World-identity references ====================

    /**
     * Audit a condition list (recursing through the boolean combinators, which is where a
     * {@code World} condition or a memory read most often hides) for the generic references.
     */
    private static void checkConditions(@Nonnull List<DialogueCondition> conditions,
                                        @Nonnull String where, @Nonnull String id,
                                        @Nonnull List<Finding> out,
                                        @Nullable FactorRegistry factors,
                                        @Nullable DialogueEngine engine) {
        for (DialogueCondition condition : conditions) {
            checkConditionKnown(condition, where, id, out, engine);
            if (condition instanceof DialogueCondition.Combinator combinator) {
                checkConditions(combinator.getChildren(), where, id, out,
                        factors, engine);
            } else if (condition instanceof DialogueCondition.World world) {
                checkWorldCondition(world, where, id, out);
            } else if (condition instanceof DialogueCondition.Factor factor) {
                checkFactorCondition(factor, where, id, out, factors);
            } else {
                checkQuestCondition(condition, where, id, out);
            }
        }
    }

    /**
     * A {@code Factor} condition naming an id nobody registered can never resolve, so its content
     * is permanently invisible - and unlike a missing node it produces no error at all at runtime,
     * which is exactly why it is worth a finding. WARNING rather than ERROR because the id may
     * legitimately belong to an optional mod: on a server that installs it the same file is
     * correct. Skipped entirely when no registry was passed, the same "cannot tell" rule every
     * vocabulary check here keeps.
     */
    private static void checkFactorCondition(@Nonnull DialogueCondition.Factor condition,
                                             @Nonnull String where, @Nonnull String id,
                                             @Nonnull List<Finding> out,
                                             @Nullable FactorRegistry factors) {
        if (factors == null) {
            return;
        }
        String factorId = condition.getFactor();
        if (factorId == null || factorId.isBlank()) {
            out.add(error("FACTOR_CONDITION_NO_ID",
                    "Dialogue '" + id + "' " + where + " has a Factor condition with no Factor id, so it"
                            + " can never pass and its content is invisible", id));
            return;
        }
        if (!factors.isRegistered(factorId)) {
            out.add(warning("FACTOR_CONDITION_UNKNOWN_FACTOR",
                    "Dialogue '" + id + "' " + where + " gates on factor '" + factorId + "', which nothing"
                            + " has registered - it fails closed, so this content stays hidden until the"
                            + " mod that owns the factor is installed", id));
        }
    }

    private static void checkWorldCondition(@Nonnull DialogueCondition.World condition,
                                            @Nonnull String where, @Nonnull String id,
                                            @Nonnull List<Finding> out) {
        // A Where with only ExcludeMatch (or nothing at all) matches NOTHING, so the gated content
        // can never appear. That reads as the opposite of the author's intent.
        if (condition.getSelector().hasNoPositiveAxis()) {
            out.add(error("WORLD_CONDITION_NO_AXIS",
                    "Dialogue '" + id + "' " + where + " has a World condition with no Match or "
                            + "GameplayConfig, so it can never pass and its content is invisible", id));
            return;
        }
        // Whether a pattern names a world that EXISTS is a runtime question, not one a decoded
        // conversation can answer: which worlds are loaded is not knowable from here, and an
        // instance world's family is legitimately absent most of the time.
        out.addAll(WhereValidator.validateSelector(condition.getSelector(), id + " " + where));
    }

    // ==================== per-world scope leaves ====================

    /**
     * Audit a {@code World} scope leaf on a {@code Once} or a {@code Memories} declaration. Both
     * take a world NAME or a pattern, so the two things that can go wrong are the same for either:
     * a blank string, which reads as a scope that is not there, and a bare {@code *}, which scopes
     * to every world - identical to no scope at all, so writing it is a statement of intent the
     * runtime cannot honour and an author would never see refuted.
     *
     * <p>A pattern that matches no world the server has loaded is caught at RUNTIME instead, by
     * {@code DialogueFlagScope}'s warn-once: which worlds exist is not knowable from a decoded
     * conversation, and an instance world's family is legitimately absent most of the time.
     */
    private static void checkWorldScope(@Nullable String world, @Nonnull String leaf,
                                        @Nonnull String where, @Nonnull String id,
                                        @Nonnull List<Finding> out) {
        if (world == null) {
            return;
        }
        if (world.isBlank()) {
            out.add(warning("WORLD_SCOPE_BLANK",
                    "Dialogue '" + id + "' " + where + " has a " + leaf + " with a blank World, which "
                            + "narrows nothing - remove the key or name a world", id));
            return;
        }
        if (Pattern.parse(world).isDefaultRule()) {
            out.add(warning("WORLD_SCOPE_MATCHES_EVERY_WORLD",
                    "Dialogue '" + id + "' " + where + " has a " + leaf + " kept per world '" + world
                            + "', which matches every world and is therefore the same as keeping it "
                            + "once per character - remove the key, or name the world you meant", id));
        }
    }

    // ==================== Once ====================

    /**
     * Audit a {@code Once} knob: the world it is kept per must actually narrow something, and (for
     * an option) the option must offer something stable to identify it by.
     */
    private static void checkOnce(@Nullable DialogueOnce once, @Nullable DialogueOption option,
                                  @Nonnull String where, @Nonnull String id,
                                  @Nonnull List<Finding> out) {
        if (once == null) {
            return;
        }
        checkWorldScope(once.getWorld(), "Once", where, id, out);
        if (option != null && option.onceDiscriminator().isBlank()) {
            out.add(warning("ONCE_NO_IDENTITY",
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
                                          @Nonnull List<Finding> out) {
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
            out.add(error("ONCE_DUPLICATE_IDENTITY",
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
     * written and read, and a world a memory is kept per must actually narrow something.
     */
    private static void checkMemories(@Nonnull NpcDialogue dialogue, @Nonnull List<Finding> out) {
        String id = dialogue.getId();
        Map<String, DialogueMemory> declared = new LinkedHashMap<>();
        for (Map.Entry<String, DialogueMemory> entry : dialogue.getMemories().entrySet()) {
            String name = normalize(entry.getKey());
            if (name.isEmpty()) {
                out.add(error("MEMORY_BLANK_NAME",
                        "Dialogue '" + id + "' declares a memory with no name", id));
                continue;
            }
            declared.put(name, entry.getValue());
            checkWorldScope(entry.getValue() == null ? null : entry.getValue().getWorld(),
                    "memory '" + name + "'", "in Memories", id, out);
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
            out.add(error("MEMORY_BLANK_NAME",
                    "Dialogue '" + id + "' has a Remember/Forget/Remembered/NotRemembered with no"
                            + " Memory name", id));
        }

        Set<String> used = new LinkedHashSet<>(written);
        used.addAll(read);
        for (String name : used) {
            if (!declared.containsKey(name)) {
                out.add(error("MEMORY_UNDECLARED",
                        "Dialogue '" + id + "' uses memory '" + name + "' without declaring it in"
                                + " Memories, so it has no scope or reset behind it", id));
            }
        }
        for (String name : declared.keySet()) {
            if (!written.contains(name)) {
                out.add(warning("MEMORY_NEVER_WRITTEN",
                        "Dialogue '" + id + "' declares memory '" + name + "' but no option ever"
                                + " Remembers it, so it can never become true here", id));
            }
            if (!read.contains(name)) {
                out.add(info("MEMORY_NEVER_READ",
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
                                                 @Nonnull List<Finding> out) {
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
                    out.add(error("MEMORY_SHARED_MISMATCH",
                            "Dialogue '" + dialogue.getId() + "' declares shared memory '" + name
                                    + "' differently from '" + firstOwner.get(name).getId()
                                    + "' - every dialogue sharing a memory must declare the same"
                                    + " World scope and ResetWithQuest", dialogue.getId()));
                }
            }
        }
    }

    // ==================== shared helpers ====================

    @Nonnull
    private static Finding error(@Nonnull String code, @Nonnull String message, @Nonnull String id) {
        return Finding.error(DOMAIN, code, message, id);
    }

    @Nonnull
    private static Finding warning(@Nonnull String code, @Nonnull String message, @Nonnull String id) {
        return Finding.warning(DOMAIN, code, message, id);
    }

    @Nonnull
    private static Finding info(@Nonnull String code, @Nonnull String message, @Nonnull String id) {
        return Finding.info(DOMAIN, code, message, id);
    }

    @Nonnull
    private static String normalize(@Nullable String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
