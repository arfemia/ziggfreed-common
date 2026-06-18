package com.ziggfreed.common.dialogue.page;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
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
import com.ziggfreed.common.dialogue.i18n.RichText;

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
 */
public class DialoguePage extends InteractiveCustomUIPage<DialogueEventData> {

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
            commandBuilder.set("#NpcName.Text", name);
        }
        Message annotation = deps.headerAnnotation().annotationFor(contextNpcId, ref, store);
        if (annotation != null) {
            commandBuilder.set("#ActiveQuestHint.Visible", true);
            commandBuilder.set("#HintText.Text", annotation);
        }

        NpcDialogue dialogue = deps.dialogueResolver().apply(dialogueId);
        if (dialogue == null) {
            commandBuilder.set("#NodeText.Text", DialogueMessages.tr(i18n, "ui.dialogue.missing"));
            appendFarewellRow(commandBuilder, eventBuilder, i18n, 0);
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
            commandBuilder.set("#NodeText.Text", DialogueMessages.tr(i18n, "ui.dialogue.missing"));
            appendFarewellRow(commandBuilder, eventBuilder, i18n, 0);
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
            applyOptionStyle(commandBuilder, sel, engine.classifyOption(option));
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, sel + " #OptionBtn",
                    EventData.of("Action", "choose")
                            .append("Node", currentNodeId)
                            .append("Option", Integer.toString(i)),
                    false);
            row++;
        }
        appendFarewellRow(commandBuilder, eventBuilder, i18n, row);
    }

    private void appendFarewellRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                                   @Nonnull DialogueI18n i18n, int row) {
        cmd.append("#OptionsList", OPTION_ROW_TEMPLATE);
        String sel = "#OptionsList[" + row + "]";
        cmd.set(sel + " #OptionBtn.Text", DialogueMessages.tr(i18n, "ui.dialogue.farewell"));
        applyOptionStyle(cmd, sel, DialogueOptionStyle.FAREWELL);
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #OptionBtn",
                EventData.of("Action", "close"));
    }

    /**
     * Paint an appended option row to its semantic {@link DialogueOptionStyle}:
     * overwrite the shared option button's three per-state background tints and
     * reveal the matching pre-authored glyph (the glyph TEXTURE lives in markup;
     * Java only flips the chosen child's {@code Visible}).
     */
    private static void applyOptionStyle(@Nonnull UICommandBuilder cmd, @Nonnull String sel,
                                         @Nonnull DialogueOptionStyle style) {
        cmd.set(sel + " #OptionBtn.Style.Default.Background.Color", style.tintDefault());
        cmd.set(sel + " #OptionBtn.Style.Hovered.Background.Color", style.tintHovered());
        cmd.set(sel + " #OptionBtn.Style.Pressed.Background.Color", style.tintPressed());
        String iconId = style.iconElementId();
        if (iconId != null) {
            cmd.set(sel + " #OptIcon " + iconId + ".Visible", true);
        }
    }

    /**
     * Set the node-text Label. When the resolved (English) value carries inline rich-text
     * markup it is parsed into a {@link Message} span tree and set on {@code .TextSpans}
     * (the Label rich-text property); otherwise a plain client-resolved translation is set
     * on {@code .Text}. So markup-free dialogue stays per-locale; only a value that opts in
     * with markup is rendered rich (and single-locale, by the nature of inline markup).
     */
    private static void setNodeText(@Nonnull UICommandBuilder cmd, @Nonnull DialogueI18n i18n,
                                    @Nonnull NpcDialogue dialogue, @Nonnull String nodeId, @Nonnull DialogueNode node) {
        String conventionKey = "dialogue." + dialogue.getId() + "." + nodeId + ".text";
        String english = DialogueMessages.english(i18n, node.getTextKey(), conventionKey, node.getText());
        if (english != null && RichText.hasMarkup(english)) {
            cmd.set("#NodeText.TextSpans", RichText.parse(english));
            return;
        }
        Message text = DialogueMessages.resolve(i18n, node.getTextKey(), conventionKey, node.getText());
        cmd.set("#NodeText.Text", text != null ? text : DialogueMessages.raw(conventionKey));
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
