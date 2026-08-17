package com.ziggfreed.common.objectives.hud;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a consumer may say about {@link TrackedQuestHud} without owning it.
 *
 * <p>Every seam here has a DEFAULT that leaves the tracker fully working on a bare server: it sits
 * in the native objective HUD's own corner, in the native objective HUD's own colours, for every
 * player, always on. A consumer fills a seam to say something the library genuinely cannot know -
 * where its owner moved the panel, whether its owner switched the panel off, which of its players
 * hid the panel for themselves, what its theme paints over the native chrome - and nothing else.
 *
 * <ul>
 *   <li>{@link HudTheme} - a paint over the already-appended tracker document. The default paints
 *       nothing, which IS the native look. A theme retints through {@code ui/UiRetint} on the
 *       tracker's own selectors; it must never be pointed at a page-frame painter, whose selectors
 *       the tracker document does not carry (a missing selector disconnects the player).</li>
 *   <li>{@link HudAudience} - whether THIS player wants the tracker on screen at all, asked on
 *       every repaint. True when in doubt: the default shows it to everyone. A consumer with a
 *       per-player "hide this HUD" preference, or a per-world rule that hides every HUD, answers
 *       here off the subject it is handed.</li>
 *   <li>the position and the enabled flag - SUPPLIERS, asked at build and on every repaint, so a
 *       consumer with an owner layout file of its own answers straight off it and its existing
 *       admin surfaces keep working. Nothing is stored here.</li>
 *   <li>the four text colours - the native objective HUD's own by default; a theme restates them
 *       without the tracker hardcoding anybody's palette.</li>
 * </ul>
 *
 * <p>Immutable; build one at setup and hand the same instance back on every ask. Every reader is
 * guarded: a seam that throws costs the consumer's own contribution, never the tracker.
 */
public final class TrackedQuestHudDeps {

    /**
     * A paint over the tracker document, called once per {@code build()} right after the document
     * is appended and positioned. {@code rootSelector} is the panel every other id lives under.
     */
    @FunctionalInterface
    public interface HudTheme {

        void paint(@Nonnull UICommandBuilder cmd, @Nonnull String rootSelector);
    }

    /** Does this subject want the tracker on their screen right now? True when in doubt. */
    @FunctionalInterface
    public interface HudAudience {

        boolean wantsHud(@Nonnull Subject subject);
    }

    /** Paint nothing: the native objective-HUD look, which is what a server with no theme wants. */
    public static final HudTheme NATIVE_LOOK = (cmd, rootSelector) -> { };

    /** Everybody sees it. */
    public static final HudAudience EVERYONE = subject -> true;

    /**
     * The native objective HUD's corner: anchored to the RIGHT screen edge (the offset is a margin
     * in pixels), so the panel hugs the corner at any resolution rather than floating off a centre
     * offset.
     */
    public static final HudPosition DEFAULT_POSITION = new HudPosition(HudPosition.AnchorEdge.TOP,
            HudPosition.HorizontalEdge.RIGHT, 24, 120);

    // The native objective HUD's own task colours (client Data ObjectiveCommon.ui): gold in-progress
    // task text, dark grey when complete; the count is grey in progress, dark grey when complete.
    public static final String NATIVE_TASK_IN_PROGRESS = "#ca9f37";
    public static final String NATIVE_TASK_COMPLETE = "#6b6b6b";
    public static final String NATIVE_COUNT_IN_PROGRESS = "#b7b8b9";
    public static final String NATIVE_COUNT_COMPLETE = "#6b6b6b";

    /** Everything at its library default: a tracker that works on a server running nothing else. */
    public static final TrackedQuestHudDeps DEFAULTS = builder().build();

    @Nonnull private final HudTheme theme;
    @Nonnull private final HudAudience audience;
    @Nonnull private final Supplier<HudPosition> position;
    @Nonnull private final BooleanSupplier enabled;
    @Nonnull private final String taskColorInProgress;
    @Nonnull private final String taskColorComplete;
    @Nonnull private final String countColorInProgress;
    @Nonnull private final String countColorComplete;

