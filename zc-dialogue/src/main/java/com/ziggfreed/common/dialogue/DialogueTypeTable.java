package com.ziggfreed.common.dialogue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonValue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.lookup.CodecMapCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.CommonLog;
import com.ziggfreed.common.codec.InheritMapCodec;

/**
 * The one DECODE vocabulary for authored dialogues: every {@code Type} an option's {@code Actions}
 * or {@code Conditions} may name, and every shorthand key an option may carry, gathered from every
 * mod that built a {@link DialogueEngine}.
 *
 * <p><b>Why this is shared while an engine is not.</b> Dialogue files live in ONE store that the
 * server reads once, so there is exactly one chance to understand a file - the schema has to know
 * every mod's vocabulary. BEHAVIOUR stays private to each engine: what an action DOES and what a
 * condition ANSWERS come from that engine's own handler and evaluator maps, so one mod's dialogue
 * can never run another mod's code, and a type this engine does not know simply hides its content.
 * Only the ability to READ the file is pooled.
 *
 * <p><b>Ordering, and why it holds.</b> The server runs every plugin's setup to completion and only
 * then reads asset files ({@code HytaleServer.boot()} dispatches {@code LoadAssetEvent} after
 * {@code PluginManager.setup()} returns), so a mod that builds its engine during setup has
 * registered its vocabulary before the first dialogue is decoded. Building an engine LATER - on
 * first use, say - is the one way to get this wrong: the file is read before the vocabulary exists
 * and fails to load. Build the engine in setup.
 *
 * <p>The codec graph is assembled once, on first use, and dropped again if a registration arrives
 * afterwards - so a late arrival still takes effect, and the log says so once, because it means a
 * mod is registering later than it should.
 */
public final class DialogueTypeTable {

    private static final DialogueTypeTable INSTANCE = new DialogueTypeTable();

    /** The option fields the engine itself owns; a shorthand key may not take one of these. */
    private static final Set<String> RESERVED_OPTION_KEYS = Set.of(
            "LabelKey", "Label", "Conditions", "Actions", "Presentation", "Style", "Once", "OnceId", "Do");

    // The in-game editor shows one of these beside each conversation field. They live here rather
    // than at either use site because the same four fields are read in two places - as the top-level
    // fields of a conversation FILE, and as the body a consumer decodes straight from a string - and
    // a field documented two ways is a field whose two descriptions drift.

    /** What the {@code Start} field is for. */
    public static final String START_DOC =
            "Which screen the conversation opens on, in sections: First (beats that outrank everything), "
                    + "Quests (one row per quest, read from the quest's own state), Then (beats tried after "
                    + "them) and Fallback (the screen of last resort). The engine walks First, then a quest "
                    + "that is ready to hand in, then one this character can offer, then one that is active, "
                    + "then Then, then Fallback - so nothing has to be hand-ordered against anything else.";

    /** What the {@code Nodes} field is for. */
    public static final String NODES_DOC =
            "Every screen in the conversation, keyed by name. A conversation that inherits another may "
                    + "restate one screen by name and keeps the rest.";

    /** What the {@code Memories} field is for. */
    public static final String MEMORIES_DOC =
            "The named things this conversation can remember about a player, and how long each lasts. "
                    + "Declare a name here and use it by bare name everywhere else.";

    /** What the {@code Fragments} field is for. */
    public static final String FRAGMENTS_DOC =
            "Shared option groups, keyed by name, that a screen pulls in with IncludeOptions. Use one "
                    + "for a footer every screen repeats. A group named here is private to this "
                    + "conversation and wins over a file of the same name under DialogueFragments.";

    @Nonnull
    public static DialogueTypeTable get() {
        return INSTANCE;
    }

    private final Map<String, DialogueActionType<?>> actions = new LinkedHashMap<>();
    private final Map<String, DialogueConditionType<?>> conditions = new LinkedHashMap<>();
    private final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();

    @Nullable private volatile Assembled assembled;
    private volatile boolean decoded;

