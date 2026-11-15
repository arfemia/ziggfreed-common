package com.ziggfreed.common.authoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * Every in-game editor this server has, by id, and the one way to open one: from an authoring tool
 * ({@link AuthoringTools#wizardTool}), from {@code /zigauthor open}, or from a consumer's own admin surface
 * ({@link #open}). A wizard is registered once at setup; the id is matched without regard to case.
 *
 * <p>Opening asks no permission: the surface that opens a wizard decides who may (a tool checks its
 * {@code ziggfreedcommon.author.<tool>} node and creative mode, the command its engine node, a consumer's page its
 * own admin gate). Saving does ask: the page's Save asks the saving player for the wizard's own author node,
 * {@code ziggfreedcommon.author.<wizard id, lower case>} ({@link AuthoringTools#permission}), before anything reaches
 * the server content pack, with no creative mode needed, so a wizard opened from any surface saves only for an
 * author. Registering a wizard registers that node into vanilla's server editor group, as a tool's is. Opening a
 * wizard drops the player's parked pick, so a wizard left waiting never takes the next tool click. World thread,
 * outside every system's tick.
 */
public final class AuthoringWizards {

    /** Makes one wizard for one open. Null opens nothing. */
    @FunctionalInterface
    public interface Factory {

        @Nullable
        AuthoringWizard create(@Nonnull AuthoringUse use);
    }

    private record Registered(@Nonnull String id, @Nonnull Factory factory) {
    }

    private static final Map<String, Registered> WIZARDS = new ConcurrentHashMap<>();

    private AuthoringWizards() {
    }

    /**
     * Register wizard {@code id} (one per id; a second registration replaces the first) and the author node its Save
     * asks for. Setup time.
     */
    public static void register(@Nonnull String id, @Nonnull Factory factory) {
        String trimmed = id.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a wizard id is blank");
        }
        Registered before = WIZARDS.put(fold(trimmed), new Registered(trimmed, factory));
        if (before != null) {
            SafeLog.info("[authoring] wizard " + trimmed + " was registered again; the newer one opens");
        }
        AuthoringTools.registerNode(trimmed);
    }

    /** Every registered wizard id, as registered, sorted. */
    @Nonnull
    public static List<String> ids() {
        TreeSet<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Registered r : WIZARDS.values()) {
            ids.add(r.id());
        }
        return new ArrayList<>(ids);
    }

    /** True when a wizard is registered under {@code id} (any case). */
    public static boolean known(@Nullable String id) {
        return id != null && WIZARDS.containsKey(fold(id.trim()));
    }

    /** Open wizard {@code id} for the player at {@code ref}, with nothing picked. False when it opened nothing. */
    public static boolean open(@Nonnull String id, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        AuthoringUse use = AuthoringUse.of(ref, store, WorldPick.NOTHING);
        return use != null && open(id, use);
    }

    /**
     * Open wizard {@code id} for {@code use}'s player, seeded from what their tool pointed at, dropping any wizard
     * they parked for a pick. False when no wizard has that id, its factory made none, or the page could not open
     * (each logged).
     */
    public static boolean open(@Nonnull String id, @Nonnull AuthoringUse use) {
        PendingPicks.forget(use.playerRef().getUuid());
        Registered registered = WIZARDS.get(fold(id.trim()));
        if (registered == null) {
            SafeLog.fine("[authoring] no wizard is registered as " + id);
            return false;
        }
        AuthoringWizard wizard;
        try {
            wizard = registered.factory().create(use);
        } catch (Throwable t) {
            SafeLog.warn("[authoring] wizard " + registered.id() + " could not be made", t);
            return false;
        }
        if (wizard == null) {
            return false;
        }
        WizardSession session = new WizardSession(registered.id(), wizard);
        try {
            wizard.begin(session, use);
        } catch (Throwable t) {
            SafeLog.warn("[authoring] wizard " + registered.id() + " could not seed itself, so it opens empty", t);
        }
        return show(session, use);
    }

    /** Open the page on {@code session} again (after a pick). */
    static boolean show(@Nonnull WizardSession session, @Nonnull AuthoringUse use) {
        try {
            use.player().getPageManager().openCustomPage(use.ref(), use.store(),
                    new AuthoringWizardPage(use.playerRef(), session));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[authoring] a wizard page could not open", t);
            return false;
        }
    }

    /**
     * When {@code use}'s player parked a wizard for a pick, fill it from {@code use.pick()} and open it again: true
     * when a parked wizard took this click (the tool opens nothing else).
     */
    static boolean resolvePending(@Nonnull AuthoringUse use) {
        PendingPicks.Pending pending = PendingPicks.take(use.playerRef().getUuid());
        if (pending == null) {
            return false;
        }
        WizardSession session = pending.session();
        session.notice(fill(session, pending.fieldId(), use.pick()));
        show(session, use);
        return true;
    }

    /** Fill {@code fieldId} from {@code pick}, answering the toast that says how it went. */
    @Nonnull
    static ToastSpec fill(@Nonnull WizardSession session, @Nonnull String fieldId, @Nonnull WorldPick pick) {
        if (pick.isEmpty()) {
            return ToastSpec.of(ToastKind.INFO, AuthoringText.line("pick.cancelled"));
        }
        WizardField field = session.field(fieldId);
        Function<WorldPick, String> reading = field == null ? null : field.fromWorld();
        Message label = field == null ? Msg.raw(fieldId) : AuthoringWizardPage.label(field.spec().labelKey());
        String value;
        try {
            value = reading == null ? null : reading.apply(pick);
        } catch (Throwable t) {
            value = null;
        }
        if (value == null || value.isBlank()) {
            return ToastSpec.of(ToastKind.WARNING, AuthoringText.line("pick.miss", label));
        }
        session.put(fieldId, value.trim());
        try {
            session.wizard().picked(session, fieldId, pick);
        } catch (Throwable t) {
            SafeLog.warn("[authoring] a wizard could not read the rest of a pick", t);
        }
        return ToastSpec.of(ToastKind.SUCCESS, AuthoringText.line("pick.done", label));
    }

    @Nonnull
    private static String fold(@Nonnull String id) {
        return id.toLowerCase(Locale.ROOT);
    }
}
