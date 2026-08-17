package com.ziggfreed.common.achievement;

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
 * A RESOLVED achievement definition: the shape the engine runs, after any authoring layer has
 * finished inheriting and decoding. Immutable, built through {@link #builder}.
 *
 * <p><b>The criteria are an ORDERED LIST and the order is load-bearing.</b> Progress is stored per
 * criterion by its POSITION, so reordering the list moves every subject's progress onto a different
 * criterion. Adding to the END is safe; inserting, removing, or swapping is a data migration. That
 * is why they are a list rather than a map: a map has no stable order to key progress by, and the
 * hazard is better stated once, loudly, than hidden behind a friendlier-looking shape.
 *
 * <p><b>Two reward lists, because there are two moments.</b> {@link #autoRewards()} are paid the
 * instant the achievement is earned; {@link #claimRewards()} wait for the subject to collect them.
 * An achievement with no claim rewards therefore settles in one step - there is nothing to come back
 * for - which is what makes {@link AchievementStatus#CLAIMED} reachable without a second interaction.
 *
 * <p>A META achievement has no criteria of its own: it earns itself when every achievement in
 * {@link #metaChildren()} is earned.
 *
 * <p>The knobs are independent rather than bundled into achievement "types": hidden-until-earned,
 * whether the points count toward a total, and whether it is in circulation at all are three
 * separate switches, so a retired one-off (hidden, points that no longer count, still earnable by
 * whoever already qualified) needs no new type constant.
 */
public final class Achievement {

    private final String id;
    private final List<ObjectiveDef> criteria;
    private final List<String> metaChildren;
    private final List<RewardSpec> autoRewards;
    private final List<RewardSpec> claimRewards;
    private final List<String> tags;
    private final int points;
    private final BooleanSupplier available;
    private final boolean hidden;
    private final boolean countsTowardTotal;
    private final boolean serverFirst;
    @Nullable private final GateSpec requires;
    private final ContentText text;
    @Nullable private final String icon;

    private Achievement(@Nonnull Builder b) {
        this.id = b.id;
        this.criteria = List.copyOf(b.criteria);
        this.metaChildren = List.copyOf(b.metaChildren);
        this.autoRewards = List.copyOf(b.autoRewards);
        this.claimRewards = List.copyOf(b.claimRewards);
        this.tags = List.copyOf(b.tags);
        this.points = b.points;
        this.available = b.available;
        this.hidden = b.hidden;
        this.countsTowardTotal = b.countsTowardTotal;
        this.serverFirst = b.serverFirst;
        this.requires = b.requires;
        this.text = b.text;
        this.icon = b.icon;
    }

    /**
     * This achievement with the AUTHORING facts the engine does not run but a shared surface needs:
     * the requirement block one gate answers, the words one text source reads, whether only one
     * subject on the server may ever own it, and the item that illustrates it. Everything else
     * carries over untouched.
     *
     * <p>A copy rather than a setter, and it lives here beside the fields so a leaf added later
     * cannot be silently dropped by a copy written somewhere else.
     */
    @Nonnull
    public Achievement withAuthoring(@Nullable GateSpec requires, @Nullable ContentText text,
            boolean serverFirst, @Nullable String icon) {
        return builder(id)
                .criteria(criteria)
                .metaChildren(metaChildren)
                .autoRewards(autoRewards)
                .claimRewards(claimRewards)
                .tags(tags)
                .points(points)
                .available(available)
                .hidden(hidden)
                .countsTowardTotal(countsTowardTotal)
                .serverFirst(serverFirst)
                .requires(requires)
                .text(text)
                .icon(icon)
                .build();
    }

    @Nonnull
    public String id() {
        return id;
    }

    /** The criteria in authored order. THE ORDER IS THE PROGRESS KEY - see the class javadoc. */
    @Nonnull
    public List<ObjectiveDef> criteria() {
        return criteria;
    }

    /** The criterion at this position, or null when there is none. */
    @Nullable
    public ObjectiveDef criterion(int index) {
        return index < 0 || index >= criteria.size() ? null : criteria.get(index);
    }

    /**
     * The POSITION of the criterion with this id, or {@code -1}. Position is what progress is keyed
     * by; an id is a label for reading, and the first match wins if content repeats one.
     */
    public int indexOf(@Nullable String criterionId) {
        if (criterionId == null) {
            return -1;
        }
        for (int i = 0; i < criteria.size(); i++) {
            if (criteria.get(i).id().equals(criterionId)) {
                return i;
            }
        }
        return -1;
    }

    /** Achievement ids that must all be earned for this one to earn itself. Empty for an ordinary one. */
    @Nonnull
    public List<String> metaChildren() {
        return metaChildren;
    }

    /** Does this achievement earn itself off other achievements rather than off criteria? */
    public boolean isMeta() {
        return !metaChildren.isEmpty();
    }

    /** Paid the instant it is earned. */
    @Nonnull
    public List<RewardSpec> autoRewards() {
        return autoRewards;
    }

    /** Paid when the subject collects; an empty list means there is nothing to come back for. */
    @Nonnull
    public List<RewardSpec> claimRewards() {
        return claimRewards;
    }

    /** True when something is left to collect after it is earned. */
    public boolean requiresClaim() {
        return !claimRewards.isEmpty();
    }

    /** How much this is worth toward a points total. */
    public int points() {
        return points;
    }

    /**
     * A switch an owner can flip to take it out of circulation without deleting it, READ LIVE every
     * time the engine asks - a feature toggle moves it while the server is up, and the catalogue is
     * only rebuilt when content reloads.
     */
    public boolean available() {
        return available.getAsBoolean();
    }

    /**
     * Only ONE subject on the server may ever own this. The engine arbitrates it at the moment of
     * earning, and a subject that loses keeps its criteria met, so the decision can be revisited
     * without anything being lost.
     */
    public boolean serverFirst() {
        return serverFirst;
    }

    /**
     * What must be true of a subject before progress counts, or null when nothing is asked. Every
     * fold puts the authored {@code Requires} block here, so ONE gate answers for content authored
     * in any format.
     */
    @Nullable
    public GateSpec requires() {
        return requires;
    }

    /** What this is CALLED, for a surface with no catalogue of its own. Never null. */
    @Nonnull
    public ContentText text() {
        return text;
    }

    /**
     * The item id that illustrates this, or null when it carries none.
     *
     * <p>Written by whoever FOLDED the catalogue, which is the layer that knows the whole authoring
     * ladder a picture comes out of - an authored leaf, a picture shared by a whole ladder of
     * related entries, one derived from what the entry is about. The engine never resolves one, so
     * a surface that paints it is painting a decision already taken rather than re-taking it per
     * moment.
     */
    @Nullable
    public String icon() {
        return icon;
    }

    /** Keep it off open listings until it is earned, for a surprise or a retired one-off. */
    public boolean hidden() {
        return hidden;
    }

    /**
     * Whether {@link #points()} counts toward a subject's total. Independent of {@link #hidden()} on
     * purpose: a retired one-off can stay visible to whoever earned it while no longer counting, and
     * a surprise can be hidden while still counting.
     */
    public boolean countsTowardTotal() {
        return countsTowardTotal;
    }

    /** Free classification carried onto the outbound events; the engine never reads their meaning. */
    @Nonnull
    public List<String> tags() {
        return tags;
    }

    /** Does this carry {@code tag} (case-insensitive)? A convenience for a consumer's policy. */
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

    @Override
    public String toString() {
        return "Achievement[" + id + " x" + criteria.size() + "]";
    }

    /** Switched on, for the ordinary achievement whose availability never moves. */
    private static final BooleanSupplier ALWAYS = () -> true;

    /** Switched off outright. */
    private static final BooleanSupplier NEVER = () -> false;

    @Nonnull
    public static Builder builder(@Nonnull String id) {
        return new Builder(id);
    }

    /** Assembles an {@link Achievement}; only the id is required, everything else has a sane default. */
    public static final class Builder {

        private final String id;
        private final List<ObjectiveDef> criteria = new ArrayList<>();
        private final List<String> metaChildren = new ArrayList<>();
        private final List<RewardSpec> autoRewards = new ArrayList<>();
        private final List<RewardSpec> claimRewards = new ArrayList<>();
        private final List<String> tags = new ArrayList<>();
        private int points = 10;
        private BooleanSupplier available = ALWAYS;
        private boolean hidden;
        private boolean countsTowardTotal = true;
        private boolean serverFirst;
        @Nullable private GateSpec requires;
        private ContentText text = ContentText.EMPTY;
        @Nullable private String icon;

        private Builder(@Nonnull String id) {
            this.id = id;
        }

        /** Append a criterion. Its POSITION becomes its progress key, so append rather than insert. */
        @Nonnull
        public Builder criterion(@Nonnull ObjectiveDef criterion) {
            criteria.add(criterion);
            return this;
        }

        @Nonnull
        public Builder criteria(@Nonnull List<ObjectiveDef> criteria) {
            this.criteria.addAll(criteria);
            return this;
        }

        @Nonnull
        public Builder metaChildren(@Nonnull List<String> metaChildren) {
            this.metaChildren.addAll(metaChildren);
            return this;
        }

        @Nonnull
        public Builder autoReward(@Nonnull RewardSpec reward) {
            autoRewards.add(reward);
            return this;
        }

        @Nonnull
        public Builder autoRewards(@Nonnull List<RewardSpec> rewards) {
            autoRewards.addAll(rewards);
            return this;
        }

        @Nonnull
        public Builder claimReward(@Nonnull RewardSpec reward) {
            claimRewards.add(reward);
            return this;
        }

        @Nonnull
        public Builder claimRewards(@Nonnull List<RewardSpec> rewards) {
            claimRewards.addAll(rewards);
            return this;
        }

        @Nonnull
        public Builder tags(@Nonnull List<String> tags) {
            this.tags.addAll(tags);
            return this;
        }

        @Nonnull
        public Builder points(int points) {
            this.points = Math.max(0, points);
            return this;
        }

        @Nonnull
        public Builder available(boolean available) {
            this.available = available ? ALWAYS : NEVER;
            return this;
        }

        /**
         * Availability as a LIVE predicate, re-asked every time the engine looks. The consumer
         * supplies the reading; the refusal built on it stays the engine's own.
         */
        @Nonnull
        public Builder available(@Nullable BooleanSupplier available) {
            this.available = available == null ? ALWAYS : available;
            return this;
        }

        /** Only one subject on the server may ever own this. */
        @Nonnull
        public Builder serverFirst(boolean serverFirst) {
            this.serverFirst = serverFirst;
            return this;
        }

        /** The authored requirement block; null (the default) leaves it open to everyone. */
        @Nonnull
        public Builder requires(@Nullable GateSpec requires) {
            this.requires = requires;
            return this;
        }

        /** The item that illustrates it; null (the default) means it carries no picture. */
        @Nonnull
        public Builder icon(@Nullable String icon) {
            this.icon = icon == null || icon.isBlank() ? null : icon.trim();
            return this;
        }

        /** What this is called; null resets to carrying no words at all. */
        @Nonnull
        public Builder text(@Nullable ContentText text) {
            this.text = text == null ? ContentText.EMPTY : text;
            return this;
        }

        @Nonnull
        public Builder hidden(boolean hidden) {
            this.hidden = hidden;
            return this;
        }

        @Nonnull
        public Builder countsTowardTotal(boolean countsTowardTotal) {
            this.countsTowardTotal = countsTowardTotal;
            return this;
        }

        @Nonnull
        public Achievement build() {
            return new Achievement(this);
        }
    }
}
