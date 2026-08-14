package com.ziggfreed.common.dialogue;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.world.WorldSelector;

/**
 * One visibility/eligibility condition on a dialogue OPTION or entry candidate,
 * authored as a {@code Type}-discriminated JSON object inside a {@code Conditions}
 * array (symmetric with {@link DialogueAction}). Conditions in the array are
 * AND-combined. Like actions, the dispatch codec is owned per-{@link DialogueEngine}
 * (pre-seeded with the generic conditions below, extended by the consumer via
 * {@link DialogueConditionType}), so a consumer adds a domain condition (quest
 * state, requirement gate, ...) without the engine knowing the domain.
 *
 * <p>Authored shape: {@code "Conditions": [ {"Type":"Remembered","Memory":"met_elder"},
 * {"Type":"QuestState","Quest":"intro","State":"ACTIVE"} ]}. Each condition is
 * evaluated by its registered {@link DialogueConditionEvaluator}; the generic
 * {@code Remembered}/{@code NotRemembered} read a declared {@link DialogueMemory}
 * through the context's state store, and the generic {@code World} scores the
 * player's current world against an embedded
 * {@link com.ziggfreed.common.world.WorldSelector}.
 */
public abstract class DialogueCondition {

    /**
     * The shared shape of the two memory conditions: a bare {@code Memory} name, declared in the
     * dialogue's own {@code Memories} map (see {@link DialogueMemory}), which is where the scope
     * and lifetime of that name live.
     */
    public abstract static class MemoryCondition extends DialogueCondition {
        @Nullable protected String memory;

        /** The declared memory name this condition reads, or null when unauthored. */
        @Nullable public String getMemory() { return memory; }
    }

    /**
     * Passes once the named memory has been remembered:
     * {@code {"Type":"Remembered","Memory":"helped_refugees"}}.
     *
     * <p>A memory kept per world family reads as forgotten in a world outside that family, so this
     * condition fails there and its content stays hidden.
     */
    public static final class Remembered extends MemoryCondition {
        public static final BuilderCodec<Remembered> CODEC =
                BuilderCodec.builder(Remembered.class, Remembered::new)
                        .append(new KeyedCodec<>("Memory", Codec.STRING, false),
                                (c, v) -> c.memory = v, c -> c.memory).add()
                        .build();
    }

    /**
     * Passes while the named memory has NOT been remembered:
     * {@code {"Type":"NotRemembered","Memory":"helped_refugees"}} - the mirror of
     * {@link Remembered}, for a line that should only appear the first time round.
     *
     * <p>A memory kept per world family reads as forgotten outside that family, so this condition
     * PASSES there: pair it with a {@code World} condition when the beat should only exist inside
     * the family at all.
     */
    public static final class NotRemembered extends MemoryCondition {
        public static final BuilderCodec<NotRemembered> CODEC =
                BuilderCodec.builder(NotRemembered.class, NotRemembered::new)
                        .append(new KeyedCodec<>("Memory", Codec.STRING, false),
                                (c, v) -> c.memory = v, c -> c.memory).add()
                        .build();
    }

    /**
     * Passes only when the player's CURRENT world matches the embedded
     * {@link com.ziggfreed.common.world.WorldSelector} under {@code Where} - the ONE
     * world-identity authority, written here in exactly the spelling every other
     * world-targeting asset uses:
     *
     * <pre>{@code
     * {"Type": "World", "Where": {"Match": ["default"]}}
     * {"Type": "World", "Where": {"Match": ["*Forgotten_Temple*"], "ExcludeMatch": ["*Arena*"]}}
     * {"Type": "World", "Where": {"GameplayConfig": ["ForgottenTemple"]}}
     * }</pre>
     *
     * <p>The selector is a NESTED GROUP rather than three keys of this type's own, so an author who
     * has written a {@code Where} on an NPC placement or a world rule already knows this one, and a
     * new selector axis reaches every surface at once instead of having to be re-added here.
     *
     * <p>The selector's own semantics apply unchanged, because this type re-models none of them:
     * {@code Match} is a world-name pattern (a bare word being an exact name), {@code ExcludeMatch}
     * is a FILTER rather than a complement, and a selector with no positive axis matches NOTHING -
     * which fails this condition closed and is a validator finding, since a condition that can
     * never pass makes its content permanently invisible.
     *
     * <p>Fail-closed on an unreadable world too, and on an omitted {@code Where}: no world and no
     * selector both mean no match, so the gated content stays hidden, consistent with
     * {@link DialogueEngine#conditionsPass}'s treatment of a throwing or unregistered evaluator.
     */
    public static final class World extends DialogueCondition {
        public static final BuilderCodec<World> CODEC = BuilderCodec.builder(World.class, World::new)
                .append(new KeyedCodec<>("Where", WorldSelector.CODEC, false),
                        (c, v) -> c.where = v, c -> c.where)
                .documentation("Which worlds this line may appear in, in the shared world-targeting "
                        + "vocabulary: Match (world-name patterns), GameplayConfig (exact config "
                        + "keys) and ExcludeMatch (a filter over those). Omit it and the line never "
                        + "shows, because nothing said where it applies.")
                .add()
                .build();

