package com.ziggfreed.common.dialogue.page;

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
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.dialogue.DialogueActionExecutor;
import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.DialogueExecContext;
import com.ziggfreed.common.dialogue.DialogueOption;
import com.ziggfreed.common.dialogue.DialogueOptionStyle;
import com.ziggfreed.common.dialogue.DialogueNode;
import com.ziggfreed.common.dialogue.NpcDialogue;
import com.ziggfreed.common.dialogue.i18n.DialogueI18n;
import com.ziggfreed.common.dialogue.i18n.DialogueMessages;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;

/**
 * The generic branching NPC dialogue page: a name header, an optional one-line
 * annotation, the localized node text, one button per option whose conditions
 * pass, and an implicit "Farewell" close row. Conditions re-evaluate on EVERY
 * render (state changes show immediately) and AGAIN on click before actions run.
 * All consumer policy (per-player state, the npc name, the annotation, the router,
 * the i18n namespace) is supplied through {@link DialoguePageDeps}; the page itself
 * is mod-agnostic.
 *
 * <p>Every handler exit path sends a response (openCustomPage / setPage) - the page
 * manager does not wrap build/handleDataEvent and the client spins forever
 * otherwise. The two {@code .ui} templates ship once in ziggfreed-common
 * ({@code Pages/ZigDialoguePage.ui}, {@code Pages/ZigDialogueOptionRow.ui}) and
 * resolve client-side across the merged asset tree.
 *
 * <p>Extends {@link ToastablePage} so a dialogue can float an in-menu completion toast: when an
 * action reports a just-completed thing ({@code Outcome.completedId}) and the consumer wired
 * a {@code completionToast} into {@link DialoguePageDeps}, the page shows it before reopening.
 * The toast overlay is inert (no toast configured / reported) for every other dialogue.
 */
public class DialoguePage extends ToastablePage<DialogueEventData> {

    private static final String PAGE_TEMPLATE = "Pages/ZigDialoguePage.ui";
    private static final String OPTION_ROW_TEMPLATE = "Pages/ZigDialogueOptionRow.ui";

    private final String dialogueId;
    @Nullable private final String contextNpcId;
    private final DialoguePageDeps deps;
    @Nullable private String currentNodeId;

