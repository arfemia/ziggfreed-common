package com.ziggfreed.common.reputation.authoring;

import static com.ziggfreed.common.reputation.authoring.ReputationDraft.ADD_MEMBER;
import static com.ziggfreed.common.reputation.authoring.ReputationDraft.DESCRIPTION;
import static com.ziggfreed.common.reputation.authoring.ReputationDraft.EDIT;
import static com.ziggfreed.common.reputation.authoring.ReputationDraft.EVERY;
import static com.ziggfreed.common.reputation.authoring.ReputationDraft.ICON;
import static com.ziggfreed.common.reputation.authoring.ReputationDraft.LOOT;
import static com.ziggfreed.common.reputation.authoring.ReputationDraft.NAME;
import static com.ziggfreed.common.reputation.authoring.ReputationDraft.REMOVE_MEMBER;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.adventure.reputation.assets.ReputationGroup;
import com.hypixel.hytale.builtin.tagset.config.NPCGroup;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.ziggfreed.common.authoring.AuthoringUse;
import com.ziggfreed.common.authoring.AuthoringWizard;
import com.ziggfreed.common.authoring.Pickers;
import com.ziggfreed.common.authoring.WizardField;
import com.ziggfreed.common.authoring.WizardSession;
import com.ziggfreed.common.authoring.WizardStep;
import com.ziggfreed.common.authoring.WorldPick;
import com.ziggfreed.common.content.AppliesWhen;
import com.ziggfreed.common.content.ServerContent;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.LootEditorDataSets;
import com.ziggfreed.common.reputation.ReputationDef;
import com.ziggfreed.common.reputation.ReputationLadder;
import com.ziggfreed.common.reputation.ReputationNative;
import com.ziggfreed.common.reputation.ReputationRuntime;
import com.ziggfreed.common.reputation.ReputationText;
import com.ziggfreed.common.ui.form.FieldSpec;
import com.ziggfreed.common.ui.hud.KeyedCustomHud;

/**
 * The reputation editor, on the authoring kit's wizard frame, and what the Reputation Tool opens: it makes a
 * reputation (the native group, its member group and the library's companion, {@link ReputationDraft}) or changes
 * one the server content pack holds, in six steps: which one, its name and picture, how it is earned (kills of a
 * creature group, the page's How to earn lines), ranks of its own above the shared top, the payout past the top
 * rank, and who belongs. Every save goes through {@link ServerContent} ({@link ReputationFiles#write}).
 *
 * <p><b>The tool.</b> Pointed at a character, it opens on Who belongs with that character's role added, the
 * reputation this admin last saved or chose already picked (or the only one the pack holds); pointed anywhere else,
 * it opens on the first step. A role joins only when the engine knows it and no other reputation's group holds it
 * ({@link ReputationMembers}), checked on Who belongs and again at Save, and the group a save writes never names an
 * NPC group the engine lacks.
 *
 * <p><b>When it applies.</b> A new reputation, its kills, its ranks of its own, its words and its payouts work at
 * once (a changed word after a restart, as for every typed word): its own ranks are tiers of the library's ladder,
 * which the companion's fold rebuilds, never native ranks. A character spawned from then on follows it, one standing
 * in the world from its next spawn. A refused save puts the form back as it stood before it.
 *
 * <p>One instance per open; the frame keeps it in the session across a pick from the world. World thread.
 */
public final class ReputationWizard implements AuthoringWizard {

    /** The wizard's and the tool's id: its author node is {@code ziggfreedcommon.author.reputation}. */
    public static final String ID = "Reputation";

    static final int WHICH_STEP = 0;
    static final int NAME_STEP = 1;
    static final int EARN_STEP = 2;
    static final int RANKS_STEP = 3;
    static final int BEYOND_STEP = 4;
    static final int MEMBERS_STEP = 5;

    /** The reputation each admin last saved or chose, which the tool picks for them next time. */
    private static final Map<UUID, String> LAST = new ConcurrentHashMap<>();

    @Nullable private final UUID admin;
    @Nonnull private final PlayerRef adminRef;

    /** The {@link ReputationDraft#EDIT} value the form was last filled for; null before the first fill. */
    @Nullable private String filledFor;

    /** The files the form was filled from (or last saved); null for a new reputation. */
    @Nullable private ReputationDraft.Saved saved;

    /** The characters' roles, as chosen. */
    private final List<String> members = new ArrayList<>();

