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
            String node = candidate.getNode();
            if (node == null || node.isBlank()) {
                continue;
            }
            if (conditionsPass(candidate.getConditions(), ctx)) {
                return node;
            }
        }
        Map<String, DialogueNode> all = dialogue.getNodes();
        return all.isEmpty() ? null : all.keySet().iterator().next();
    }

    /**
     * The decisive look for an option: the first action whose registered type
     * declared a {@link DialogueOptionStyle}, else {@link DialogueOptionStyle#CONTINUE}
     * (an option with no Goto/Close re-renders its node, which reads as a continue).
     */
    @Nonnull
    public DialogueOptionStyle classifyOption(@Nonnull DialogueOption option) {
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

            BuilderCodec<DialogueOption> optionCodec = BuilderCodec.builder(DialogueOption.class, DialogueOption::new)
                    .append(new KeyedCodec<>("LabelKey", Codec.STRING, false),
                            (o, v) -> o.labelKey = v, o -> o.labelKey).add()
                    .append(new KeyedCodec<>("Label", Codec.STRING, false),
                            (o, v) -> o.label = v, o -> o.label).add()
                    .append(new KeyedCodec<>("Conditions", conditionsArray, false),
                            (o, v) -> o.conditions = v, o -> o.conditions).add()
                    .append(new KeyedCodec<>("Actions", actionsArray, false),
                            (o, v) -> o.actions = v, o -> o.actions).add()
                    .build();

            BuilderCodec<DialogueNode> nodeCodec = BuilderCodec.builder(DialogueNode.class, DialogueNode::new)
                    .append(new KeyedCodec<>("TextKey", Codec.STRING, false),
                            (n, v) -> n.textKey = v, n -> n.textKey).add()
                    .append(new KeyedCodec<>("Text", Codec.STRING, false),
                            (n, v) -> n.text = v, n -> n.text).add()
                    .append(new KeyedCodec<>("TextParams",
                                    new ArrayCodec<>(DialogueTextParam.CODEC, DialogueTextParam[]::new), false),
                            (n, v) -> n.textParams = v, n -> n.textParams).add()
                    .append(new KeyedCodec<>("Options",
                                    new ArrayCodec<>(optionCodec, DialogueOption[]::new), false),
                            (n, v) -> n.options = v, n -> n.options).add()
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
            Codec<Map<String, DialogueNode>> nodesCodec = new MapCodec<>(nodeCodec, LinkedHashMap::new);

            BuilderCodec<NpcDialogue> dialogueCodec = BuilderCodec.builder(NpcDialogue.class, NpcDialogue::new)
                    .append(new KeyedCodec<>("Start", startCodec, false),
                            (d, v) -> d.start = v, d -> d.start).add()
                    .append(new KeyedCodec<>("Nodes", nodesCodec, false),
                            (d, v) -> d.nodes = v, d -> d.nodes).add()
                    .build();

            DialogueActionExecutor executor = new DialogueActionExecutor(handlers, warn);
            DialogueSugar sugarPass = new DialogueSugar(expanders);
            return new DialogueEngine(dialogueCodec, evaluators, styles, executor, sugarPass, warn);
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

    /** Default warn: logs through the common plugin logger, guarded for log-manager-less unit JVMs. */
    private static final Consumer<String> DEFAULT_WARN = msg -> {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log("[Dialogue] %s", msg);
        } catch (Throwable ignored) {
            // a unit JVM with no log manager throws an Error from the fluent logger; swallow it.
        }
    };
}
