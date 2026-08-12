package com.ziggfreed.common.quest;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.ObjectiveDef;

/**
 * A RESOLVED quest definition: the shape the engine runs, after any authoring layer has finished
 * inheriting, templating, and decoding. Immutable, built through {@link #builder}.
 *
 * <p>The knobs are deliberately independent rather than bundled into quest "types" - a consumer
 * composes daily, one-shot, hand-in-at-a-board, and auto-started behaviour out of the same handful
 * of switches instead of asking for a new type constant:
 * <ul>
 *   <li>{@link Repeat} - repeatable at all, how long the wait is, and WHEN the clock starts.
 *   <li>{@link Visibility} - hidden until offered, and whether a consumer gate has to pass first.
 *   <li>{@link #sequential()} / per-objective {@link ObjectiveDef#order()} - the unlock order.
 *   <li>{@link #autoAccept()} / {@link #autoTrack()} / {@link #autoClaim()} - how much the player
 *   has to do by hand.
 *   <li>{@link #tags()} - free classification the engine never interprets; it only carries them
 *   onto the outbound events so a listener can group, count, or filter by its own vocabulary.
 * </ul>
 */
public final class Quest {

    /**
     * How (and how often) a quest comes back around.
     *
     * <p>{@code stampOnPark} is the subtle one. A repeatable quest's clock normally starts when the
     * player TAKES the reward. Set this and it starts the moment the objectives are met and the
     * quest parks for manual claim instead - which is what a quest belonging to a rotating,
     * period-based offer needs, so that walking back to collect after the offer rotates does not
     * burn a slot in the NEW period. It also makes the quest exempt from the off-cooldown reset in
     * {@link QuestEngine#selfHeal}, because in that arrangement the offer's own rotation owns the
     * quest's lifecycle rather than the cooldown does.
     */
    public record Repeat(boolean repeatable, long cooldownMs, boolean stampOnPark) {

        /** A one-shot quest. */
        public static final Repeat ONCE = new Repeat(false, 0L, false);

        /** A repeatable quest whose cooldown starts when the reward is taken. */
        @Nonnull
        public static Repeat every(long cooldownMs) {
            return new Repeat(true, Math.max(0L, cooldownMs), false);
        }

        /** A repeatable quest whose clock starts the moment the objectives are met. */
        @Nonnull
        public static Repeat everyStampedOnPark(long cooldownMs) {
            return new Repeat(true, Math.max(0L, cooldownMs), true);
        }
    }

    /**
     * Who gets to SEE a quest before accepting it. {@code hidden} keeps it off open listings
     * entirely (it is offered some other way); {@code requirePrerequisites} asks the consumer's
     * {@link QuestGates} whether the player has earned the sight of it. An already-started quest
     * ignores both - a player must always be able to see what they are in the middle of.
     */
    public record Visibility(boolean hidden, boolean requirePrerequisites) {

        /** Listed to everybody. */
        public static final Visibility OPEN = new Visibility(false, false);
    }

    private final String id;
    private final List<ObjectiveDef> objectives;
    private final List<RewardSpec> rewards;
    private final Repeat repeat;
    private final Visibility visibility;
    private final boolean sequential;
    private final boolean autoAccept;
    private final boolean autoTrack;
    private final boolean autoClaim;
    private final boolean available;
    private final List<String> tags;

    private Quest(@Nonnull Builder b) {
        this.id = b.id;
        this.objectives = List.copyOf(b.objectives);
        this.rewards = List.copyOf(b.rewards);
        this.repeat = b.repeat;
        this.visibility = b.visibility;
        this.sequential = b.sequential;
        this.autoAccept = b.autoAccept;
        this.autoTrack = b.autoTrack;
        this.autoClaim = b.autoClaim;
        this.available = b.available;
        this.tags = List.copyOf(b.tags);
    }

    @Nonnull
    public String id() {
        return id;
    }

    /** The objectives in authored order; that order is what {@link #sequential()} walks. */
    @Nonnull
    public List<ObjectiveDef> objectives() {
        return objectives;
    }

    /** What the player gets, interpreted by the registered handler for each spec's kind. */
    @Nonnull
    public List<RewardSpec> rewards() {
        return rewards;
    }

    @Nonnull
    public Repeat repeat() {
        return repeat;
    }

    @Nonnull
    public Visibility visibility() {
        return visibility;
    }

