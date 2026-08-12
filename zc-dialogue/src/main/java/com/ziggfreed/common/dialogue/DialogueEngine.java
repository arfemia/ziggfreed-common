package com.ziggfreed.common.dialogue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.CommonLog;
import com.ziggfreed.common.dialogue.quest.DialogueQuests;
import com.ziggfreed.common.dialogue.quest.QuestDialogueActions;
import com.ziggfreed.common.dialogue.quest.QuestDialogueConditions;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;

/**
 * One mod's dialogue runtime: what its actions DO, what its conditions ANSWER, which page its
 * {@code OpenPage} reaches, which numbers its {@code Factor} conditions read, and which quest
 * runtime its quest lines talk to. Built once via {@link #builder()}, in the mod's setup.
 *
 * <p><b>Behaviour is private to an engine; the SCHEMA is shared.</b> Registering a type here teaches
 * this engine what to do with it AND teaches {@link DialogueTypeTable} how to read it, because
 * dialogue files live in one store the server reads once. So two mods can author into that store
 * without either one running the other's code: an action this engine has no handler for does
 * nothing, and a condition it cannot evaluate hides its line.
 *
 * <p>Build the engine in setup, before assets load. An engine built later has missed the read.
 */
public final class DialogueEngine {

    private final Map<Class<? extends DialogueCondition>, DialogueConditionEvaluator<?>> evaluators;
    private final Map<Class<? extends DialogueAction>, DialogueOptionStyle> styles;
    private final DialogueActionExecutor executor;
    private final Consumer<String> warn;
    @Nullable private final FactorRegistry factors;
    private final DialogueQuests quests;

    /** Authoring mistakes that repeat on every render, reported once per distinct case. */
    private final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();

