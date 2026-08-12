package com.ziggfreed.common.dialogue;

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

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.lookup.CodecMapCodec;
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
        final BuilderCodec<NpcDialogue> dialogueCodec;
        final DialogueSugar sugar;

        Assembled(Codec<DialogueAction[]> actionsArray, Codec<DialogueCondition[]> conditionsArray,
                  BuilderCodec<NpcDialogue> dialogueCodec, DialogueSugar sugar) {
            this.actionsArray = actionsArray;
            this.conditionsArray = conditionsArray;
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

        BuilderCodec<NpcDialogue.DialogueEntry> entryCodec =
                BuilderCodec.builder(NpcDialogue.DialogueEntry.class, NpcDialogue.DialogueEntry::new)
                        .append(new KeyedCodec<>("Node", Codec.STRING, false),
                                (e, v) -> e.node = v, e -> e.node)
                        .documentation("The screen this greeting opens on.").add()
                        .append(new KeyedCodec<>("Conditions", conditionsArray, false),
                                (e, v) -> e.conditions = v, e -> e.conditions)
                        .documentation("When this greeting applies. The first candidate whose conditions pass "
                                + "wins, so order them from most specific to most general.").add()
                        .append(new KeyedCodec<>("Once", DialogueOnce.CODEC, false),
                                (e, v) -> e.once = v, e -> e.once)
                        .documentation("Show this greeting only until the player has played it through. "
                                + "Write true for once per character, or name a world family to keep it per "
                                + "place.").add()
                        .build();

        Codec<NpcDialogue.DialogueEntry[]> startCodec =
                new ArrayCodec<>(entryCodec, NpcDialogue.DialogueEntry[]::new);
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
                .documentation("The greeting candidates, most specific first. The first one whose conditions "
                        + "pass decides which screen the conversation opens on.").add()
                .appendInherited(new KeyedCodec<>("Nodes", nodesCodec, false),
                        (d, v) -> d.nodes = v, d -> d.nodes,
                        (child, parent) -> child.nodes = parent.nodes)
                .documentation("Every screen in the conversation, keyed by name. A dialogue that inherits "
                        + "another may restate one screen by name and keeps the rest.").add()
                .appendInherited(new KeyedCodec<>("Memories", memoriesCodec, false),
                        (d, v) -> d.memories = v, d -> d.memories,
                        (child, parent) -> child.memories = parent.memories)
                .documentation("The named things this conversation can remember about a player, and how long "
                        + "each lasts. Declare a name here and use it by bare name everywhere else.").add()
                .appendInherited(new KeyedCodec<>("Fragments", fragmentsCodec, false),
                        (d, v) -> d.fragments = v, d -> d.fragments,
                        (child, parent) -> child.fragments = parent.fragments)
                .documentation("Shared option groups, keyed by name, that a screen pulls in with "
                        + "IncludeOptions. Use one for a footer every screen repeats.").add()
                .afterDecode((dialogue, extraInfo) -> dialogue.spliceFragments())
                .build();

        return new Assembled(actionsArray, conditionsArray, dialogueCodec, sugar);
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
