package com.ziggfreed.common.quest;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.ContentText;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.gate.GateSpec;

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
 *   <li>{@link #autoAccept()} / {@link #autoTrack()} - how much the player has to do by hand.
 *   Whether the finished quest waits to be COLLECTED is not a knob at all: a quest with anything
 *   in its {@link #claimRewards()} bucket parks for the player, one with only
 *   {@link #autoRewards()} (or nothing) settles on the spot.
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
    private final List<RewardSpec> autoRewards;
    private final List<RewardSpec> claimRewards;
    @Nullable private final Repeat repeat;
    private final Visibility visibility;
    @Nullable private final QuestTurnInSite turnInAt;
    private final boolean sequential;
    private final boolean autoAccept;
    private final boolean autoTrack;
    private final boolean occupiesLog;
    private final BooleanSupplier available;
    private final List<String> tags;
    @Nullable private final GateSpec requires;
    private final ContentText text;
    @Nullable private final String npcViewId;
    private final int listOrder;
    @Nullable private final String category;
    @Nullable private final String icon;

    private Quest(@Nonnull Builder b) {
        this.id = b.id;
        this.objectives = List.copyOf(b.objectives);
        this.autoRewards = List.copyOf(b.autoRewards);
        this.claimRewards = List.copyOf(b.claimRewards);
        this.repeat = b.repeat;
        this.visibility = b.visibility;
        this.turnInAt = b.turnInAt;
        this.sequential = b.sequential;
        this.autoAccept = b.autoAccept;
        this.autoTrack = b.autoTrack;
        this.occupiesLog = b.occupiesLog;
        this.available = b.available;
        this.tags = List.copyOf(b.tags);
        this.requires = b.requires;
        this.text = b.text;
        this.npcViewId = b.npcViewId;
        this.listOrder = b.listOrder;
        this.category = b.category;
        this.icon = b.icon;
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
                .autoRewards(autoRewards)
                .claimRewards(claimRewards)
                .tags(tags)
                .repeat(repeat)
                .visibility(visibility)
                .turnInAt(site)
                .sequential(sequential)
                .autoAccept(autoAccept)
                .autoTrack(autoTrack)
                .occupiesLog(occupiesLog)
                .available(available)
                .requires(requires)
                .text(text)
                .npcViewId(npcViewId)
                .listOrder(listOrder)
                .category(category)
                .icon(icon)
                .build();
    }

    /**
     * This quest with the AUTHORING facts an engine does not run but a shared surface needs: the
     * requirement block one gate answers, the words one text source reads, the character that
     * hands it out, and the item that illustrates it. Everything else carries over untouched.
     *
     * <p>It is a copy rather than a setter for the same reason {@link #withTurnInAt} is: a quest is
     * immutable, and keeping the copy here beside the fields means a leaf added later cannot be
     * silently dropped by a copy written somewhere else.
     */
    @Nonnull
    public Quest withAuthoring(@Nullable GateSpec requires, @Nullable ContentText text,
            @Nullable String npcViewId, int listOrder, @Nullable String category,
            @Nullable String icon) {
        return builder(id)
                .objectives(objectives)
                .autoRewards(autoRewards)
                .claimRewards(claimRewards)
                .tags(tags)
                .repeat(repeat)
                .visibility(visibility)
                .turnInAt(turnInAt)
                .sequential(sequential)
                .autoAccept(autoAccept)
                .autoTrack(autoTrack)
                .occupiesLog(occupiesLog)
                .available(available)
                .requires(requires)
                .text(text)
                .npcViewId(npcViewId)
                .listOrder(listOrder)
                .category(category)
                .icon(icon)
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

    /**
     * EVERYTHING the player gets, both buckets in authored order (auto first), interpreted by the
     * registered handler for each spec's kind. The view a listing or a grant of the whole payout
     * wants; the split is {@link #autoRewards()} / {@link #claimRewards()}.
     */
    @Nonnull
    public List<RewardSpec> rewards() {
        if (claimRewards.isEmpty()) {
            return autoRewards;
        }
        if (autoRewards.isEmpty()) {
            return claimRewards;
        }
        List<RewardSpec> combined = new ArrayList<>(autoRewards.size() + claimRewards.size());
        combined.addAll(autoRewards);
        combined.addAll(claimRewards);
        return List.copyOf(combined);
    }

    /** The rewards that land where the quest settles, needing nothing further from the player. */
    @Nonnull
    public List<RewardSpec> autoRewards() {
        return autoRewards;
    }

    /**
     * The rewards that wait to be COLLECTED. Authoring any is what makes a finished quest park in
     * {@link QuestStatus#COMPLETED_UNCLAIMED} for the player instead of settling on the spot.
     */
    @Nonnull
    public List<RewardSpec> claimRewards() {
        return claimRewards;
    }

    /**
     * True when something waits to be collected after the steps are done, which is what parks a
     * finished quest for the player. A quest with only {@link #autoRewards()} settles on the spot.
     */
    public boolean requiresClaim() {
        return !claimRewards.isEmpty();
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
     * Whether carrying this quest spends one of the player's quest-log slots, and so counts against
     * the cap a consumer registers with {@code maxActiveQuests}. True by default: an ordinary quest
     * is a quest log entry.
     *
     * <p>Turn it OFF for an errand a player picks up somewhere that keeps its own list - a board
     * contract above all. Those are read, taken and collected at that board, they are never listed
     * in a quest log, and a player working three of them would otherwise find three of their quest
     * slots gone with nothing on the log screen to explain it. It is an independent switch rather
     * than something inferred from {@link #turnInAt()}: an ordinary quest handed in at a specific
     * character is still a log entry.
     */
    public boolean occupiesLog() {
        return occupiesLog;
    }

    /**
     * A switch an owner can flip to take the quest out of circulation without deleting it, READ LIVE
     * every time the engine asks.
     *
     * <p>It is a predicate rather than a stored boolean because what answers it usually moves while
     * the server is up - a feature toggle, an owner file reloaded, a dependency that came back - and
     * the catalogue is only rebuilt when content reloads. A consumer supplies the predicate; the
     * decision to refuse an accept on it stays the engine's.
     */
    public boolean available() {
        return available.getAsBoolean();
    }

    /**
     * What must be true of a player before this quest may be taken or seen, or null when nothing is
     * asked. Every fold puts the authored {@code Requires} block here, so ONE gate answers for
     * content authored in any format rather than one gate per authoring layer.
     */
    @Nullable
    public GateSpec requires() {
        return requires;
    }

    /** What this quest is CALLED, for a surface with no catalogue of its own. Never null. */
    @Nonnull
    public ContentText text() {
        return text;
    }

    /**
     * The character that hands this quest out, or null when nothing does. It is the whole of what a
     * generic offer listing needs: which quests a place offers is an authoring-layer association,
     * and carrying it here is what lets the library answer "has this character anything for me"
     * without a per-mod provider.
     */
    @Nullable
    public String npcViewId() {
        return npcViewId;
    }

    /**
     * Where this quest reads among the others a listing shows, lower first; {@code 0} for content
     * that expressed no preference. It rides here because the one surface that needs it - a generic
     * listing of what a character is holding out - has nothing else to sort by, and a list whose
     * order changes between two mods' content is a list an author cannot arrange.
     */
    public int listOrder() {
        return listOrder;
    }

    /**
     * The listing category the quest was authored under ({@code Listing.Category}), lower-case by
     * the asset codec's own normalization, or null for content that expressed none. Pure display
     * grouping for a listing surface (the objective book's category filter); the engine never
     * reads it.
     */
    @Nullable
    public String category() {
        return category;
    }

    /**
     * The item id that illustrates this quest, or null when it carries none. Written by whoever
     * folded the catalogue (the shared schema's {@code Listing.Icon}); the engine never reads it -
     * it is what lets a shared surface paint a quest without a per-consumer definition lookup.
     */
    @Nullable
    public String icon() {
        return icon;
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

    /** Switched on, for the ordinary quest whose availability never moves. */
    private static final BooleanSupplier ALWAYS = () -> true;

    /** Switched off outright. */
    private static final BooleanSupplier NEVER = () -> false;

    @Nonnull
    public static Builder builder(@Nonnull String id) {
        return new Builder(id);
    }

    /** Assembles a {@link Quest}; only the id is required, everything else has a sane default. */
    public static final class Builder {

        private final String id;
        private final List<ObjectiveDef> objectives = new ArrayList<>();
        private final List<RewardSpec> autoRewards = new ArrayList<>();
        private final List<RewardSpec> claimRewards = new ArrayList<>();
        private final List<String> tags = new ArrayList<>();
        @Nullable private Repeat repeat;
        private Visibility visibility = Visibility.OPEN;
        @Nullable private QuestTurnInSite turnInAt;
        private boolean sequential;
        private boolean autoAccept;
        private boolean autoTrack;
        private boolean occupiesLog = true;
        private BooleanSupplier available = ALWAYS;
        @Nullable private GateSpec requires;
        private ContentText text = ContentText.EMPTY;
        @Nullable private String npcViewId;
        private int listOrder;
        @Nullable private String category;
        @Nullable private String icon;

        private Builder(@Nonnull String id) {
            this.id = id;
        }

        /** The listing category ({@link Quest#category()}); null (the default) means none. */
        @Nonnull
        public Builder category(@Nullable String category) {
            this.category = category == null || category.isBlank() ? null : category.trim();
            return this;
        }

        /** The item that illustrates it ({@link Quest#icon()}); null (the default) means none. */
        @Nonnull
        public Builder icon(@Nullable String icon) {
            this.icon = icon == null || icon.isBlank() ? null : icon.trim();
            return this;
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

        /**
         * Append a reward to the CLAIM bucket - the default home of a reward: it waits to be
         * collected, which is also what parks the finished quest for the player. Use
         * {@link #autoReward} for one that should land on the spot instead.
         */
        @Nonnull
        public Builder reward(@Nonnull RewardSpec reward) {
            claimRewards.add(reward);
            return this;
        }

        /** Every entry through {@link #reward}: the CLAIM bucket, the default home of a reward. */
        @Nonnull
        public Builder rewards(@Nonnull List<RewardSpec> rewards) {
            claimRewards.addAll(rewards);
            return this;
        }

        /** Append a reward that lands where the quest settles, with nothing further to collect. */
        @Nonnull
        public Builder autoReward(@Nonnull RewardSpec reward) {
            autoRewards.add(reward);
            return this;
        }

        /** Every entry through {@link #autoReward}. */
        @Nonnull
        public Builder autoRewards(@Nonnull List<RewardSpec> rewards) {
            autoRewards.addAll(rewards);
            return this;
        }

        /** Every entry through {@link #reward}, named for symmetry with {@link #autoRewards}. */
        @Nonnull
        public Builder claimRewards(@Nonnull List<RewardSpec> rewards) {
            claimRewards.addAll(rewards);
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

        /**
         * Whether carrying it spends a quest-log slot, so it counts against the registered cap.
         * Leave it true for an ordinary quest; turn it OFF for an errand kept on a list of its own.
         */
        @Nonnull
        public Builder occupiesLog(boolean occupiesLog) {
            this.occupiesLog = occupiesLog;
            return this;
        }

        @Nonnull
        public Builder available(boolean available) {
            this.available = available ? ALWAYS : NEVER;
            return this;
        }

        /**
         * Availability as a LIVE predicate, re-asked every time the engine looks. What a consumer
         * supplies here is data - "is this switched on right now" - and the refusal built on it
         * stays the engine's own.
         */
        @Nonnull
        public Builder available(@Nullable BooleanSupplier available) {
            this.available = available == null ? ALWAYS : available;
            return this;
        }

        /** The authored requirement block; null (the default) leaves the quest open to everyone. */
        @Nonnull
        public Builder requires(@Nullable GateSpec requires) {
            this.requires = requires;
            return this;
        }

        /** What this quest is called; null resets to carrying no words at all. */
        @Nonnull
        public Builder text(@Nullable ContentText text) {
            this.text = text == null ? ContentText.EMPTY : text;
            return this;
        }

        /** The character that hands this quest out; null (the default) leaves it unattached. */
        @Nonnull
        public Builder npcViewId(@Nullable String npcViewId) {
            this.npcViewId = npcViewId == null || npcViewId.isBlank() ? null : npcViewId.trim();
            return this;
        }

        /** Where this quest reads among the others in a listing, lower first. */
        @Nonnull
        public Builder listOrder(int listOrder) {
            this.listOrder = listOrder;
            return this;
        }

        @Nonnull
        public Quest build() {
            return new Quest(this);
        }
    }
}
