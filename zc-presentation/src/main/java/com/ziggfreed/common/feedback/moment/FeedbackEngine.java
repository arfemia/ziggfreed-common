package com.ziggfreed.common.feedback.moment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import com.ziggfreed.common.command.CommandRunner;
import com.ziggfreed.common.feedback.EventTitles;
import com.ziggfreed.common.feedback.Notify;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.ui.toast.RewardToastLines;
import com.ziggfreed.common.ui.toast.ToastLine;
import com.ziggfreed.common.ui.toast.ToastRenderer;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.sound.Sound3D;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * Does whatever a server AUTHORED for one lifecycle moment: reads the {@link FeedbackMomentAsset}
 * filed under that moment id and drives the notification, banner, sound and command primitives that
 * already exist beside it.
 *
 * <p><b>It knows nothing about what produced the moment.</b> A moment is a free string and a bag of
 * named values, so a quest engine, a shop, a minigame round or a mod that does not exist yet all
 * reach the same authoring surface; this class never learns one of their vocabularies. Wiring it to
 * a producer is somebody else's job - in this library the plugin root does it, which is the only
 * place that can see both ends.
 *
 * <p><b>Nothing here refuses loudly.</b> A moment nobody authored a file for does nothing at all; a
 * line naming a value the moment did not carry is skipped; a part that throws costs its own part and
 * not the rest. Feedback is the layer that must never be the reason something else did not happen.
 *
 * <p>World-thread: writes packets and runs a console command.
 */
public final class FeedbackEngine {

    /** Prefixed on log lines from the sound seam, so a missing jingle is traceable to a moment. */
    private static final String SOUND_CONTEXT = "MOMENT";

    /**
     * The one argument name a toast's picture is read from. Fixed rather than authored, so a
     * producer that has an item to illustrate its moment with offers it under this name and every
     * authored file gets the picture with nothing written for it.
     */
    static final String ICON_ARG = "icon";

    /**
     * The one argument name a toast's reward rows are read from: a producer whose moment pays (or
     * parks) rewards offers its list of reward specs under this name, and the engine draws one row
     * per readable reward under the headline of the in-page toast, through the same chip reading
     * every reward panel uses. Fixed rather than authored for the same reason as {@link #ICON_ARG}:
     * an author cannot mis-spell a value they cannot see. The corner feed outside a menu has no row
     * concept, so there the headline stands alone; the authored {@code Toast.Rows} group switches
     * the rows off or caps them.
     */
    static final String REWARDS_ARG = "rewards";

    /**
     * The name of whoever the moment is about, always available to a line, a variant and a
     * command placeholder. A producer that carries its own value under this name keeps it.
     */
    public static final String PLAYER_ARG = "player";

    /** A progress moment's position, read for the toast's {@code EveryPercent}. */
    public static final String CURRENT_ARG = "current";

    /** A progress moment's goal, read for the toast's {@code EveryPercent}. */
    public static final String REQUIRED_ARG = "required";

    /** A progress moment's "this tick finished the step" flag; a finish always shows. */
    public static final String FINISHED_ARG = "finished";

    /**
     * Added to the values a {@link FeedbackAudience} is asked with, when the authored toast set an
     * {@code EveryPercent} and the moment carries {@link #CURRENT_ARG} and {@link #REQUIRED_ARG}:
     * {@code true} when this tick crossed one of those marks (or finished the step), {@code false}
     * for an ordinary tick between them. Never present otherwise, so a reader can tell "no mark was
     * authored" from "not a mark".
     */
    public static final String MILESTONE_ARG = "milestone";

    /**
     * The players a moment is ABOUT as a group, by uuid, under one fixed name: a fight's members, a
     * round's party. A producer with such a group offers it here and an authored banner's
     * {@code ToParticipants} reaches them wherever they stand; a moment that carries none is
     * unaffected. Read as a collection of uuids and never bound into a line.
     */
    public static final String PARTICIPANTS_ARG = "participants";

    /**
     * What the moment is about, as one id (a boss's encounter, a quest), under one fixed name: what
     * an authored banner's {@code MinSecondsBetween} is kept per, so one fight's repeated beats hold
     * back while another fight announces. Absent, the throttle is kept per moment alone.
     */
    public static final String SOURCE_ARG = "source";

