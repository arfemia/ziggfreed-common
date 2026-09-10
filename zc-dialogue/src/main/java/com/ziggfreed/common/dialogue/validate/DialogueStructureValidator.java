package com.ziggfreed.common.dialogue.validate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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

import com.ziggfreed.common.dialogue.type.DialogueAction;
import com.ziggfreed.common.dialogue.type.DialogueCondition;
import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.state.DialogueFlagScope;
import com.ziggfreed.common.dialogue.schema.DialogueFragmentConfig;
import com.ziggfreed.common.dialogue.schema.DialogueFragmentGroup;
import com.ziggfreed.common.dialogue.state.DialogueMemory;
import com.ziggfreed.common.dialogue.schema.DialogueNode;
import com.ziggfreed.common.dialogue.schema.DialogueSugarValues;
import com.ziggfreed.common.dialogue.schema.NodeSelector;
import com.ziggfreed.common.dialogue.state.DialogueOnce;
import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.dialogue.schema.DialogueStart;
import com.ziggfreed.common.dialogue.state.DialogueStateKeys;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.quest.QuestDialogueActions;
import com.ziggfreed.common.dialogue.quest.QuestDialogueConditions;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.world.WhereValidator;
import com.ziggfreed.common.world.WorldSelector;

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
        DialogueStart start = dialogue.getStart();
        if (start.isEmpty()) {
            out.add(warning("MISSING_START",
                    "Dialogue '" + id + "' says nothing about which screen it opens on (falls back to the"
                            + " first node)", id));
            entryNodes.add(dialogue.getNodes().keySet().iterator().next());
        } else {
            checkStart(dialogue, start, id, out, factors, engine, entryNodes);
        }

        // Validate every Goto target regardless of reachability, plus the generic state refs.
        for (var nodeEntry : dialogue.getNodes().entrySet()) {
            String nodeId = nodeEntry.getKey();
            DialogueNode node = nodeEntry.getValue();
            checkConditions(node.getConditions(), "node '" + nodeId + "'", id, out,
                    factors, engine);
            for (String fragment : node.getIncludeOptions()) {
                checkFragmentKnown(dialogue, fragment, "node '" + nodeId + "' pulls in", id, out);
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
                    checkOpen(action, id, out);
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

        checkFragments(dialogue, out);
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

    // ==================== Start ====================

    /**
     * Audit the opening ladder: every beat of {@code First} and {@code Then}, every quest row, and the
     * fallback screen. Each screen a beat can open is collected into {@code entryNodes}, which is what
     * the reachability walk starts from - a screen only a quest row or a draw opens is reachable.
     */
    private static void checkStart(@Nonnull NpcDialogue dialogue, @Nonnull DialogueStart start,
                                   @Nonnull String id, @Nonnull List<Finding> out,
                                   @Nullable FactorRegistry factors, @Nullable DialogueEngine engine,
                                   @Nonnull Set<String> entryNodes) {
        checkBeats(dialogue, start.first(), "Start First", id, out, factors, engine, entryNodes);
        checkBeats(dialogue, start.then(), "Start Then", id, out, factors, engine, entryNodes);

        for (Map.Entry<String, DialogueStart.QuestRow> row : start.quests().entrySet()) {
            String questId = row.getKey();
            if (questId == null || questId.isBlank()) {
                out.add(error("START_QUEST_NO_ID",
                        "Dialogue '" + id + "' has a Start quest row with no quest id, so it can never"
                                + " apply", id));
                continue;
            }
            DialogueStart.QuestRow value = row.getValue();
            if (value == null) {
                continue;
            }
            for (DialogueStart.Band band : DialogueStart.Band.values()) {
                checkQuestBeat(dialogue, value.forBand(band), questId, band, id, out, entryNodes);
            }
        }

        String fallback = start.fallback();
        if (fallback == null || fallback.isBlank()) {
            return;
        }
        if (dialogue.getNode(fallback) == null) {
            out.add(error("START_MISSING_NODE",
                    "Dialogue '" + id + "' Start Fallback names missing node '" + fallback + "'", id));
        } else {
            entryNodes.add(fallback);
        }
    }

    private static void checkBeats(@Nonnull NpcDialogue dialogue, @Nonnull List<DialogueStart.Beat> beats,
                                   @Nonnull String section, @Nonnull String id,
                                   @Nonnull List<Finding> out, @Nullable FactorRegistry factors,
                                   @Nullable DialogueEngine engine, @Nonnull Set<String> entryNodes) {
        int index = 0;
        for (DialogueStart.Beat beat : beats) {
            String where = section + " beat " + index++;
            if (beat == null) {
                continue;
            }
            checkConditions(beat.getWhen(), where, id, out, factors, engine);
            checkOnce(beat.getOnce(), null, where, id, out);

            if (beat.hasNode() && beat.hasPick()) {
                out.add(error("START_NODE_AND_PICK",
                        "Dialogue '" + id + "' " + where + " writes both Node and Pick, so which screen it"
                                + " opens on is not something a reader can tell - keep one of them", id));
            }
            if (beat.hasNode()) {
                collectStartNode(dialogue, beat.getNode(), where, id, out, entryNodes);
                continue;
            }
            if (!beat.hasPick()) {
                out.add(error("START_MISSING_NODE",
                        "Dialogue '" + id + "' " + where + " names no screen at all", id));
                continue;
            }
            if (beat.getPick().isEmpty()) {
                out.add(error("START_PICK_EMPTY",
                        "Dialogue '" + id + "' " + where + " has a Pick with nothing in it, so the beat can"
                                + " never open anything - name the screens to draw between", id));
                continue;
            }
            int variant = 0;
            for (DialogueStart.Variant option : beat.getPick()) {
                collectStartNode(dialogue, option == null ? null : option.getNode(),
                        where + " variant " + variant++, id, out, entryNodes);
            }
        }
    }

    /**
     * Audit one moment of a quest row. A screen must exist; a destination is audited by whichever mod
     * registered its type, which is the only layer that knows what its fields mean.
     */
    private static void checkQuestBeat(@Nonnull NpcDialogue dialogue,
                                       @Nullable DialogueStart.QuestBeat beat, @Nonnull String questId,
                                       @Nonnull DialogueStart.Band band, @Nonnull String id,
                                       @Nonnull List<Finding> out, @Nonnull Set<String> entryNodes) {
        if (beat == null) {
            return;
        }
        String where = "Start quest '" + questId + "' " + band.name().toLowerCase(Locale.ROOT);
        if (beat.getDestination() != null) {
            out.addAll(Destinations.validate(beat.getDestination(), id));
            return;
        }
        if (beat.isQuestView()) {
            return;
        }
        collectStartNode(dialogue, beat.getNode(), where, id, out, entryNodes);
    }

    /** One screen a beat can open: it must exist, and it counts as reachable when it does. */
    private static void collectStartNode(@Nonnull NpcDialogue dialogue, @Nullable String node,
                                         @Nonnull String where, @Nonnull String id,
                                         @Nonnull List<Finding> out, @Nonnull Set<String> entryNodes) {
        if (node == null || node.isBlank()) {
            out.add(error("START_MISSING_NODE",
                    "Dialogue '" + id + "' " + where + " names no screen", id));
            return;
        }
        if (dialogue.getNode(node) == null) {
            out.add(error("START_MISSING_NODE",
                    "Dialogue '" + id + "' " + where + " names missing node '" + node + "'", id));
            return;
        }
        entryNodes.add(node);
    }

    // ==================== shared option groups ====================

    /**
     * A shared group name a screen or a group refers to must be answered by this conversation's own
     * {@code Fragments} or by a shared {@code DialogueFragments} file; only one neither answers is a
     * finding, and the screen or group silently loses those lines at runtime.
     */
    private static void checkFragmentKnown(@Nonnull NpcDialogue dialogue, @Nullable String fragment,
                                           @Nonnull String who, @Nonnull String id,
                                           @Nonnull List<Finding> out) {
        if (fragment == null || !(NodeSelector.containsIgnoreCase(dialogue.getFragments().keySet(), fragment)
                || DialogueFragmentConfig.getInstance().declares(fragment))) {
            out.add(error("UNKNOWN_FRAGMENT",
                    "Dialogue '" + id + "' " + who + " shared options '" + fragment
                            + "', which is neither declared under its own Fragments nor shipped as a"
                            + " DialogueFragments file", id));
        }
    }

    /**
     * Audit the groups that place themselves and the groups that fold in other groups: everything
     * a group can say about where it goes that the screen it lands on cannot show.
     *
     * <p>A group is given to a screen by tag or by exact id, and neither is checked anywhere at
     * runtime: a tag misspelt on one side matches nothing and the line is simply absent from every
     * screen it was meant for, an id nobody declares selects nothing, and a selector with no positive
     * axis selects nothing at all. Each is a finding here. A screen's tag no group names is
     * information, since a tag may be written ahead of the group that will use it. And a group that
     * includes itself, however many steps round, would recurse forever if the splice followed it,
     * so the splice drops the re-entry and this names the loop.
     */
    private static void checkFragments(@Nonnull NpcDialogue dialogue, @Nonnull List<Finding> out) {
        String id = dialogue.getId();
        Map<String, DialogueFragmentGroup> groups = dialogue.getFragments();
        if (groups.isEmpty()) {
            return;
        }
        Set<String> nodeIds = new HashSet<>();
        Set<String> nodeTags = new HashSet<>();
        for (Map.Entry<String, DialogueNode> node : dialogue.getNodes().entrySet()) {
            nodeIds.add(normalize(node.getKey()));
            for (String tag : node.getValue().getTags()) {
                nodeTags.add(normalize(tag));
            }
        }
        Set<String> selectedTags = new HashSet<>();
        Set<String> inALoop = new HashSet<>();
        for (Map.Entry<String, DialogueFragmentGroup> entry : groups.entrySet()) {
            String name = entry.getKey();
            DialogueFragmentGroup group = entry.getValue();
            if (group == null) {
                continue;
            }
            for (String included : group.getInclude()) {
                checkFragmentKnown(dialogue, included, "shared option group '" + name + "' includes",
                        id, out);
            }
            checkIncludeCycle(groups, name, inALoop, out, id);
            NodeSelector on = group.getOn();
            if (on == null) {
                continue;
            }
            if (on.hasNoPositiveAxis()) {
                out.add(error("FRAGMENT_ON_NO_AXIS",
                        "Dialogue '" + id + "' shared option group '" + name + "' has an On with no Nodes"
                                + " or Tags, so it lands on no screen at all - name the screens or the"
                                + " tag it belongs on, or drop the On", id));
            }
            checkSelectorNodes(on.getNodes(), "Nodes", nodeIds, name, id, out);
            checkSelectorNodes(on.getExclude(), "Exclude", nodeIds, name, id, out);
            for (String tag : on.getTags()) {
                String folded = normalize(tag);
                selectedTags.add(folded);
                if (!nodeTags.contains(folded)) {
                    out.add(warning("FRAGMENT_TAG_UNKNOWN",
                            "Dialogue '" + id + "' shared option group '" + name + "' is placed on tag '"
                                    + tag + "', which no screen carries, so its lines reach nobody - tag"
                                    + " the screens it belongs on, or fix the spelling on one side", id));
                }
            }
        }
        for (Map.Entry<String, DialogueNode> node : dialogue.getNodes().entrySet()) {
            for (String tag : node.getValue().getTags()) {
                if (!selectedTags.contains(normalize(tag))) {
                    out.add(info("NODE_TAG_UNUSED",
                            "Dialogue '" + id + "' node '" + node.getKey() + "' carries tag '" + tag
                                    + "', which no shared option group is placed on", id));
                }
            }
        }
    }

    /** Every exact id a selector leaf names must be a screen of this conversation. */
    private static void checkSelectorNodes(@Nonnull List<String> named, @Nonnull String leaf,
                                           @Nonnull Set<String> nodeIds, @Nonnull String group,
                                           @Nonnull String id, @Nonnull List<Finding> out) {
        for (String nodeId : named) {
            if (nodeId == null || !nodeIds.contains(normalize(nodeId))) {
                out.add(error("FRAGMENT_ON_MISSING_NODE",
                        "Dialogue '" + id + "' shared option group '" + group + "' names screen '" + nodeId
                                + "' under On." + leaf + ", which this conversation does not have", id));
            }
        }
    }

    /**
     * Walk one group's {@code Include} chain and report a name that leads back to {@code root}.
     * Only this conversation's own groups can include anything, so the walk stays inside them; a
     * shared file is lines only and ends every chain. A loop is one finding, reported from the
     * first of its members the map lists: every member is remembered in {@code inALoop}, and a root
     * already known to sit on a reported loop is not walked again.
     */
    private static void checkIncludeCycle(@Nonnull Map<String, DialogueFragmentGroup> groups,
                                          @Nonnull String root, @Nonnull Set<String> inALoop,
                                          @Nonnull List<Finding> out, @Nonnull String id) {
        if (inALoop.contains(normalize(root))) {
            return;
        }
        Deque<String> path = new ArrayDeque<>();
        path.push(normalize(root));
        walkIncludes(groups, root, root, path, inALoop, out, id);
    }

    private static void walkIncludes(@Nonnull Map<String, DialogueFragmentGroup> groups,
                                     @Nonnull String root, @Nonnull String current,
                                     @Nonnull Deque<String> path, @Nonnull Set<String> inALoop,
                                     @Nonnull List<Finding> out, @Nonnull String id) {
        DialogueFragmentGroup group = localGroup(groups, current);
        if (group == null) {
            return;
        }
        for (String included : group.getInclude()) {
            if (included == null) {
                continue;
            }
            String folded = normalize(included);
            if (path.contains(folded)) {
                if (folded.equals(normalize(root)) && inALoop.add(folded)) {
                    inALoop.addAll(path);
                    out.add(error("FRAGMENT_CYCLE",
                            "Dialogue '" + id + "' shared option group '" + root + "' includes itself by way of "
                                    + describePath(path) + "; the splice drops the re-entry rather than"
                                    + " following it, so remove the loop", id));
                }
                continue;
            }
            path.push(folded);
            walkIncludes(groups, root, included, path, inALoop, out, id);
            path.pop();
        }
    }

    /** The local group under {@code name}, matched without regard to case; null for a file or a typo. */
    @Nullable
    private static DialogueFragmentGroup localGroup(@Nonnull Map<String, DialogueFragmentGroup> groups,
                                                    @Nonnull String name) {
        String folded = normalize(name);
        for (Map.Entry<String, DialogueFragmentGroup> entry : groups.entrySet()) {
            if (normalize(entry.getKey()).equals(folded)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** The include path, root first, as {@code a -> b -> c}. */
    @Nonnull
    private static String describePath(@Nonnull Deque<String> path) {
        List<String> names = new ArrayList<>(path);
        Collections.reverse(names);
        return String.join(" -> ", names);
    }

    // ==================== shorthand ====================

    /**
     * Four ways an option's actions quietly run in an order other than the one the file reads as,
     * every one of which looks correct on the page.
     *
     * <p>An option that spells its order out with {@code Do} runs ONLY those atoms, so a bare
     * shorthand sitting beside it never happens - which looks exactly like an option that forgot to
     * do half its job. Two actions of the same kind on one option (a {@code Goto} shorthand beside a
     * hand-written {@code Goto}, say) means one of them decides and the other is dead weight; which
     * one wins is not something an author should have to know. An option that writes both
     * {@code Actions} and {@code Do} runs every {@code Actions} entry FIRST whatever the file's
     * order, so a native step meant to follow a shorthand runs ahead of it; the fix is one
     * {@code Action} atom inside {@code Do}. And an atom carrying an {@code Action} beside a shorthand
     * folds the native step first and the shorthand after it, whichever was written first, so one
     * step per atom is the rule.
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
        if (option.hasDoAtoms() && option.authoredActionCount() > 0) {
            out.add(warning("ACTIONS_BESIDE_DO",
                    "Dialogue '" + id + "' " + where + " authors both Actions and Do; every Actions entry"
                            + " runs before the Do array whatever order the file reads in - write each"
                            + " native step as an {\"Action\": {...}} atom inside Do instead", id));
        }
        int atomIndex = 0;
        for (DialogueSugarValues atom : option.getDoAtoms()) {
            if (atom != null && atom.mixesActionWithShorthand()) {
                out.add(warning("DO_ATOM_MIXED",
                        "Dialogue '" + id + "' " + where + " Do atom " + atomIndex + " carries Action beside"
                                + " a shorthand key; inside one atom the native step runs first whichever"
                                + " was written first - one step per atom, so split them", id));
            }
            atomIndex++;
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

    /**
     * What an {@code Open} line opens is audited by whichever mod REGISTERED that destination type -
     * only its owner knows whether the skill, the shop or the board it names exists. A type with no
     * audit of its own reports nothing, which is the honest answer rather than a guess.
     */
    private static void checkOpen(@Nonnull DialogueAction action, @Nonnull String id,
                                  @Nonnull List<Finding> out) {
        if (action instanceof DialogueAction.OpenPage open) {
            out.addAll(Destinations.validate(open.getTarget(), id));
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
     * Audit the per-world {@code Where} on a {@code Once} or a {@code Memories} declaration.
     *
     * <p>The group's own malformed shapes are the SHARED {@code WhereValidator} findings, reported
     * under domain {@code where} exactly as a placement carrying the same mistake reports them -
     * one selector grammar, one set of findings, never a dialogue-flavoured near-duplicate. What is
     * added here is the one thing only a SCOPE can get wrong: a selector whose only statement is
     * "every world", which is identical to no scope at all, so writing it is a statement of intent
     * the runtime cannot honour and an author would never see refuted.
     *
     * <p>A selector that matches no world the server has loaded is caught at RUNTIME instead, by
     * {@code DialogueFlagScope}'s warn-once: which worlds exist is not knowable from a decoded
     * conversation, and an instance world is legitimately absent most of the time.
     */
    private static void checkWorldScope(@Nullable WorldSelector selector, @Nonnull String leaf,
                                        @Nonnull String where, @Nonnull String id,
                                        @Nonnull List<Finding> out) {
        if (selector == null) {
            return;
        }
        out.addAll(WhereValidator.validateSelector(selector, id + " " + where + " " + leaf));
        if (DialogueFlagScope.matchesEveryWorld(selector)) {
            out.add(warning("WORLD_SCOPE_MATCHES_EVERY_WORLD",
                    "Dialogue '" + id + "' " + where + " has a " + leaf + " kept per world, but its Where "
                            + "matches every world - which is the same as keeping it once per character. "
                            + "Remove the Where, or name the worlds you meant", id));
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
        checkWorldScope(once.getWhere(), "Once", where, id, out);
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
            checkWorldScope(entry.getValue() == null ? null : entry.getValue().getWhere(),
                    "memory '" + name + "'", "in Memories", id, out);
        }

        Set<String> written = new LinkedHashSet<>();
        Set<String> read = new LinkedHashSet<>();
        boolean blankUse = false;
        for (DialogueStart.Beat beat : startBeats(dialogue)) {
            blankUse |= collectReads(beat.getWhen(), read);
        }
        for (Map.Entry<String, DialogueNode> entry : dialogue.getNodes().entrySet()) {
            String nodeId = entry.getKey();
            DialogueNode node = entry.getValue();
            blankUse |= collectReads(node.getConditions(), read);
            for (int i = 0; i < node.getOptions().size(); i++) {
                DialogueOption option = node.getOptions().get(i);
                blankUse |= collectReads(option.getConditions(), read);
                for (DialogueAction action : option.getActions()) {
                    if (action instanceof DialogueAction.MemoryAction memory) {
                        blankUse |= !collect(memory.getMemory(), written);
                        checkMemoryScopeGuarded(dialogue, declared.get(normalize(memory.getMemory())),
                                memory, nodeId, node, option, i, id, out);
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

    /**
     * A memory kept per world (a declaration with a {@code Where}) is written by a {@code Remember}
     * or {@code Forget} that does NOTHING outside those worlds, and a read there answers forgotten.
     * When nothing on the way to that write says which world the player is standing in - no
     * {@code World} condition on the option, on its screen, or on any {@code Start} beat that opens
     * that screen - the write may be reached from anywhere and silently do nothing where it was
     * not meant to. Information rather than a warning, because this does not walk {@code Goto}
     * reachability: a screen reached only by a jump from a world-gated one is guarded in practice
     * and is still named here.
     */
    private static void checkMemoryScopeGuarded(@Nonnull NpcDialogue dialogue,
                                                @Nullable DialogueMemory declaration,
                                                @Nonnull DialogueAction.MemoryAction write,
                                                @Nonnull String nodeId, @Nonnull DialogueNode node,
                                                @Nonnull DialogueOption option, int optionIndex,
                                                @Nonnull String id, @Nonnull List<Finding> out) {
        if (declaration == null || declaration.getWhere() == null) {
            return;
        }
        if (hasWorldCondition(option.getConditions()) || hasWorldCondition(node.getConditions())) {
            return;
        }
        for (DialogueStart.Beat beat : startBeats(dialogue)) {
            if (beatOpens(beat, nodeId) && hasWorldCondition(beat.getWhen())) {
                return;
            }
        }
        out.add(info("MEMORY_SCOPE_UNGUARDED",
                "Dialogue '" + id + "' node '" + nodeId + "' option " + optionIndex + " writes memory '"
                        + write.getMemory() + "', which is kept per world, with no World condition on the"
                        + " option, its screen, or a Start beat opening that screen. Outside the worlds"
                        + " that declaration's Where names, this write does nothing and the read answers"
                        + " forgotten", id));
    }

    /** True when the beat can open {@code nodeId}, by name or as one of its Pick variants. */
    private static boolean beatOpens(@Nonnull DialogueStart.Beat beat, @Nonnull String nodeId) {
        if (beat.hasNode() && nodeId.equalsIgnoreCase(beat.getNode())) {
            return true;
        }
        for (DialogueStart.Variant variant : beat.getPick()) {
            if (variant != null && nodeId.equalsIgnoreCase(variant.getNode())) {
                return true;
            }
        }
        return false;
    }

    /** True when a {@code World} condition sits anywhere in the list, combinators included. */
    private static boolean hasWorldCondition(@Nonnull List<DialogueCondition> conditions) {
        for (DialogueCondition condition : conditions) {
            if (condition instanceof DialogueCondition.World) {
                return true;
            }
            if (condition instanceof DialogueCondition.Combinator combinator
                    && hasWorldCondition(combinator.getChildren())) {
                return true;
            }
        }
        return false;
    }

    /** Every ordered beat of the opening ladder, both sections, as one list to walk. */
    @Nonnull
    private static List<DialogueStart.Beat> startBeats(@Nonnull NpcDialogue dialogue) {
        List<DialogueStart.Beat> beats = new ArrayList<>(dialogue.getStart().first());
        beats.addAll(dialogue.getStart().then());
        beats.removeIf(beat -> beat == null);
        return beats;
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