        @Nullable protected WorldSelector where;

        /**
         * The authored selector, or an EMPTY one when {@code Where} was omitted. Empty has no
         * positive axis, so it matches nothing - which is what makes the omission fail closed
         * rather than quietly meaning "everywhere".
         */
        @Nonnull
        public WorldSelector getSelector() {
            WorldSelector authored = where;
            return authored == null ? WorldSelector.of(null, null, null) : authored;
        }
    }

    /**
     * Passes when a NUMBER some other mod owns satisfies the authored bounds:
     * {@code {"Type":"Factor","Factor":"yourmod:reputation","Param":"guild","Min":10}}.
     *
     * <p>The shape is the shared {@link com.ziggfreed.common.factor.FactorCondition} leaf, written
     * inline here with its own keys, and the semantics are entirely that type's (see
     * {@link #getCondition()}): {@code Min}/{@code Max} are inclusive and independently optional,
     * and a condition with NEITHER is a presence check that passes as long as the factor resolves
     * at all - which is how "only where that mod is installed" is written.
     *
     * <p>The number comes from the {@link com.ziggfreed.common.factor.FactorRegistry} the engine
     * was built with ({@code DialogueEngine.Builder#factors}). <b>Fail-closed twice over</b>: an
     * unregistered id, a provider that cannot answer, and a THROWING provider all resolve to
     * nothing and hide the gated content, and so does an engine that was never handed a registry
     * at all - so a dialogue authored against a factor vocabulary that is not present shows its
     * ungated lines rather than promising something the server cannot deliver.
     */
    public static final class Factor extends DialogueCondition {
        public static final BuilderCodec<Factor> CODEC = BuilderCodec.builder(Factor.class, Factor::new)
                .append(new KeyedCodec<>("Factor", Codec.STRING, false),
                        (c, v) -> { c.factor = v; c.resolved = null; }, c -> c.factor)
                .metadata(new UIEditor(new UIEditor.Dropdown(EditorDataSets.FACTORS))).add()
                .append(new KeyedCodec<>("Param", Codec.STRING, false),
                        (c, v) -> { c.param = v; c.resolved = null; }, c -> c.param).add()
                .append(new KeyedCodec<>("Min", Codec.DOUBLE, false),
                        (c, v) -> { c.min = v; c.resolved = null; }, c -> c.min).add()
                .append(new KeyedCodec<>("Max", Codec.DOUBLE, false),
                        (c, v) -> { c.max = v; c.resolved = null; }, c -> c.max).add()
                .build();

        @Nullable protected String factor;
        @Nullable protected String param;
        @Nullable protected Double min;
        @Nullable protected Double max;

        @Nullable private volatile FactorCondition resolved;

        /** The authored factor id, or null. */
        @Nullable public String getFactor() { return factor; }

        /**
         * The embedded {@link FactorCondition} carrying the authored leaves: ALL bound behaviour
         * (inclusive bounds, the null-fails rule, the bounds-less presence check) lives there and
         * is never duplicated here. Built lazily and memoized; every setter drops the memo, so a
         * post-decode field write can never leave a stale condition behind.
         */
        @Nonnull
        public FactorCondition getCondition() {
            FactorCondition cached = resolved;
            if (cached == null) {
                cached = FactorCondition.of(factor, param, min, max);
                resolved = cached;
            }
            return cached;
        }
    }

    /**
     * A boolean combinator over child conditions. Its child-list codec is the
     * per-{@link DialogueEngine} {@code conditionsArray}, so (unlike the leaf
     * conditions above) it has NO static {@code CODEC} - the engine builds and
     * registers {@code AllOf}/{@code AnyOf}/{@code Not} in {@code Builder.build()}
     * once that array exists, and evaluates them by delegating each child back
     * through {@link DialogueEngine#conditionsPass}. Authored shape:
     * {@code {"Type":"AnyOf","Any":[ {"Type":"QuestState",...}, ... ]}}.
     */
    public abstract static class Combinator extends DialogueCondition {
        @Nullable protected DialogueCondition[] children;

        @Nonnull
        public List<DialogueCondition> getChildren() {
            return children == null ? Collections.emptyList() : List.of(children);
        }
    }

    /** Passes when EVERY child condition passes (an empty list passes). Key: {@code All}. */
    public static final class AllOf extends Combinator {
    }

    /** Passes when AT LEAST ONE child condition passes (an empty list FAILS). Key: {@code Any}. */
    public static final class AnyOf extends Combinator {
    }

    /** Passes when the child conditions do NOT all pass (AND then negate). Key: {@code Of}. */
    public static final class Not extends Combinator {
    }
}
