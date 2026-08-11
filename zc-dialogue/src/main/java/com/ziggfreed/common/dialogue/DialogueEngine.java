package com.ziggfreed.common.dialogue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.codec.lookup.CodecMapCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ziggfreed.common.CommonLog;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;

/**
 * A self-contained, per-consumer dialogue runtime. Built once via {@link #builder()},
 * it OWNS its action/condition dispatch codecs (pre-seeded with the generic types,
 * extended by the consumer's {@link DialogueActionType}/{@link DialogueConditionType}
 * registrations), assembles the whole {@link NpcDialogue} codec graph around them,
 * and exposes the decode + execute + condition-eval + sugar + option-style surface
 * the page and config need.
 *
 * <p>Per-consumer (not a shared mutable registry) by design: the codecs are fully
 * populated before they are ever used to decode, so there is no registration race
 * and one consumer's domain types never leak into another's. The MMO registers
 * quest/reward/gate types; a minigame builds with generics only.
 */
public final class DialogueEngine {

    private final BuilderCodec<NpcDialogue> dialogueCodec;
    private final Map<Class<? extends DialogueCondition>, DialogueConditionEvaluator<?>> evaluators;
    private final Map<Class<? extends DialogueAction>, DialogueOptionStyle> styles;
    private final DialogueActionExecutor executor;
    private final DialogueSugar sugar;
    private final Consumer<String> warn;
    @Nullable private final FactorRegistry factors;

    /** Authoring mistakes that repeat on every render, reported once per distinct case. */
    private final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();

    private DialogueEngine(@Nonnull BuilderCodec<NpcDialogue> dialogueCodec,
                           @Nonnull Map<Class<? extends DialogueCondition>, DialogueConditionEvaluator<?>> evaluators,
                           @Nonnull Map<Class<? extends DialogueAction>, DialogueOptionStyle> styles,
                           @Nonnull DialogueActionExecutor executor, @Nonnull DialogueSugar sugar,
                           @Nonnull Consumer<String> warn, @Nullable FactorRegistry factors) {
        this.dialogueCodec = dialogueCodec;
        this.evaluators = evaluators;
        this.styles = styles;
        this.executor = executor;
        this.sugar = sugar;
        this.warn = warn;
        this.factors = factors;
    }

