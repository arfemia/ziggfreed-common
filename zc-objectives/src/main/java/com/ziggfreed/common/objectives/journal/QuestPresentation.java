package com.ziggfreed.common.objectives.journal;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.npc.NpcNames;
import com.ziggfreed.common.objectives.book.ObjectiveBookDeps;
import com.ziggfreed.common.objectives.questlist.NpcQuestPageDeps;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.Pill;
import com.ziggfreed.common.ui.kit.Tone;

/**
 * What a consumer says about how a quest reads, as the {@link QuestReader} asks it: whether a board manages the
 * quest (and the pills and hint its page wears then), the requirements line, a tag's name, the claim pre-check, how
 * a reward reads, what a character is called and any extra page blocks. Every answer is guarded by the adapter that
 * builds it, so a consumer's seam that throws costs its own answer, never the page.
 *
 * <p>The book reads it from its {@link ObjectiveBookDeps} ({@link #of(ObjectiveBookDeps)}); the NPC quest page from
 * its {@link NpcQuestPageDeps} ({@link #of(NpcQuestPageDeps)}), or both at once ({@link #of(ObjectiveBookDeps,
 * NpcQuestPageDeps)}) so a quest reads at its giver exactly as it reads in the book.
 */
public interface QuestPresentation {

    /** Whether a board takes, hands in and collects this quest rather than the log. */
    boolean managed(@Nonnull Quest q);

    /** The pills a board-managed quest's row and page wear; empty for none. */
    @Nonnull
    List<Pill> pills(@Nonnull Quest q);

    /** The line a board-managed quest shows where Accept would be; null for none. */
    @Nullable
    Message acceptHint(@Nonnull Quest q);

    /** The consumer's one-line requirements reading for a quest not yet taken; null for none. */
    @Nullable
    Message requirementLine(@Nonnull Quest q);

    /** A tag's name. */
    @Nonnull
    Message tagLabel(@Nonnull String tag);

    /** The consumer's refusal of a Collect before the engine is asked (a full inventory); null lets it through. */
    @Nullable
    Message claimPreCheck(@Nonnull Quest q, @Nonnull Store<EntityStore> s, @Nonnull Ref<EntityStore> r,
            @Nonnull Player p);

    /** How one reward reads, ahead of the generic reading ({@link RewardChips#chipsFor}). */
    @Nonnull
    default RewardChips.Source rewardChips() {
        return RewardChips.GENERIC;
    }

    /** What a character is called; null when nothing knows. */
    @Nullable
    default Message npcName(@Nullable String npcId) {
        if (npcId == null || npcId.isBlank()) {
            return null;
        }
        try {
            return NpcNames.nameFor(npcId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Extra blocks a consumer adds to a quest's page, after Rewards; empty for none. */
    @Nonnull
    default List<DetailBlock> blocks(@Nonnull Quest q, @Nullable Subject subject) {
        return List.of();
    }

    /** No consumer at all: nothing is board-managed, tags read tidied, no pre-check, the generic reward reading. */
    @Nonnull
    static QuestPresentation defaults() {
        return of(ObjectiveBookDeps.DEFAULTS);
    }

    /** The book's reading, through its deps' guarded seams. */
    @Nonnull
    static QuestPresentation of(@Nonnull ObjectiveBookDeps deps) {
        return new BookPresentation(deps, deps.rewardChips(), null);
    }

    /**
     * The NPC quest page's reading on its own deps: its reward reading and character names, the library's answer
     * for everything the book's consumer fills (nothing board-managed, no requirements line, tags tidied).
     */
    @Nonnull
    static QuestPresentation of(@Nonnull NpcQuestPageDeps deps) {
        return of(ObjectiveBookDeps.DEFAULTS, deps);
    }

    /**
     * The NPC quest page's reading with the book's consumer seams behind it: board, requirements, tags, the claim
     * pre-check and extra blocks from {@code book}; the reward reading and character names from {@code npc}.
     */
    @Nonnull
    static QuestPresentation of(@Nonnull ObjectiveBookDeps book, @Nonnull NpcQuestPageDeps npc) {
        return new BookPresentation(book, npc.rewardChips(), npc);
    }

    /** A board's pill as the kit draws it: its words on its own fill. */
    @Nonnull
    static Pill pill(@Nonnull ObjectiveBookDeps.Pill pill) {
        return new Pill(pill.label(), Tone.NEUTRAL, pill.background());
    }

    /** {@link QuestPresentation} over an {@link ObjectiveBookDeps}, optionally naming characters by NPC deps. */
    final class BookPresentation implements QuestPresentation {

        @Nonnull private final ObjectiveBookDeps deps;
        @Nonnull private final RewardChips.Source chips;
        @Nullable private final NpcQuestPageDeps npc;

        BookPresentation(@Nonnull ObjectiveBookDeps deps, @Nullable RewardChips.Source chips,
                @Nullable NpcQuestPageDeps npc) {
            this.deps = deps;
            this.chips = chips != null ? chips : RewardChips.GENERIC;
            this.npc = npc;
        }

        @Override
        public boolean managed(@Nonnull Quest q) {
            return deps.managedGuarded(q);
        }

        @Nonnull
        @Override
        public List<Pill> pills(@Nonnull Quest q) {
            List<Pill> out = new ArrayList<>();
            for (ObjectiveBookDeps.Pill pill : deps.pillsGuarded(q)) {
                if (pill != null) {
                    out.add(pill(pill));
                }
            }
            return out;
        }

        @Nullable
        @Override
        public Message acceptHint(@Nonnull Quest q) {
            return deps.acceptHintGuarded(q);
        }

        @Nullable
        @Override
        public Message requirementLine(@Nonnull Quest q) {
            return deps.requirementLineGuarded(q);
        }

        @Nonnull
        @Override
        public Message tagLabel(@Nonnull String tag) {
            return deps.tagLabelGuarded(tag);
        }

        @Nullable
        @Override
        public Message claimPreCheck(@Nonnull Quest q, @Nonnull Store<EntityStore> s, @Nonnull Ref<EntityStore> r,
                @Nonnull Player p) {
            return deps.claimPreCheckGuarded(q, s, r, p);
        }

        @Nonnull
        @Override
        public RewardChips.Source rewardChips() {
            return chips;
        }

        @Nullable
        @Override
        public Message npcName(@Nullable String npcId) {
            if (npc != null) {
                Message name = npc.nameOrNull(npcId);
                if (name != null) {
                    return name;
                }
            }
            return QuestPresentation.super.npcName(npcId);
        }

        @Nonnull
        @Override
        public List<DetailBlock> blocks(@Nonnull Quest q, @Nullable Subject subject) {
            return deps.detailBlocksGuarded(q, subject);
        }
    }
}
