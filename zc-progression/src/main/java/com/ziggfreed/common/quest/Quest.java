package com.ziggfreed.common.quest;

import java.time.DayOfWeek;
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
 *   <li>{@link QuestTurnInSite} - where it may be collected, when anywhere will not do.
 *   <li>{@link #sequential()} / per-objective {@link ObjectiveDef#order()} - the unlock order.
 *   <li>{@link #autoAccept()} / {@link #autoTrack()} / {@link #autoClaim()} - how much the player
 *   has to do by hand.
 *   <li>{@link #tags()} - free classification the engine never interprets; it only carries them
 *   onto the outbound events so a listener can group, count, or filter by its own vocabulary.
 * </ul>
 */
public final class Quest {

    /**
     * How, and how often, a quest comes back around. Its PRESENCE is what makes a quest repeatable:
     * a quest with no {@code Repeat} at all is a one-shot, and there is deliberately no "repeatable"
     * boolean inside, because a flag saying false on an object that exists is the ambiguity this
     * shape removes.
     *
     * <p><b>Three independent constraints, ANDed, each with a neutral value.</b> A rolling
     * {@link #cooldownMs()} wait, a calendar {@link Reset} allowance, and a lifetime
     * {@link #maxCompletions()} cap: author one, two, or all three, and
     * {@link QuestLifecycle#repeatCheck} settles the lot. Every one of them neutral - the EMPTY
     * group - means the quest holds nothing back and is offerable again the moment it settles,
     * which is what an externally governed quest wants: whatever rotating offer hands it out owns
     * when it comes round.
     *
     * <p>{@link CooldownFrom} is an ANCHOR, not a mode: it bundles no switches and toggles nothing
     * else, it names the single instant one clock counts from. Nothing else changes with it.
     */
    public record Repeat(long cooldownMs,
                         @Nonnull CooldownFrom cooldownFrom,
                         @Nullable Reset reset,
                         int maxCompletions) {

        /** Where a rolling cooldown's clock starts. */
        public enum CooldownFrom {

            /** The instant the reward is TAKEN, which is the ordinary reading of "since last time". */
            CLAIM,

            /**
             * The instant the objectives were MET, whether or not the reward has been collected. What
             * a quest belonging to a rotating, period-based offer wants, so walking back to collect
             * late does not burn a slot in the next period.
             */
            COMPLETE
        }

        /**
         * The calendar window a completion consumes, independent of any rolling cooldown. Anchored to
         * the SERVER clock in UTC: a boundary that moved with an owner's timezone setting would move
         * every already-stamped completion with it.
         *
         * @param atMinutes minutes past the period boundary the window rolls over, wrapped into one
         *                  period; the escape hatch for a day that should start at 04:00
         * @param weekStart which day a weekly window starts on; ignored for a daily one
         * @param times     how many completions fit inside one window; at least 1
         */
        public record Reset(@Nonnull Period period, int atMinutes, @Nonnull DayOfWeek weekStart,
                            int times) {

            /** Which calendar window is counted. */
            public enum Period { DAILY, WEEKLY }

            public Reset {
                period = period == null ? Period.DAILY : period;
                weekStart = weekStart == null ? DayOfWeek.MONDAY : weekStart;
                times = Math.max(1, times);
                long lengthMinutes = period == Period.WEEKLY ? 7L * 24L * 60L : 24L * 60L;
                atMinutes = (int) Math.floorMod((long) atMinutes, lengthMinutes);
            }

            /** A window of this period with every other knob at its default. */
            @Nonnull
            public static Reset of(@Nonnull Period period) {
                return new Reset(period, 0, DayOfWeek.MONDAY, 1);
            }
        }

        public Repeat {
            cooldownMs = Math.max(0L, cooldownMs);
            cooldownFrom = cooldownFrom == null ? CooldownFrom.CLAIM : cooldownFrom;
            maxCompletions = Math.max(0, maxCompletions);
        }

        /**
         * Everything unconstrained: the quest itself holds nothing back, so whatever offers it decides
         * when it comes round again.
         */
        public static final Repeat EXTERNALLY_GOVERNED =
                new Repeat(0L, CooldownFrom.CLAIM, null, 0);

        /** A repeatable whose rolling wait starts when the reward is taken. */
        @Nonnull
        public static Repeat every(long cooldownMs) {
            return new Repeat(cooldownMs, CooldownFrom.CLAIM, null, 0);
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
    @Nullable private final Repeat repeat;
    private final Visibility visibility;
    @Nullable private final QuestTurnInSite turnInAt;
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
        this.turnInAt = b.turnInAt;
        this.sequential = b.sequential;
        this.autoAccept = b.autoAccept;
        this.autoTrack = b.autoTrack;
        this.autoClaim = b.autoClaim;
        this.available = b.available;
        this.tags = List.copyOf(b.tags);
    }

    /**
     * This quest with a different hand-in site and everything else as it is - the one-line stamp for
     * a consumer whose content declares its site by policy rather than per file (every quest of one
     * family collected where it was taken, say). Copying lives here, beside the fields, so a field
     * added later cannot be silently dropped by a copy written somewhere else.
     */
    @Nonnull
    public Quest withTurnInAt(@Nullable QuestTurnInSite site) {
        return builder(id)
                .objectives(objectives)
                .rewards(rewards)
                .tags(tags)
                .repeat(repeat)
                .visibility(visibility)
                .turnInAt(site)
                .sequential(sequential)
                .autoAccept(autoAccept)
                .autoTrack(autoTrack)
                .autoClaim(autoClaim)
                .available(available)
                .build();
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

    /** The repeat rules, or null for a one-shot. Presence is the flag; see {@link Repeat}. */
    @Nullable
    public Repeat repeat() {
        return repeat;
    }

    /** Does this quest come back around at all? The one-line form of {@code repeat() != null}. */
    public boolean repeatable() {
        return repeat != null;
    }

    @Nonnull
    public Visibility visibility() {
        return visibility;
    }

    /**
     * Where this quest may be completed and collected, or null - the default - for anywhere. Its
     * PRESENCE is the restriction, exactly as {@link #repeat()}'s presence is the repeatable flag;
     * see {@link QuestTurnInSite}. The engine enforces it inside the completion path itself, so no
     * surface has to remember to.
     */
    @Nullable
    public QuestTurnInSite turnInAt() {
        return turnInAt;
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
        @Nullable private Repeat repeat;
        private Visibility visibility = Visibility.OPEN;
        @Nullable private QuestTurnInSite turnInAt;
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

        /** The repeat rules; null (the default) leaves the quest a one-shot. */
        @Nonnull
        public Builder repeat(@Nullable Repeat repeat) {
            this.repeat = repeat;
            return this;
        }

        @Nonnull
        public Builder visibility(@Nonnull Visibility visibility) {
            this.visibility = visibility;
            return this;
        }

        /** Where it may be collected; null (the default) leaves it collectable anywhere. */
        @Nonnull
        public Builder turnInAt(@Nullable QuestTurnInSite turnInAt) {
            this.turnInAt = turnInAt;
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
