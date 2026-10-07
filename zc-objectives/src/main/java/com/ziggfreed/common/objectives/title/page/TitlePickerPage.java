package com.ziggfreed.common.objectives.title.page;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.book.ObjectiveBookPage;
import com.ziggfreed.common.objectives.book.ObjectiveBookPages;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.objectives.title.TitleConfig;
import com.ziggfreed.common.objectives.title.TitleText;
import com.ziggfreed.common.objectives.title.TitleUnlocks;
import com.ziggfreed.common.objectives.title.page.TitlePickerRows.Line;
import com.ziggfreed.common.objectives.title.page.TitlePickerRows.Plan;
import com.ziggfreed.common.objectives.title.page.TitlePickerRows.Tile;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionSystem;
import com.ziggfreed.common.progress.runtime.ProgressionTexts;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.SettingsUiUtil;
import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailPainter;
import com.ziggfreed.common.ui.kit.EmptyState;
import com.ziggfreed.common.ui.kit.EmptyStatePainter;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Progress;
import com.ziggfreed.common.ui.kit.Tick;
import com.ziggfreed.common.ui.kit.ZigStyles;
import com.ziggfreed.common.ui.kit.ZigTokens;
import com.ziggfreed.common.ui.name.PlayerDisplayNames;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * The title picker: the preview bar saying how the player appears, the titles they have earned as tiles with a Show
 * button each (at most one shown), and every other title on offer as a line naming the achievement that gives it and
 * how far along they are; a line opens the objective book on that achievement. What it shows is the pure plan
 * {@link TitlePickerRows}; what gives a title is {@link TitleSources}.
 *
 * <p>Show shows that title, or takes it off when it is the one shown; Show none takes any off. Either writes through
 * {@code TitleUnlocks} and repaints only the tiles it moved and the preview, so the scroll position survives. Back
 * goes where the consumer's {@link TitlePickerDeps} says (the Settings tab by default). Every exit path sends an
 * update or hands the screen to another page.
 */
public final class TitlePickerPage extends ToastablePage<TitlePickerEventData> {

    static final String PAGE_TEMPLATE = "Pages/ZigTitlePickerPage.ui";

    static final String TILE_TEMPLATE = "Pages/ZigTitleTile.ui";

    /** The wrapping host the earned tiles append into, flat: {@code #Earned[i]}. */
    private static final String EARNED = "#Earned";

    /** The host the not-earned lines append into. */
    private static final String NOT_EARNED = "#NotEarned";

    /** Between a title's name and where it comes from on a not-earned line: spacing, not words. */
    private static final String GAP = "   ";

    /** The earned tiles as last painted, so a press repaints only those whose state it moved. */
    private List<Tile> tiles = List.of();

    /** Title id to its tile's root. */
    private final Map<String, String> tileOf = new LinkedHashMap<>();

    TitlePickerPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, TitlePickerEventData.CODEC);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        appendTemplate(cmd);
        tileOf.clear();
        tiles = List.of();
        Plan plan = readPlan(ref);

        cmd.set("#Title.TextSpans", TitleText.picker("title"));
        cmd.set("#Subtitle.TextSpans", TitleText.picker("subtitle"));
        cmd.set("#Preview.TextSpans", preview());
        ZigRichButton.text(cmd, "#BackButton", TitleText.picker("back"));
        ZigRichButton.text(cmd, "#ShowNone", TitleText.picker("show_none"));
        SettingsUiUtil.bindNavigation(events);
        SettingsUiUtil.bindButton(events, "#ShowNone", "none");

        render(cmd, events, plan);
        renderToastInto(cmd);
    }

    private void appendTemplate(@Nonnull UICommandBuilder cmd) {
        try {
            TitlePickerPages.resolvedDeps().theme().appendThemed(cmd, PAGE_TEMPLATE);
            return;
        } catch (Throwable t) {
            SafeLog.warn("[title] a page theme failed, so the picker renders plain: " + t.getMessage());
        }
        cmd.append(PAGE_TEMPLATE);
    }

    /** The player's titles and what gives the rest; a read that fails lists nothing rather than failing the page. */
    @Nonnull
    private Plan readPlan(@Nonnull Ref<EntityStore> ref) {
        try {
            Ref<EntityStore> own = playerEntityRef(ref);
            Store<EntityStore> ownStore = own.getStore();
            return TitlePickerRows.plan(TitleUnlocks.unlocked(ownStore, own), TitleUnlocks.active(ownStore, own),
                    TitleConfig.getInstance(), sources(ownStore, own));
        } catch (Throwable t) {
            SafeLog.warn("[title] the picker could not read the player's titles: " + t.getMessage());
            return new Plan(List.of(), List.of());
        }
    }

    /**
     * What gives each title, as this player sees the achievement catalogue; no source at all when they have no
     * achievement record or the owner switched achievements off for them, since a line could not open the book then.
     */
    @Nonnull
    private static Function<String, TitleSources.Source> sources(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        try {
            Subject subject = ProgressionRuntime.subjects().achievementSubject(store, ref);
            if (subject == null || !ProgressionRuntime.systemEnabled(ProgressionSystem.ACHIEVEMENT, subject)) {
                return titleId -> null;
            }
            AchievementEngine engine = ProgressionRuntime.achievements();
            TitleSources index = TitleSources.of(engine.achievements());
            return titleId -> index.source(titleId, engine, subject);
        } catch (Throwable t) {
            SafeLog.warn("[title] the picker could not read what gives each title: " + t.getMessage());
            return titleId -> null;
        }
    }

    /** "You appear as: ..." through the very seam every menu names the player by. */
    @Nonnull
    private Message preview() {
        return TitleText.picker("preview",
                PlayerDisplayNames.displayName(playerRef.getUuid(), playerRef.getUsername()));
    }

    private void render(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull Plan plan) {
        if (plan.noneOnServer()) {
            cmd.set("#Body.Visible", false);
            cmd.set("#ShowNone.Visible", false);
            cmd.set("#Empty.Visible", true);
            EmptyStatePainter.paint(cmd, events, "#Empty",
                    new EmptyState(Picture.NONE, TitleText.picker("none_on_server"), null, null), null);
            return;
        }

        boolean anyEarned = !plan.earned().isEmpty();
        cmd.set("#NoneYet.Visible", plan.noneYet());
        if (plan.noneYet()) {
            cmd.set("#NoneYet.TextSpans", TitleText.picker("none_yet"));
        }
        cmd.set("#EarnedHead.Visible", anyEarned);
        cmd.set(EARNED + ".Visible", anyEarned);
        cmd.set("#ShowNone.Visible", anyEarned);
        if (anyEarned) {
            cmd.set("#EarnedHead.TextSpans", TitleText.picker("earned"));
            renderTiles(cmd, events, plan.earned());
        }

        boolean anyToEarn = !plan.notEarned().isEmpty();
        // With nothing earned, the none_yet line already heads the lines.
        cmd.set("#NotEarnedHead.Visible", anyToEarn && !plan.noneYet());
        cmd.set(NOT_EARNED + ".Visible", anyToEarn);
        if (anyToEarn) {
            cmd.set("#NotEarnedHead.TextSpans", TitleText.picker("not_earned"));
            renderLines(cmd, events, plan.notEarned());
        }
    }

    private void renderTiles(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull List<Tile> earned) {
        for (int i = 0; i < earned.size(); i++) {
            Tile tile = earned.get(i);
            cmd.append(EARNED, TILE_TEMPLATE);
            String sel = EARNED + "[" + i + "]";
            tileOf.put(tile.titleId(), sel);
            cmd.set(sel + " #Name.TextSpans", TitleText.nameOf(tile.titleId()));
            Message flavor = TitleText.flavorOf(tile.titleId());
            if (flavor != null) {
                cmd.set(sel + " #Flavor.TextSpans", flavor);
                cmd.set(sel + " #Flavor.Visible", true);
            }
            paintTile(cmd, sel, tile.shown());
            SettingsUiUtil.bindButton(events, sel + " #Show", "press", "Title", tile.titleId());
        }
        tiles = earned;
    }

    /** A tile as shown or not: its Memories face (complete or default) and its button's word and look. */
    private void paintTile(@Nonnull UICommandBuilder cmd, @Nonnull String tile, boolean shown) {
        cmd.set(tile + " #FaceShown.Visible", shown);
        cmd.set(tile + " #FaceRest.Visible", !shown);
        ZigRichButton.text(cmd, tile + " #Show", shown ? TitleText.picker("on") : TitleText.picker("off"));
        ZigStyles.apply(cmd, tile + " #Show.Style",
                shown ? ZigStyles.Name.BUTTON_PRIMARY : ZigStyles.Name.BUTTON_SECONDARY, playerRef);
    }

    private static void renderLines(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull List<Line> notEarned) {
        AchievementEngine engine = ProgressionRuntime.achievements();
        List<DetailLine> lines = new ArrayList<>(notEarned.size());
        for (Line line : notEarned) {
            lines.add(lineOf(line, engine));
        }
        DetailPainter.lines(cmd, events, NOT_EARNED, lines,
                line -> EventData.of("Action", "open").append("Achievement", line.selectId()));
    }

    /**
     * A title still to earn: its name in bold, then the {@code picker.from} line (the achievement that gives it and
     * the player's count toward it) over that achievement's picture, opening it; with no source, its name and
     * flavor, opening nothing.
     */
    @Nonnull
    private static DetailLine lineOf(@Nonnull Line line, @Nonnull AchievementEngine engine) {
        Message name = Msg.bold(TitleText.nameOf(line.titleId()));
        TitleSources.Source source = line.source();
        if (source == null) {
            Message flavor = TitleText.flavorOf(line.titleId());
            return DetailLine.of(Picture.NONE, flavor == null ? name
                    : Msg.join(name, Msg.raw(GAP), Msg.color(flavor, ZigTokens.INK_MUTED)));
        }
        Progress progress = source.progress();
        Message from = TitleText.picker("from", ProgressionTexts.titleOrUntitled(source.achievementId()),
                progress.current(), progress.total());
        return new DetailLine(pictureOf(engine.achievement(source.achievementId())),
                Msg.join(name, Msg.raw(GAP), Msg.color(from, ZigTokens.INK_MUTED)),
                null, null, Tick.NONE, source.achievementId(), false);
    }

    /** The achievement's picture: its own, else the one its folded file names, else none. */
    @Nonnull
    private static Picture pictureOf(@Nullable Achievement achievement) {
        if (achievement == null) {
            return Picture.NONE;
        }
        String icon = achievement.icon();
        if (icon == null || icon.isBlank()) {
            icon = ProgressionDefaults.achievementIcon(achievement.id());
        }
        return Picture.item(icon);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull TitlePickerEventData data) {
        try {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                answerNothing();
                return;
            }
            String action = data.action == null ? "" : data.action;
            switch (action) {
                case "press" -> handlePress(store, ref, data.title);
                case "none" -> handleNone(store, ref);
                case "open" -> openAchievement(store, ref, player, data.achievement);
                case "back" -> {
                    if (!TitlePickerPages.resolvedDeps().backGuarded(store, ref, player)) {
                        player.getPageManager().setPage(ref, store, Page.None);
                    }
                }
                case "close", "" -> player.getPageManager().setPage(ref, store, Page.None);
                default -> answerNothing();
            }
        } catch (Throwable t) {
            SafeLog.warn("[title] the picker could not answer '" + data.action + "': " + t.getMessage());
            answerNothing();
        }
    }

    /** Show the pressed title, or take it off when it is the one shown. */
    private void handlePress(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nullable String titleId) {
        String id = key(titleId);
        if (id == null || !tileOf.containsKey(id)) {
            answerNothing();
            return;
        }
        boolean takingOff = id.equals(key(TitleUnlocks.active(store, ref)));
        TitleUnlocks.Outcome outcome = takingOff
                ? TitleUnlocks.deactivate(store, ref, playerRef)
                : TitleUnlocks.activate(store, ref, playerRef, id);
        answer(store, ref, outcome, takingOff ? TitleText.picker("cleared")
                : TitleText.picker("shown", TitleText.nameOf(id)));
    }

    /** Take off whatever title is shown; with none shown there is nothing to do. */
    private void handleNone(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        if (key(TitleUnlocks.active(store, ref)) == null) {
            answerNothing();
            return;
        }
        answer(store, ref, TitleUnlocks.deactivate(store, ref, playerRef), TitleText.picker("cleared"));
    }

    /**
     * Repaint what a write moved (the tiles whose state changed, by the single-choice rule over what is shown now,
     * and the preview) with {@code done} as its toast; a write with no record behind it says so instead.
     */
    private void answer(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull TitleUnlocks.Outcome outcome, @Nonnull Message done) {
        UICommandBuilder cmd = new UICommandBuilder();
        if (outcome.changed()) {
            List<Tile> next = TitlePickerRows.showing(tiles, TitleUnlocks.active(store, ref));
            for (int i = 0; i < next.size(); i++) {
                Tile tile = next.get(i);
                String sel = tileOf.get(tile.titleId());
                if (sel != null && tile.shown() != tiles.get(i).shown()) {
                    paintTile(cmd, sel, tile.shown());
                }
            }
            tiles = next;
            cmd.set("#Preview.TextSpans", preview());
            showToast(ToastKind.SUCCESS, done);
        } else if (outcome == TitleUnlocks.Outcome.NO_RECORD) {
            showToast(ToastKind.ERROR, TitleText.picker("not_kept"));
        }
        this.sendUpdate(cmd, new UIEventBuilder(), false);
    }

    /** Open the objective book on the achievement a line comes from; a refusal leaves the picker as it was. */
    private void openAchievement(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player, @Nullable String achievementId) {
        String id = achievementId == null ? "" : achievementId.trim();
        if (id.isEmpty() || !ObjectiveBookPages.open(ObjectiveBookPage.TAB_ACHIEVEMENTS, id, store, ref, player)) {
            answerNothing();
        }
    }

    private void answerNothing() {
        this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }

    @Nullable
    private static String key(@Nullable String titleId) {
        return titleId == null || titleId.isBlank() ? null : titleId.trim().toLowerCase(Locale.ROOT);
    }
}
