package com.ziggfreed.common.npc.placement.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.npc.placement.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.NpcPlacementAuthoring;
import com.ziggfreed.common.npc.placement.NpcPlacementConfig;
import com.ziggfreed.common.npc.placement.NpcPlacementLedger;
import com.ziggfreed.common.npc.placement.NpcPlacementOverrides;
import com.ziggfreed.common.npc.placement.NpcPlacementReconciler;
import com.ziggfreed.common.npc.placement.NpcPlacementService;
import com.ziggfreed.common.npc.placement.PlacementGate;
import com.ziggfreed.common.npc.placement.PlacementGates;
import com.ziggfreed.common.npc.placement.command.NpcAdminMessages;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * The NPC placement admin page: what should be standing in this world, what actually is, and the
 * two things an admin does about it - switch one off, or stand a new one where they are.
 *
 * <p>Opened only through {@link NpcPlacementAdminPages#open}, whose audience seam defaults to DENY;
 * this page never checks permissions itself, on the family's usual terms.
 *
 * <p><b>The rows answer the question the command answers</b>, off the same reads: every placement
 * whose {@code Where} matches this world, its gate verdict, and how many of it the ledger says are
 * standing. A row that is denied says so with the gate's own reason rather than painting as merely
 * off, because "an admin switched this off" and "its requirements are not met" are different facts
 * and only one of them is the admin's to change here.
 *
 * <p><b>The role picker mirrors the first-party entity tool.</b> That page filters
 * {@code getRoleTemplateNames(true)} by a search box, and so does this one, through
 * {@link NpcPlacementAuthoring#spawnableRoles()} - spawnable only, so the abstract templates other
 * roles are built on are never offered: naming one writes a placement that can never appear.
 *
 * <p>Stateless but for the filter: every binding round-trips the whole state, and the filter rides
 * along on all of them so a Place click after typing still knows what was typed.
 */
public final class NpcPlacementAdminPage extends ToastablePage<NpcPlacementAdminEventData> {

    static final String PAGE_TEMPLATE = "Pages/ZigNpcPlacementAdminPage.ui";

    /** The shared settings-form toggle row (zc-presentation), appended once per placement. */
    static final String ROW_TEMPLATE = "Pages/ZigFormToggleRow.ui";

    /** The shared list row (zc-presentation), appended once per offered role. */
    static final String ROLE_ROW_TEMPLATE = "Pages/ZigListRow.ui";

    /** How many matching roles are worth offering before the filter has to do more work. */
    private static final int MAX_ROLES = 12;

    // A denied row is not merely off, so it must not paint like an off switch somebody chose.
    private static final String DENIED_BG = "#4a3636";
    private static final String DENIED_LABEL = "#e08a8a";

    @Nonnull private NpcPlacementAdminDeps deps = NpcPlacementAdminDeps.DEFAULTS;

    /** The live contents of the role filter, carried across every rebuild. */
    @Nonnull private final String search;

    public NpcPlacementAdminPage(@Nonnull PlayerRef playerRef, @Nullable String search) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction,
                NpcPlacementAdminEventData.CODEC);
        this.search = search == null ? "" : search;
    }

    /** A line of this page, resolved from the admin lang family the command surface already ships. */
    @Nonnull
    private static Message msg(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(NpcAdminMessages.PREFIX + key, args);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        deps = NpcPlacementAdminPages.resolvedDeps();
        appendTemplate(cmd);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                EventData.of("Action", "close"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
                EventData.of("Action", "back"));

        World world = store.getExternalData().getWorld();
        String worldName = world == null ? "" : NpcPlacementService.worldName(world);

        cmd.set("#Title.TextSpans", msg("page.title"));
        cmd.set("#Subtitle.TextSpans", msg("page.subtitle", worldName));
        ZigRichButton.text(cmd, "#BackButton", msg("page.back"));

        buildPlacements(cmd, events, store, world, worldName);
        buildRolePicker(cmd, events);
        renderToastInto(cmd);
    }

    // ==================== the placements half ====================

    /** Every placement targeting this world, with the state the sweep would act on. */
    private void buildPlacements(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store, @Nullable World world, @Nonnull String worldName) {

        cmd.set("#PlacementsHeading.TextSpans", msg("page.placements"));
        List<NpcPlacementAsset> here = placementsInWorld(world);
        if (here.isEmpty()) {
            cmd.set("#Empty.TextSpans", msg("page.empty", worldName));
            cmd.set("#Empty.Visible", true);
            return;
        }
        NpcPlacementLedger ledger = NpcPlacementLedger.getInstance();
        for (int i = 0; i < here.size(); i++) {
            appendPlacementRow(cmd, events, "#Rows[" + i + "]", here.get(i), store, world, ledger,
                    worldName);
        }
    }

    /**
     * Which placements this world's sweep would consider. Empty when there is no world to ask about,
     * which reads as "cannot tell" rather than "none are authored".
     */
    @Nonnull
    private static List<NpcPlacementAsset> placementsInWorld(@Nullable World world) {
        List<NpcPlacementAsset> out = new ArrayList<>();
        if (world == null) {
            return out;
        }
        for (NpcPlacementAsset placement : NpcPlacementConfig.getInstance().all().values()) {
            if (placement != null && placement.getId() != null
                    && NpcPlacementReconciler.matchesWorld(placement, world)) {
                out.add(placement);
            }
        }
        out.sort((a, b) -> a.getId().compareTo(b.getId()));
        return out;
    }

    /** One placement: what it is, whether it is standing, and the switch, where flipping it is ours. */
    private void appendPlacementRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull String rowSel, @Nonnull NpcPlacementAsset placement,
            @Nonnull Store<EntityStore> store, @Nullable World world,
            @Nonnull NpcPlacementLedger ledger, @Nonnull String worldName) {

        String id = placement.getId();
        cmd.append("#Rows", ROW_TEMPLATE);
        cmd.set(rowSel + " #Title.TextSpans", msg("page.row.title", id, roleOf(placement)));

        PlacementGate.GateVerdict verdict = verdictFor(placement, world, store);
        long standing = ledger.rowsInWorld(worldName).stream()
                .filter(row -> row.placementId().equalsIgnoreCase(id))
                .count();

        cmd.set(rowSel + " #Hint.TextSpans", hintFor(verdict, standing));
        cmd.set(rowSel + " #Hint.Visible", true);

        boolean enabled = NpcPlacementOverrides.getInstance().isEnabled(id);
        if (verdict != null && verdict.isDenied() && enabled) {
            // Denied for a reason the switch cannot change: paint it as blocked, not as off.
            ZigRichButton.text(cmd, rowSel + " #Toggle", msg("page.blocked"));
            UiRetint.retintButtonStates(cmd, rowSel + " #Toggle", DENIED_BG, DENIED_BG, DENIED_BG);
            ZigRichButton.color(cmd, rowSel + " #Toggle", DENIED_LABEL);
        } else {
            SettingsUiUtil.setToggle(cmd, rowSel + " #Toggle", enabled, msg("page.on"),
                    msg("page.off"));
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, rowSel + " #Toggle",
                EventData.of("Action", "toggle").append("Id", id).append("Search", "#Search.Value"),
                false);
    }

    /** The gate's answer, or null when it could not be asked. */
    @Nullable
    private static PlacementGate.GateVerdict verdictFor(@Nonnull NpcPlacementAsset placement,
            @Nullable World world, @Nonnull Store<EntityStore> store) {
        if (world == null) {
            return null;
        }
        try {
            return PlacementGates.decide(new PlacementGate.GateContext(placement, world, store));
        } catch (Throwable t) {
            SafeLog.fine("[placement-admin] a gate could not be read: " + t.getMessage());
            return null;
        }
    }

    /** The row's sub-label: standing, pending, or denied with the gate's own reason. */
    @Nonnull
    private static Message hintFor(@Nullable PlacementGate.GateVerdict verdict, long standing) {
        if (verdict != null && verdict.isDenied()) {
            String reason = verdict.reasonKey();
            return msg("page.state.denied", reason == null ? "" : reason);
        }
        if (standing > 0) {
            return msg("page.state.standing", standing);
        }
        return msg("page.state.pending");
    }

    /** The role a placement stands, for the row label; blank when it names none. */
    @Nonnull
    private static String roleOf(@Nonnull NpcPlacementAsset placement) {
        NpcPlacementAsset.Identity identity = placement.getIdentity();
        String role = identity == null ? null : identity.getRole();
        return role == null ? "" : role;
    }

    // ==================== the place-one-here half ====================

    /**
     * The filter and whatever it matches. The list is the roles this server can actually stand up,
     * so an abstract template is never offered.
     */
    private void buildRolePicker(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events) {
        cmd.set("#PlaceHeading.TextSpans", msg("page.place.heading"));
        cmd.set("#PlaceHint.TextSpans", msg("page.place.hint"));
        cmd.set("#Search.Value", search);
        SettingsUiUtil.bindTextField(events, "#Search", "Search");
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#Search",
                EventData.of("Action", "search").append("Search", "#Search.Value"), false);

        List<String> roles = matchingRoles();
        if (roles.isEmpty()) {
            cmd.set("#NoRoles.TextSpans", msg("page.place.none"));
            cmd.set("#NoRoles.Visible", true);
            return;
        }
        for (int i = 0; i < roles.size(); i++) {
            String role = roles.get(i);
            String sel = "#RoleRows[" + i + "]";
            cmd.append("#RoleRows", ROLE_ROW_TEMPLATE);
            cmd.set(sel + " #Title.TextSpans", Msg.raw(role));
            ZigRichButton.text(cmd, sel + " #EditBtn", msg("page.place.button"));
            // #RemoveBtn already ships hidden; this row offers Place and nothing else.
            events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #EditBtn",
                    EventData.of("Action", "place").append("Role", role)
                            .append("Search", "#Search.Value"),
                    false);
        }
    }

    /**
     * The offered roles: every spawnable one whose id contains the filter, capped so a server with
     * hundreds does not paint hundreds of rows. An empty filter offers the first few, which is a
     * starting point rather than a browse.
     */
    @Nonnull
    private List<String> matchingRoles() {
        String needle = search.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String role : NpcPlacementAuthoring.spawnableRoles()) {
            if (needle.isEmpty() || role.toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(role);
                if (out.size() >= MAX_ROLES) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Get the page's markup onto the screen, through the consumer's theme where there is one.
     * Guarded with the plain append as the fallback: a theme is decoration, and one that throws must
     * not cost the admin the screen.
     */
    private void appendTemplate(@Nonnull UICommandBuilder cmd) {
        try {
            deps.theme().appendThemed(cmd, PAGE_TEMPLATE, "#PlacementPanel", "#PlacePanel");
            return;
        } catch (Throwable t) {
            SafeLog.warn("[placement-admin] a page theme failed, so the admin page renders plain: "
                    + t.getMessage());
        }
        cmd.append(PAGE_TEMPLATE);
    }

    // ==================== events ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull NpcPlacementAdminEventData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        String action = data.action == null ? "" : data.action;
        String filter = data.search == null ? search : data.search;
        switch (action) {
            case "back" -> {
                if (!deps.backGuarded(store, ref, player)) {
                    player.getPageManager().setPage(ref, store, Page.None);
                }
            }
            case "toggle" -> handleToggle(ref, store, player, data.id, filter);
            case "place" -> handlePlace(ref, store, player, data.role, filter);
            case "search" -> reopen(player, ref, store, filter);
            case "close", "" -> player.getPageManager().setPage(ref, store, Page.None);
            default -> this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
        }
    }

    /**
     * Flip one placement's owner switch and sweep, so a stop DESPAWNS what is standing rather than
     * merely declining to place it next time.
     */
    private void handleToggle(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Player player, @Nullable String id, @Nonnull String filter) {
        if (id == null || id.isBlank()) {
            reopen(player, ref, store, filter);
            return;
        }
        NpcPlacementOverrides overrides = NpcPlacementOverrides.getInstance();
        boolean target = !overrides.isEnabled(id);
        if (!overrides.setEnabled(id, target)) {
            showToast(ToastKind.ERROR, msg("page.toggle.failed", id));
            reopen(player, ref, store, filter);
            return;
        }
        World world = store.getExternalData().getWorld();
        if (world != null) {
            NpcPlacementReconciler.forceSweep(world, store);
        }
        showToast(ToastKind.SUCCESS,
                msg("page.toggle.done", id, msg(target ? "page.on" : "page.off")));
        reopen(player, ref, store, filter);
    }

    /** Stand {@code role} where the admin is, through the same path the command writes with. */
    private void handlePlace(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Player player, @Nullable String role, @Nonnull String filter) {
        World world = store.getExternalData().getWorld();
        TransformComponent transform =
                store.getComponent(ref, TransformComponent.getComponentType());
        if (role == null || role.isBlank() || world == null || transform == null) {
            showToast(ToastKind.ERROR, msg("place.noPosition"));
            reopen(player, ref, store, filter);
            return;
        }
        var position = transform.getPosition();
        NpcPlacementAuthoring.Result result = NpcPlacementAuthoring.place(world, store, role, role,
                null, NpcPlacementService.worldName(world),
                NpcPlacementAuthoring.round(position.x(), 2),
                NpcPlacementAuthoring.round(position.y(), 2),
                NpcPlacementAuthoring.round(position.z(), 2),
                NpcPlacementAuthoring.round(transform.getRotation().yaw(), 1));

        switch (result.outcome()) {
            case PLACED -> showToast(ToastKind.SUCCESS,
                    msg("place.done", result.id(), result.role(), result.worldName()));
            case ID_TAKEN -> showToast(ToastKind.ERROR, msg("place.idTaken", result.id()));
            case ROLE_NOT_SPAWNABLE -> showToast(ToastKind.ERROR,
                    msg("place.roleNotSpawnable", result.role()));
            case WRITE_FAILED -> showToast(ToastKind.ERROR,
                    msg("place.writeFailed", NpcPlacementOverrides.getInstance().getFile().toString()));
        }
        reopen(player, ref, store, filter);
    }

    /** Reopen fresh, so every row repaints from a live read; the toast rides the rebuild. */
    private void reopen(@Nonnull Player player, @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store, @Nonnull String filter) {
        player.getPageManager().openCustomPage(ref, store,
                new NpcPlacementAdminPage(playerRef, filter));
    }
}