    /** Roles the tool brought, which stay chosen whichever reputation is picked until a save. */
    private final List<String> carried = new ArrayList<>();

    /** The id a new reputation was minted on its first save, and its member group's. */
    @Nullable private String newId;
    @Nullable private String newMemberGroupId;

    public ReputationWizard(@Nonnull AuthoringUse use) {
        this.adminRef = use.playerRef();
        this.admin = use.playerRef().getUuid();
    }

    /** Forget a departing admin's last reputation. */
    public static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            LAST.remove(event.getPlayerRef().getUuid());
        } catch (Throwable ignored) {
            // A departure with no player to name forgets nothing.
        }
    }

    @Nonnull
    @Override
    public Message title() {
        return ReputationAuthorText.line("title");
    }

    @Override
    public boolean saves() {
        return true;
    }

    // ---------------------------------------------------------------- the steps

    @Nonnull
    @Override
    public List<WizardStep> steps(@Nonnull WizardSession session) {
        sync(session);
        return List.of(
                WizardStep.of(key("step.which"),
                        WizardField.of(FieldSpec.note("whichNote", key("note.which"))),
                        WizardField.picker(EDIT, "Reputation", key("field.edit"), Pickers.of(ReputationFiles::held))),
                WizardStep.of(key("step.name"),
                        WizardField.of(FieldSpec.text(NAME, "Text.TitleKey", key("field.name")))
                                .withHint(key("hint.name")),
                        WizardField.of(FieldSpec.text(DESCRIPTION, "Text.FlavorKey", key("field.description"))),
                        WizardField.picker(ICON, "Icon", key("field.icon"), Pickers.items())
                                .fromWorld(WorldPick::itemId).withHint(key("hint.icon"))),
                new WizardStep(key("step.earn"), earnFields()),
                new WizardStep(key("step.ranks"), rankFields()),
                new WizardStep(key("step.beyond"), beyondFields()),
                WizardStep.of(key("step.members"),
                        WizardField.of(FieldSpec.note("membersNote", key("note.members"))),
                        WizardField.picker(ADD_MEMBER, "Members", key("field.add"), Pickers.npcRoles())
                                .fromWorld(WorldPick::npcRole),
                        WizardField.picker(REMOVE_MEMBER, "Members", key("field.remove"),
                                Pickers.of(() -> List.copyOf(members)))));
    }

    @Nonnull
    private static List<WizardField> earnFields() {
        List<WizardField> fields = new ArrayList<>();
        fields.add(WizardField.of(FieldSpec.note("earnNote", key("note.earn"))));
        for (int n = 1; n <= ReputationDraft.KILL_ROWS; n++) {
            fields.add(WizardField.picker(ReputationDraft.killGroup(n), "Kills.NPCGroups", key("field.kill." + n),
                    Pickers.npcGroups()));
            fields.add(WizardField.of(FieldSpec.integer(ReputationDraft.killAmount(n), "Kills.Amount",
                    key("field.amount"))));
        }
        fields.add(WizardField.of(FieldSpec.header("linesHeader", key("header.lines"))));
        fields.add(WizardField.of(FieldSpec.note("linesNote", key("note.lines"))));
        for (int n = 1; n <= ReputationDraft.EARN_LINES; n++) {
            fields.add(WizardField.of(FieldSpec.text(ReputationDraft.earn(n), "Earn.Lines", key("field.earn." + n))));
        }
        return fields;
    }

    @Nonnull
    private static List<WizardField> rankFields() {
        List<WizardField> fields = new ArrayList<>();
        fields.add(WizardField.of(FieldSpec.note("ranksNote", key("note.ranks"))));
        for (int n = 1; n <= ReputationDraft.RANK_ROWS; n++) {
            fields.add(WizardField.of(FieldSpec.text(ReputationDraft.rankName(n), "Ranks.Name", key("field.rank." + n))));
            fields.add(WizardField.of(FieldSpec.integer(ReputationDraft.rankFrom(n), "Ranks.From", key("field.from"))));
        }
        return fields;
    }

    @Nonnull
    private static List<WizardField> beyondFields() {
        List<WizardField> fields = new ArrayList<>();
        fields.add(WizardField.of(FieldSpec.note("beyondNote", key("note.beyond"))));
        fields.add(WizardField.of(FieldSpec.integer(EVERY, "Beyond.Every", key("field.every"))));
        for (int n = 1; n <= ReputationDraft.ITEM_REWARDS; n++) {
            fields.add(WizardField.picker(ReputationDraft.item(n), "Beyond.Rewards", key("field.item." + n),
                    Pickers.items()));
            fields.add(WizardField.of(FieldSpec.integer(ReputationDraft.count(n), "Beyond.Rewards", key("field.count"))));
        }
        fields.add(WizardField.picker(LOOT, "Beyond.Rewards", key("field.loot"),
                Pickers.of(LootEditorDataSets::lootableIds)));
        return fields;
    }

    // ---------------------------------------------------------------- opening and the form's state

    /** A character picked: its role joins, on the reputation last worked on (or the only one), at Who belongs. */
    @Override
    public void begin(@Nonnull WizardSession session, @Nonnull AuthoringUse use) {
        String role = use.pick().npcRole();
        String start = role == null ? "" : preselect();
        session.put(EDIT, start);
        if (role != null) {
            carried.add(role);
        }
        sync(session);
        if (role != null) {
            session.step(start.isEmpty() ? WHICH_STEP : MEMBERS_STEP);
        }
    }

    @Override
    public void picked(@Nonnull WizardSession session, @Nonnull String fieldId, @Nonnull WorldPick pick) {
        sync(session);
    }

    /** The reputation this admin last worked on, else the pack's only one, else blank (a new one). */
    @Nonnull
    private String preselect() {
        List<String> held = ReputationFiles.held();
        String last = admin == null ? null : LAST.get(admin);
        for (String id : held) {
            if (id.equalsIgnoreCase(last)) {
                return id;
            }
        }
        return held.size() == 1 ? held.get(0) : "";
    }

    /**
     * Fill the form when the reputation picked on the first step changed (its files read back, or every value
     * cleared for a new one), then fold a character added or taken out into the list. Every entry point calls it,
     * so the other steps always show the reputation picked.
     */
    private void sync(@Nonnull WizardSession session) {
        String chosen = session.value(EDIT).trim();
        if (!chosen.equals(filledFor)) {
            fill(session, chosen);
            filledFor = chosen;
            if (!chosen.isEmpty() && admin != null && saved != null) {
                LAST.put(admin, chosen);
            }
        }
        String add = session.value(ADD_MEMBER).trim();
        if (!add.isEmpty()) {
            addMember(add);
            session.put(ADD_MEMBER, null);
        }
        String take = session.value(REMOVE_MEMBER).trim();
        if (!take.isEmpty()) {
            members.removeIf(role -> role.equalsIgnoreCase(take));
            carried.removeIf(role -> role.equalsIgnoreCase(take));
            session.put(REMOVE_MEMBER, null);
        }
    }

    private void fill(@Nonnull WizardSession session, @Nonnull String chosen) {
        for (String field : ReputationDraft.formIds()) {
            session.put(field, null);
        }
        members.clear();
        saved = null;
        if (chosen.isEmpty()) {
            newId = null;
            newMemberGroupId = null;
        } else {
            saved = ReputationFiles.read(chosen);
            if (saved != null) {
                ReputationDraft.Form form = ReputationDraft.read(saved, ServerContent::text);
                for (Map.Entry<String, String> e : form.values().entrySet()) {
                    if (!EDIT.equals(e.getKey())) {
                        session.put(e.getKey(), e.getValue());
                    }
                }
                form.members().forEach(this::addMember);
            }
        }
        carried.forEach(this::addMember);
    }

    private void addMember(@Nonnull String role) {
        String trimmed = role.trim();
        if (!trimmed.isEmpty() && members.stream().noneMatch(m -> m.equalsIgnoreCase(trimmed))) {
            members.add(trimmed);
        }
    }

    /** The id this form saves under: the reputation picked, else the one minted on a first save; null before. */
    @Nullable
    private String currentId() {
        return saved != null ? saved.id() : newId;
    }

    // ---------------------------------------------------------------- the preview

    @Nonnull
    @Override
    public List<Message> preview(@Nonnull WizardSession session) {
        sync(session);
        List<Message> lines = new ArrayList<>();
        String name = session.value(NAME).trim();
        lines.add(Msg.bold(name.isEmpty() ? ReputationAuthorText.line("preview.unnamed")
                : ReputationAuthorText.line("preview.name", name)));
        String id = currentId();
        lines.add(id == null ? ReputationAuthorText.line("preview.new") : ReputationAuthorText.line("preview.id", id));
        lines.add(members.isEmpty() ? ReputationAuthorText.line("preview.members.none")
                : ReputationAuthorText.line("preview.members", String.join(", ", members)));
        for (int n = 1; n <= ReputationDraft.KILL_ROWS; n++) {
            String group = session.value(ReputationDraft.killGroup(n)).trim();
            Integer amount = ReputationDraft.whole(session.value(ReputationDraft.killAmount(n)));
            if (!group.isEmpty() && amount != null && amount != 0) {
                lines.add(ReputationAuthorText.line(amount > 0 ? "preview.kill.gain" : "preview.kill.loss", group,
                        amount));
            }
        }
        for (int n = 1; n <= ReputationDraft.RANK_ROWS; n++) {
            String rank = session.value(ReputationDraft.rankName(n)).trim();
            Integer from = ReputationDraft.whole(session.value(ReputationDraft.rankFrom(n)));
            if (!rank.isEmpty() && from != null) {
                lines.add(ReputationAuthorText.line("preview.rank", rank, from));
            }
        }
        Integer every = ReputationDraft.whole(session.value(EVERY));
        int payouts = payouts(session);
        if (every != null && every > 0 && payouts > 0) {
            lines.add(ReputationAuthorText.line("preview.beyond", every, payouts));
        }
        if (saved != null) {
            int kept = ReputationDraft.keptCount(saved, ServerContent::text);
            if (kept > 0) {
                lines.add(ReputationAuthorText.line("preview.kept", kept));
            }
            List<String> dropped = ReputationDraft.unloadedGroups(saved,
                    ReputationRuntime.service().engine()::npcGroupExists);
            if (!dropped.isEmpty()) {
                lines.add(ReputationAuthorText.line("preview.dropped", String.join(", ", dropped)));
            }
        }
        for (ReputationMembers.Problem problem : memberProblems()) {
            lines.add(problemLine(problem));
        }
        return lines;
    }

    /** How many payouts past the top rank the form sets: items and a loot table. */
    private static int payouts(@Nonnull WizardSession session) {
        int count = session.value(LOOT).isBlank() ? 0 : 1;
        for (int n = 1; n <= ReputationDraft.ITEM_REWARDS; n++) {
            count += session.value(ReputationDraft.item(n)).isBlank() ? 0 : 1;
        }
        return count;
    }

    // ---------------------------------------------------------------- the checks

    @Nullable
    @Override
    public Message check(@Nonnull WizardSession session, int step) {
        sync(session);
        return switch (step) {
            case WHICH_STEP -> checkWhich(session);
            case NAME_STEP -> checkName(session);
            case EARN_STEP -> checkEarn(session);
            case RANKS_STEP -> checkRanks(session);
            case BEYOND_STEP -> checkBeyond(session);
            case MEMBERS_STEP -> {
                List<ReputationMembers.Problem> problems = memberProblems();
                yield problems.isEmpty() ? null : problemLine(problems.get(0));
            }
            default -> null;
        };
    }

    @Nullable
    private Message checkWhich(@Nonnull WizardSession session) {
        String chosen = session.value(EDIT).trim();
        return !chosen.isEmpty() && saved == null ? ReputationAuthorText.line("check.edit", chosen) : null;
    }

    @Nullable
    private Message checkName(@Nonnull WizardSession session) {
        if (session.value(NAME).isBlank() && (saved == null || ReputationDraft.foreignTitle(saved) == null)) {
            return ReputationAuthorText.line("check.name");
        }
        if (backslash(session, NAME, DESCRIPTION)) {
            return ReputationAuthorText.line("check.backslash");
        }
        String icon = session.value(ICON).trim();
        return icon.isEmpty() || isItem(icon) ? null : ReputationAuthorText.line("check.item", icon);
    }

    @Nullable
    private static Message checkEarn(@Nonnull WizardSession session) {
        ReputationNative engine = ReputationRuntime.service().engine();
        for (int n = 1; n <= ReputationDraft.KILL_ROWS; n++) {
            String group = session.value(ReputationDraft.killGroup(n)).trim();
            String raw = session.value(ReputationDraft.killAmount(n));
            if (!ReputationDraft.blankOrWhole(raw)) {
                return ReputationAuthorText.line("check.whole");
            }
            Integer amount = ReputationDraft.whole(raw);
            boolean moves = amount != null && amount != 0;
            if (group.isEmpty() && moves) {
                return ReputationAuthorText.line("check.kill.group");
            }
            if (!group.isEmpty() && !moves) {
                return ReputationAuthorText.line("check.kill.amount", group);
            }
            if (!group.isEmpty() && !engine.npcGroupExists(group)) {
                return ReputationAuthorText.line("check.kill.unknown", group);
            }
        }
        for (int n = 1; n <= ReputationDraft.EARN_LINES; n++) {
            if (backslash(session, ReputationDraft.earn(n))) {
                return ReputationAuthorText.line("check.backslash");
            }
        }
        return null;
    }

    @Nullable
    private static Message checkRanks(@Nonnull WizardSession session) {
        Map<String, Integer> floors = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (int n = 1; n <= ReputationDraft.RANK_ROWS; n++) {
            String name = session.value(ReputationDraft.rankName(n)).trim();
            String raw = session.value(ReputationDraft.rankFrom(n));
            String rankId = session.value(ReputationDraft.rankId(n)).trim();
            if (!ReputationDraft.blankOrWhole(raw)) {
                return ReputationAuthorText.line("check.whole");
            }
            if (backslash(session, ReputationDraft.rankName(n))) {
                return ReputationAuthorText.line("check.backslash");
            }
            Integer from = ReputationDraft.whole(raw);
            if (!name.isEmpty() && from == null) {
                return ReputationAuthorText.line("check.rank.from", name);
            }
            if (name.isEmpty() && rankId.isEmpty() && from != null) {
                return ReputationAuthorText.line("check.rank.name", from);
            }
            if (from != null) {
                // A stand-in id no shared rank has: the save mints the real one, and only floors decide here.
                String slot = "Zig_Author_Rank_Slot_" + n;
                floors.put(slot, from);
                names.put(slot, name.isEmpty() ? rankId : name);
            }
        }
        List<ReputationLadder.Rank> shared = ReputationRuntime.service().engine().ranks();
        for (ReputationLadder.RefusedTier refused : ReputationLadder.refusedTiers(shared, floors)) {
            if (refused.refusal() == ReputationLadder.Refusal.SHARED_FLOOR) {
                return ReputationAuthorText.line("check.rank.same", refused.from());
            }
            ReputationLadder.Rank top = ReputationLadder.of(shared).top();
            return ReputationAuthorText.line("check.rank.range", names.getOrDefault(refused.id(), refused.id()),
                    top == null ? 0 : top.min(), top == null ? 0 : top.max());
        }
        return null;
    }

    @Nullable
    private static Message checkBeyond(@Nonnull WizardSession session) {
        String rawEvery = session.value(EVERY);
        if (!ReputationDraft.blankOrWhole(rawEvery)) {
            return ReputationAuthorText.line("check.whole");
        }
        Integer every = ReputationDraft.whole(rawEvery);
        if ((every != null && every < 0) || (payouts(session) > 0 && (every == null || every == 0))) {
            return ReputationAuthorText.line("check.every");
        }
        for (int n = 1; n <= ReputationDraft.ITEM_REWARDS; n++) {
            String item = session.value(ReputationDraft.item(n)).trim();
            String rawCount = session.value(ReputationDraft.count(n));
            if (!ReputationDraft.blankOrWhole(rawCount)) {
                return ReputationAuthorText.line("check.whole");
            }
            Integer count = ReputationDraft.whole(rawCount);
            if (!item.isEmpty() && !isItem(item)) {
                return ReputationAuthorText.line("check.item", item);
            }
            if (count != null && count < 1) {
                return ReputationAuthorText.line("check.count");
            }
        }
        String loot = session.value(LOOT).trim();
        if (!loot.isEmpty() && LootEditorDataSets.lootableIds().stream().noneMatch(loot::equalsIgnoreCase)) {
            return ReputationAuthorText.line("check.loot", loot);
        }
        return null;
    }

    /** Why the chosen characters may not join, asked of the engine. */
    @Nonnull
    private List<ReputationMembers.Problem> memberProblems() {
        ReputationNative engine = ReputationRuntime.service().engine();
        String self = currentId();
        return ReputationMembers.problems(members, self == null ? "" : self, ReputationWizard::isRole,
                engine.groups(), engine::npcGroupExists, engine::roleInGroup);
    }

    @Nonnull
    private static Message problemLine(@Nonnull ReputationMembers.Problem problem) {
        if (problem.kind() == ReputationMembers.Kind.UNKNOWN_ROLE || problem.reputation() == null) {
            return ReputationAuthorText.line("check.role", problem.role());
        }
        ReputationDef holder = ReputationRuntime.service().known(problem.reputation());
        return ReputationAuthorText.line("check.role.taken", problem.role(),
                holder == null ? Msg.raw(problem.reputation()) : ReputationText.name(holder));
    }

    // ---------------------------------------------------------------- the save

    @Nonnull
    @Override
    public List<ServerContent.Result> save(@Nonnull WizardSession session) {
        sync(session);
        String id = currentId();
        if (id == null) {
            id = ServerContent.mintId(ReputationGroup.getAssetStore(), session.value(NAME));
            newId = id;
        }
        boolean needsMemberGroup = (saved == null || saved.memberGroupId() == null)
                && !ReputationMembers.knownRoles(members, ReputationWizard::isRole).isEmpty();
        if (needsMemberGroup && newMemberGroupId == null) {
            newMemberGroupId = ServerContent.mintId(NPCGroup.getAssetStore(), id);
        }
        ReputationNative engine = ReputationRuntime.service().engine();
        List<String> sharedRanks = new ArrayList<>();
        for (ReputationLadder.Rank rank : engine.ranks()) {
            sharedRanks.add(rank.id());
        }
        ReputationDraft.Plan plan = ReputationDraft.plan(id, session.values(), members, saved, newMemberGroupId,
                ReputationWizard::isRole, engine::npcGroupExists, sharedRanks, ServerContent::text);
        for (Map.Entry<String, String> minted : plan.minted().entrySet()) {
            session.put(minted.getKey(), minted.getValue().isEmpty() ? null : minted.getValue());
        }
        List<ServerContent.Result> results = new ArrayList<>();
        if (!plan.texts().isEmpty()) {
            results.add(ServerContent.setTexts(plan.texts()));
        }
        CompletableFuture<Void> files = ReputationFiles.write(plan);
        results.add(new ServerContent.Result(id, AppliesWhen.NOW, files));
        // From here the form changes what it is saving, under the same id; a refused save puts the form back.
        Before before = new Before(saved, filledFor, session.value(EDIT), List.copyOf(carried),
                admin == null ? null : LAST.get(admin));
        ReputationDraft.Saved attempt = plan.asSaved();
        saved = attempt;
        filledFor = id;
        session.put(EDIT, id);
        carried.clear();
        if (admin != null) {
            LAST.put(admin, id);
        }
        files.whenComplete((done, failure) -> {
            World world = failure == null ? null : KeyedCustomHud.aliveWorldOf(adminRef);
            if (world != null) {
                world.execute(() -> undo(session, attempt, before));
            }
        });
        return results;
    }

    /** The form as it stood before a save, which a refused save puts back. */
    private record Before(@Nullable ReputationDraft.Saved saved, @Nullable String filledFor, @Nonnull String edit,
            @Nonnull List<String> carried, @Nullable String last) {
    }

    /**
     * World thread, once a save of {@code attempt} was refused: put the form back as it stood before it, so an id
     * the pack never took is not treated as a saved reputation (the next save tries the same id again). A form that
     * moved on since (another save, another reputation picked) is left alone.
     */
    private void undo(@Nonnull WizardSession session, @Nonnull ReputationDraft.Saved attempt, @Nonnull Before before) {
        if (saved != attempt) {
            return;
        }
        saved = before.saved();
        filledFor = before.filledFor();
        session.put(EDIT, before.edit().isEmpty() ? null : before.edit());
        before.carried().forEach(role -> {
            if (carried.stream().noneMatch(role::equalsIgnoreCase)) {
                carried.add(role);
            }
        });
        if (admin != null) {
            if (before.last() == null) {
                LAST.remove(admin);
            } else {
                LAST.put(admin, before.last());
            }
        }
    }

    // ---------------------------------------------------------------- engine readings

    /** Is {@code role} an NPC role the engine has loaded? */
    static boolean isRole(@Nonnull String role) {
        try {
            NPCPlugin npcs = NPCPlugin.get();
            return npcs != null && npcs.hasRoleName(role);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isItem(@Nonnull String id) {
        try {
            Item item = Item.getAssetMap().getAsset(id);
            return item != null && item != Item.UNKNOWN;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean backslash(@Nonnull WizardSession session, @Nonnull String... fields) {
        for (String field : fields) {
            if (session.value(field).indexOf('\\') >= 0) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static String key(@Nonnull String key) {
        return ReputationAuthorText.key(key);
    }
}