    /**
     * The factor vocabulary the generic {@code Factor} condition resolves against, or null when
     * the consumer wired none (every {@code Factor} condition then fails closed). Also what a
     * consumer hands {@code validate.DialogueStructureValidator} so an authored id nobody
     * registered is a startup finding rather than permanently invisible content.
     */
    @Nullable
    public FactorRegistry factors() {
        return factors;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /** The assembled {@code Start}/{@code Nodes} codec (consumer configs decode the canonical body through this). */
    @Nonnull
    public BuilderCodec<NpcDialogue> dialogueCodec() {
        return dialogueCodec;
    }

    @Nonnull
    public DialogueActionExecutor executor() {
        return executor;
    }

    @Nonnull
    public DialogueSugar sugar() {
        return sugar;
    }

    /**
     * Decode a canonical (already template-resolved + desugared) {@code Start}/{@code Nodes}
     * JSON body into an {@link NpcDialogue} with its id set. Null + warn on failure.
     */
    @Nullable
    public NpcDialogue decode(@Nonnull String id, @Nonnull String canonicalJson) {
        try {
            NpcDialogue d = dialogueCodec.decodeJson(RawJsonReader.fromJsonString(canonicalJson), new ExtraInfo());
            if (d == null) {
                return null;
            }
            d.setId(id);
            return d;
        } catch (Exception e) {
            warn.accept("Failed to decode dialogue '" + id + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Decode a NON-templated authored body (with option sugar but no {@code extends}):
     * runs the engine's sugar pass then {@link #decode}. A Gson-free convenience for a
     * consumer that hand-authors a dialogue without the template DSL (a minigame's demo
     * tree); templated bodies must go through
     * {@code template.DialogueTemplateResolver.resolve(..., sugar(), ...)} with a
     * template map instead. Null + warn on failure.
     */
    @Nullable
    public NpcDialogue decodeAuthored(@Nonnull String id, @Nonnull String authoredJson) {
        try {
            JsonObject body = JsonParser.parseString(authoredJson).getAsJsonObject();
            sugar.desugar(body);
            return decode(id, body.toString());
        } catch (Exception e) {
            warn.accept("Failed to decode authored dialogue '" + id + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Decode a canonical body that may declare native {@code Parent} inheritance: when
     * {@code parent} is non-null the body is decoded THROUGH the engine's real
     * {@code decodeAndInheritJson} against the already-decoded parent (child fields
     * override; {@code Nodes} keyed-merges via {@link InheritMapCodec}; an omitted
     * {@code Start}/{@code Nodes} inherits the parent's). The caller resolves the
     * {@code Parent} id to the parent {@link NpcDialogue} (see
     * {@link DialogueBodyResolver}) and strips the {@code Parent} key from the body
     * before calling. Null + warn on failure.
     */
    @Nullable
    public NpcDialogue decodeWithParent(@Nonnull String id, @Nonnull String canonicalJson,
                                        @Nullable NpcDialogue parent) {
        try {
            RawJsonReader reader = RawJsonReader.fromJsonString(canonicalJson);
            NpcDialogue d = parent == null
                    ? dialogueCodec.decodeJson(reader, new ExtraInfo())
                    : dialogueCodec.decodeAndInheritJson(reader, parent, new ExtraInfo());
            if (d == null) {
                return null;
            }
            d.setId(id);
            return d;
        } catch (Exception e) {
            warn.accept("Failed to decode dialogue '" + id + "': " + e.getMessage());
            return null;
        }
    }

    /** AND-combine an option's / entry's conditions for this player (an empty list passes). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean conditionsPass(@Nonnull List<DialogueCondition> conditions, @Nonnull DialogueContext ctx) {
        for (DialogueCondition c : conditions) {
            DialogueConditionEvaluator evaluator = evaluators.get(c.getClass());
            if (evaluator == null) {
                warn.accept("No evaluator registered for dialogue condition " + c.getClass().getName()
                        + " - hiding the gated content");
                return false;
            }
            try {
                if (!evaluator.passes(c, ctx)) {
                    return false;
                }
            } catch (Exception e) {
                warn.accept("Dialogue condition " + c.getClass().getSimpleName()
                        + " evaluation failed: " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    /**
     * The chosen entry node plus, when that entry carries a {@link DialogueOnce}, the PENDING key
     * to write once the player finishes the beat. A null {@code onceKey} means there is nothing to
     * spend: either the entry has no {@code Once}, or its scope names a world family this world is
     * not part of (so the write is a deliberate no-op).
     */
    public record EntryResolution(@Nullable String nodeId, @Nullable String onceKey) {

        /** No node at all (an empty dialogue). */
        public static final EntryResolution NONE = new EntryResolution(null, null);
    }

    /**
     * Pick the entry node for this player: the first {@code Start} candidate whose
     * conditions pass, else the first authored node (the validator flags a missing
     * start). Null only when the dialogue has no nodes at all.
     *
     * <p>Prefer {@link #resolveEntry} when the caller can complete the beat: this overload drops
     * the pending {@code Once} key, so a first-visit entry resolved through it is never spent.
     */
    @Nullable
    public String resolveEntryNodeId(@Nonnull NpcDialogue dialogue, @Nonnull DialogueContext ctx) {
        return resolveEntry(dialogue, ctx).nodeId();
    }

    /**
     * Pick the entry node AND the pending {@code Once} key for this player. A candidate carrying a
     * {@code Once} is skipped once that key is set, which is what retires a first-visit beat; the
     * key is only written when the player completes the beat (see {@link #consumeOnce}), so
     * leaving mid-conversation shows it again.
     */
    @Nonnull
    public EntryResolution resolveEntry(@Nonnull NpcDialogue dialogue, @Nonnull DialogueContext ctx) {
        for (NpcDialogue.DialogueEntry candidate : dialogue.getStart()) {
            String nodeId = candidate.getNode();
            if (nodeId == null || nodeId.isBlank()) {
                continue;
            }
            if (!conditionsPass(candidate.getConditions(), ctx)) {
                continue;
            }
            // The target node may ALSO self-gate (node-level conditions replace the
            // old (node x state) duplication) - both the candidate's and the node's
            // conditions must pass for this candidate to win.
            DialogueNode node = dialogue.getNode(nodeId);
            if (node != null && node.hasConditions() && !conditionsPass(node.getConditions(), ctx)) {
                continue;
            }
            DialogueOnce once = candidate.getOnce();
            if (once == null) {
                return new EntryResolution(nodeId, null);
            }
            // Read the Once only after the conditions passed, so a scope warning cannot fire for
            // an entry the player was never eligible for anyway.
            String key = once.keyFor(DialogueStateKeys.entryOnce(dialogue.getId(), nodeId), ctx);
            if (key != null && ctx.flags().has(key)) {
                continue;
            }
            return new EntryResolution(nodeId, key);
        }
        // Fallback: the first node whose own conditions pass, else the first node.
        Map<String, DialogueNode> all = dialogue.getNodes();
        for (Map.Entry<String, DialogueNode> e : all.entrySet()) {
            DialogueNode node = e.getValue();
            if (!node.hasConditions() || conditionsPass(node.getConditions(), ctx)) {
                return new EntryResolution(e.getKey(), null);
            }
        }
        return all.isEmpty() ? EntryResolution.NONE
                : new EntryResolution(all.keySet().iterator().next(), null);
    }

    /**
     * Whether {@code option} should be offered right now: its conditions pass AND its own
     * {@code Once} (if any) has not been spent. The ONE predicate a page uses both when rendering
     * a node and when re-checking a click, so a stale click can never run a spent option.
     */
    public boolean optionAvailable(@Nonnull NpcDialogue dialogue, @Nonnull String nodeId,
                                   @Nonnull DialogueOption option, @Nonnull DialogueContext ctx) {
        if (option.hasConditions() && !conditionsPass(option.getConditions(), ctx)) {
            return false;
        }
        String key = optionOnceKey(dialogue, nodeId, option, ctx);
        return key == null || !ctx.flags().has(key);
    }

    /**
     * Spend the {@code Once}es a completed beat consumes: the entry's pending key (the player
     * finished the beat that entry routed to) and the chosen option's own. Call AFTER the option's
     * actions have run, and only on the path that actually ran them - an option filtered out on
     * the click re-check, or a page dismissed with Escape, must leave both unspent.
     *
     * <p>{@code chosen} is null for the implicit Farewell row, which still completes the beat.
     */
    public void consumeOnce(@Nullable String pendingEntryOnceKey, @Nonnull NpcDialogue dialogue,
                            @Nonnull String nodeId, @Nullable DialogueOption chosen,
                            @Nonnull DialogueContext ctx) {
        if (pendingEntryOnceKey != null) {
            ctx.flags().set(pendingEntryOnceKey);
        }
        if (chosen == null) {
            return;
        }
        String key = optionOnceKey(dialogue, nodeId, chosen, ctx);
        if (key != null) {
            ctx.flags().set(key);
        }
    }

    /**
     * The storage key an option's {@code Once} occupies, or null when the option has none (or its
     * scope names a world family this world is not part of, so the guard does not apply here).
     */
    @Nullable
    private String optionOnceKey(@Nonnull NpcDialogue dialogue, @Nonnull String nodeId,
                                 @Nonnull DialogueOption option, @Nonnull DialogueContext ctx) {
        DialogueOnce once = option.getOnce();
        if (once == null) {
            return null;
        }
        String discriminator = option.onceDiscriminator();
        if (discriminator.isBlank()) {
            warnOnce("once:" + dialogue.getId() + ":" + nodeId,
                    "Dialogue '" + dialogue.getId() + "' node '" + nodeId + "' has an option with"
                            + " Once but no LabelKey or OnceId to identify it - author an OnceId;"
                            + " the option stays repeatable until then");
            return null;
        }
        return once.keyFor(DialogueStateKeys.optionOnce(dialogue.getId(), nodeId, discriminator), ctx);
    }

    /**
     * The storage key a declared {@link DialogueMemory} occupies for this player, or null when the
     * name is unusable (blank, or no dialogue on the context) or the memory is kept per world
     * family and this world is not part of it. An UNDECLARED name still works - it falls back to a
     * plain per-dialogue memory - but warns once and is a validator error, because the declaration
     * is where its scope and lifetime would have been written.
     */
    @Nullable
    String memoryKey(@Nullable String name, @Nonnull DialogueContext ctx) {
        if (name == null || name.isBlank()) {
            warnOnce("mem-blank", "A dialogue memory action/condition has no Memory name");
            return null;
        }
        NpcDialogue dialogue = ctx.dialogue();
        if (dialogue == null) {
            warnOnce("mem-ctx:" + name,
                    "Memory '" + name + "' cannot be resolved: this dialogue context carries no"
                            + " dialogue");
            return null;
        }
        DialogueMemory declaration = dialogue.getMemory(name);
        if (declaration == null) {
            warnOnce("mem:" + dialogue.getId() + ":" + name,
                    "Dialogue '" + dialogue.getId() + "' uses memory '" + name + "' without"
                            + " declaring it in Memories - it is remembered per character with no"
                            + " scope or reset until declared");
            declaration = DialogueMemory.DEFAULT;
        }
        return declaration.keyFor(dialogue.getId(), name, ctx);
    }

    /**
     * Evaluate one generic {@code Factor} condition against the wired {@link FactorRegistry}.
     *
     * <p>The context hands the provider everything a dialogue evaluation genuinely knows: the
     * player is BOTH the store owner and the subject the question is about, the world is the one
     * they are standing in (through the same guarded {@link DialogueWorlds} read the {@code World}
     * condition uses, so the two can never disagree about what "the player's world" means), and
     * the authored {@code Param} rides along untouched.
     *
     * <p>With NO registry wired the condition fails and warns once: a server that never installed
     * the mod owning the vocabulary should see the ungated version of the conversation, and the
     * one log line is what turns that from silently-missing content into something an owner can
     * act on.
     */
    private boolean factorPasses(@Nonnull DialogueCondition.Factor condition, @Nonnull DialogueContext ctx) {
        if (factors == null) {
            warnOnce("factor-registry",
                    "A dialogue uses a Factor condition but this engine was built with no factor"
                            + " registry - every Factor condition fails closed and its content stays"
                            + " hidden");
            return false;
        }
        FactorContext.Builder factorCtx = FactorContext.builder()
                .param(condition.getCondition().getParam())
                .world(DialogueWorlds.currentWorld(ctx));
        // Guarded exactly like the world read beside it: a context whose engine handles cannot be
        // read supplies no subject rather than throwing, so the provider sees an honest "nothing
        // to inspect" and the gate closes on that instead of on an exception.
        try {
            factorCtx.store(ctx.store()).subject(ctx.ref());
        } catch (Throwable ignored) {
            // leave both unset
        }
        return condition.getCondition().accepts(factors.resolve(condition.getFactor(), factorCtx.build()));
    }

    /** Report an authoring mistake that repeats on every render exactly once per distinct case. */
    private void warnOnce(@Nonnull String key, @Nonnull String message) {
        if (warnedOnce.add(key)) {
            warn.accept(message);
        }
    }

    /**
     * The decisive look for an option: an explicit {@code Style} kind override if authored
     * ({@link DialogueOption#getStyleKind}, resolved through {@link DialogueOptionStyle#byKey}), else
     * the first action whose registered type declared a {@link DialogueOptionStyle}, else
     * {@link DialogueOptionStyle#CONTINUE} (an option with no Goto/Close re-renders its node, which
     * reads as a continue).
     */
    @Nonnull
    public DialogueOptionStyle classifyOption(@Nonnull DialogueOption option) {
        String kind = option.getStyleKind();
        if (kind != null && !kind.isBlank()) {
            DialogueOptionStyle explicit = DialogueOptionStyle.byKey(kind);
            if (explicit != null) {
                return explicit;
            }
        }
        for (DialogueAction action : option.getActions()) {
            DialogueOptionStyle style = styles.get(action.getClass());
            if (style != null) {
                return style;
            }
        }
        return DialogueOptionStyle.CONTINUE;
    }

    // ==================== Builder ====================

    /** Assembles a {@link DialogueEngine}: pre-seeds the generic types, then the consumer adds its own. */
    public static final class Builder {

        private final Map<String, DialogueActionType<?>> actions = new LinkedHashMap<>();
        private final Map<String, DialogueConditionType<?>> conditions = new LinkedHashMap<>();
        private DialoguePageRouter router = DialoguePageRouter.NONE;
        private Consumer<String> warn = DEFAULT_WARN;
        @Nullable private FactorRegistry factors;

        /**
         * The one-slot holder the seeded handlers/evaluators reach the FINISHED engine through
         * (they are registered before it exists, and are only ever invoked long after
         * {@link #build()} filled this in). The combinators use the same holder.
         */
        private final DialogueEngine[] self = new DialogueEngine[1];

        Builder() {
            seedGenericConditions();
            seedGenericActions();
        }

        /** Register (or override by Type id) an action type. */
        @Nonnull
        public Builder action(@Nonnull DialogueActionType<?> type) {
            actions.put(type.typeId(), type);
            return this;
        }

        /** Register (or override by Type id) a condition type. */
        @Nonnull
        public Builder condition(@Nonnull DialogueConditionType<?> type) {
            conditions.put(type.typeId(), type);
            return this;
        }

        /** The router the generic {@code OpenPage} action uses (default routes nowhere). */
        @Nonnull
        public Builder router(@Nonnull DialoguePageRouter router) {
            this.router = router;
            return this;
        }

        /** The warn sink (default logs through the common plugin logger, unit-JVM-guarded). */
        @Nonnull
        public Builder warn(@Nonnull Consumer<String> warn) {
            this.warn = warn;
            return this;
        }

        /**
         * The factor vocabulary the generic {@code Factor} condition resolves against. Wire the
         * registry your own mod populated at setup; leaving it unset means every {@code Factor}
         * condition fails closed (with one warn), so a dialogue authored for a vocabulary this
         * server does not have hides those lines rather than offering them.
         */
        @Nonnull
        public Builder factors(@Nullable FactorRegistry factors) {
            this.factors = factors;
            return this;
        }

        @Nonnull
        public DialogueEngine build() {
            // Generic OpenPage, bound to the FINAL router (added unless a consumer overrode it).
            final DialoguePageRouter finalRouter = this.router;
            actions.putIfAbsent("OpenPage", DialogueActionType.of("OpenPage",
                            DialogueAction.OpenPage.class, DialogueAction.OpenPage.CODEC,
                            (DialogueAction.OpenPage a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> {
                                String target = DialogueActionExecutor.resolveTarget(a.getTarget(), ctx.contextId());
                                if (target != null && !target.isBlank() && finalRouter.open(target, ctx)) {
                                    out.markOpenedOtherPage();
                                }
                            })
                    .withStyle(DialogueOptionStyle.NEUTRAL)
                    .withSugar(DialogueSugar.string("Open", 50, "Target", "OpenPage")));

            // Assemble the action codec + handler/style maps + sugar table.
            CodecMapCodec<DialogueAction> actionCodec = new CodecMapCodec<>("Type");
            Map<Class<? extends DialogueAction>, DialogueActionHandler<?>> handlers = new HashMap<>();
            Map<Class<? extends DialogueAction>, DialogueOptionStyle> styles = new HashMap<>();
            List<DialogueSugarExpander> expanders = new ArrayList<>();
            for (DialogueActionType<?> type : actions.values()) {
                registerAction(actionCodec, type);
                handlers.put(type.actionClass(), type.handler());
                if (type.style() != null) {
                    styles.put(type.actionClass(), type.style());
                }
                if (type.sugar() != null) {
                    expanders.add(type.sugar());
                }
            }

            // Assemble the condition codec + evaluator map.
            CodecMapCodec<DialogueCondition> conditionCodec = new CodecMapCodec<>("Type");
            Map<Class<? extends DialogueCondition>, DialogueConditionEvaluator<?>> evaluators = new HashMap<>();
            for (DialogueConditionType<?> type : conditions.values()) {
                registerCondition(conditionCodec, type);
                evaluators.put(type.conditionClass(), type.evaluator());
            }

            // Build the dialogue codec graph around the two dispatch codecs.
            Codec<DialogueAction[]> actionsArray = new ArrayCodec<>(actionCodec, DialogueAction[]::new);
            Codec<DialogueCondition[]> conditionsArray = new ArrayCodec<>(conditionCodec, DialogueCondition[]::new);

            // Generic boolean combinators: their child-list codec IS the conditionsArray, so
            // they can only be assembled + registered now (like OpenPage's router binding).
            // Evaluation delegates each child back through the finished engine's conditionsPass,
            // reached via the one-slot holder set right after construction below.
            registerCombinators(conditionCodec, conditionsArray, evaluators, self);

            BuilderCodec<DialogueOption> optionCodec = BuilderCodec.builder(DialogueOption.class, DialogueOption::new)
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
                    .build();

            // Node fields are appendInherited so a child that overrides a node by key (via the
            // InheritMapCodec on Nodes) and restates only SOME fields keeps the parent node's
            // other fields (e.g. change just the text, keep the same options + conditions).
            BuilderCodec<DialogueNode> nodeCodec = BuilderCodec.builder(DialogueNode.class, DialogueNode::new)
                    .appendInherited(new KeyedCodec<>("TextKey", Codec.STRING, false),
                            (n, v) -> n.textKey = v, n -> n.textKey,
                            (child, parent) -> child.textKey = parent.textKey).add()
                    .appendInherited(new KeyedCodec<>("Text", Codec.STRING, false),
                            (n, v) -> n.text = v, n -> n.text,
                            (child, parent) -> child.text = parent.text).add()
                    .appendInherited(new KeyedCodec<>("Conditions", conditionsArray, false),
                            (n, v) -> n.conditions = v, n -> n.conditions,
                            (child, parent) -> child.conditions = parent.conditions).add()
                    .appendInherited(new KeyedCodec<>("Options",
                                    new ArrayCodec<>(optionCodec, DialogueOption[]::new), false),
                            (n, v) -> n.options = v, n -> n.options,
                            (child, parent) -> child.options = parent.options).add()
                    .build();

            BuilderCodec<NpcDialogue.DialogueEntry> entryCodec =
                    BuilderCodec.builder(NpcDialogue.DialogueEntry.class, NpcDialogue.DialogueEntry::new)
                            .append(new KeyedCodec<>("Node", Codec.STRING, false),
                                    (e, v) -> e.node = v, e -> e.node).add()
                            .append(new KeyedCodec<>("Conditions", conditionsArray, false),
                                    (e, v) -> e.conditions = v, e -> e.conditions).add()
                            .append(new KeyedCodec<>("Once", DialogueOnce.CODEC, false),
                                    (e, v) -> e.once = v, e -> e.once).add()
                            .build();

            Codec<NpcDialogue.DialogueEntry[]> startCodec =
                    new ArrayCodec<>(entryCodec, NpcDialogue.DialogueEntry[]::new);
            // InheritMapCodec (not MapCodec): under native Parent inheritance a child that
            // provides SOME nodes deep-merges them onto the parent's node map by key (each
            // DialogueNode is a BuilderCodec = InheritCodec, so its fields merge too), rather
            // than whole-replacing. Parent-only nodes are retained.
            Codec<Map<String, DialogueNode>> nodesCodec = new InheritMapCodec<>(nodeCodec, LinkedHashMap::new);
            // Memories keyed-merge exactly like Nodes: a child dialogue re-declares or adds ONE
            // memory (its own leaves merging onto the inherited declaration) and keeps the rest.
            Codec<Map<String, DialogueMemory>> memoriesCodec =
                    new InheritMapCodec<>(DialogueMemory.CODEC, LinkedHashMap::new);

            // Start + Nodes + Memories are appendInherited: when a child body OMITS the field
            // entirely it inherits the parent's value (Phase-1 seed); when it provides the field,
            // Phase-2 overrides (Start whole-replaces like a vanilla list; Nodes and Memories
            // keyed-merge via the InheritMapCodecs above).
            BuilderCodec<NpcDialogue> dialogueCodec = BuilderCodec.builder(NpcDialogue.class, NpcDialogue::new)
                    .appendInherited(new KeyedCodec<>("Start", startCodec, false),
                            (d, v) -> d.start = v, d -> d.start,
                            (child, parent) -> child.start = parent.start).add()
                    .appendInherited(new KeyedCodec<>("Nodes", nodesCodec, false),
                            (d, v) -> d.nodes = v, d -> d.nodes,
                            (child, parent) -> child.nodes = parent.nodes).add()
                    .appendInherited(new KeyedCodec<>("Memories", memoriesCodec, false),
                            (d, v) -> d.memories = v, d -> d.memories,
                            (child, parent) -> child.memories = parent.memories).add()
                    .build();

            DialogueActionExecutor executor = new DialogueActionExecutor(handlers, warn);
            DialogueSugar sugarPass = new DialogueSugar(expanders);
            DialogueEngine engine = new DialogueEngine(dialogueCodec, evaluators, styles, executor, sugarPass,
                    warn, factors);
            self[0] = engine;
            return engine;
        }

        private void seedGenericActions() {
            action(DialogueActionType.of("Goto", DialogueAction.Goto.class, DialogueAction.Goto.CODEC,
                            (DialogueAction.Goto a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) ->
                                    out.goTo(a.getNode()))
                    .withStyle(DialogueOptionStyle.CONTINUE)
                    .withSugar(DialogueSugar.string("Goto", 60, "Node", "Goto")));

            action(DialogueActionType.of("Close", DialogueAction.Close.class, DialogueAction.Close.CODEC,
                            (DialogueAction.Close a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) ->
                                    out.requestClose())
                    .withStyle(DialogueOptionStyle.FAREWELL)
                    .withSugar(DialogueSugar.close("Close", 70)));

            // Remember / Forget write a memory the dialogue DECLARED in its Memories map; the
            // scope and lifetime live in that declaration, so the use site is just the name.
            // A null key means the memory does not exist here (per world family, wrong world),
            // which makes the write a deliberate no-op. Bare-key sugar orders 32/33 keep the
            // memory writes clear of the 10/20/25/30 band consumers register their own quest
            // sugar in, so a bare {"TurnIn": ..., "Remember": ...} records the memory AFTER the
            // turn-in that justifies it rather than before.
            action(DialogueActionType.of("Remember", DialogueAction.Remember.class,
                            DialogueAction.Remember.CODEC,
                            (DialogueAction.Remember a, DialogueExecContext ctx,
                             DialogueActionExecutor.Mut out) -> {
                                String key = self[0].memoryKey(a.getMemory(), ctx);
                                if (key != null) {
                                    ctx.flags().set(key);
                                }
                            })
                    .withSugar(DialogueSugar.string("Remember", 32, "Memory", "Remember")));

            action(DialogueActionType.of("Forget", DialogueAction.Forget.class,
                            DialogueAction.Forget.CODEC,
                            (DialogueAction.Forget a, DialogueExecContext ctx,
                             DialogueActionExecutor.Mut out) -> {
                                String key = self[0].memoryKey(a.getMemory(), ctx);
                                if (key != null) {
                                    ctx.flags().clear(key);
                                }
                            })
                    .withSugar(DialogueSugar.string("Forget", 33, "Memory", "Forget")));

            // Talk is a generic carrier with a no-op handler; a consumer overrides "Talk"
            // to inject domain behavior (e.g. firing a quest objective).
            action(DialogueActionType.of("Talk", DialogueAction.Talk.class, DialogueAction.Talk.CODEC,
                            (DialogueAction.Talk a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> {
                                /* no-op carrier */ })
                    .withSugar(DialogueSugar.talk("Talk", 10)));
        }

        private void seedGenericConditions() {
            // A memory kept per world family does not exist outside it, which reads as forgotten:
            // Remembered fails (content hidden) and NotRemembered passes.
            condition(DialogueConditionType.of("Remembered", DialogueCondition.Remembered.class,
                    DialogueCondition.Remembered.CODEC,
                    (DialogueCondition.Remembered c, DialogueContext ctx) -> {
                        String key = self[0].memoryKey(c.getMemory(), ctx);
                        return key != null && ctx.flags().has(key);
                    }));
            condition(DialogueConditionType.of("NotRemembered", DialogueCondition.NotRemembered.class,
                    DialogueCondition.NotRemembered.CODEC,
                    (DialogueCondition.NotRemembered c, DialogueContext ctx) -> {
                        String key = self[0].memoryKey(c.getMemory(), ctx);
                        return key == null || !ctx.flags().has(key);
                    }));
            // The player's current world, scored against an embedded WorldSelector. Fail-closed:
            // an unreadable world (or a selector with no positive axis) matches nothing, and
            // WorldSelector.match is itself try-guarded.
            condition(DialogueConditionType.of("World", DialogueCondition.World.class,
                    DialogueCondition.World.CODEC,
                    (DialogueCondition.World c, DialogueContext ctx) ->
                            c.getSelector().match(DialogueWorlds.currentWorld(ctx)) != null));
            // A number some OTHER mod owns, resolved through the registry this engine was built
            // with. Fail-closed at both altitudes: no registry wired at all, and an id inside one
            // that nobody registered, both hide the gated content rather than offering a line the
            // server cannot back up.
            condition(DialogueConditionType.of("Factor", DialogueCondition.Factor.class,
                    DialogueCondition.Factor.CODEC,
                    (DialogueCondition.Factor c, DialogueContext ctx) -> self[0].factorPasses(c, ctx)));
        }
    }

    // ==================== capture helpers ====================

    private static <A extends DialogueAction> void registerAction(
            @Nonnull CodecMapCodec<DialogueAction> codec, @Nonnull DialogueActionType<A> type) {
        codec.register(type.typeId(), type.actionClass(), type.codec());
    }

    private static <C extends DialogueCondition> void registerCondition(
            @Nonnull CodecMapCodec<DialogueCondition> codec, @Nonnull DialogueConditionType<C> type) {
        codec.register(type.typeId(), type.conditionClass(), type.codec());
    }

    /**
     * Register the generic boolean combinators ({@code AllOf}/{@code AnyOf}/{@code Not})
     * whose child-list codec is the engine-scoped {@code conditionsArray} (so they can
     * only be assembled inside {@code build()}). Each evaluator delegates its children
     * back through the finished engine's {@link #conditionsPass}, reached via the
     * one-slot {@code self} holder set immediately after construction.
     */
    private static void registerCombinators(
            @Nonnull CodecMapCodec<DialogueCondition> conditionCodec,
            @Nonnull Codec<DialogueCondition[]> conditionsArray,
            @Nonnull Map<Class<? extends DialogueCondition>, DialogueConditionEvaluator<?>> evaluators,
            @Nonnull DialogueEngine[] self) {

        BuilderCodec<DialogueCondition.AllOf> allOf = BuilderCodec.builder(
                        DialogueCondition.AllOf.class, DialogueCondition.AllOf::new)
                .append(new KeyedCodec<>("All", conditionsArray, false),
                        (c, v) -> c.children = v, c -> c.children).add()
                .build();
        conditionCodec.register("AllOf", DialogueCondition.AllOf.class, allOf);
        DialogueConditionEvaluator<DialogueCondition.AllOf> allEval =
                (c, ctx) -> self[0].conditionsPass(c.getChildren(), ctx);
        evaluators.put(DialogueCondition.AllOf.class, allEval);

        BuilderCodec<DialogueCondition.AnyOf> anyOf = BuilderCodec.builder(
                        DialogueCondition.AnyOf.class, DialogueCondition.AnyOf::new)
                .append(new KeyedCodec<>("Any", conditionsArray, false),
                        (c, v) -> c.children = v, c -> c.children).add()
                .build();
        conditionCodec.register("AnyOf", DialogueCondition.AnyOf.class, anyOf);
        DialogueConditionEvaluator<DialogueCondition.AnyOf> anyEval = (c, ctx) -> {
            List<DialogueCondition> children = c.getChildren();
            if (children.isEmpty()) {
                return false;
            }
            for (DialogueCondition child : children) {
                if (self[0].conditionsPass(List.of(child), ctx)) {
                    return true;
                }
            }
            return false;
        };
        evaluators.put(DialogueCondition.AnyOf.class, anyEval);

        BuilderCodec<DialogueCondition.Not> not = BuilderCodec.builder(
                        DialogueCondition.Not.class, DialogueCondition.Not::new)
                .append(new KeyedCodec<>("Of", conditionsArray, false),
                        (c, v) -> c.children = v, c -> c.children).add()
                .build();
        conditionCodec.register("Not", DialogueCondition.Not.class, not);
        DialogueConditionEvaluator<DialogueCondition.Not> notEval =
                (c, ctx) -> !self[0].conditionsPass(c.getChildren(), ctx);
        evaluators.put(DialogueCondition.Not.class, notEval);
    }

    /** Default warn: logs through the common plugin logger, guarded for log-manager-less unit JVMs. */
    private static final Consumer<String> DEFAULT_WARN = msg -> {
        try {
            CommonLog.LOGGER.atWarning().log("[Dialogue] %s", msg);
        } catch (Throwable ignored) {
            // a unit JVM with no log manager throws an Error from the fluent logger; swallow it.
        }
    };
}
