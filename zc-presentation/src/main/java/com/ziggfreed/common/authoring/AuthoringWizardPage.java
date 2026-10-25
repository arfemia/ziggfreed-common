package com.ziggfreed.common.authoring;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.content.AppliesWhen;
import com.ziggfreed.common.content.ContentProblem;
import com.ziggfreed.common.content.ContentRefusedException;
import com.ziggfreed.common.content.ServerContent;
import com.ziggfreed.common.feedback.Notify;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.ZigSearchRow;
import com.ziggfreed.common.ui.form.FieldKind;
import com.ziggfreed.common.ui.form.FieldSpec;
import com.ziggfreed.common.ui.form.SettingsForm;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * The authoring wizard frame every in-game editor opens in: the {@link AuthoringWizard}'s title and numbered steps,
 * the current step's rows drawn by {@link SettingsForm} (its pickers narrowed by the shared search row), a "Pick in
 * the world" button per field the world can fill, the preview pane, and Back, Next and Save. Its document is
 * {@code Pages/ZigAuthoringWizardPage.ui} with {@code Pages/ZigAuthoringPickRow.ui}; its words are
 * {@code ziggfreedcommon.authoring.lang}'s {@code wizard.*}, the wizard's own are its keys.
 *
 * <p>Every value lives in the {@link WizardSession}, so Back and Next keep what was typed, and a pick from the world
 * (which closes this page) opens a new page on the same session. Save first asks the saving player for the wizard's
 * author node ({@code ziggfreedcommon.author.<wizard id>}, no creative mode needed), whatever surface opened the page,
 * then runs the wizard's {@link AuthoringWizard#save} through {@link ServerContent} and, once every result has
 * landed, shows the slowest one's
 * {@link AppliesWhen#notice()}, or the refusal's {@link ContentProblem#message()}, as the page's toast. Every event
 * is answered once, through {@link WizardActions}. World thread.
 */
public final class AuthoringWizardPage extends ToastablePage<AuthoringWizardEventData> {

    static final String TEMPLATE = "Pages/ZigAuthoringWizardPage.ui";
    static final String PICK_ROW = "Pages/ZigAuthoringPickRow.ui";
    static final String NOTE_ROW = "Pages/ZigFormNoteRow.ui";

    /** The key a row's live value rides under (the settings form's binding); the {@code @} is the directive. */
    static final String VALUE_KEY = "@Value";

    /** The key the search row's live text rides under. */
    static final String SEARCH_KEY = "@Search";

    private static final String FORM = "#Form";
    private static final String PICKS = "#Picks";
    private static final String PREVIEW = "#Preview";
    private static final String SEARCH = "#PickerSearch";
    private static final String STATUS = "#Status";

    @Nonnull private final WizardSession session;

    /** The step's form and rows as last built, which a partial update addresses. */
    @Nullable private SettingsForm form;
    @Nonnull private List<WizardField> stepFields = List.of();

    /** Why Next or Save last refused, until the step changes. */
    @Nullable private Message status;

    AuthoringWizardPage(@Nonnull PlayerRef playerRef, @Nonnull WizardSession session) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, AuthoringWizardEventData.CODEC);
        this.session = session;
    }

    /** A wizard's label or step name: a full registered lang key, resolved on the client. */
    @Nonnull
    static Message label(@Nonnull String key) {
        return Msg.key(key);
    }

    // ---------------------------------------------------------------- build

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        cmd.append(TEMPLATE);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                EventData.of("Action", WizardActions.CLOSE), false);
        try {
            paint(cmd, events);
        } catch (Throwable t) {
            SafeLog.warn("[authoring] a wizard page could not paint everything", t);
        }
        ToastSpec notice = session.takeNotice();
        if (notice != null) {
            primeToast(notice);
        }
        renderToastInto(cmd);
    }

    private void paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        AuthoringWizard wizard = session.wizard();
        List<WizardStep> steps = session.steps();
        int count = steps.size();
        int at = Math.min(session.step(), count - 1);
        session.step(at);
        WizardStep step = steps.get(at);

        cmd.set("#Title.TextSpans", title(wizard));
        cmd.set("#StepLine.TextSpans", AuthoringText.line("wizard.step", at + 1, count, label(step.titleKey())));

        stepFields = step.fields();
        List<FieldSpec> specs = new ArrayList<>();
        boolean pickers = false;
        for (WizardField field : stepFields) {
            specs.add(field.specFor(session.filter(), session.value(field.id())));
            pickers |= field.picker() != null;
        }
        SettingsForm built = new SettingsForm(specs, AuthoringText.line("wizard.on"), AuthoringText.line("wizard.off"));
        built.seed(session.values());
        built.buildRows(cmd, events, FORM, AuthoringWizardPage::label);
        form = built;

        cmd.set(SEARCH + ".Visible", pickers);
        if (pickers) {
            ZigSearchRow.wire(cmd, events, SEARCH, session.filter(), SEARCH_KEY,
                    EventData.of("Action", WizardActions.SEARCH), EventData.of("Action", WizardActions.CLEAR));
        }

        int picks = 0;
        for (WizardField field : stepFields) {
            if (field.fromWorld() == null) {
                continue;
            }
            cmd.append(PICKS, PICK_ROW);
            String sel = PICKS + "[" + picks++ + "]";
            ZigRichButton.text(cmd, sel, AuthoringText.line("wizard.pick", label(field.spec().labelKey())));
            events.addEventBinding(CustomUIEventBindingType.Activating, sel,
                    EventData.of("Action", WizardActions.PICK).append("Field", field.id()), false);
        }

        cmd.set("#PreviewTitle.TextSpans", AuthoringText.line("wizard.preview"));
        appendPreview(cmd);

        boolean first = at == 0;
        boolean last = at == count - 1;
        button(cmd, events, "#CloseAction", "wizard.close", WizardActions.CLOSE, true);
        button(cmd, events, "#BackAction", "wizard.back", WizardActions.BACK, !first);
        button(cmd, events, "#NextAction", "wizard.next", WizardActions.NEXT, !last);
        button(cmd, events, "#SaveAction", "wizard.save", WizardActions.SAVE, last && saves(wizard));
        SettingsUiUtil.setStatus(cmd, STATUS, status, true);
    }

    /** One of the action buttons: worded, bound and shown, or hidden. */
    private static void button(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String sel,
            @Nonnull String key, @Nonnull String action, boolean shown) {
        cmd.set(sel + ".Visible", shown);
        if (shown) {
            ZigRichButton.text(cmd, sel, AuthoringText.line(key));
            events.addEventBinding(CustomUIEventBindingType.Activating, sel, EventData.of("Action", action), false);
        }
    }

    /** The preview pane's lines, one note row each; a pane with nothing to say says so. */
    private void appendPreview(@Nonnull UICommandBuilder cmd) {
        List<Message> lines;
        try {
            lines = session.wizard().preview(session);
        } catch (Throwable t) {
            SafeLog.warn("[authoring] a wizard's preview could not be read", t);
            lines = null;
        }
        if (lines == null || lines.isEmpty()) {
            lines = List.of(AuthoringText.line("wizard.preview.empty"));
        }
        for (int i = 0; i < lines.size(); i++) {
            cmd.append(PREVIEW, NOTE_ROW);
            cmd.set(PREVIEW + "[" + i + "] #Note.TextSpans", lines.get(i));
        }
    }

    @Nonnull
    private static Message title(@Nonnull AuthoringWizard wizard) {
        try {
            return wizard.title();
        } catch (Throwable t) {
            return Msg.raw("");
        }
    }

    private static boolean saves(@Nonnull AuthoringWizard wizard) {
        try {
            return wizard.saves();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- events

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull AuthoringWizardEventData data) {
        WizardActions.event(new Events(ref, store), data.action, data.field, data.value, data.search);
    }

    /** The page's steps for one event, and its four ways of answering. */
    private final class Events implements WizardActions.Host {

        @Nonnull private final Ref<EntityStore> ref;
        @Nonnull private final Store<EntityStore> store;
        @Nonnull private UICommandBuilder queued = new UICommandBuilder();

        Events(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
            this.ref = ref;
            this.store = store;
        }

        /** A row's value changed: keep it, and repaint the preview, which may read it. */
        @Nonnull
        @Override
        public WizardActions.Exit field(@Nullable String field, @Nullable String value) {
            SettingsForm built = form;
            if (field == null || value == null || built == null || !built.cache(field, value)) {
                return WizardActions.Exit.ANSWER;
            }
            session.put(field, value);
            repaintPreview();
            return WizardActions.Exit.PARTIAL;
        }

        /** A toggle was pressed: flip it, and repaint it and the preview. */
        @Nonnull
        @Override
        public WizardActions.Exit press(@Nullable String field) {
            SettingsForm built = form;
            FieldSpec spec = field == null || built == null ? null : built.spec(field);
            if (spec == null || spec.kind() != FieldKind.TOGGLE) {
                return WizardActions.Exit.ANSWER;
            }
            String flipped = session.isOn(field) ? "" : "on";
            session.put(field, flipped);
            built.seedValue(field, flipped);
            built.applyValue(queued, FORM, field);
            repaintPreview();
            return WizardActions.Exit.PARTIAL;
        }

        @Nonnull
        @Override
        public WizardActions.Exit search(@Nullable String text) {
            session.filter(text);
            return WizardActions.Exit.REBUILD;
        }

        @Nonnull
        @Override
        public WizardActions.Exit back() {
            status = null;
            session.step(session.step() - 1);
            return WizardActions.Exit.REBUILD;
        }

        @Nonnull
        @Override
        public WizardActions.Exit next() {
            Message refused = check(session.step());
            if (refused != null) {
                return refuse(refused);
            }
            status = null;
            session.step(session.step() + 1);
            return WizardActions.Exit.REBUILD;
        }

        /** Every step's check, then the wizard's save through the server content pack, watched until it lands. */
        @Nonnull
        @Override
        public WizardActions.Exit save() {
            AuthoringWizard wizard = session.wizard();
            if (!saves(wizard)) {
                return WizardActions.Exit.ANSWER;
            }
            // The gate is here, on the server, whatever surface opened the page: only an author of this wizard saves.
            String node = AuthoringTools.permission(session.wizardId());
            if (!playerRef.hasPermission(node)) {
                return refuse(AuthoringText.line("wizard.save_permission", Msg.raw(node)));
            }
            if (session.saving()) {
                showToast(ToastKind.WARNING, AuthoringText.line("wizard.busy"));
                return WizardActions.Exit.ANSWER;
            }
            int count = session.steps().size();
            for (int i = 0; i < count; i++) {
                Message refused = check(i);
                if (refused != null) {
                    return refuse(refused);
                }
            }
            List<ServerContent.Result> results;
            try {
                results = wizard.save(session);
            } catch (Throwable t) {
                SafeLog.warn("[authoring] a wizard's save failed before it reached the pack", t);
                showToast(ToastKind.ERROR, AuthoringText.line("wizard.save_failed"));
                return WizardActions.Exit.ANSWER;
            }
            if (results == null || results.isEmpty()) {
                showToast(ToastKind.WARNING, AuthoringText.line("wizard.nothing"));
                return WizardActions.Exit.ANSWER;
            }
            status = null;
            session.saving(true);
            watch(results);
            queued.set(STATUS + ".Visible", false);
            return WizardActions.Exit.PARTIAL;
        }

        /** Park the session for a pick from the world, tell the player what to do, and close the page. */
        @Nonnull
        @Override
        public WizardActions.Exit pick(@Nullable String field) {
            WizardField picking = null;
            for (WizardField f : stepFields) {
                if (f.id().equals(field) && f.fromWorld() != null) {
                    picking = f;
                }
            }
            if (picking == null) {
                return WizardActions.Exit.ANSWER;
            }
            PendingPicks.await(playerRef.getUuid(), session, picking.id());
            Notify.def(playerRef, AuthoringText.line("pick.start", label(picking.spec().labelKey())));
            return WizardActions.Exit.CLOSE;
        }

        @Override
        public void rebuild() {
            AuthoringWizardPage.this.rebuild();
        }

        @Override
        public void partial() {
            sendUpdate(queued, new UIEventBuilder(), false);
        }

        @Override
        public void close() {
            Player player = ref.isValid() ? store.getComponent(ref, Player.getComponentType()) : null;
            if (player == null) {
                answer();
                return;
            }
            player.getPageManager().setPage(ref, store, Page.None);
        }

        @Override
        public void answer() {
            sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
        }

        private void repaintPreview() {
            queued.clear(PREVIEW);
            appendPreview(queued);
        }

        @Nonnull
        private WizardActions.Exit refuse(@Nonnull Message refused) {
            status = refused;
            SettingsUiUtil.setStatus(queued, STATUS, refused, true);
            return WizardActions.Exit.PARTIAL;
        }
    }

    /** The wizard's check of step {@code step}, guarded: one that throws lets the admin go on. */
    @Nullable
    private Message check(int step) {
        try {
            return session.wizard().check(session, step);
        } catch (Throwable t) {
            SafeLog.warn("[authoring] a wizard's check of step " + step + " failed", t);
            return null;
        }
    }

    // ---------------------------------------------------------------- the save landing

    /** Watch every result; once all have landed (or one is refused), say so on the player's world thread. */
    private void watch(@Nonnull List<ServerContent.Result> results) {
        AppliesWhen when = AppliesWhen.NOW;
        CompletableFuture<?>[] all = new CompletableFuture<?>[results.size()];
        for (int i = 0; i < results.size(); i++) {
            ServerContent.Result result = results.get(i);
            when = when.orLater(result.appliesWhen());
            all[i] = result.completion();
        }
        AppliesWhen applies = when;
        CompletableFuture.allOf(all).whenComplete((done, failure) -> landed(failure, applies));
    }

    /** Any thread: hop to the player's world thread to say how the save went. */
    private void landed(@Nullable Throwable failure, @Nonnull AppliesWhen applies) {
        World world;
        try {
            Ref<EntityStore> ref = playerRef.getReference();
            world = ref == null ? null : ref.getStore().getExternalData().getWorld();
        } catch (Throwable t) {
            world = null;
        }
        if (world == null) {
            session.saving(false);
            if (failure == null) {
                session.markSaved();
            }
            SafeLog.fine("[authoring] a wizard's save landed for a player who has gone");
            return;
        }
        world.execute(() -> settle(failure, applies));
    }

    /** World thread: the toast on this page while it is open, else a notice in the corner. */
    private void settle(@Nullable Throwable failure, @Nonnull AppliesWhen applies) {
        session.saving(false);
        Message said;
        boolean ok = failure == null;
        if (ok) {
            session.markSaved();
            said = applies.notice();
        } else {
            said = ContentRefusedException.problemOf(failure).message();
            SafeLog.warn("[authoring] a wizard's save was refused: " + failure.getMessage());
        }
        try {
            if (!isDismissed()) {
                showToast(ok ? ToastKind.SUCCESS : ToastKind.ERROR, said);
            } else if (ok) {
                Notify.success(playerRef, said);
            } else {
                Notify.danger(playerRef, said);
            }
        } catch (Throwable t) {
            SafeLog.fine("[authoring] a wizard's save notice could not be shown: " + t.getMessage());
        }
    }
}
