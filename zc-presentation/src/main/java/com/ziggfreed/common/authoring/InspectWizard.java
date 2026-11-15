package com.ziggfreed.common.authoring;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.match.MatchKey;
import com.ziggfreed.common.match.MatchSets;
import com.ziggfreed.common.ui.form.FieldSpec;

/**
 * The Inspect Tool's wizard, the kit's own proof: what a block, a creature or an item is called, in the words an
 * author writes in a match key. Three steps, one per kind of thing, each a field the world can fill (and a picker on
 * the item step); the preview names the id and every {@code BlockFamily:}, {@code NpcGroup:} and {@code ItemTag:}
 * key that matches it, read through the shared matcher ({@link MatchSets}), so the page and the engines always
 * agree. It saves nothing.
 */
final class InspectWizard implements AuthoringWizard {

    /** The tool's and the wizard's id. */
    static final String ID = "Inspect";

    static final String BLOCK = "block";
    static final String ROLE = "role";
    static final String ITEM = "item";

    private static final int BLOCK_STEP = 0;
    private static final int CREATURE_STEP = 1;
    private static final int ITEM_STEP = 2;

    InspectWizard(@Nonnull AuthoringUse use) {
        // Everything it shows is read from the session's values.
    }

    @Nonnull
    @Override
    public Message title() {
        return AuthoringText.line("inspect.title");
    }

    @Nonnull
    @Override
    public List<WizardStep> steps(@Nonnull WizardSession session) {
        return List.of(
                WizardStep.of(AuthoringText.key("inspect.step.block"),
                        WizardField.of(FieldSpec.note("blockNote", AuthoringText.key("inspect.note.block"))),
                        WizardField.of(FieldSpec.text(BLOCK, "Block", AuthoringText.key("inspect.field.block")))
                                .fromWorld(WorldPick::blockId)),
                WizardStep.of(AuthoringText.key("inspect.step.creature"),
                        WizardField.of(FieldSpec.note("roleNote", AuthoringText.key("inspect.note.creature"))),
                        WizardField.of(FieldSpec.text(ROLE, "Role", AuthoringText.key("inspect.field.role")))
                                .fromWorld(WorldPick::npcRole)),
                WizardStep.of(AuthoringText.key("inspect.step.item"),
                        WizardField.of(FieldSpec.note("itemNote", AuthoringText.key("inspect.note.item"))),
                        WizardField.picker(ITEM, "Item", AuthoringText.key("inspect.field.item"), Pickers.items())
                                .fromWorld(WorldPick::itemId)));
    }

    /** Fill every field the pick answers, and start on the step of what was clicked. */
    @Override
    public void begin(@Nonnull WizardSession session, @Nonnull AuthoringUse use) {
        WorldPick pick = use.pick();
        session.put(BLOCK, pick.blockId());
        session.put(ROLE, pick.npcRole());
        session.put(ITEM, pick.itemId());
        if (pick.droppedItemId() != null) {
            session.step(ITEM_STEP);
        } else if (pick.hasEntity()) {
            session.step(CREATURE_STEP);
        } else {
            session.step(BLOCK_STEP);
        }
    }

    @Nonnull
    @Override
    public List<Message> preview(@Nonnull WizardSession session) {
        List<Message> lines = new ArrayList<>();
        block(lines, session.value(BLOCK).trim());
        role(lines, session.value(ROLE).trim());
        item(lines, session.value(ITEM).trim());
        return lines;
    }

    private static void block(@Nonnull List<Message> lines, @Nonnull String id) {
        if (id.isEmpty()) {
            return;
        }
        if (!isBlock(id)) {
            lines.add(AuthoringText.line("inspect.preview.no_block", Msg.raw(id)));
            return;
        }
        lines.add(Msg.bold(AuthoringText.line("inspect.preview.block", Msg.raw(id))));
        lines.add(AuthoringText.line("inspect.preview.families", keys(MatchKey.Form.BLOCK_FAMILY,
                MatchSets.setsOf(MatchKey.Form.BLOCK_FAMILY, id))));
        lines.add(AuthoringText.line("inspect.preview.tags", keys(MatchKey.Form.ITEM_TAG,
                MatchSets.setsOf(MatchKey.Form.ITEM_TAG, id))));
    }

    private static void role(@Nonnull List<Message> lines, @Nonnull String role) {
        if (role.isEmpty()) {
            return;
        }
        lines.add(Msg.bold(AuthoringText.line("inspect.preview.role", Msg.raw(role))));
        lines.add(AuthoringText.line("inspect.preview.groups", keys(MatchKey.Form.NPC_GROUP,
                MatchSets.setsOf(MatchKey.Form.NPC_GROUP, role))));
    }

    private static void item(@Nonnull List<Message> lines, @Nonnull String id) {
        if (id.isEmpty()) {
            return;
        }
        if (!isItem(id)) {
            lines.add(AuthoringText.line("inspect.preview.no_item", Msg.raw(id)));
            return;
        }
        lines.add(Msg.bold(AuthoringText.line("inspect.preview.item", Msg.raw(id))));
        lines.add(AuthoringText.line("inspect.preview.tags", keys(MatchKey.Form.ITEM_TAG,
                MatchSets.setsOf(MatchKey.Form.ITEM_TAG, id))));
    }

    /** {@code BlockFamily:Rock, BlockFamily:Building}: the match keys an author copies; "none" for none. */
    @Nonnull
    private static Message keys(@Nonnull MatchKey.Form form, @Nonnull List<String> sets) {
        if (sets.isEmpty()) {
            return AuthoringText.line("inspect.preview.none");
        }
        List<String> keys = new ArrayList<>(sets.size());
        for (String set : sets) {
            keys.add(form.prefix() + set);
        }
        return Msg.raw(String.join(", ", keys));
    }

    private static boolean isBlock(@Nullable String id) {
        try {
            return id != null && BlockType.getAssetMap().getAsset(id) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isItem(@Nullable String id) {
        try {
            Item item = id == null ? null : Item.getAssetMap().getAsset(id);
            return item != null && item != Item.UNKNOWN;
        } catch (Throwable t) {
            return false;
        }
    }
}