    /** The one table of banners being held back, across every moment and every world. */
    private static final BroadcastScope.Throttle THROTTLE = new BroadcastScope.Throttle();

    private FeedbackEngine() {
    }

    /**
     * Is there an authored file for {@code momentId} at all? A producer asks before it goes to the
     * trouble of composing what the moment would carry: a moment nobody wrote a file for costs
     * nothing at all this way, which is what lets one be announced on a hot path.
     */
    public static boolean answers(@Nonnull String momentId) {
        try {
            return FeedbackMomentConfig.getInstance().resolve(momentId) != null;
        } catch (Throwable t) {
            // Unable to tell is not "no": a moment lost because a lookup hiccuped would be a toast
            // nobody could explain the absence of.
            return true;
        }
    }

    /**
     * Do whatever {@code momentId} is authored to do.
     *
     * @param subject who it happened to; the notification, the sound and the {@code player}
     *                placeholder all come from here
     * @param args    what the moment carried, keyed by name; a localized value is a
     *                {@link Message} and stays one, anything else is plain data
     */
    public static void fire(@Nonnull String momentId, @Nonnull Subject subject,
            @Nonnull Map<String, Object> args) {
        FeedbackMomentAsset moment = FeedbackMomentConfig.getInstance().resolve(momentId);
        if (moment == null) {
            return;
        }
        Map<String, Object> values = withPlayer(subject, args);
        FeedbackMomentAsset.Resolved resolved = moment.resolve(values);
        PlayerRef playerRef = subject.handleAs(PlayerRef.class);
        FeedbackMomentAsset.Toast toastSpec = resolved.toast();
        // Only a moment that actually draws a personal notification, to a subject there IS a screen
        // for, asks whether this player wanted one; the other three parts are not one player's
        // screen, and a subject with no screen is nobody to ask.
        if (toastSpec != null && playerRef != null) {
            toast(toastSpec, playerRef, values, wantsToast(subject, momentId, toastSpec, values));
        }
        broadcast(momentId, resolved.broadcast(), playerRef, values);
        sound(resolved.sound(), playerRef);
        command(resolved.command(), subject, values);
    }

    // ==================== the four parts ====================

    /**
     * The picture comes from the FIXED argument name {@link #ICON_ARG} rather than from anything
     * authored: a producer that has a picture for its moment puts one there, and a moment carrying
     * none simply draws a toast without one. Naming it in the file would only let an author
     * mis-spell a value they cannot see anyway.
     */
    private static void toast(@Nonnull FeedbackMomentAsset.Toast spec, @Nonnull PlayerRef playerRef,
            @Nonnull Map<String, Object> args, boolean wanted) {
        if (!wanted) {
            return;
        }
        try {
            Message title = line(spec.getTitle(), args);
            if (title == null) {
                return;
            }
            Message secondary = line(spec.getSecondary(), args);
            // A notice belongs where the player is looking. With a page open the HUD toast would be
            // behind it, so the same words are drawn INTO the page; with no page open it is the HUD
            // toast as before. That is also why a page needs no notice hook of its own: finishing a
            // quest mid-conversation shows its toast on the conversation without the page knowing a
            // quest exists.
            UUID viewer = playerRef.getUuid();
            if (viewer != null && ToastablePage.isShowing(viewer)) {
                ToastablePage.showOnActive(viewer, inPageToast(spec, args, title, secondary));
                return;
            }
            Notify.withIcon(playerRef, title, secondary, icon(args), spec.tone().feedStyle());
        } catch (Throwable t) {
            SafeLog.fine("moment toast failed: " + t.getMessage());
        }
    }

