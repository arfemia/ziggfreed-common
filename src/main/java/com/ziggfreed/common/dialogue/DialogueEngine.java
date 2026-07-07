package com.ziggfreed.common.dialogue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.ziggfreed.common.ZiggfreedCommonPlugin;
import com.ziggfreed.common.codec.InheritMapCodec;

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

    private DialogueEngine(@Nonnull BuilderCodec<NpcDialogue> dialogueCodec,
                           @Nonnull Map<Class<? extends DialogueCondition>, DialogueConditionEvaluator<?>> evaluators,
                           @Nonnull Map<Class<? extends DialogueAction>, DialogueOptionStyle> styles,
                           @Nonnull DialogueActionExecutor executor, @Nonnull DialogueSugar sugar,
                           @Nonnull Consumer<String> warn) {
        this.dialogueCodec = dialogueCodec;
        this.evaluators = evaluators;
        this.styles = styles;
        this.executor = executor;
        this.sugar = sugar;
        this.warn = warn;
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
     * Pick the entry node for this player: the first {@code Start} candidate whose
     * conditions pass, else the first authored node (the validator flags a missing
     * start). Null only when the dialogue has no nodes at all.
     */
    @Nullable
    public String resolveEntryNodeId(@Nonnull NpcDialogue dialogue, @Nonnull DialogueContext ctx) {
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
            return nodeId;
        }
        // Fallback: the first node whose own conditions pass, else the first node.
        Map<String, DialogueNode> all = dialogue.getNodes();
        for (Map.Entry<String, DialogueNode> e : all.entrySet()) {
            DialogueNode node = e.getValue();
            if (!node.hasConditions() || conditionsPass(node.getConditions(), ctx)) {
                return e.getKey();
            }
        }
        return all.isEmpty() ? null : all.keySet().iterator().next();
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
            // reached via a one-slot holder set right after construction below.
            final DialogueEngine[] self = new DialogueEngine[1];
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
                            .build();

            Codec<NpcDialogue.DialogueEntry[]> startCodec =
                    new ArrayCodec<>(entryCodec, NpcDialogue.DialogueEntry[]::new);
            // InheritMapCodec (not MapCodec): under native Parent inheritance a child that
            // provides SOME nodes deep-merges them onto the parent's node map by key (each
            // DialogueNode is a BuilderCodec = InheritCodec, so its fields merge too), rather
            // than whole-replacing. Parent-only nodes are retained.
            Codec<Map<String, DialogueNode>> nodesCodec = new InheritMapCodec<>(nodeCodec, LinkedHashMap::new);

            // Start + Nodes are appendInherited: when a child body OMITS the field entirely it
            // inherits the parent's value (Phase-1 seed); when it provides the field, Phase-2
            // overrides (Start whole-replaces like a vanilla list; Nodes keyed-merges via the
            // InheritMapCodec above).
            BuilderCodec<NpcDialogue> dialogueCodec = BuilderCodec.builder(NpcDialogue.class, NpcDialogue::new)
                    .appendInherited(new KeyedCodec<>("Start", startCodec, false),
                            (d, v) -> d.start = v, d -> d.start,
                            (child, parent) -> child.start = parent.start).add()
                    .appendInherited(new KeyedCodec<>("Nodes", nodesCodec, false),
                            (d, v) -> d.nodes = v, d -> d.nodes,
                            (child, parent) -> child.nodes = parent.nodes).add()
                    .build();

            DialogueActionExecutor executor = new DialogueActionExecutor(handlers, warn);
            DialogueSugar sugarPass = new DialogueSugar(expanders);
            DialogueEngine engine = new DialogueEngine(dialogueCodec, evaluators, styles, executor, sugarPass, warn);
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

            action(DialogueActionType.of("SetFlag", DialogueAction.SetFlag.class, DialogueAction.SetFlag.CODEC,
                    (DialogueAction.SetFlag a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> {
                        String flag = a.getFlag();
                        if (flag != null && !flag.isBlank()) {
                            ctx.flags().set(flag);
                        }
                    }));

            // Talk is a generic carrier with a no-op handler; a consumer overrides "Talk"
            // to inject domain behavior (e.g. firing a quest objective).
            action(DialogueActionType.of("Talk", DialogueAction.Talk.class, DialogueAction.Talk.CODEC,
                            (DialogueAction.Talk a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> {
                                /* no-op carrier */ })
                    .withSugar(DialogueSugar.talk("Talk", 10)));
        }

        private void seedGenericConditions() {
            condition(DialogueConditionType.of("Flag", DialogueCondition.Flag.class, DialogueCondition.Flag.CODEC,
                    (DialogueCondition.Flag c, DialogueContext ctx) -> {
                        String f = c.getFlag();
                        return f == null || f.isBlank() || ctx.flags().has(f);
                    }));
            condition(DialogueConditionType.of("NotFlag", DialogueCondition.NotFlag.class,
                    DialogueCondition.NotFlag.CODEC,
                    (DialogueCondition.NotFlag c, DialogueContext ctx) -> {
                        String f = c.getFlag();
                        return f == null || f.isBlank() || !ctx.flags().has(f);
                    }));
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
            ZiggfreedCommonPlugin.LOGGER.atWarning().log("[Dialogue] %s", msg);
        } catch (Throwable ignored) {
            // a unit JVM with no log manager throws an Error from the fluent logger; swallow it.
        }
    };
}