    private DialogueEngine(@Nonnull Map<Class<? extends DialogueCondition>, DialogueConditionEvaluator<?>> evaluators,
                           @Nonnull Map<Class<? extends DialogueAction>, DialogueOptionStyle> styles,
                           @Nonnull DialogueActionExecutor executor,
                           @Nonnull Consumer<String> warn, @Nullable FactorRegistry factors,
                           @Nonnull DialogueQuests quests) {
        this.evaluators = evaluators;
        this.styles = styles;
        this.executor = executor;
        this.warn = warn;
        this.factors = factors;
        this.quests = quests;
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

    /**
     * The quest runtime the quest-aware lines read and act through. Never null: an engine built
     * without one keeps {@link DialogueQuests#NONE}, which answers nothing and refuses everything.
     */
    @Nonnull
    public DialogueQuests quests() {
        return quests;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /** The shared {@code Start}/{@code Nodes} codec every authored dialogue is read through. */
    @Nonnull
    public BuilderCodec<NpcDialogue> dialogueCodec() {
        return DialogueTypeTable.get().dialogueCodec();
    }

    @Nonnull
    public DialogueActionExecutor executor() {
        return executor;
    }

    /**
     * Whether this engine can actually answer a condition of that shape. The shared schema can READ
     * every mod's conditions, so a file may carry one this engine knows nothing about - and an
     * unanswerable condition hides its line. The content audit reports that rather than leaving it
     * to be noticed in game.
     */
    public boolean evaluates(@Nonnull Class<? extends DialogueCondition> conditionClass) {
        return evaluators.containsKey(conditionClass);
    }

    /**
     * Read a dialogue body ({@code Start}/{@code Nodes}/{@code Memories}/{@code Fragments}) written
     * as JSON, for a consumer building a tree in code or a test. Authored FILES do not come through
     * here - the asset store reads those directly, which is what gives them {@code Parent}
     * inheritance. Null + warn on failure.
     */
    @Nullable
    public NpcDialogue decode(@Nonnull String id, @Nonnull String json) {
        try {
            NpcDialogue d = DialogueTypeTable.get().dialogueCodec()
                    .decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
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

    /** Assembles a {@link DialogueEngine}: pre-seeds the generic vocabulary, then the consumer adds its own. */
    public static final class Builder {

        private final Map<String, DialogueActionType<?>> actions = new LinkedHashMap<>();
        private final Map<String, DialogueConditionType<?>> conditions = new LinkedHashMap<>();
        private DialoguePageRouter router = DialoguePageRouter.NONE;
        private Consumer<String> warn = DEFAULT_WARN;
        @Nullable private FactorRegistry factors;
        private DialogueQuests quests = DialogueQuests.NONE;

        /**
         * The one-slot holder the seeded handlers/evaluators reach the FINISHED engine through
         * (they are registered before it exists, and are only ever invoked long after
         * {@link #build()} filled this in). The combinators use the same holder.
         */
        private final DialogueEngine[] self = new DialogueEngine[1];

        Builder() {
            seedGenericConditions();
            seedGenericActions();
            seedQuestVocabulary();
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

        /**
         * The quest runtime the quest-aware lines read and act through. Leaving it unset means every
         * quest condition reads NOT_STARTED and every accept / hand-in refuses, so a dialogue written
         * for a quest system this server does not run hides those beats.
         */
        @Nonnull
        public Builder quests(@Nonnull DialogueQuests quests) {
            this.quests = quests;
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
                    .withSugar(DialogueSugar.string("Open", 50, target -> {
                        DialogueAction.OpenPage open = new DialogueAction.OpenPage();
                        open.target = target;
                        return open;
                    })));

            DialogueTypeTable table = DialogueTypeTable.get();
            Map<Class<? extends DialogueAction>, DialogueActionHandler<?>> handlers = new HashMap<>();
            Map<Class<? extends DialogueAction>, DialogueOptionStyle> styles = new HashMap<>();
            for (DialogueActionType<?> type : actions.values()) {
                table.register(type);
                handlers.put(type.actionClass(), type.handler());
                if (type.style() != null) {
                    styles.put(type.actionClass(), type.style());
                }
            }

            Map<Class<? extends DialogueCondition>, DialogueConditionEvaluator<?>> evaluators = new HashMap<>();
            for (DialogueConditionType<?> type : conditions.values()) {
                table.register(type);
                evaluators.put(type.conditionClass(), type.evaluator());
            }
            registerCombinatorEvaluators(evaluators, self);

            DialogueActionExecutor executor = new DialogueActionExecutor(handlers, warn);
            DialogueEngine engine = new DialogueEngine(evaluators, styles, executor, warn, factors, quests);
            self[0] = engine;
            return engine;
        }

        private void seedGenericActions() {
            action(DialogueActionType.of("Goto", DialogueAction.Goto.class, DialogueAction.Goto.CODEC,
                            (DialogueAction.Goto a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) ->
                                    out.goTo(a.getNode()))
                    .withStyle(DialogueOptionStyle.CONTINUE)
                    .withSugar(DialogueSugar.string("Goto", 60, node -> {
                        DialogueAction.Goto go = new DialogueAction.Goto();
                        go.node = node;
                        return go;
                    })));

            action(DialogueActionType.of("Close", DialogueAction.Close.class, DialogueAction.Close.CODEC,
                            (DialogueAction.Close a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) ->
                                    out.requestClose())
                    .withStyle(DialogueOptionStyle.FAREWELL)
                    .withSugar(DialogueSugar.close("Close", 70)));

            // Remember / Forget write a memory the dialogue DECLARED in its Memories map; the
            // scope and lifetime live in that declaration, so the use site is just the name.
            // A null key means the memory does not exist here (per world family, wrong world),
            // which makes the write a deliberate no-op. Orders 32/33 keep the memory writes clear
            // of the quest band (20/30), so a bare {"TurnIn": ..., "Remember": ...} records the
            // memory AFTER the turn-in that justifies it rather than before.
            action(DialogueActionType.of("Remember", DialogueAction.Remember.class,
                            DialogueAction.Remember.CODEC,
                            (DialogueAction.Remember a, DialogueExecContext ctx,
                             DialogueActionExecutor.Mut out) -> {
                                String key = self[0].memoryKey(a.getMemory(), ctx);
                                if (key != null) {
                                    ctx.flags().set(key);
                                }
                            })
                    .withSugar(DialogueSugar.string("Remember", 32, name -> {
                        DialogueAction.Remember remember = new DialogueAction.Remember();
                        remember.memory = name;
                        return remember;
                    })));

            action(DialogueActionType.of("Forget", DialogueAction.Forget.class,
                            DialogueAction.Forget.CODEC,
                            (DialogueAction.Forget a, DialogueExecContext ctx,
                             DialogueActionExecutor.Mut out) -> {
                                String key = self[0].memoryKey(a.getMemory(), ctx);
                                if (key != null) {
                                    ctx.flags().clear(key);
                                }
                            })
                    .withSugar(DialogueSugar.string("Forget", 33, name -> {
                        DialogueAction.Forget forget = new DialogueAction.Forget();
                        forget.memory = name;
                        return forget;
                    })));

            // MarkTalked is the credit beat, and it has NO sugar on purpose: crediting a conversation
            // is a deliberate statement about the story, so it is written out in full rather than
            // hidden inside a one-word shorthand that reads like a flag. Order 10 keeps it with the
            // other "record what just happened" writes, ahead of the quest band.
            action(DialogueActionType.of("MarkTalked", DialogueAction.MarkTalked.class,
                    DialogueAction.MarkTalked.CODEC,
                    (DialogueAction.MarkTalked a, DialogueExecContext ctx,
                     DialogueActionExecutor.Mut out) ->
                            DialogueTalk.credit(ctx,
                                    DialogueActionExecutor.resolveTarget(a.getTarget(), ctx.contextId()),
                                    a.getQualifier())));
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

        /**
         * The quest-aware vocabulary, seeded like the rest and reading through whatever the consumer
         * later wires with {@link #quests}. Seeded rather than opt-in because a quest giver with
         * nothing to say about the state of what it gave out is not a giver, and because a shared
         * store needs one spelling of "is this quest active" rather than one per mod.
         */
        private void seedQuestVocabulary() {
            for (DialogueConditionType<?> type : QuestDialogueConditions.types(() -> self[0].quests())) {
                condition(type);
            }
            for (DialogueActionType<?> type : QuestDialogueActions.types(() -> self[0].quests())) {
                action(type);
            }
        }
    }

    /**
     * The combinator EVALUATORS: each walks its children back through this engine's own condition
     * pass, so a combinator wrapping a domain condition is evaluated by the mod that owns it. Their
     * codecs live with the shared schema, because a combinator's children are the shared array.
     */
    private static void registerCombinatorEvaluators(
            @Nonnull Map<Class<? extends DialogueCondition>, DialogueConditionEvaluator<?>> evaluators,
            @Nonnull DialogueEngine[] self) {

        DialogueConditionEvaluator<DialogueCondition.AllOf> allEval =
                (c, ctx) -> self[0].conditionsPass(c.getChildren(), ctx);
        evaluators.put(DialogueCondition.AllOf.class, allEval);

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