    /**
     * The banner, sent to each admitted player SEPARATELY as the same unresolved message, so every
     * one of them resolves it in their own language; sending finished text once would pin the
     * announcement to whichever language the server happened to be in.
     *
     * <p>Who is admitted is the authored group's business ({@link BroadcastScope#admits}): with
     * nothing authored, everybody online. The subject's own world and position anchor a
     * world-scoped or radius-scoped banner; a subject that is not a live player anchors nothing,
     * so such a banner reaches only the moment's participants. The throttle is asked once the
     * title has been built, so a banner that would have shown nothing spends no window.
     */
    private static void broadcast(@Nonnull String momentId, @Nullable FeedbackMomentAsset.Broadcast spec,
            @Nullable PlayerRef subjectRef, @Nonnull Map<String, Object> args) {
        if (spec == null) {
            return;
        }
        try {
            Message title = line(spec.getTitle(), args);
            if (title == null) {
                return;
            }
            Anchor anchor = Anchor.of(subjectRef);
            if (!THROTTLE.allow(throttleKey(momentId, spec, anchor, args), spec.minSecondsBetween(),
                    System.currentTimeMillis())) {
                return;
            }
            Message secondary = line(spec.getSecondary(), args);
            Message body = secondary != null ? secondary : Msg.raw("");
            Collection<?> participants = participants(args);
            for (PlayerRef viewer : Universe.get().getPlayers()) {
                if (viewer == null) {
                    continue;
                }
                boolean participant = spec.toParticipants() && participants.contains(viewer.getUuid());
                if (!spec.isScoped()
                        || BroadcastScope.admits(spec, participant, anchor.sameWorld(viewer), anchor.distanceTo(viewer))) {
                    EventTitles.show(viewer, title, body, spec.isMajor());
                }
            }
        } catch (Throwable t) {
            SafeLog.fine("moment broadcast failed: " + t.getMessage());
        }
    }

    /**
     * What one banner is throttled under: the moment, what it is about ({@link #SOURCE_ARG}, so two
     * fights announce separately), and the world when the banner is world-scoped (so two worlds
     * each hear their own).
     */
    @Nonnull
    static String throttleKey(@Nonnull String momentId, @Nonnull FeedbackMomentAsset.Broadcast spec,
            @Nonnull Anchor anchor, @Nonnull Map<String, Object> args) {
        String source = text(args.get(SOURCE_ARG));
        return momentId + '|' + (source == null ? "" : source) + '|'
                + (spec.sameWorldOnly() ? anchor.worldName() : "*");
    }

    /** The participant ids under {@link #PARTICIPANTS_ARG}, or empty when the moment lists none. */
    @Nonnull
    private static Collection<?> participants(@Nonnull Map<String, Object> args) {
        return args.get(PARTICIPANTS_ARG) instanceof Collection<?> listed ? listed : List.of();
    }

    /**
     * Where the moment happened, as far as a banner needs to know: the subject's store (their world)
     * and their position, both absent for a subject that is not a live player.
     */
    static final class Anchor {

        @Nullable private final Store<EntityStore> store;
        @Nullable private final Vector3d position;
        @Nonnull private final String worldName;

        private Anchor(@Nullable Store<EntityStore> store, @Nullable Vector3d position, @Nonnull String worldName) {
            this.store = store;
            this.position = position;
            this.worldName = worldName;
        }

        @Nonnull
        static Anchor of(@Nullable PlayerRef subjectRef) {
            Ref<EntityStore> ref = subjectRef == null ? null : subjectRef.getReference();
            if (ref == null || !ref.isValid()) {
                return new Anchor(null, null, "");
            }
            Store<EntityStore> store = ref.getStore();
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            String world;
            try {
                world = store.getExternalData().getWorld().getName();
            } catch (Throwable t) {
                world = "";
            }
            return new Anchor(store, transform == null ? null : new Vector3d(transform.getPosition()), world);
        }

        @Nonnull
        String worldName() {
            return worldName;
        }

        /** Whether the viewer stands in the subject's world: the same store, on this same thread. */
        boolean sameWorld(@Nonnull PlayerRef viewer) {
            Ref<EntityStore> ref = viewer.getReference();
            return store != null && ref != null && ref.isValid() && ref.getStore() == store;
        }

        /** The viewer's distance from the subject in blocks, or {@code NaN} when it cannot be read. */
        double distanceTo(@Nonnull PlayerRef viewer) {
            if (position == null || !sameWorld(viewer)) {
                return Double.NaN;
            }
            Ref<EntityStore> ref = viewer.getReference();
            TransformComponent transform = ref == null ? null
                    : store.getComponent(ref, TransformComponent.getComponentType());
            return transform == null ? Double.NaN : transform.getPosition().distance(position);
        }
    }