    private DialogueTypeTable() {
    }

    // ==================== registration ====================

    /**
     * Adopt an action type into the shared decode vocabulary. The FIRST registration of a
     * {@code Type} id wins; a second one naming the same class is the ordinary case (two mods both
     * using a generic action) and is silently ignored, while one naming a DIFFERENT class is a
     * genuine clash and is reported, because only one of the two shapes can be read.
     */
    public synchronized void register(@Nonnull DialogueActionType<?> type) {
        DialogueActionType<?> existing = actions.get(type.typeId());
        if (existing != null) {
            if (existing.actionClass() != type.actionClass()) {
                warnOnce("action-clash:" + type.typeId(),
                        "Two mods registered different dialogue actions under Type '" + type.typeId()
                                + "' (" + existing.actionClass().getName() + " and "
                                + type.actionClass().getName() + "); authored files are read as the"
                                + " first one, so rename one of them");
            }
            return;
        }
        actions.put(type.typeId(), type);
        invalidate();
    }

    /** Adopt a condition type into the shared decode vocabulary; same first-wins rule as actions. */
    public synchronized void register(@Nonnull DialogueConditionType<?> type) {
        DialogueConditionType<?> existing = conditions.get(type.typeId());
        if (existing != null) {
            if (existing.conditionClass() != type.conditionClass()) {
                warnOnce("condition-clash:" + type.typeId(),
                        "Two mods registered different dialogue conditions under Type '" + type.typeId()
                                + "' (" + existing.conditionClass().getName() + " and "
                                + type.conditionClass().getName() + "); authored files are read as the"
                                + " first one, so rename one of them");
            }
            return;
        }
        conditions.put(type.typeId(), type);
        invalidate();
    }

    private void invalidate() {
        assembled = null;
        if (decoded) {
            warnOnce("late-registration",
                    "A dialogue action/condition type was registered after dialogues had already been"
                            + " read. It takes effect from now on, but any file that named it has"
                            + " already failed to load - register dialogue types in your plugin's"
                            + " setup, before assets are loaded");
        }
    }

    /**
     * Forget every registration and start from the generic vocabulary again. For tests, which build
     * many engines with different vocabularies in one process; nothing in a running server should
     * ever call it.
     */
    public synchronized void resetForTests() {
        actions.clear();
        conditions.clear();
        warnedOnce.clear();
        assembled = null;
        decoded = false;
    }

    // ==================== the assembled codec graph ====================

    /** The assembled {@code Start}/{@code Nodes}/{@code Memories} codec every dialogue decodes through. */
    @Nonnull
    public BuilderCodec<NpcDialogue> dialogueCodec() {
        decoded = true;
        return assembled().dialogueCodec;
    }

    /** The assembled {@code Actions} array codec (a consumer building a sub-schema needs it). */
    @Nonnull
    public Codec<DialogueAction[]> actionsArray() {
        return assembled().actionsArray;
    }

    /** The assembled {@code Conditions} array codec, shared by options, nodes and Start candidates. */
    @Nonnull
    public Codec<DialogueCondition[]> conditionsArray() {
        return assembled().conditionsArray;
    }

    /**
     * The assembled option-list codec: an option row with every registered shorthand on it. It is
     * what a screen's {@code Options}, a shared group and a whole fragment FILE are each read by, so
     * an option means the same thing wherever it is written.
     */
    @Nonnull
    public Codec<DialogueOption[]> optionsArray() {
        decoded = true;
        return assembled().optionsArray;
    }

    /** The assembled {@code Start} codec: the declared opening sections. Replaces whole on inherit. */
    @Nonnull
    public Codec<DialogueStart> startCodec() {
        decoded = true;
        return assembled().startCodec;
    }

    /** The assembled {@code Nodes} codec: the screen map, merged per key on inherit. */
    @Nonnull
    public Codec<Map<String, DialogueNode>> nodesCodec() {
        decoded = true;
        return assembled().nodesCodec;
    }

