package com.ziggfreed.common.npc;

import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.dialogue.DialogueQuestView;
import com.ziggfreed.common.dialogue.page.DialogueOpener;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.util.SafeLog;

/**
 * The two destinations every server has, whatever else is installed: a conversation, and what a
 * character has to offer.
 *
 * <p>They are seeded here rather than in the routing vocabulary itself because this is where their
 * behaviour lives - the library owns the dialogue engine, and the quest list rides a host seam a
 * consumer fills. The vocabulary stays domain-free and merely holds them.
 *
 * <pre>{@code
 * "Open": { "Type": "Dialogue", "Dialogue": "guide_intro" }
 * "Open": "Quests"                                  the character in front of the player
 * "Open": { "Type": "Quests", "Npc": "guide" }      a named character
 * }</pre>
 *
 * <p><b>Neither type ever names the character it is about when the moment already knows.</b> A
 * conversation opened at a placement is automatically WITH the character standing there: the header
 * name, {@code @self}, the talk credit and every quest-aware condition read that one declaration, so
 * an author writes a dialogue id and nothing else.
 */
public final class NpcDestinations {

    /** The owner every registration here is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    /** The {@code Type} id of the conversation destination. */
    public static final String DIALOGUE_TYPE = "Dialogue";

    /** The {@code Type} id of the quest-list destination. */
    public static final String QUESTS_TYPE = "Quests";

    private NpcDestinations() {
    }

    /**
     * Seed both types into the shared vocabulary. Called by this library's own plugin at
     * {@code setup()}, before assets load, because neither needs anything a consumer has to wire:
     * the conversation resolves its page dependencies at open time and the quest list asks whatever
     * hosts are registered by then.
     */
    public static void register() {
        Destinations.register(OWNER, DestinationType.of(
                DIALOGUE_TYPE, Dialogue.class, Dialogue.CODEC, NpcDestinations::openDialogue));
        Destinations.register(OWNER, DestinationType.of(
                QUESTS_TYPE, Quests.class, Quests.CODEC, NpcDestinations::openQuests));
        // What a conversation's Start quest row fires. The engine reads quest STATE and hands over a
        // quest id; turning that into "this character's list, with that quest called out" is this
        // layer's business, which is why the engine takes it back through a seam it never inspects.
        DialogueQuestView.install(NpcDestinations::routeQuest);
    }

    /**
     * The destination a quest row opens: what the row wrote, or this character's own list, either way
     * carrying the row's quest so the list opens on it.
     *
     * <p>A row pointing somewhere that is not a quest list is passed straight through - a board, a
     * shop, another mod's screen have no row to call out and nothing to be told about a quest.
     */
    @Nonnull
    private static Destination routeQuest(@Nullable Destination authored, @Nonnull String questId) {
        if (authored == null) {
            return Quests.of(null, questId);
        }
        if (authored instanceof Quests quests) {
            return Quests.of(quests.getNpc(), questId);
        }
        return authored;
    }

    // ==================== Dialogue ====================

    /** Open a conversation, with whichever character the moment is already about. */
    public static final class Dialogue extends Destination {

        @Nullable protected String dialogue;

        public static final BuilderCodec<Dialogue> CODEC = BuilderCodec.builder(Dialogue.class, Dialogue::new)
                .append(new KeyedCodec<>("Dialogue", Codec.STRING, false),
                        (d, v) -> d.dialogue = v, d -> d.dialogue)
                .documentation("The conversation to open, named by its file id. It is automatically a "
                        + "conversation WITH the character the player is standing at, so nothing here "
                        + "names one.").add()
                .build();

        public Dialogue() {
        }

        /** Java-side construction (the terse {@code Interact.Dialogue} spelling folds into this). */
        @Nonnull
        public static Dialogue of(@Nullable String dialogueId) {
            Dialogue d = new Dialogue();
            d.dialogue = dialogueId;
            return d;
        }

        @Nullable
        public String getDialogue() {
            return dialogue;
        }
    }

    /**
     * Put the conversation on the screen, TOLD who it is with.
     *
     * <p>The npc context is what makes a conversation NPC-aware: without it a {@code MarkTalked} beat
     * has nobody to credit, {@code @self} substitutes nothing, and every quest-aware condition asks
     * about a character with no name and is answered no.
     */
    private static boolean openDialogue(@Nonnull Dialogue destination, @Nonnull DestinationContext ctx) {
        String dialogueId = trimToNull(destination.getDialogue());
        if (dialogueId == null) {
            SafeLog.warn("[destination] a Dialogue destination names no conversation, so it opens nothing");
            return false;
        }
        // Through the opener, never straight to the page: a conversation whose Start routes to a quest
        // row's destination has to hand the screen over before the page is built.
        return DialogueOpener.open(ctx, dialogueId, ctx.npcId());
    }

    // ==================== Quests ====================

    /**
     * Show what a character has to offer. Author it as one word for the character the player is
     * standing at; name {@code Npc} only to point at somebody else.
     */
    public static final class Quests extends Destination {

        @Nullable protected String npc;
        @Nullable protected String highlight;

        public static final BuilderCodec<Quests> CODEC = BuilderCodec.builder(Quests.class, Quests::new)
                .append(new KeyedCodec<>("Npc", Codec.STRING, false),
                        (q, v) -> q.npc = v, q -> q.npc)
                .documentation("Whose list to show. Leave it out for the character the player is standing "
                        + "at, which is what you want almost always; name a character id to point at a "
                        + "different one.").add()
                .append(new KeyedCodec<>("Highlight", Codec.STRING, false),
                        (q, v) -> q.highlight = v, q -> q.highlight)
                .documentation("One quest to open the list on, so the player lands on the row they were just "
                        + "told about instead of hunting for it. A conversation's Start quest row fills this "
                        + "in on its own; author it only to point at a quest the row is not about.").add()
                .build();

        public Quests() {
        }

        /** Java-side construction; a null id means the character the moment is already about. */
        @Nonnull
        public static Quests of(@Nullable String npcId) {
            return of(npcId, null);
        }

        /** The same, opening the list on one quest. */
        @Nonnull
        public static Quests of(@Nullable String npcId, @Nullable String highlightQuestId) {
            Quests q = new Quests();
            q.npc = npcId;
            q.highlight = highlightQuestId;
            return q;
        }

        @Nullable
        public String getNpc() {
            return npc;
        }

        /** The quest the list should open on, or null to just show it. */
        @Nullable
        public String getHighlight() {
            return highlight;
        }
    }

    /**
     * Route the list to whichever quest UI is installed. With none registered nothing opens, which is
     * the correct answer on a server running no quest surface at all.
     */
    private static boolean openQuests(@Nonnull Quests destination, @Nonnull DestinationContext ctx) {
        String npcId = trimToNull(destination.getNpc());
        if (npcId == null) {
            npcId = trimToNull(ctx.npcId());
        }
        return NpcQuestListHosts.open(npcId, trimToNull(destination.getHighlight()),
                ctx.store(), ctx.pageAnchor(), ctx.player());
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