    private static void sound(@Nullable FeedbackMomentAsset.Sound spec, @Nullable PlayerRef playerRef) {
        if (spec == null || playerRef == null || spec.getId() == null) {
            return;
        }
        try {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null) {
                return;
            }
            Sound3D.playAt(spec.getId(), Sound3D.DEFAULT_CATEGORY, ref, ref.getStore(),
                    SOUND_CONTEXT, false);
        } catch (Throwable t) {
            SafeLog.fine("moment sound failed: " + t.getMessage());
        }
    }

    private static void command(@Nullable FeedbackMomentAsset.Command spec, @Nonnull Subject subject,
            @Nonnull Map<String, Object> args) {
        if (spec == null || spec.getLine() == null) {
            return;
        }
        try {
            Map<String, String> placeholders = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                String value = text(entry.getValue());
                if (value != null) {
                    placeholders.put(entry.getKey(), value);
                }
            }
            CommandRunner.run(spec.getLine(), placeholders, SafeLog::warn);
        } catch (Throwable t) {
            SafeLog.fine("moment command failed: " + t.getMessage());
        }
    }

    // ==================== internals ====================

    /**
     * The moment's values with {@link #PLAYER_ARG} answering for the subject's name, so a line, a
     * variant and a command can all reach it. A producer that carried its own {@code player} keeps
     * it: the subject's name is the fallback, never an override.
     */
    @Nonnull
    static Map<String, Object> withPlayer(@Nonnull Subject subject, @Nonnull Map<String, Object> args) {
        if (args.containsKey(PLAYER_ARG)) {
            return args;
        }
        Map<String, Object> values = new LinkedHashMap<>(args.size() + 1);
        values.put(PLAYER_ARG, subject.name());
        values.putAll(args);
        return values;
    }

    /**
     * The toast as drawn INTO an open page. The authored {@code Tone} drives the panel frame, the
     * headline colour and the feed style in one word, so the frame and the words can never
     * disagree; it is built {@code silent()} because the moment's own {@code Sound} group owns the
     * audio and a toned toast must not add a second chime. The moment's rewards, when it carries
     * any under {@link #REWARDS_ARG}, paint one row each under the headline.
     */
    @Nonnull
    static ToastSpec inPageToast(@Nonnull FeedbackMomentAsset.Toast spec,
            @Nonnull Map<String, Object> args, @Nonnull Message title, @Nullable Message secondary) {
        return ToastSpec.of(spec.tone(), secondary != null ? secondary : title)
                .withTitle(secondary != null ? title : null)
                .withIcon(icon(args))
                .withLines(rewardRows(spec, args))
                .silent();
    }

    /**
     * The reward rows for one moment: whatever it carried under {@link #REWARDS_ARG}, read through
     * the shared chip bridge under the authored {@code Rows} knobs. Empty when the moment carries
     * none, when the authored group switched them off, or when nothing on this server could name
     * any of them.
     */
    @Nonnull
    static List<ToastLine> rewardRows(@Nonnull FeedbackMomentAsset.Toast spec,
            @Nonnull Map<String, Object> args) {
        FeedbackMomentAsset.Toast.Rows rows = spec.getRows();
        if (rows != null && !rows.showRows()) {
            return List.of();
        }
        List<RewardSpec> rewards = rewards(args);
        if (rewards.isEmpty()) {
            return List.of();
        }
        Integer max = rows == null ? null : rows.getMax();
        return RewardToastLines.lines(rewards, null,
                max == null ? ToastRenderer.MAX_LINES : max,
                dropped -> Msg.tr("ziggfreedcommon.feedback.", "rewards.more", dropped));
    }

    /**
     * The reward list under {@link #REWARDS_ARG}, or empty. Read leniently: the value is data a
     * producer composed, so anything in it that is not a reward spec is skipped rather than allowed
     * to cost the toast.
     */
    @Nonnull
    static List<RewardSpec> rewards(@Nonnull Map<String, Object> args) {
        if (!(args.get(REWARDS_ARG) instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<RewardSpec> out = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (entry instanceof RewardSpec reward) {
                out.add(reward);
            }
        }
        return out;
    }

    /**
     * Should this toast be drawn for this subject? Its own handle answers when it has an opinion
     * ({@link FeedbackAudience}), told everything the moment carries plus whether a progress tick
     * crossed the authored {@code EveryPercent} mark; a handle that offers none gets what was
     * authored - every tick when no mark was set, the marks and the finish when one was - and an
     * opinion that throws is not allowed to cost the moment.
     */
    static boolean wantsToast(@Nonnull Subject subject, @Nonnull String momentId,
            @Nonnull FeedbackMomentAsset.Toast spec, @Nonnull Map<String, Object> args) {
        Boolean crossed = crossedMark(spec.getEveryPercent(), args);
        try {
            FeedbackAudience audience = subject.handleAs(FeedbackAudience.class);
            if (audience == null) {
                return crossed == null || crossed;
            }
            Map<String, Object> asked = args;
            if (crossed != null) {
                asked = new LinkedHashMap<>(args);
                asked.put(MILESTONE_ARG, crossed);
            }
            return audience.wantsNotification(momentId, asked);
        } catch (Throwable t) {
            SafeLog.fine("moment audience check failed: " + t.getMessage());
            return true;
        }
    }

    /**
     * Did this progress tick cross a multiple of {@code everyPercent} of the way, or finish the
     * step? Null when there is nothing to ask: no mark authored, or a moment that does not report
     * progress at all.
     *
     * <p>A tick crosses a mark when the whole-mark count of {@code current} exceeds that of the
     * value just before it, so a jump over several marks still counts once and the last tick, at
     * one hundred percent, always does.
     */
    @Nullable
    static Boolean crossedMark(@Nullable Integer everyPercent, @Nonnull Map<String, Object> args) {
        if (everyPercent == null) {
            return null;
        }
        Long current = number(args.get(CURRENT_ARG));
        Long required = number(args.get(REQUIRED_ARG));
        if (current == null || required == null || required <= 0L) {
            return null;
        }
        if (Boolean.TRUE.equals(args.get(FINISHED_ARG))) {
            return true;
        }
        long percentNow = current * 100L / required;
        long percentBefore = (current - 1L) * 100L / required;
        return percentNow / everyPercent > percentBefore / everyPercent;
    }

    /**
     * One authored line as a client-resolved message, or null when it cannot be built.
     *
     * <p>The authored key carries no namespace - a moment file is written the way the content beside
     * it is - so the owning consumer's own catalogue lends it one before the client ever sees it.
     * A line whose key comes from one of the moment's own values ({@code KeyArg}) reads it the same
     * way.
     *
     * <p>Each named argument is bound TWICE, once under its own name and once under its position,
     * so a lang value written {@code {0}} and one written {@code {title}} both fill. An argument the
     * moment did not carry abandons the whole line: a sentence missing its subject reads worse than
     * no sentence.
     */
    @Nullable
    static Message line(@Nullable FeedbackMomentAsset.Line spec,
            @Nonnull Map<String, Object> args) {
        if (spec == null) {
            return null;
        }
        String key = spec.keyFor(args);
        if (key == null) {
            return null;
        }
        Map<String, Object> bound = new LinkedHashMap<>();
        String[] names = spec.getArgs();
        for (int i = 0; i < names.length; i++) {
            String name = names[i] == null ? "" : names[i].trim();
            Object value = args.get(name);
            if (value == null) {
                return null;
            }
            bound.put(Integer.toString(i), value);
            bound.put(name, value);
        }
        // The key is authored WITHOUT a namespace, the way every other authored key in this library
        // is written; the seam turns it into the id the owning consumer actually registered.
        Message message = Msg.keyNamed(ContentKeys.resolved(key), bound);
        String color = spec.getColor();
        return color == null ? message : Msg.color(message, color);
    }

    /**
     * The item id a toast is illustrated with, read from the ONE fixed argument name, or null when
     * this moment carries no picture. Fixed rather than authored so a file never names a value it
     * cannot see; a producer with a picture to offer puts it under {@link #ICON_ARG} and every
     * authored toast gets it with nothing written for it.
     */
    @Nullable
    static String icon(@Nonnull Map<String, Object> args) {
        return text(args.get(ICON_ARG));
    }

    /**
     * One argument as plain text, or null when it is not plain data. A localized value is
     * deliberately NOT flattened: an icon id and a command placeholder are data, a title is a
     * sentence somebody else's client has to render.
     */
    @Nullable
    private static String text(@Nullable Object value) {
        // A collection is the engine's own data (a reward list), not a word for a placeholder.
        if (value == null || value instanceof Message || value instanceof Collection<?>) {
            return null;
        }
        String raw = value.toString();
        return raw.isBlank() ? null : raw;
    }

    /** One argument as a whole number, or null when it is not one. */
    @Nullable
    private static Long number(@Nullable Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return null;
    }
}
