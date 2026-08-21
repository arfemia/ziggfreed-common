package com.ziggfreed.common.dialogue.page;

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
import com.ziggfreed.common.dialogue.DialogueOptionTheme;
import com.ziggfreed.common.dialogue.DialogueOptionThemeConfig;
import com.ziggfreed.common.dialogue.DialogueNode;
import com.ziggfreed.common.dialogue.NpcDialogue;
import com.ziggfreed.common.dialogue.DialogueChrome;
import com.ziggfreed.common.dialogue.DialogueHeaders;
import com.ziggfreed.common.dialogue.asset.DialogueAssetStore;
import com.ziggfreed.common.dialogue.i18n.DialogueMessages;
import com.ziggfreed.common.npc.NpcNames;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.UiText;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * The generic branching NPC dialogue page: a name header, an optional one-line
 * annotation, the localized node text, one button per option whose conditions
 * pass, and an implicit "Farewell" close row when none of the rendered options
 * already closes the dialogue themselves (see {@link DialogueOption#anyCloses};
 * an option filtered out by its own conditions never reaches the rendered list,
 * so a currently-hidden Close option still yields the implicit row).
 * Conditions re-evaluate on EVERY render (state changes show immediately) and
 * AGAIN on click before actions run.
 *
 * <p><b>Nothing about this page belongs to one mod.</b> It reads the process-wide dialogue
 * vocabulary ({@link DialogueEngine#shared()}), the one conversation store, the header sources a
 * conversation names, and the name the character's own role authored. Authored keys resolve in the
 * namespace of whichever mod ships them and every mod's payload is reachable through
 * {@link com.ziggfreed.common.dialogue.DialoguePayloads}, so a conversation reads the same whoever
 * opened it - which is the whole point: a server running two talking mods has one dialogue system,
 * not one per mod with the last one to start up deciding for everybody.
 *
 * <p>Every handler exit path sends a response (openCustomPage / setPage) - the page
 * manager does not wrap build/handleDataEvent and the client spins forever
 * otherwise. The two {@code .ui} templates ship once in ziggfreed-common
 * ({@code Pages/ZigDialoguePage.ui}, {@code Pages/ZigDialogueOptionRow.ui}) and
 * resolve client-side across the merged asset tree.
 *
 * <p><b>The render reads the PLAYER, whatever ref it was opened on.</b> An NPC action opens a page
 * on the NPC's own ref, so {@code build}'s {@code ref} argument is not reliably the player;
 * {@code build} therefore resolves the player from the {@link PlayerRef} the page holds, the same
 * way first-party {@code BarterPage} does. Click renders come back through the player's ref already
 * (the client's page event names the player), so the two paths agree.
 *
 * <p>Extends {@link ToastablePage} so an in-menu toast can float over the conversation. The page
 * raises none of its own: a lifecycle notice is a feedback moment, and a moment draws into whatever
 * page is open, so finishing a quest mid-conversation shows its toast here without the page knowing
 * a quest exists.
 */
public class DialoguePage extends ToastablePage<DialogueEventData> {

    private static final String PAGE_TEMPLATE = "Pages/ZigDialoguePage.ui";
    private static final String OPTION_ROW_TEMPLATE = "Pages/ZigDialogueOptionRow.ui";

    private final String dialogueId;
    @Nullable private final String contextNpcId;
    @Nullable private final Ref<EntityStore> npcRef;
    @Nullable private String currentNodeId;

    /**
     * The entry-level {@code Once} this conversation is holding open, written only once the player
     * finishes the beat (chooses any option on the entry node, the implicit Farewell included).
     * Dismissing the page instead leaves it unwritten, so the beat shows again next time.
     */
    @Nullable private String pendingEntryOnceKey;

    /**
     * The beat {@link DialogueOpener} already worked out, used for the FIRST render and dropped after
     * it. It is carried rather than re-derived so the ladder is walked once per open, which is what
     * keeps a {@code Pick} beat from drawing a different variant than the one the open decided on.
     */
    @Nullable private DialogueEngine.EntryResolution preResolved;

    public DialoguePage(@Nonnull PlayerRef playerRef, @Nonnull String dialogueId,
                        @Nullable String contextNpcId) {
        this(playerRef, dialogueId, contextNpcId, null, null);
    }

    /**
     * @param preResolved the entry {@link DialogueOpener} resolved for this open, or null to let the
     *        page work it out itself. Prefer opening through {@code DialogueOpener}: a conversation
     *        whose {@code Start} routes elsewhere cannot hand the screen over from inside a page.
     */
    public DialoguePage(@Nonnull PlayerRef playerRef, @Nonnull String dialogueId,
                        @Nullable String contextNpcId,
                        @Nullable DialogueEngine.EntryResolution preResolved) {
        this(playerRef, dialogueId, contextNpcId, null, preResolved);
    }

    /**
     * @param npcRef the live NPC entity the player is standing at, when there is one, so the
     *        header name can be answered from the live role ({@link com.ziggfreed.common.npc.NpcNames}'s
     *        live-entity form) instead of a static walk. Null for a conversation opened with no
     *        entity in hand (e.g. a Java call site that only has an id).
     * @param preResolved the entry {@link DialogueOpener} resolved for this open, or null to let the
     *        page work it out itself. Prefer opening through {@code DialogueOpener}: a conversation
     *        whose {@code Start} routes elsewhere cannot hand the screen over from inside a page.
     */
    public DialoguePage(@Nonnull PlayerRef playerRef, @Nonnull String dialogueId,
                        @Nullable String contextNpcId, @Nullable Ref<EntityStore> npcRef,
                        @Nullable DialogueEngine.EntryResolution preResolved) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, DialogueEventData.CODEC);
        this.dialogueId = dialogueId;
        this.contextNpcId = (contextNpcId == null || contextNpcId.isBlank()) ? null : contextNpcId;
        this.npcRef = npcRef;
        this.preResolved = preResolved;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder commandBuilder, UIEventBuilder eventBuilder,
                      Store<EntityStore> store) {
        commandBuilder.append(PAGE_TEMPLATE);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                EventData.of("Action", "close"));

        DialogueEngine engine = DialogueEngine.shared();

        // WHO this render is about: the PLAYER, never whoever opened the page. The ref argument is
        // simply what the opener handed the page manager, and every NPC-action route hands it the
        // NPC (first-party ActionOpenBarterShop does exactly that). Reading player state off it
        // would evaluate this first render against an entity that has none of the player's
        // components - quest lines read as not started, factor gates fail closed - and the options
        // that should be there would appear only after a click re-rendered through the player's own
        // ref. So resolve the player from the PlayerRef this page was built with, which is what
        // first-party BarterPage.build does for the same reason.
        Ref<EntityStore> subject = playerRef.getReference();
        Player player = subject == null ? null
                : (Player) store.getComponent(subject, Player.getComponentType());
        if (player == null) {
            subject = ref;
            player = (Player) store.getComponent(ref, Player.getComponentType());
        }
        if (player == null) {
            // Nobody to read and nobody to talk to. Still SEND something: the page manager does not
            // wrap build(), so a silent return leaves the client loading forever.
            SafeLog.warn("[dialogue] '" + dialogueId + "' opened with no reachable player - showing nothing");
            commandBuilder.set("#NodeText.TextSpans", missingText(null));
            appendFarewellRow(commandBuilder, eventBuilder, null, 0, null);
            renderToastInto(commandBuilder);
            return;
        }

        // The name header + optional annotation, both consumer-supplied (default: none). The
        // three-arg form reaches the live-entity answer when this page was opened with an NPC ref
        // in hand (world thread: it reads a component); it falls back to the id-only static walk
        // on a provider that only implements the single-arg form, or when there is no live ref.
        Message name = NpcNames.nameFor(contextNpcId, npcRef, store);
        if (name != null) {
            // A Label's Message goes on .TextSpans, never .Text: .Text is a client-side String sink
            // that resolves a translation and nothing else, so a raw or composed Message there fails
            // the set command ("couldn't set value. Selector: #NpcName.Text") and disconnects the
            // player. TextSpans takes a whole message tree and has none of that. The option rows
            // below have no TextSpans to use, so they go through UiText, which enforces the same
            // rule the other way round. Matches #NodeText.TextSpans; see hytale-rich-text-textspans.
            commandBuilder.set("#NpcName.TextSpans", name);
        }
        NpcDialogue dialogue = DialogueAssetStore.getInstance().dialogue(dialogueId);
        if (dialogue != null) {
            // The note under the name comes from the sources the CONVERSATION names, so a character
            // who never talks about quests shows none and no mod decides that for another's screen.
            Message annotation = DialogueHeaders.lineFor(dialogue, contextNpcId, subject, store);
            if (annotation != null) {
                commandBuilder.set("#ActiveQuestHint.Visible", true);
                commandBuilder.set("#HintText.TextSpans", annotation);
            }
        }
        if (dialogue == null) {
            commandBuilder.set("#NodeText.TextSpans", missingText(dialogue));
            appendFarewellRow(commandBuilder, eventBuilder, dialogue, 0, null);
            renderToastInto(commandBuilder);
            return;
        }

        // One eval context for the whole render (conditions ignore node/option, so the placeholders are fine).
        DialogueExecContext ctx = context(dialogue, currentNodeId != null ? currentNodeId : "", -1,
                subject, store, player);

        if (currentNodeId == null || dialogue.getNode(currentNodeId) == null) {
            DialogueEngine.EntryResolution entry = preResolved != null ? preResolved
                    : engine.resolveEntry(dialogue, ctx);
            preResolved = null;
            if (entry.routes()) {
                // The conversation should never have opened: its Start routes somewhere else, and a
                // page cannot hand the screen over from inside its own build. Whoever opened it went
                // around DialogueOpener, so say which fix is wanted rather than showing a dead screen.
                SafeLog.warn("[dialogue] '" + dialogueId + "' routes elsewhere at Start, but it was opened"
                        + " as a page - open conversations through DialogueOpener so the routed screen"
                        + " can take over");
            }
            currentNodeId = entry.nodeId();
            pendingEntryOnceKey = entry.onceKey();
        }
        String nodeId = currentNodeId;
        DialogueNode node = dialogue.getNode(nodeId);
        if (node == null || nodeId == null) {
            commandBuilder.set("#NodeText.TextSpans", missingText(dialogue));
            appendFarewellRow(commandBuilder, eventBuilder, dialogue, 0, null);
            renderToastInto(commandBuilder);
            return;
        }

        setNodeText(commandBuilder, dialogue, nodeId, node);

        List<DialogueOption> options = node.getOptions();
        int row = 0;
        List<DialogueOption> renderedOptions = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            DialogueOption option = options.get(i);
            if (!engine.optionAvailable(dialogue, nodeId, option, ctx)) {
                continue;
            }
            commandBuilder.append("#OptionsList", OPTION_ROW_TEMPLATE);
            String sel = "#OptionsList[" + row + "]";
            UiText.setText(commandBuilder, sel + " #OptionBtn.Text",
                    resolveOptionLabel(dialogue, nodeId, i, option));
            DialogueOptionStyle style = engine.classifyOption(option);
            applyOptionLook(commandBuilder, sel, style, themeFor(style), option.getPresentation());
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, sel + " #OptionBtn",
                    EventData.of("Action", "choose")
                            .append("Node", nodeId)
                            .append("Option", Integer.toString(i)),
                    false);
            renderedOptions.add(option);
            row++;
        }
        if (!DialogueOption.anyCloses(renderedOptions)) {
            appendFarewellRow(commandBuilder, eventBuilder, dialogue, row, nodeId);
        }
        // LAST: the toast overlay draws over the dialogue; inert unless a completion toast is live.
        renderToastInto(commandBuilder);
    }

    /**
     * Append the implicit exit row. It reports {@code farewell} rather than {@code close} because
     * taking it is a CHOICE that finishes the beat (it spends a first-visit {@code Once}), while
     * the header close button and Escape are an abandon that spends nothing.
     */
    private void appendFarewellRow(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
                                   @Nullable NpcDialogue dialogue, int row, @Nullable String nodeId) {
        cmd.append("#OptionsList", OPTION_ROW_TEMPLATE);
        String sel = "#OptionsList[" + row + "]";
        UiText.setText(cmd, sel + " #OptionBtn.Text", farewellText(dialogue));
        applyOptionLook(cmd, sel, DialogueOptionStyle.FAREWELL, themeFor(DialogueOptionStyle.FAREWELL), null);
        EventData data = EventData.of("Action", "farewell");
        if (nodeId != null) {
            data = data.append("Node", nodeId);
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #OptionBtn", data);
    }

    /**
     * The per-player context every render and click is evaluated in. It packs NO payload of its own:
     * each mod says how to build its own through
     * {@link com.ziggfreed.common.dialogue.DialoguePayloads}, and the context asks for one by class
     * when an action or condition reaches for it. So a line belonging to any mod on the server
     * answers correctly here, whoever's character the player happens to be standing at.
     */
    @Nonnull
    private DialogueExecContext context(@Nonnull NpcDialogue dialogue, @Nonnull String nodeId,
                                        int optionIndex, @Nonnull Ref<EntityStore> ref,
                                        @Nonnull Store<EntityStore> store, @Nonnull Player player) {
        return new SimpleDialogueExecContext(store, ref, player, contextNpcId, null,
                dialogue, nodeId, optionIndex);
    }

    /** The exit row's words: this conversation's own when it authored some, else the library's. */
    @Nonnull
    private static Message farewellText(@Nullable NpcDialogue dialogue) {
        DialogueChrome chrome = dialogue == null ? null : dialogue.getChrome();
        return DialogueMessages.page(chrome == null ? null : chrome.getFarewellKey(), "farewell");
    }

    /** The nothing-to-say line, same precedence as {@link #farewellText}. */
    @Nonnull
    private static Message missingText(@Nullable NpcDialogue dialogue) {
        DialogueChrome chrome = dialogue == null ? null : dialogue.getChrome();
        return DialogueMessages.page(chrome == null ? null : chrome.getMissingKey(), "missing");
    }

    /** The data-driven {@link DialogueOptionTheme} for a style kind, or null when no layer authored it. */
    @Nullable
    private static DialogueOptionTheme themeFor(@Nonnull DialogueOptionStyle style) {
        return DialogueOptionThemeConfig.getInstance().forStyle(style);
    }

    /**
     * Paint an appended option row from its semantic {@link DialogueOptionStyle}, resolved through
     * the data-driven {@link DialogueOptionTheme} (authored in {@code DialogueOptionTheme/*.json},
     * folded {@code defaults < pack < owner}), then let an explicit per-option
     * {@link DialogueOption.Presentation} override the colour and/or icon. The {@code style} enum is
     * only the per-leaf fail-closed fallback (used when a theme leaf, or the whole kind, is absent).
     *
     * <p>Colour precedence per state: {@code Presentation.Color} (hover/press derived) &gt; the
     * theme's {@code Color}/{@code HoverColor}/{@code PressColor} (a missing hover/press derives from
     * {@code Color}) &gt; the enum tints. Icon: {@code Presentation.Icon.Item} reveals
     * {@code #OptIconItem} with the game item icon; else a glyph token from the Presentation, else the
     * theme's {@code Glyph}, else the enum glyph. Glyph TEXTURES live in markup - Java only flips
     * {@code Visible} / sets {@code Slots}.
     */
    private static void applyOptionLook(@Nonnull UICommandBuilder cmd, @Nonnull String sel,
                                        @Nonnull DialogueOptionStyle style,
                                        @Nullable DialogueOptionTheme theme,
                                        @Nullable DialogueOption.Presentation presentation) {
        // Base colours from the theme per leaf, falling back to the enum. A theme that authors only
        // Color derives hover/press from it; a theme with no Color at all uses the enum tints.
        String themeColor = theme != null ? theme.color() : null;
        String def = themeColor != null ? themeColor : style.tintDefault();
        String hov = theme != null && theme.hover() != null ? theme.hover()
                : (themeColor != null ? shade(themeColor, 0.18, true) : style.tintHovered());
        String prs = theme != null && theme.press() != null ? theme.press()
                : (themeColor != null ? shade(themeColor, 0.15, false) : style.tintPressed());

        // A per-option Presentation.Color wins over the kind theme (hover/press re-derived from it).
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
        // Glyph precedence: Presentation.Icon.Glyph > the theme's Glyph token > the enum's element id.
        String presGlyph = icon != null ? icon.getGlyph() : null;
        String themeGlyph = theme != null ? theme.glyphToken() : null;
        String glyphElem;
        if (presGlyph != null && !presGlyph.isBlank()) {
            glyphElem = glyphElementId(presGlyph);
        } else if (themeGlyph != null && !themeGlyph.isBlank()) {
            glyphElem = glyphElementId(themeGlyph);
        } else {
            glyphElem = style.iconElementId();
        }
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
    private static void setNodeText(@Nonnull UICommandBuilder cmd,
                                    @Nonnull NpcDialogue dialogue, @Nonnull String nodeId, @Nonnull DialogueNode node) {
        String conventionKey = "dialogue." + dialogue.getId() + "." + nodeId + ".text";
        Message text = DialogueMessages.resolve(node.getTextKey(), conventionKey, node.getText());
        cmd.set("#NodeText.TextSpans", text != null ? text : DialogueMessages.raw(conventionKey));
    }

    @Nonnull
    private static Message resolveOptionLabel(@Nonnull NpcDialogue dialogue,
                                              @Nonnull String nodeId, int optionIndex, @Nonnull DialogueOption option) {
        String conventionKey = "dialogue." + dialogue.getId() + "." + nodeId + ".opt." + optionIndex;
        Message label = DialogueMessages.resolve(option.getLabelKey(), conventionKey, option.getLabel());
        return label != null ? label : DialogueMessages.raw(conventionKey);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull DialogueEventData data) {
        Player player = (Player) store.getComponent(ref, Player.getComponentType());

        boolean farewell = "farewell".equals(data.action);
        if (farewell) {
            // The implicit exit row: a deliberate end to the beat, so it finishes any first-visit
            // Once the same way an authored option does before closing.
            consumeFarewell(ref, store, player, data.node);
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }
        if (!"choose".equals(data.action) || data.node == null || data.option == null) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }

        NpcDialogue dialogue = DialogueAssetStore.getInstance().dialogue(dialogueId);
        DialogueNode node = dialogue != null ? dialogue.getNode(data.node) : null;
        int optionIndex = parseIndex(data.option);
        if (dialogue == null || node == null
                || optionIndex < 0 || optionIndex >= node.getOptions().size()) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }

        DialogueOption option = node.getOptions().get(optionIndex);
        DialogueExecContext ctx = context(dialogue, data.node, optionIndex, ref, store, player);

        // Re-check availability on click: state may have moved since the render, and a spent
        // one-time option must never run twice.
        if (!DialogueEngine.shared().optionAvailable(dialogue, data.node, option, ctx)) {
            player.getPageManager().openCustomPage(ref, store, this);
            return;
        }

        DialogueActionExecutor.Outcome outcome =
                DialogueEngine.shared().executor().execute(option.getActions(), ctx);
        // The beat is done: spend the entry's first-visit Once and the option's own.
        DialogueEngine.shared().consumeOnce(pendingEntryOnceKey, dialogue, data.node, option, ctx);
        pendingEntryOnceKey = null;

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
        // Nothing to raise here: whatever just finished announced its own feedback moment, and a
        // moment draws into the page that is open, so its toast is already on its way to this screen.
        player.getPageManager().openCustomPage(ref, store, this);
    }

    /**
     * Spend a pending first-visit {@code Once} when the player leaves through the implicit
     * Farewell row. No option ran, so only the entry's own key is at stake.
     */
    private void consumeFarewell(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                 @Nonnull Player player, @Nullable String nodeId) {
        if (pendingEntryOnceKey == null || nodeId == null) {
            pendingEntryOnceKey = null;
            return;
        }
        NpcDialogue dialogue = DialogueAssetStore.getInstance().dialogue(dialogueId);
        if (dialogue != null) {
            DialogueExecContext ctx = context(dialogue, nodeId, -1, ref, store, player);
            DialogueEngine.shared().consumeOnce(pendingEntryOnceKey, dialogue, nodeId, null, ctx);
        }
        pendingEntryOnceKey = null;
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