    /** The assembled {@code Memories} codec: the declaration map, merged per key on inherit. */
    @Nonnull
    public Codec<Map<String, DialogueMemory>> memoriesCodec() {
        decoded = true;
        return assembled().memoriesCodec;
    }

    /** The assembled {@code Fragments} codec: the shared option groups, merged per key on inherit. */
    @Nonnull
    public Codec<Map<String, DialogueOption[]>> fragmentsCodec() {
        decoded = true;
        return assembled().fragmentsCodec;
    }

    /** The registered shorthand table, in fold order. */
    @Nonnull
    public DialogueSugar sugar() {
        return assembled().sugar;
    }

    @Nonnull
    private Assembled assembled() {
        Assembled cached = assembled;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            Assembled again = assembled;
            if (again != null) {
                return again;
            }
            Assembled built = assemble();
            assembled = built;
            return built;
        }
    }

    /** One frozen codec graph over one snapshot of the vocabulary. */
    private static final class Assembled {
        final Codec<DialogueAction[]> actionsArray;
        final Codec<DialogueCondition[]> conditionsArray;
        final Codec<DialogueOption[]> optionsArray;
        final Codec<DialogueStart> startCodec;
        final Codec<Map<String, DialogueNode>> nodesCodec;
        final Codec<Map<String, DialogueMemory>> memoriesCodec;
        final Codec<Map<String, DialogueOption[]>> fragmentsCodec;
        final BuilderCodec<NpcDialogue> dialogueCodec;
        final DialogueSugar sugar;

        Assembled(Codec<DialogueAction[]> actionsArray, Codec<DialogueCondition[]> conditionsArray,
                  Codec<DialogueOption[]> optionsArray, Codec<DialogueStart> startCodec,
                  Codec<Map<String, DialogueNode>> nodesCodec,
                  Codec<Map<String, DialogueMemory>> memoriesCodec,
                  Codec<Map<String, DialogueOption[]>> fragmentsCodec,
                  BuilderCodec<NpcDialogue> dialogueCodec, DialogueSugar sugar) {
            this.actionsArray = actionsArray;
            this.conditionsArray = conditionsArray;
            this.optionsArray = optionsArray;
            this.startCodec = startCodec;
            this.nodesCodec = nodesCodec;
            this.memoriesCodec = memoriesCodec;
            this.fragmentsCodec = fragmentsCodec;
            this.dialogueCodec = dialogueCodec;
            this.sugar = sugar;
        }
    }

    @Nonnull
    private Assembled assemble() {
        CodecMapCodec<DialogueAction> actionCodec = new CodecMapCodec<>("Type");
        for (DialogueActionType<?> type : actions.values()) {
            registerAction(actionCodec, type);
        }
        CodecMapCodec<DialogueCondition> conditionCodec = new CodecMapCodec<>("Type");
        for (DialogueConditionType<?> type : conditions.values()) {
            registerCondition(conditionCodec, type);
        }

        Codec<DialogueAction[]> actionsArray = new ArrayCodec<>(actionCodec, DialogueAction[]::new);
        Codec<DialogueCondition[]> conditionsArray = new ArrayCodec<>(conditionCodec, DialogueCondition[]::new);
        registerCombinatorCodecs(conditionCodec, conditionsArray);

        DialogueSugar sugar = new DialogueSugar(collectLeaves());

        // The Do atom carries the same shorthand keys an option does, so the two are built from one
        // description of the vocabulary and cannot drift apart.
        BuilderCodec.Builder<DialogueSugarValues> atomBuilder =
                BuilderCodec.builder(DialogueSugarValues.class, DialogueSugarValues::new);
        appendSugarFields(sugar, atomBuilder, values -> values);
        BuilderCodec<DialogueSugarValues> atomCodec = atomBuilder.build();

        BuilderCodec.Builder<DialogueOption> optionBuilder =
                BuilderCodec.builder(DialogueOption.class, DialogueOption::new)
                        .append(new KeyedCodec<>("LabelKey", Codec.STRING, false),
                                (o, v) -> o.labelKey = v, o -> o.labelKey).add()
                        .append(new KeyedCodec<>("Label", Codec.STRING, false),
                                (o, v) -> o.label = v, o -> o.label).add()
                        .append(new KeyedCodec<>("Conditions", conditionsArray, false),
                                (o, v) -> o.conditions = v, o -> o.conditions).add()
                        .append(new KeyedCodec<>("Actions", actionsArray, false),
                                (o, v) -> o.actions = v, o -> o.actions).add()
                        .append(new KeyedCodec<>("Presentation", DialogueOption.Presentation.CODEC, false),
                                (o, v) -> o.presentation = v, o -> o.presentation).add()
                        .append(new KeyedCodec<>("Style", Codec.STRING, false),
                                (o, v) -> o.styleKind = v, o -> o.styleKind).add()
                        .append(new KeyedCodec<>("Once", DialogueOnce.CODEC, false),
                                (o, v) -> o.once = v, o -> o.once).add()
                        .append(new KeyedCodec<>("OnceId", Codec.STRING, false),
                                (o, v) -> o.onceId = v, o -> o.onceId).add()
                        .append(new KeyedCodec<>("Do",
                                        new ArrayCodec<>(atomCodec, DialogueSugarValues[]::new), false),
                                (o, v) -> o.doAtoms = v, o -> o.doAtoms).add();
        appendSugarFields(sugar, optionBuilder, DialogueOption::sugarValues);
        BuilderCodec<DialogueOption> optionCodec = optionBuilder.build();

        Codec<DialogueOption[]> optionsArray = new ArrayCodec<>(optionCodec, DialogueOption[]::new);

        // Node fields are appendInherited so a child that overrides a node by key (via the
        // InheritMapCodec on Nodes) and restates only SOME fields keeps the parent node's other
        // fields (e.g. change just the text, keep the same options + conditions).
        BuilderCodec<DialogueNode> nodeCodec = BuilderCodec.builder(DialogueNode.class, DialogueNode::new)
                .appendInherited(new KeyedCodec<>("TextKey", Codec.STRING, false),
                        (n, v) -> n.textKey = v, n -> n.textKey,
                        (child, parent) -> child.textKey = parent.textKey)
                .documentation("Localization key for what the character says on this screen.").add()
                .appendInherited(new KeyedCodec<>("Text", Codec.STRING, false),
                        (n, v) -> n.text = v, n -> n.text,
                        (child, parent) -> child.text = parent.text)
                .documentation("A plain fallback line for a screen whose key is not written yet. It reaches "
                        + "every player in the one language it is typed in, so author TextKey for anything "
                        + "you ship.").add()
                .appendInherited(new KeyedCodec<>("Conditions", conditionsArray, false),
                        (n, v) -> n.conditions = v, n -> n.conditions,
                        (child, parent) -> child.conditions = parent.conditions)
                .documentation("When this screen may be entered at all. A screen that gates itself keeps a "
                        + "conversation to one set of nodes instead of one per state.").add()
                .appendInherited(new KeyedCodec<>("Options", optionsArray, false),
                        (n, v) -> n.options = v, n -> n.options,
                        (child, parent) -> child.options = parent.options)
                .documentation("The lines the player can pick, in the order they are shown.").add()
                .appendInherited(new KeyedCodec<>("IncludeOptions", Codec.STRING_ARRAY, false),
                        (n, v) -> n.includeOptions = v, n -> n.includeOptions,
                        (child, parent) -> child.includeOptions = parent.includeOptions)
                .documentation("Names of shared option groups from this dialogue's Fragments, appended after "
                        + "this screen's own Options. Write a footer such as 'open the menu / goodbye' once "
                        + "and name it from every screen that needs it.").add()
                .build();

        BuilderCodec<DialogueStart.Beat> beatCodec =
                BuilderCodec.builder(DialogueStart.Beat.class, DialogueStart.Beat::new)
                        .append(new KeyedCodec<>("Node", Codec.STRING, false),
                                (b, v) -> b.node = v, b -> b.node)
                        .documentation("The screen this beat opens on. Write this or Pick, never both.").add()
                        .append(new KeyedCodec<>("Pick", DialogueStart.pickCodec(), false),
                                (b, v) -> b.pick = v, b -> b.pick)
                        .documentation("Several screens to choose between, drawn afresh every time the "
                                + "conversation opens. Give a variant a Weight to make it more or less likely; "
                                + "omit it for an even chance.").add()
                        .append(new KeyedCodec<>("When", conditionsArray, false),
                                (b, v) -> b.when = v, b -> b.when)
                        .documentation("When this beat applies. Leave it out for one that always does, which "
                                + "is how a section's last beat is usually written.").add()
                        .append(new KeyedCodec<>("Once", DialogueOnce.CODEC, false),
                                (b, v) -> b.once = v, b -> b.once)
                        .documentation("Show this beat only until the player has played it through. Write true "
                                + "for once per character, or name a world family to keep it per place.").add()
                        .build();

        BuilderCodec<DialogueStart> startGroup =
                BuilderCodec.builder(DialogueStart.class, DialogueStart::new)
                        .append(new KeyedCodec<>("First",
                                        new ArrayCodec<>(beatCodec, DialogueStart.Beat[]::new), false),
                                (s, v) -> s.first = v, s -> s.first)
                        .documentation("Beats that outrank anything about quests: a first-visit greeting, a "
                                + "beat for one world, a line gated on another mod's numbers. Tried in the "
                                + "order written.").add()
                        .append(new KeyedCodec<>("Quests",
                                        new InheritMapCodec<>(DialogueStart.QuestRow.CODEC, LinkedHashMap::new),
                                        false),
                                (s, v) -> s.quests = v, s -> s.quests)
                        .documentation("One row per quest, keyed by quest id, saying what this conversation "
                                + "does while that quest is ready to hand in, offerable here, or active. The "
                                + "engine reads the quest's own state, so no condition is written.").add()
                        .append(new KeyedCodec<>("Then",
                                        new ArrayCodec<>(beatCodec, DialogueStart.Beat[]::new), false),
                                (s, v) -> s.then = v, s -> s.then)
                        .documentation("Beats tried once no quest row applied: the steady-state greeting and "
                                + "anything that varies with the world rather than with a quest.").add()
                        .append(new KeyedCodec<>("Fallback", Codec.STRING, false),
                                (s, v) -> s.fallback = v, s -> s.fallback)
                        .documentation("The screen when nothing else applies. One name, no conditions - it is "
                                + "the answer of last resort.").add()
                        .build();

        Codec<DialogueStart> startCodec = new StartFieldCodec(startGroup);
        // InheritMapCodec (not MapCodec): under Parent inheritance a child that provides SOME nodes
        // deep-merges them onto the parent's node map by key, rather than whole-replacing.
        Codec<Map<String, DialogueNode>> nodesCodec = new InheritMapCodec<>(nodeCodec, LinkedHashMap::new);
        Codec<Map<String, DialogueMemory>> memoriesCodec =
                new InheritMapCodec<>(DialogueMemory.CODEC, LinkedHashMap::new);
        Codec<Map<String, DialogueOption[]>> fragmentsCodec =
                new InheritMapCodec<>(optionsArray, LinkedHashMap::new);

        BuilderCodec<NpcDialogue> dialogueCodec = BuilderCodec.builder(NpcDialogue.class, NpcDialogue::new)
                .appendInherited(new KeyedCodec<>("Start", startCodec, false),
                        (d, v) -> d.start = v, d -> d.start,
                        (child, parent) -> child.start = parent.start)
                .documentation(START_DOC).add()
                .appendInherited(new KeyedCodec<>("Nodes", nodesCodec, false),
                        (d, v) -> d.nodes = v, d -> d.nodes,
                        (child, parent) -> child.nodes = parent.nodes)
                .documentation(NODES_DOC).add()
                .appendInherited(new KeyedCodec<>("Memories", memoriesCodec, false),
                        (d, v) -> d.memories = v, d -> d.memories,
                        (child, parent) -> child.memories = parent.memories)
                .documentation(MEMORIES_DOC).add()
                .appendInherited(new KeyedCodec<>("Fragments", fragmentsCodec, false),
                        (d, v) -> d.fragments = v, d -> d.fragments,
                        (child, parent) -> child.fragments = parent.fragments)
                .documentation(FRAGMENTS_DOC).add()
                .afterDecode((dialogue, extraInfo) -> dialogue.spliceFragments())
                .build();

        return new Assembled(actionsArray, conditionsArray, optionsArray, startCodec, nodesCodec,
                memoriesCodec, fragmentsCodec, dialogueCodec, sugar);
    }

    /**
     * The shorthand leaves the registered actions contribute, keeping the FIRST claim on a key and
     * refusing one that would collide with an option field the engine itself owns.
     */
    @Nonnull
    private List<DialogueSugarLeaf<?>> collectLeaves() {
        List<DialogueSugarLeaf<?>> out = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>();
        for (DialogueActionType<?> type : actions.values()) {
            DialogueSugarLeaf<?> leaf = type.sugar();
            if (leaf == null) {
                continue;
            }
            if (RESERVED_OPTION_KEYS.contains(leaf.key())) {
                warnOnce("sugar-reserved:" + leaf.key(),
                        "The dialogue shorthand '" + leaf.key() + "' registered by action Type '"
                                + type.typeId() + "' is already an option field, so it is ignored -"
                                + " pick another name");
                continue;
            }
            if (!claimed.add(leaf.key())) {
                warnOnce("sugar-clash:" + leaf.key(),
                        "Two dialogue actions registered the shorthand '" + leaf.key()
                                + "'; the first one wins, so rename the other");
                continue;
            }
            boolean modifierClash = false;
            for (String modifier : leaf.modifiers().keySet()) {
                if (RESERVED_OPTION_KEYS.contains(modifier) || !claimed.add(modifier)) {
                    warnOnce("sugar-modifier-clash:" + modifier,
                            "The dialogue shorthand '" + leaf.key() + "' wants to read '" + modifier
                                    + "', which is already taken; the whole shorthand is ignored");
                    modifierClash = true;
                }
            }
            if (!modifierClash) {
                out.add(leaf);
            }
        }
        return out;
    }

    /** Append one keyed field per registered shorthand (and per declared modifier) to a builder. */
    private static <T> void appendSugarFields(@Nonnull DialogueSugar sugar,
                                              @Nonnull BuilderCodec.Builder<T> builder,
                                              @Nonnull Function<T, DialogueSugarValues> holder) {
        for (DialogueSugarLeaf<?> leaf : sugar.leaves()) {
            appendSugarField(builder, leaf.key(), leaf.codec(), holder);
            for (Map.Entry<String, Codec<?>> modifier : leaf.modifiers().entrySet()) {
                appendSugarField(builder, modifier.getKey(), modifier.getValue(), holder);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, V> void appendSugarField(@Nonnull BuilderCodec.Builder<T> builder,
                                                @Nonnull String key, @Nonnull Codec<V> codec,
                                                @Nonnull Function<T, DialogueSugarValues> holder) {
        builder.append(new KeyedCodec<>(key, codec, false),
                (T owner, V value) -> holder.apply(owner).put(key, value),
                (T owner) -> (V) holder.apply(owner).raw(key)).add();
    }

    private static <A extends DialogueAction> void registerAction(
            @Nonnull CodecMapCodec<DialogueAction> codec, @Nonnull DialogueActionType<A> type) {
        codec.register(type.typeId(), type.actionClass(), type.codec());
    }

    private static <C extends DialogueCondition> void registerCondition(
            @Nonnull CodecMapCodec<DialogueCondition> codec, @Nonnull DialogueConditionType<C> type) {
        codec.register(type.typeId(), type.conditionClass(), type.codec());
    }

    /**
     * The boolean combinators, whose child-list codec is the very array being assembled - which is
     * why they can only be registered here. Their EVALUATION stays with each engine, which walks the
     * children back through its own condition pass.
     */
    private static void registerCombinatorCodecs(@Nonnull CodecMapCodec<DialogueCondition> conditionCodec,
                                                 @Nonnull Codec<DialogueCondition[]> conditionsArray) {
        conditionCodec.register("AllOf", DialogueCondition.AllOf.class,
                BuilderCodec.builder(DialogueCondition.AllOf.class, DialogueCondition.AllOf::new)
                        .append(new KeyedCodec<>("All", conditionsArray, false),
                                (c, v) -> c.children = v, c -> c.children).add()
                        .build());
        conditionCodec.register("AnyOf", DialogueCondition.AnyOf.class,
                BuilderCodec.builder(DialogueCondition.AnyOf.class, DialogueCondition.AnyOf::new)
                        .append(new KeyedCodec<>("Any", conditionsArray, false),
                                (c, v) -> c.children = v, c -> c.children).add()
                        .build());
        conditionCodec.register("Not", DialogueCondition.Not.class,
                BuilderCodec.builder(DialogueCondition.Not.class, DialogueCondition.Not::new)
                        .append(new KeyedCodec<>("Of", conditionsArray, false),
                                (c, v) -> c.children = v, c -> c.children).add()
                        .build());
    }

    /**
     * The {@code Start} field's codec, and the two things it does beyond decoding the group.
     *
     * <p>It is deliberately NOT an inheriting codec, which is what keeps {@code Start} whole-replacing
     * under {@code Parent}: it is one ladder, and half a child's beside half a parent's would be a
     * ladder nobody wrote. Every other conversation field merges by key and says so through its own
     * codec; this one says the opposite here, in one place, rather than through a rule somewhere else.
     *
     * <p>And a file that writes the retired list form is told what to write instead. An array reaching
     * the group codec would fail with a parser message about an unexpected character, which says
     * nothing an author can act on.
     */
    private static final class StartFieldCodec implements Codec<DialogueStart> {

        private static final String LIST_FORM =
                "Start is written as sections now, not as a list: "
                        + "{\"First\": [...], \"Quests\": {...}, \"Then\": [...], \"Fallback\": \"<screen>\"}. "
                        + "Put a beat that outranks quests under First, one tried after them under Then, and "
                        + "the screen of last resort under Fallback; every section is optional.";

        private final BuilderCodec<DialogueStart> group;

        StartFieldCodec(@Nonnull BuilderCodec<DialogueStart> group) {
            this.group = group;
        }

        @Override
        @Nullable
        public DialogueStart decode(BsonValue value, ExtraInfo extraInfo) {
            if (value != null && value.isArray()) {
                throw new IllegalArgumentException(LIST_FORM);
            }
            return group.decode(value, extraInfo);
        }

        @Nonnull
        @Override
        public BsonValue encode(DialogueStart start, ExtraInfo extraInfo) {
            return group.encode(start, extraInfo);
        }

        @Override
        @Nullable
        public DialogueStart decodeJson(RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
            if (reader.peek() == '[') {
                throw new IllegalArgumentException(LIST_FORM);
            }
            return group.decodeJson(reader, extraInfo);
        }

        @Nonnull
        @Override
        public Schema toSchema(@Nonnull SchemaContext context) {
            return group.toSchema(context);
        }
    }

    private void warnOnce(@Nonnull String key, @Nonnull String message) {
        if (!warnedOnce.add(key.toLowerCase(Locale.ROOT))) {
            return;
        }
        try {
            CommonLog.LOGGER.atWarning().log("[Dialogue] %s", message);
        } catch (Throwable ignored) {
            // a unit JVM with no log manager throws an Error from the fluent logger; swallow it.
        }
    }
}