    private TrackedQuestHudDeps(@Nonnull Builder b) {
        this.theme = b.theme;
        this.audience = b.audience;
        this.position = b.position;
        this.enabled = b.enabled;
        this.taskColorInProgress = b.taskColorInProgress;
        this.taskColorComplete = b.taskColorComplete;
        this.countColorInProgress = b.countColorInProgress;
        this.countColorComplete = b.countColorComplete;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    @Nonnull
    public HudTheme theme() {
        return theme;
    }

    @Nonnull
    public HudAudience audience() {
        return audience;
    }

    // ==================== guarded readers ====================

    /** Where the panel sits, guarded: a supplier that throws or answers null costs the layout, not the tracker. */
    @Nonnull
    public HudPosition position() {
        try {
            HudPosition answer = position.get();
            return answer != null ? answer : DEFAULT_POSITION;
        } catch (Throwable t) {
            warn("position", t);
            return DEFAULT_POSITION;
        }
    }

    /** Whether the owner has the tracker switched on, guarded: in doubt, it is on. */
    public boolean isEnabled() {
        try {
            return enabled.getAsBoolean();
        } catch (Throwable t) {
            warn("enabled flag", t);
            return true;
        }
    }

    /** Whether {@code subject} wants the tracker, guarded: in doubt, they do. */
    public boolean wantsHud(@Nonnull Subject subject) {
        try {
            return audience.wantsHud(subject);
        } catch (Throwable t) {
            warn("audience", t);
            return true;
        }
    }

    /** Let the theme paint, guarded: a theme that throws costs the theme, never the tracker. */
    public void paintTheme(@Nonnull UICommandBuilder cmd, @Nonnull String rootSelector) {
        try {
            theme.paint(cmd, rootSelector);
        } catch (Throwable t) {
            warn("theme", t);
        }
    }

    /** The task text colour for a row in this state. */
    @Nonnull
    public String taskColor(boolean complete) {
        return complete ? taskColorComplete : taskColorInProgress;
    }

    /** The count label colour for a row in this state. */
    @Nonnull
    public String countColor(boolean complete) {
        return complete ? countColorComplete : countColorInProgress;
    }

    private static void warn(@Nonnull String seam, @Nonnull Throwable t) {
        SafeLog.warn("[progression] the tracked-quest HUD's " + seam + " seam failed: " + t.getMessage());
    }

    /** Immutable-by-copy assembly; every knob defaults to the library's own answer. */
    public static final class Builder {

        @Nonnull private HudTheme theme = NATIVE_LOOK;
        @Nonnull private HudAudience audience = EVERYONE;
        @Nonnull private Supplier<HudPosition> position = () -> DEFAULT_POSITION;
        @Nonnull private BooleanSupplier enabled = () -> true;
        @Nonnull private String taskColorInProgress = NATIVE_TASK_IN_PROGRESS;
        @Nonnull private String taskColorComplete = NATIVE_TASK_COMPLETE;
        @Nonnull private String countColorInProgress = NATIVE_COUNT_IN_PROGRESS;
        @Nonnull private String countColorComplete = NATIVE_COUNT_COMPLETE;

        private Builder() {
        }

        @Nonnull
        public Builder theme(@Nullable HudTheme value) {
            this.theme = value != null ? value : NATIVE_LOOK;
            return this;
        }

        @Nonnull
        public Builder audience(@Nullable HudAudience value) {
            this.audience = value != null ? value : EVERYONE;
            return this;
        }

        /** Where the panel sits, asked at build and on every reposition; null restores the native corner. */
        @Nonnull
        public Builder position(@Nullable Supplier<HudPosition> value) {
            this.position = value != null ? value : () -> DEFAULT_POSITION;
            return this;
        }

        /** Whether the owner has the tracker on, asked on every repaint; null restores always-on. */
        @Nonnull
        public Builder enabled(@Nullable BooleanSupplier value) {
            this.enabled = value != null ? value : () -> true;
            return this;
        }

        @Nonnull
        public Builder taskColorInProgress(@Nullable String hex) {
            this.taskColorInProgress = hex != null ? hex : NATIVE_TASK_IN_PROGRESS;
            return this;
        }

        @Nonnull
        public Builder taskColorComplete(@Nullable String hex) {
            this.taskColorComplete = hex != null ? hex : NATIVE_TASK_COMPLETE;
            return this;
        }

        @Nonnull
        public Builder countColorInProgress(@Nullable String hex) {
            this.countColorInProgress = hex != null ? hex : NATIVE_COUNT_IN_PROGRESS;
            return this;
        }

        @Nonnull
        public Builder countColorComplete(@Nullable String hex) {
            this.countColorComplete = hex != null ? hex : NATIVE_COUNT_COMPLETE;
            return this;
        }

        @Nonnull
        public TrackedQuestHudDeps build() {
            return new TrackedQuestHudDeps(this);
        }
    }
}
