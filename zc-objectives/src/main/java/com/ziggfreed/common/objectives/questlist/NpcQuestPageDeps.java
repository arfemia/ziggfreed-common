package com.ziggfreed.common.objectives.questlist;

import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * What a consumer may say about {@link ZigNpcQuestPage} without owning it.
 *
 * <p>Every seam here has a DEFAULT that leaves the page fully working on a bare server: the quest
 * state and the catalogue come from the shared progression runtime, the offers come from the open
 * offer table, and the text comes from the registered text sources. Nothing in this record is
 * required for the page to run - a consumer fills a seam to say something the library genuinely
 * cannot know, and nothing else.
 *
 * <ul>
 *   <li>{@link NpcNameSource} - what a character is CALLED. The library has no NPC identity of its
 *       own down here, so an unfilled seam renders the character's id and a filled one renders the
 *       one authored name every other surface shows.</li>
 *   <li>{@link AnswerSetSource} - which ids a character ANSWERS TO. The default answers "just its
 *       own id", which is right for a character with no aliases and wrong for one standing in two
 *       worlds, so a consumer with an identity layer fills it.</li>
 *   <li>{@link RewardChipSource} - how ONE reward reads, when a mod knows something about its own
 *       kind that the generic reading cannot recover. The generic reading is tried when this
 *       returns null, never instead of it.</li>
 *   <li>{@link CompletionHandOff} - what follows a quest settled at this character. The routing
 *       policy belongs to the dialogue layer, which sits beside this module rather than under it,
 *       so the wiring root fills this and the page merely HOSTS the beat.</li>
 *   <li>{@link CompletionToast} - the toast a consumer floats when a quest settles here, so a
 *       hand-in made on this page reads exactly like one made in that mod's own menu.</li>
 *   <li>{@link PageTheme} - how the page frame is painted, for a consumer shipping a theme.</li>
 * </ul>
 *
 * <p>Immutable; build one at setup and hand the same instance back on every open.
 */
public final class NpcQuestPageDeps {

    /**
     * How the page's root template reaches the screen. A consumer with a theme appends it and
     * retints the frame in one call; the default simply appends it.
     */
    @FunctionalInterface
    public interface PageTheme {

        /** Append {@code template} and paint whatever theme the consumer has for {@code frameSelector}. */
        void appendThemed(@Nonnull UICommandBuilder cmd, @Nonnull String template,
                @Nonnull String frameSelector);
    }

    /** What a character is called, as a client-resolved message; null when nothing knows. */
    @FunctionalInterface
    public interface NpcNameSource {

        @Nullable
        Message nameFor(@Nonnull String npcId);
    }

    /** Every id a character answers to, its own included. Never empty. */
    @FunctionalInterface
    public interface AnswerSetSource {

        @Nonnull
        Set<String> answerSetOf(@Nonnull String npcId);
    }

    /** How one reward reads on the detail panel; null to fall through to the generic reading. */
    @FunctionalInterface
    public interface RewardChipSource {

        @Nullable
        RewardChip chipFor(@Nonnull RewardSpec spec);
    }

    /**
     * What follows a quest that settled at this character: a conversation, or nothing.
     *
     * <p>Return true ONLY when something else took the screen, in which case the page stops
     * refreshing itself - the player is looking at whatever replaced it.
     */
    @FunctionalInterface
    public interface CompletionHandOff {

        boolean handOff(@Nonnull String questId, @Nullable String npcId,
                @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef, @Nonnull Player player);
    }

    /** The toast a consumer floats when a quest settles here; null for the library's own line. */
    @FunctionalInterface
    public interface CompletionToast {

        @Nullable
        ToastSpec forCompleted(@Nonnull Quest quest);
    }

    /** Append and nothing else: the honest default for a server shipping no theme. */
    public static final PageTheme PLAIN_THEME = (cmd, template, frameSelector) -> cmd.append(template);

    /** Nobody knows a name, so the page falls back to the character's own id. */
    public static final NpcNameSource NO_NAMES = npcId -> null;

    /** A character answers to its own id and nothing else. */
    public static final AnswerSetSource OWN_ID_ONLY = Set::of;

    /** No consumer opinion about any reward; every chip takes the generic reading. */
    public static final RewardChipSource GENERIC_CHIPS = spec -> null;

