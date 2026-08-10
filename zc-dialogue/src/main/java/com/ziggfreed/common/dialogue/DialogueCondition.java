package com.ziggfreed.common.dialogue;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
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
 * <p>Authored shape: {@code "Conditions": [ {"Type":"Flag","Flag":"met_elder"},
 * {"Type":"QuestState","Quest":"intro","State":"ACTIVE"} ]}. Each condition is
 * evaluated by its registered {@link DialogueConditionEvaluator}; the generic
 * {@code Flag}/{@code NotFlag} read dialogue-local memory through the context's
 * flag store, and the generic {@code World} scores the player's current world
 * against an embedded {@link com.ziggfreed.common.world.WorldSelector}.
 */
public abstract class DialogueCondition {

    /**
     * Passes only when the per-player dialogue flag IS set.
     *
     * <p>An optional {@link DialogueFlagScope} narrows WHICH flag is read: with
     * {@code "Scope": {"WorldSelector": "forgotten_temple"}} the condition reads the
     * temple-scoped copy of the flag, and in a world that does not carry that selector name the
     * flag reads as UNSET (so this condition fails, hiding the gated content). Omit {@code Scope}
     * for the plain global flag.
     */
    public static final class Flag extends DialogueCondition {
        public static final BuilderCodec<Flag> CODEC = BuilderCodec.builder(Flag.class, Flag::new)
                .append(new KeyedCodec<>("Flag", Codec.STRING, false),
                        (c, v) -> c.flag = v, c -> c.flag).add()
                .append(new KeyedCodec<>("Scope", DialogueFlagScope.CODEC, false),
                        (c, v) -> c.scope = v, c -> c.scope).add()
                .build();

        @Nullable protected String flag;
        @Nullable protected DialogueFlagScope scope;

        @Nullable public String getFlag() { return flag; }

        /** The namespace this flag is read from, or null for the global flag. */
        @Nullable public DialogueFlagScope getScope() { return scope; }
    }

    /**
     * Passes only when the per-player dialogue flag is NOT set.
     *
     * <p>Takes the same optional {@link DialogueFlagScope} as {@link Flag}. In a world that does
     * not carry the scoped selector name the flag reads as UNSET, so this condition PASSES - which
     * is why a first-visit beat gated on a scoped {@code NotFlag} should sit beside a
     * {@code World} condition, and why a mistyped selector name warns at runtime and is a
     * validator finding.
     */
    public static final class NotFlag extends DialogueCondition {
        public static final BuilderCodec<NotFlag> CODEC = BuilderCodec.builder(NotFlag.class, NotFlag::new)
                .append(new KeyedCodec<>("Flag", Codec.STRING, false),
                        (c, v) -> c.flag = v, c -> c.flag).add()
                .append(new KeyedCodec<>("Scope", DialogueFlagScope.CODEC, false),
                        (c, v) -> c.scope = v, c -> c.scope).add()
                .build();

        @Nullable protected String flag;
        @Nullable protected DialogueFlagScope scope;

        @Nullable public String getFlag() { return flag; }

        /** The namespace this flag is read from, or null for the global flag. */
        @Nullable public DialogueFlagScope getScope() { return scope; }
    }

    /**
     * Passes only when the player's CURRENT world matches an embedded
     * {@link com.ziggfreed.common.world.WorldSelector} - the ONE world-identity authority, written
     * inline here with its own keys:
     *
     * <pre>{@code
     * {"Type": "World", "Names": ["forgotten_temple"]}
     * {"Type": "World", "Match": ["*Forgotten_Temple*"], "ExcludeNames": ["arena"]}
     * {"Type": "World", "GameplayConfig": ["ForgottenTemple"]}
     * }</pre>
     *
     * <p>The selector's own semantics apply unchanged, because this type re-models none of them:
     * it holds the four authored axes and hands them to {@link WorldSelector#of} (see
     * {@link #getSelector()}). So {@code Names} resolves through the cached world-identity index,
     * {@code ExcludeNames} is a FILTER rather than a complement, and a selector with no positive
     * axis matches NOTHING - which fails this condition closed and is a validator finding, since a
     * condition that can never pass makes its content permanently invisible.
     *
     * <p>Fail-closed on an unreadable world too: no world means no match means the gated content
     * stays hidden, consistent with {@link DialogueEngine#conditionsPass}'s treatment of a throwing
     * or unregistered evaluator.
     */
    public static final class World extends DialogueCondition {
        public static final BuilderCodec<World> CODEC = BuilderCodec.builder(World.class, World::new)
                .append(new KeyedCodec<>("Names", Codec.STRING_ARRAY, false),
                        (c, v) -> { c.names = v; c.resolved = null; }, c -> c.names).add()
                .append(new KeyedCodec<>("Match", Codec.STRING_ARRAY, false),
                        (c, v) -> { c.match = v; c.resolved = null; }, c -> c.match).add()
                .append(new KeyedCodec<>("GameplayConfig", Codec.STRING_ARRAY, false),
                        (c, v) -> { c.gameplayConfig = v; c.resolved = null; }, c -> c.gameplayConfig).add()
                .append(new KeyedCodec<>("ExcludeNames", Codec.STRING_ARRAY, false),
                        (c, v) -> { c.excludeNames = v; c.resolved = null; }, c -> c.excludeNames).add()
                .build();

        @Nullable protected String[] names;
        @Nullable protected String[] match;
        @Nullable protected String[] gameplayConfig;
        @Nullable protected String[] excludeNames;

        @Nullable private volatile WorldSelector resolved;

        /** The authored selector NAMES, or null. */
        @Nullable public String[] getNames() { return names; }

        /**
         * The embedded {@link WorldSelector} carrying the authored axes: ALL matching behaviour
         * (rank ladder, name resolution, the exclude filter) lives there and is never duplicated
         * here. Built lazily and memoized; every setter drops the memo, so a post-decode field
         * write can never leave a stale selector behind.
         */
        @Nonnull
        public WorldSelector getSelector() {
            WorldSelector cached = resolved;
            if (cached == null) {
                cached = WorldSelector.of(names, match, gameplayConfig, excludeNames);
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