    public DialoguePage(@Nonnull PlayerRef playerRef, @Nonnull String dialogueId,
                        @Nullable String contextNpcId, @Nonnull DialoguePageDeps deps) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, DialogueEventData.CODEC);
        this.dialogueId = dialogueId;
        this.contextNpcId = (contextNpcId == null || contextNpcId.isBlank()) ? null : contextNpcId;
        this.deps = deps;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder commandBuilder, UIEventBuilder eventBuilder,
                      Store<EntityStore> store) {
        commandBuilder.append(PAGE_TEMPLATE);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                EventData.of("Action", "close"));

        DialogueEngine engine = deps.engine();
        DialogueI18n i18n = deps.i18n();
        Player player = (Player) store.getComponent(ref, Player.getComponentType());

        // The name header + optional annotation, both consumer-supplied (default: none).
        Message name = deps.npcName().nameFor(contextNpcId);
        if (name != null) {
            // A Message MUST go on a Label's .TextSpans, NOT .Text (a String sink): a Message on
            // .Text fails the client's set command ("couldn't set value. Selector: #NpcName.Text")
            // and disconnects. Matches #NodeText.TextSpans below. See hytale-rich-text-textspans.
            commandBuilder.set("#NpcName.TextSpans", name);
        }
        Message annotation = deps.headerAnnotation().annotationFor(contextNpcId, ref, store);
        if (annotation != null) {
            commandBuilder.set("#ActiveQuestHint.Visible", true);
            commandBuilder.set("#HintText.TextSpans", annotation);
        }

        NpcDialogue dialogue = deps.dialogueResolver().apply(dialogueId);
        if (dialogue == null) {
            commandBuilder.set("#NodeText.TextSpans", DialogueMessages.tr(i18n, "ui.dialogue.missing"));
            appendFarewellRow(commandBuilder, eventBuilder, i18n, 0);
            renderToastInto(commandBuilder);
            return;
        }

        // One eval context for the whole render (conditions ignore node/option, so the placeholders are fine).
        DialogueExecContext ctx = deps.contextFactory().create(
                dialogue, currentNodeId != null ? currentNodeId : "", -1,
                contextNpcId, ref, store, playerRef, player);

        if (currentNodeId == null || dialogue.getNode(currentNodeId) == null) {
            currentNodeId = engine.resolveEntryNodeId(dialogue, ctx);
        }
        DialogueNode node = dialogue.getNode(currentNodeId);
        if (node == null) {
            commandBuilder.set("#NodeText.TextSpans", DialogueMessages.tr(i18n, "ui.dialogue.missing"));
            appendFarewellRow(commandBuilder, eventBuilder, i18n, 0);
            renderToastInto(commandBuilder);
            return;
        }

        setNodeText(commandBuilder, i18n, dialogue, currentNodeId, node);

        List<DialogueOption> options = node.getOptions();
        int row = 0;
        for (int i = 0; i < options.size(); i++) {
            DialogueOption option = options.get(i);
            if (option.hasConditions() && !engine.conditionsPass(option.getConditions(), ctx)) {
                continue;
            }
            commandBuilder.append("#OptionsList", OPTION_ROW_TEMPLATE);
            String sel = "#OptionsList[" + row + "]";
            commandBuilder.set(sel + " #OptionBtn.Text", resolveOptionLabel(i18n, dialogue, currentNodeId, i, option));
            applyOptionLook(commandBuilder, sel, engine.classifyOption(option), option.getPresentation());
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, sel + " #OptionBtn",
                    EventData.of("Action", "choose")
                            .append("Node", currentNodeId)
                            .append("Option", Integer.toString(i)),
                    false);
            row++;
        }
        appendFarewellRow(commandBuilder, eventBuilder, i18n, row);
        // LAST: the toast overlay draws over the dialogue; inert unless a completion toast is live.
        renderToastInto(commandBuilder);
    }

    private void appendFarewellRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                                   @Nonnull DialogueI18n i18n, int row) {
        cmd.append("#OptionsList", OPTION_ROW_TEMPLATE);
        String sel = "#OptionsList[" + row + "]";
        cmd.set(sel + " #OptionBtn.Text", DialogueMessages.tr(i18n, "ui.dialogue.farewell"));
        applyOptionLook(cmd, sel, DialogueOptionStyle.FAREWELL, null);
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #OptionBtn",
                EventData.of("Action", "close"));
    }

    /**
     * Paint an appended option row: start from its semantic {@link DialogueOptionStyle}
     * (derived from the option's decisive action), then let an explicit per-option
     * {@link DialogueOption.Presentation} override the colour and/or icon.
     *
     * <p>Colour: a {@code Presentation.Color} hex replaces the style's three button-state
     * tints (hover lightened, press darkened from it); otherwise the style's tints apply.
     * Icon: {@code Presentation.Icon.Item} reveals {@code #OptIconItem} and pushes the game
     * item's icon as an {@link ItemGridSlot} (the RewardRow mechanism); else
     * {@code Presentation.Icon.Glyph} reveals the named pre-authored glyph; else the style's
     * glyph. Glyph TEXTURES live in markup - Java only flips {@code Visible} / sets {@code Slots}.
     */
    private static void applyOptionLook(@Nonnull UICommandBuilder cmd, @Nonnull String sel,
                                        @Nonnull DialogueOptionStyle style,
                                        @Nullable DialogueOption.Presentation presentation) {
        String def = style.tintDefault();
        String hov = style.tintHovered();
        String prs = style.tintPressed();
        String color = presentation != null ? presentation.getColor() : null;
        if (color != null && UiRetint.isSixDigitHex(color)) {
            def = color;
            hov = shade(color, 0.18, true);
            prs = shade(color, 0.15, false);
        }
        UiRetint.retintButtonStates(cmd, sel + " #OptionBtn", def, hov, prs);

        DialogueOption.Icon icon = presentation != null ? presentation.getIcon() : null;
        String itemId = icon != null ? icon.getItem() : null;
        if (itemId != null && !itemId.isBlank()) {
            cmd.set(sel + " #OptIcon #OptIconItem.Visible", true);
            cmd.set(sel + " #OptIcon #OptIconItem.Slots", List.of(new ItemGridSlot(new ItemStack(itemId, 1))));
            return;
        }
        String glyphToken = icon != null ? icon.getGlyph() : null;
        String glyphElem = glyphToken != null ? glyphElementId(glyphToken) : style.iconElementId();
        if (glyphElem != null) {
            cmd.set(sel + " #OptIcon " + glyphElem + ".Visible", true);
        }
    }

    /** Map a {@code Presentation.Icon.Glyph} token to a pre-authored glyph element id, or null. */
    @Nullable
    private static String glyphElementId(@Nonnull String token) {
        switch (token.toLowerCase(Locale.ROOT)) {
            case "accept": return "#IcoAccept";
            case "turnin":
            case "turn_in": return "#IcoTurnIn";
            case "continue": return "#IcoContinue";
            case "open": return "#IcoOpen";
            case "farewell":
            case "close": return "#IcoFarewell";
            default: return null;
        }
    }

    /** Lighten (toward white) or darken (toward black) a {@code #rrggbb} hex by a factor in [0,1]. */
    @Nonnull
    private static String shade(@Nonnull String hex, double factor, boolean lighten) {
        try {
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            r = shadeChannel(r, factor, lighten);
            g = shadeChannel(g, factor, lighten);
            b = shadeChannel(b, factor, lighten);
            return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b);
        } catch (RuntimeException e) {
            return hex;
        }
    }

    private static int shadeChannel(int c, double factor, boolean lighten) {
        double v = lighten ? c + (255 - c) * factor : c * (1 - factor);
        return Math.max(0, Math.min(255, (int) Math.round(v)));
    }

    /**
     * Set the node-text Label via {@code TextSpans} (NOT {@code .Text}). The resolved
     * translation may carry Hytale's native inline markup ({@code <color is="#hex">},
     * {@code <b>}, {@code <i>}), which the client parses PER-LOCALE on a Label's
     * {@code TextSpans} - the same mechanism vanilla item descriptions / customUI strings
     * use. Plain (markup-free) text renders unchanged. Option rows are {@code TextButton}s
     * (no {@code TextSpans}), so their colour comes from {@link DialogueOptionStyle}, not markup.
     */
    private static void setNodeText(@Nonnull UICommandBuilder cmd, @Nonnull DialogueI18n i18n,
                                    @Nonnull NpcDialogue dialogue, @Nonnull String nodeId, @Nonnull DialogueNode node) {
        String conventionKey = "dialogue." + dialogue.getId() + "." + nodeId + ".text";
        Message text = DialogueMessages.resolve(i18n, node.getTextKey(), conventionKey, node.getText());
        cmd.set("#NodeText.TextSpans", text != null ? text : DialogueMessages.raw(conventionKey));
    }

    @Nonnull
    private static Message resolveOptionLabel(@Nonnull DialogueI18n i18n, @Nonnull NpcDialogue dialogue,
                                              @Nonnull String nodeId, int optionIndex, @Nonnull DialogueOption option) {
        String conventionKey = "dialogue." + dialogue.getId() + "." + nodeId + ".opt." + optionIndex;
        Message label = DialogueMessages.resolve(i18n, option.getLabelKey(), conventionKey, option.getLabel());
        return label != null ? label : DialogueMessages.raw(conventionKey);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull DialogueEventData data) {
        Player player = (Player) store.getComponent(ref, Player.getComponentType());

        if (!"choose".equals(data.action) || data.node == null || data.option == null) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }

        NpcDialogue dialogue = deps.dialogueResolver().apply(dialogueId);
        DialogueNode node = dialogue != null ? dialogue.getNode(data.node) : null;
        int optionIndex = parseIndex(data.option);
        if (dialogue == null || node == null
                || optionIndex < 0 || optionIndex >= node.getOptions().size()) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }

        DialogueOption option = node.getOptions().get(optionIndex);
        DialogueExecContext ctx = deps.contextFactory().create(
                dialogue, data.node, optionIndex, contextNpcId, ref, store, playerRef, player);

        // Re-check conditions on click: state may have moved since the render.
        if (option.hasConditions() && !deps.engine().conditionsPass(option.getConditions(), ctx)) {
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }

        DialogueActionExecutor.Outcome outcome = deps.engine().executor().execute(option.getActions(), ctx);

        if (outcome.openedOtherPage()) {
            return;
        }
        if (outcome.close()) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }
        if (outcome.gotoNode() != null && dialogue.getNode(outcome.gotoNode()) != null) {
            currentNodeId = outcome.gotoNode();
        }
        // A handler reported a just-completed thing: float the consumer's completion toast over the
        // dialogue (it paints on the reopen below). Inert when no toast is wired or none applies.
        if (outcome.completedId() != null) {
            ToastSpec spec = deps.completionToast(outcome.completedId());
            if (spec != null) {
                showToast(spec);
            }
        }
        player.getPageManager().openCustomPage(ref, store, this);
    }

    private static int parseIndex(@Nullable String raw) {
        if (raw == null) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