    /**
     * When no objective authors an {@link ObjectiveDef#order()}, this makes the objectives run
     * strictly one after another in authored order. Ignored the moment any order is authored.
     */
    public boolean sequential() {
        return sequential;
    }

    /** Accept itself as soon as the player is eligible, with no player action. */
    public boolean autoAccept() {
        return autoAccept;
    }

    /** Pin to the tracker on accept, capacity permitting (it never displaces a player's own pins). */
    public boolean autoTrack() {
        return autoTrack;
    }

    /**
     * Pay out the moment the objectives are met. Turn it OFF for a quest whose reward is collected
     * somewhere specific: the quest parks in {@link QuestStatus#COMPLETED_UNCLAIMED} and waits.
     */
    public boolean autoClaim() {
        return autoClaim;
    }

    /** A switch an owner can flip to take the quest out of circulation without deleting it. */
    public boolean available() {
        return available;
    }

    /** Free classification carried onto the outbound events; the engine never reads their meaning. */
    @Nonnull
    public List<String> tags() {
        return tags;
    }

    /** Does this quest carry {@code tag} (case-insensitive)? A convenience for a consumer's policy. */
    public boolean hasTag(@Nullable String tag) {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        for (String t : tags) {
            if (t.equalsIgnoreCase(tag)) {
                return true;
            }
        }
        return false;
    }

    /** The objective with this id, or null when the quest has none. */
    @Nullable
    public ObjectiveDef objective(@Nullable String objectiveId) {
        if (objectiveId == null) {
            return null;
        }
        for (ObjectiveDef obj : objectives) {
            if (obj.id().equals(objectiveId)) {
                return obj;
            }
        }
        return null;
    }

    /** True when at least one objective authors a non-zero order, i.e. ordering is in play. */
    public boolean hasOrderedObjectives() {
        for (ObjectiveDef obj : objectives) {
            if (obj.order() > 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "Quest[" + id + " x" + objectives.size() + "]";
    }

    @Nonnull
    public static Builder builder(@Nonnull String id) {
        return new Builder(id);
    }

    /** Assembles a {@link Quest}; only the id is required, everything else has a sane default. */
    public static final class Builder {

        private final String id;
        private final List<ObjectiveDef> objectives = new ArrayList<>();
        private final List<RewardSpec> rewards = new ArrayList<>();
        private final List<String> tags = new ArrayList<>();
        private Repeat repeat = Repeat.ONCE;
        private Visibility visibility = Visibility.OPEN;
        private boolean sequential;
        private boolean autoAccept;
        private boolean autoTrack;
        private boolean autoClaim = true;
        private boolean available = true;

        private Builder(@Nonnull String id) {
            this.id = id;
        }

        @Nonnull
        public Builder objective(@Nonnull ObjectiveDef objective) {
            objectives.add(objective);
            return this;
        }

        @Nonnull
        public Builder objectives(@Nonnull List<ObjectiveDef> objectives) {
            this.objectives.addAll(objectives);
            return this;
        }

        @Nonnull
        public Builder reward(@Nonnull RewardSpec reward) {
            rewards.add(reward);
            return this;
        }

        @Nonnull
        public Builder rewards(@Nonnull List<RewardSpec> rewards) {
            this.rewards.addAll(rewards);
            return this;
        }

        @Nonnull
        public Builder tag(@Nonnull String tag) {
            tags.add(tag);
            return this;
        }

        @Nonnull
        public Builder tags(@Nonnull List<String> tags) {
            this.tags.addAll(tags);
            return this;
        }

        @Nonnull
        public Builder repeat(@Nonnull Repeat repeat) {
            this.repeat = repeat;
            return this;
        }

        @Nonnull
        public Builder visibility(@Nonnull Visibility visibility) {
            this.visibility = visibility;
            return this;
        }

        @Nonnull
        public Builder sequential(boolean sequential) {
            this.sequential = sequential;
            return this;
        }

        @Nonnull
        public Builder autoAccept(boolean autoAccept) {
            this.autoAccept = autoAccept;
            return this;
        }

        @Nonnull
        public Builder autoTrack(boolean autoTrack) {
            this.autoTrack = autoTrack;
            return this;
        }

        @Nonnull
        public Builder autoClaim(boolean autoClaim) {
            this.autoClaim = autoClaim;
            return this;
        }

        @Nonnull
        public Builder available(boolean available) {
            this.available = available;
            return this;
        }

        @Nonnull
        public Quest build() {
            return new Quest(this);
        }
    }
}
