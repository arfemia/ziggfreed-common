package com.ziggfreed.common.quest.asset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * Whether a quest situation at a character SHOWS, and how: the {@code Indicator} block. One codec
 * is the schema for all three places it is written, and the three merge per leaf, narrowest
 * winning:
 * <ol>
 * <li>the server's global word, {@code Server/ZiggfreedCommon/QuestIndicators/Default.json} plus
 * the owner file over it;</li>
 * <li>a quest's own {@code Indicator} block;</li>
 * <li>a step's own {@code Indicator} block, for the situation that step raises (a hand-in step's
 * {@code TurnIn}, a carried step's {@code InProgress}; a quest's {@code Collect} and
 * {@code Available} are the quest's alone).</li>
 * </ol>
 *
 * <pre>{@code
 * "Indicator": {
 *   "Enabled": true,
 *   "Available": { "State": "Quest_Available", "Overhead": { "Enabled": true },
 *                  "Map": { "Enabled": true, "Icon": "Coordinate.png" } },
 *   "InProgress": { "Overhead": { "Enabled": false } }
 * }
 * }</pre>
 *
 * <p>Every leaf is nullable and every group is optional, so a file states one fact and leaves the
 * rest to the layer below it; {@link #merge} is the per-leaf overlay and {@link #resolve} the
 * reading with the library's own defaults applied last. Orthogonal knobs throughout: a situation's
 * own switch, which overhead state it shows, whether the overhead shows at all, whether the map
 * marks it and with what icon, and one switch over the whole block.
 */
public class QuestIndicatorSpec {

    /** The overhead half of one situation: whether a marker floats over the character. */
    public static final class Overhead {

        @Nullable protected Boolean enabled;

        public static final BuilderCodec<Overhead> CODEC = BuilderCodec.builder(Overhead.class, Overhead::new)
                .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                        (o, v) -> o.enabled = v, o -> o.enabled, (o, p) -> o.enabled = p.enabled)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether a marker floats over the character for this situation. "
                        + "Unauthored means yes.").add()
                .build();

        public Overhead() {
        }

        @Nonnull
        public static Overhead of(@Nullable Boolean enabled) {
            Overhead o = new Overhead();
            o.enabled = enabled;
            return o;
        }

        @Nullable
        public Boolean getEnabled() {
            return enabled;
        }

        @Nonnull
        static Overhead merge(@Nullable Overhead base, @Nullable Overhead over) {
            return of(pick(base == null ? null : base.enabled, over == null ? null : over.enabled));
        }
    }

    /** The map half of one situation: whether the character is marked on the map and compass. */
    public static final class MapMark {

        @Nullable protected Boolean enabled;
        @Nullable protected String icon;

        public static final BuilderCodec<MapMark> CODEC = BuilderCodec.builder(MapMark.class, MapMark::new)
                .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                        (m, v) -> m.enabled = v, m -> m.enabled, (m, p) -> m.enabled = p.enabled)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("Whether the character is marked on the world map and compass for this "
                        + "situation. Unauthored means no.").add()
                .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                        (m, v) -> m.icon = v, m -> m.icon, (m, p) -> m.icon = p.icon)
                .documentation("The map marker texture, e.g. \"Coordinate.png\". Unauthored takes whatever "
                        + "the marker service draws by default.").add()
                .build();

        public MapMark() {
        }

        @Nonnull
        public static MapMark of(@Nullable Boolean enabled, @Nullable String icon) {
            MapMark m = new MapMark();
            m.enabled = enabled;
            m.icon = icon;
            return m;
        }

        @Nullable
        public Boolean getEnabled() {
            return enabled;
        }

        @Nullable
        public String getIcon() {
            return icon;
        }

        @Nonnull
        static MapMark merge(@Nullable MapMark base, @Nullable MapMark over) {
            return of(pick(base == null ? null : base.enabled, over == null ? null : over.enabled),
                    pick(base == null ? null : base.icon, over == null ? null : over.icon));
        }
    }

    /** One situation's knobs: its own switch, the state it shows, and its two halves. */
    public static final class Situation {

        @Nullable protected Boolean enabled;
        @Nullable protected String state;
        @Nullable protected Overhead overhead;
        @Nullable protected MapMark map;

        public static final BuilderCodec<Situation> CODEC = BuilderCodec.builder(Situation.class, Situation::new)
                .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                        (s, v) -> s.enabled = v, s -> s.enabled, (s, p) -> s.enabled = p.enabled)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether this situation shows anything at all, overhead or map. "
                        + "Unauthored means yes.").add()
                .appendInherited(new KeyedCodec<>("State", Codec.STRING, false),
                        (s, v) -> s.state = v, s -> s.state, (s, p) -> s.state = p.state)
                .documentation("The overhead state to show, matching a look file at "
                        + "Server/ZiggfreedCommon/OverheadIndicators/<State>.json. Unauthored means the "
                        + "situation's own state (Quest_Reward_Ready, Quest_Ready_To_Turn_In, "
                        + "Quest_Available, Quest_In_Progress).").add()
                .appendInherited(new KeyedCodec<>("Overhead", Overhead.CODEC, false),
                        (s, v) -> s.overhead = v, s -> s.overhead, (s, p) -> s.overhead = p.overhead)
                .documentation("The marker over the character's head.").add()
                .appendInherited(new KeyedCodec<>("Map", MapMark.CODEC, false),
                        (s, v) -> s.map = v, s -> s.map, (s, p) -> s.map = p.map)
                .documentation("The marker on the world map and compass.").add()
                .build();

        public Situation() {
        }

        @Nonnull
        public static Situation of(@Nullable Boolean enabled, @Nullable String state, @Nullable Overhead overhead,
                @Nullable MapMark map) {
            Situation s = new Situation();
            s.enabled = enabled;
            s.state = state;
            s.overhead = overhead;
            s.map = map;
            return s;
        }

        @Nullable
        public Boolean getEnabled() {
            return enabled;
        }

        @Nullable
        public String getState() {
            return state;
        }

        @Nullable
        public Overhead getOverhead() {
            return overhead;
        }

        @Nullable
        public MapMark getMap() {
            return map;
        }

        @Nonnull
        static Situation merge(@Nullable Situation base, @Nullable Situation over) {
            if (base == null && over == null) {
                return new Situation();
            }
            return of(pick(base == null ? null : base.enabled, over == null ? null : over.enabled),
                    pick(base == null ? null : base.state, over == null ? null : over.state),
                    Overhead.merge(base == null ? null : base.overhead, over == null ? null : over.overhead),
                    MapMark.merge(base == null ? null : base.map, over == null ? null : over.map));
        }
    }

    /**
     * One situation READ with every default applied: what the indicator engine and the map source
     * act on.
     *
     * @param enabled  the situation's own switch and the block's, both on
     * @param state    the overhead state to show, never blank
     * @param overhead whether the overhead marker shows for it
     * @param map      whether the map marks it
     * @param mapIcon  the map marker texture, or null for the marker service's default
     */
    public record Resolved(boolean enabled, @Nonnull String state, boolean overhead, boolean map,
                           @Nullable String mapIcon) {

        /** Does a marker float over the character for this situation? */
        public boolean showsOverhead() {
            return enabled && overhead;
        }

        /** Is the character marked on the map for this situation? */
        public boolean showsMap() {
            return enabled && map;
        }
    }

    /** A block with nothing written in it: every read resolves to the library's own defaults. */
    public static final QuestIndicatorSpec EMPTY = new QuestIndicatorSpec();

    @Nullable protected Boolean enabled;
    @Nullable protected Situation collect;
    @Nullable protected Situation turnIn;
    @Nullable protected Situation available;
    @Nullable protected Situation inProgress;

    public static final BuilderCodec<QuestIndicatorSpec> CODEC =
            appendLeaves(BuilderCodec.builder(QuestIndicatorSpec.class, QuestIndicatorSpec::new)).build();

    /**
     * Register the block's leaves on {@code builder}. The global default asset appends the same
     * leaves onto its own asset codec through this call, so the three scopes cannot drift apart.
     */
    @Nonnull
    protected static <T extends QuestIndicatorSpec, S extends BuilderCodec.BuilderBase<T, S>> S appendLeaves(
            @Nonnull S builder) {
        return builder
                .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                        (q, v) -> q.enabled = v, q -> q.enabled, (q, p) -> q.enabled = p.enabled)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("One switch over the whole block: false shows nothing for any situation, "
                        + "whatever the groups below say. Unauthored means yes.").add()
                .appendInherited(new KeyedCodec<>(QuestSituation.COLLECT.key(), Situation.CODEC, false),
                        (q, v) -> q.collect = v, q -> q.collect, (q, p) -> q.collect = p.collect)
                .documentation("A finished quest whose reward is collected at this character.").add()
                .appendInherited(new KeyedCodec<>(QuestSituation.TURN_IN.key(), Situation.CODEC, false),
                        (q, v) -> q.turnIn = v, q -> q.turnIn, (q, p) -> q.turnIn = p.turnIn)
                .documentation("Handing over what the player carries, here, would finish the quest.").add()
                .appendInherited(new KeyedCodec<>(QuestSituation.AVAILABLE.key(), Situation.CODEC, false),
                        (q, v) -> q.available = v, q -> q.available, (q, p) -> q.available = p.available)
                .documentation("Not started, and the player could take it here right now.").add()
                .appendInherited(new KeyedCodec<>(QuestSituation.IN_PROGRESS.key(), Situation.CODEC, false),
                        (q, v) -> q.inProgress = v, q -> q.inProgress, (q, p) -> q.inProgress = p.inProgress)
                .documentation("Being carried, with this character part of the errand.").add();
    }

    public QuestIndicatorSpec() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static QuestIndicatorSpec of(@Nullable Boolean enabled, @Nullable Situation collect,
            @Nullable Situation turnIn, @Nullable Situation available, @Nullable Situation inProgress) {
        QuestIndicatorSpec q = new QuestIndicatorSpec();
        q.enabled = enabled;
        q.collect = collect;
        q.turnIn = turnIn;
        q.available = available;
        q.inProgress = inProgress;
        return q;
    }

    @Nullable
    public Boolean getEnabled() {
        return enabled;
    }

    /** The authored group for {@code situation}, or null when the block says nothing about it. */
    @Nullable
    public Situation situation(@Nonnull QuestSituation situation) {
        return switch (situation) {
            case COLLECT -> collect;
            case TURN_IN -> turnIn;
            case AVAILABLE -> available;
            case IN_PROGRESS -> inProgress;
        };
    }

    /** True when nothing at all is written here. */
    public boolean isBlank() {
        return enabled == null && collect == null && turnIn == null && available == null && inProgress == null;
    }

    /**
     * {@code over} laid over {@code base}, leaf by leaf: every leaf {@code over} wrote wins, every
     * leaf it left alone keeps {@code base}'s. Either may be null, which reads as nothing written.
     */
    @Nonnull
    public static QuestIndicatorSpec merge(@Nullable QuestIndicatorSpec base, @Nullable QuestIndicatorSpec over) {
        if (base == null && over == null) {
            return EMPTY;
        }
        if (over == null) {
            return base;
        }
        if (base == null) {
            return over;
        }
        return of(pick(base.enabled, over.enabled),
                mergeSituation(base.collect, over.collect),
                mergeSituation(base.turnIn, over.turnIn),
                mergeSituation(base.available, over.available),
                mergeSituation(base.inProgress, over.inProgress));
    }

    /** {@code situation} read off this block with the library's own defaults filled in last. */
    @Nonnull
    public Resolved resolve(@Nonnull QuestSituation situation) {
        Situation s = situation(situation);
        boolean on = (enabled == null || enabled) && (s == null || s.enabled == null || s.enabled);
        String state = s == null || s.state == null || s.state.isBlank() ? situation.defaultState() : s.state.trim();
        boolean overhead = s == null || s.overhead == null || s.overhead.enabled == null || s.overhead.enabled;
        boolean map = s != null && s.map != null && s.map.enabled != null && s.map.enabled;
        String icon = s == null || s.map == null || s.map.icon == null || s.map.icon.isBlank() ? null : s.map.icon.trim();
        return new Resolved(on, state, overhead, map, icon);
    }

    @Nullable
    private static Situation mergeSituation(@Nullable Situation base, @Nullable Situation over) {
        return base == null && over == null ? null : Situation.merge(base, over);
    }

    @Nullable
    private static <V> V pick(@Nullable V base, @Nullable V over) {
        return over != null ? over : base;
    }
}