    /** Nothing follows a settled quest, so the page keeps the screen and refreshes itself. */
    public static final CompletionHandOff NO_HAND_OFF = (questId, npcId, store, ref, playerRef, player) -> false;

    /** No consumer toast, so the page floats its own line. */
    public static final CompletionToast NO_COMPLETION_TOAST = quest -> null;

    /** Everything at its library default: a page that works on a server running nothing else. */
    public static final NpcQuestPageDeps DEFAULTS = builder().build();

    @Nonnull private final PageTheme theme;
    @Nonnull private final NpcNameSource npcNames;
    @Nonnull private final AnswerSetSource answerSets;
    @Nonnull private final RewardChipSource rewardChips;
    @Nonnull private final CompletionHandOff completion;
    @Nonnull private final CompletionToast completionToast;

    private NpcQuestPageDeps(@Nonnull Builder builder) {
        this.theme = builder.theme;
        this.npcNames = builder.npcNames;
        this.answerSets = builder.answerSets;
        this.rewardChips = builder.rewardChips;
        this.completion = builder.completion;
        this.completionToast = builder.completionToast;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    @Nonnull
    public PageTheme theme() {
        return theme;
    }

    @Nonnull
    public NpcNameSource npcNames() {
        return npcNames;
    }

    @Nonnull
    public AnswerSetSource answerSets() {
        return answerSets;
    }

    @Nonnull
    public RewardChipSource rewardChips() {
        return rewardChips;
    }

    @Nonnull
    public CompletionHandOff completion() {
        return completion;
    }

    @Nonnull
    public CompletionToast completionToast() {
        return completionToast;
    }

    /**
     * Every id {@code npcId} answers to, guarded: a seam that throws or answers with nothing costs
     * the alias set rather than the page, and the character still answers to its own id.
     */
    @Nonnull
    public Set<String> answerSetOrOwn(@Nullable String npcId) {
        if (npcId == null || npcId.isBlank()) {
            return Set.of();
        }
        try {
            Set<String> answers = answerSets.answerSetOf(npcId);
            if (answers != null && !answers.isEmpty()) {
                return answers;
            }
        } catch (Throwable ignored) {
            // A consumer's identity layer failing costs the aliases, never the screen.
        }
        return Set.of(npcId);
    }

    /** What {@code npcId} is called, guarded; null when nothing knows and the id itself is shown. */
    @Nullable
    public Message nameOrNull(@Nullable String npcId) {
        if (npcId == null || npcId.isBlank()) {
            return null;
        }
        try {
            return npcNames.nameFor(npcId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Immutable-by-copy assembly; every knob defaults to the library's own answer. */
    public static final class Builder {

        @Nonnull private PageTheme theme = PLAIN_THEME;
        @Nonnull private NpcNameSource npcNames = NO_NAMES;
        @Nonnull private AnswerSetSource answerSets = OWN_ID_ONLY;
        @Nonnull private RewardChipSource rewardChips = GENERIC_CHIPS;
        @Nonnull private CompletionHandOff completion = NO_HAND_OFF;
        @Nonnull private CompletionToast completionToast = NO_COMPLETION_TOAST;

        private Builder() {
        }

        @Nonnull
        public Builder theme(@Nullable PageTheme value) {
            this.theme = value != null ? value : PLAIN_THEME;
            return this;
        }

        @Nonnull
        public Builder npcNames(@Nullable NpcNameSource value) {
            this.npcNames = value != null ? value : NO_NAMES;
            return this;
        }

        @Nonnull
        public Builder answerSets(@Nullable AnswerSetSource value) {
            this.answerSets = value != null ? value : OWN_ID_ONLY;
            return this;
        }

        @Nonnull
        public Builder rewardChips(@Nullable RewardChipSource value) {
            this.rewardChips = value != null ? value : GENERIC_CHIPS;
            return this;
        }

        @Nonnull
        public Builder completion(@Nullable CompletionHandOff value) {
            this.completion = value != null ? value : NO_HAND_OFF;
            return this;
        }

        @Nonnull
        public Builder completionToast(@Nullable CompletionToast value) {
            this.completionToast = value != null ? value : NO_COMPLETION_TOAST;
            return this;
        }

        @Nonnull
        public NpcQuestPageDeps build() {
            return new NpcQuestPageDeps(this);
        }
    }
}
