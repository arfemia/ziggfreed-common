package com.ziggfreed.common.almanac.asset;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.ModGates;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.ui.page.LinkAsset;

/**
 * The items a player can find this season, as a grid in a season page's {@code Sections}. Each item
 * shows its own picture once the player has obtained it once (any route into any of their inventory
 * sections, recorded for good), or while it is not {@code Hidden}; a hidden one shows a '?' until then.
 *
 * <p>A slot may carry its own {@code Requires}, in the block every store writes (zc-core's {@code GateSpec},
 * its mod gate read through {@code GateSpec.missingMod}): where its plain {@code hytale:mod_installed}
 * condition ({@code Min: 1}) names a mod that reads a definite 0, the slot is not on this server at all
 * ({@link #slots()} leaves it out, so the grid, the audit and the owned marks never see it, and nothing
 * counts or names it). Any other condition there is not read: the page's own findings warn about it.
 *
 * <pre>{@code
 * { "Collection": { "Text": { "TitleKey": "almanac.spring_fair.items.title" },
 *     "Items": [ { "Item": "Spring_Fair_Ribbon", "SourceKey": "almanac.spring_fair.source.stall" },
 *                { "Item": "Spring_Fair_Kite_Gold", "Hidden": true },
 *                { "Item": "Yourmod_Kite", "Requires": { "Factors": [
 *                    { "Factor": "hytale:mod_installed", "Param": "Yourgroup:Yourmod", "Min": 1 } ] } } ],
 *     "Button": { "TextKey": "almanac.spring_fair.items.stall",
 *                 "Destination": { "Type": "Shop", "Shop": "Spring_Fair_Stall" } } } }
 * }</pre>
 */
public final class AlmanacCollectionAsset {

    @Nullable private ContentTextAsset text;
    @Nullable private Slot[] items;
    @Nullable private LinkAsset button;

    public static final BuilderCodec<AlmanacCollectionAsset> CODEC =
            BuilderCodec.builder(AlmanacCollectionAsset.class, AlmanacCollectionAsset::new)
                    .append(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false), (c, v) -> c.text = v, c -> c.text)
                    .documentation("The grid's heading (TitleKey; the library's own words when unauthored) and a "
                            + "line under it (FlavorKey).").add()
                    .append(new KeyedCodec<>("Items", new ArrayCodec<>(Slot.CODEC, Slot[]::new), false),
                            (c, v) -> c.items = v, c -> c.items)
                    .documentation("The items a player can find this season, drawn in the order written, at most "
                            + "45, nine a row. An item the server does not have is left out, and so is a slot "
                            + "whose Requires names a mod this server does not run.").add()
                    .append(new KeyedCodec<>("Button", LinkAsset.CODEC, false),
                            (c, v) -> c.button = v, c -> c.button)
                    .documentation("A button under the grid: its words and what it opens, in the shared "
                            + "destination vocabulary, for example a storefront: { \"Type\": \"Shop\", \"Shop\": "
                            + "\"<storefront id>\" }. A button with no words, or one opening a screen no installed "
                            + "mod registers, is left out.").add()
                    .build();

    public AlmanacCollectionAsset() {
    }

    /**
     * The entries on this server that name an item, in the order written (the reader caps and checks them): a
     * slot whose own {@code Requires} names a missing mod is not among them, so it takes no place in the grid's
     * 45 and no reader (the view, the audit, the owned marks) sees it.
     */
    @Nonnull
    public List<Slot> slots() {
        if (items == null) {
            return List.of();
        }
        List<Slot> out = new ArrayList<>();
        for (Slot slot : items) {
            if (slot != null && slot.item() != null && slot.passesModGate()) {
                out.add(slot);
            }
        }
        return List.copyOf(out);
    }

    /** The grid's heading key, or null for the library's own words. */
    @Nullable
    public String titleKey() {
        return text == null ? null : AlmanacHeroAsset.blankToNull(text.getTitleKey());
    }

    /** The key of the line under the heading, or null. */
    @Nullable
    public String flavorKey() {
        return text == null ? null : AlmanacHeroAsset.blankToNull(text.getFlavorKey());
    }

    /** The button, when it has words and somewhere this server can open; else null. */
    @Nullable
    public LinkAsset button() {
        return button != null && button.usable() ? button : null;
    }

    /** Whether a button was written that has no words or nowhere this server can open. */
    public boolean buttonLeftOut() {
        return button != null && !button.usable();
    }

    /**
     * One item of the grid: which item, whether it hides until obtained once, where it comes from, and the mod it
     * needs on this server.
     */
    public static final class Slot {

        /** The sentence the Asset Editor shows beside a slot's {@code Requires}. */
        private static final String REQUIRES_DOCUMENTATION = "Whether this slot is on this server at all. A "
                + "condition { \"Factor\": \"hytale:mod_installed\", \"Param\": \"Group:Name\", \"Min\": 1 } "
                + "leaves the slot out of the grid where that mod is not installed, as if it were never written, "
                + "while the rest of the grid shows. Write Min 1: a condition with no bound is satisfied by a "
                + "missing mod too. A slot reads nothing else here: any other condition is ignored, and the "
                + "page's audit says so.";

        @Nullable private String item;
        @Nullable private Boolean hidden;
        @Nullable private String sourceKey;
        @Nullable private GateSpec requires;

        public static final BuilderCodec<Slot> CODEC = BuilderCodec.builder(Slot.class, Slot::new)
                .append(new KeyedCodec<>("Item", Codec.STRING, false), (s, v) -> s.item = v, s -> s.item)
                .metadata(EditorSchema.assetRef(Item.class))
                .documentation("The item, by id. It shows its own picture once the player has it, or while it "
                        + "is not Hidden.").add()
                .append(new KeyedCodec<>("Hidden", Codec.BOOLEAN, false), (s, v) -> s.hidden = v, s -> s.hidden)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True shows a '?' in its place until the player has obtained it once, then the "
                        + "item for good.").add()
                .append(new KeyedCodec<>("SourceKey", Codec.STRING, false),
                        (s, v) -> s.sourceKey = v, s -> s.sourceKey)
                .documentation("Where the item comes from, as a localization key, shown in its tooltip once it "
                        + "is shown.").add()
                .append(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                        (s, v) -> s.requires = v, s -> s.requires)
                .documentation(REQUIRES_DOCUMENTATION).add()
                .build();

        public Slot() {
        }

        /**
         * The mod ({@code Group:Name}) that keeps this slot off this server, or null when it is here (it writes no
         * {@code Requires}, or its mods are installed, or the server cannot tell yet).
         */
        @Nullable
        public String missingMod() {
            return GateSpec.missingMod(requires);
        }

        /** Is this slot on this server? False only when its own {@code Requires} names a missing mod. */
        public boolean passesModGate() {
            return missingMod() == null;
        }

        /**
         * What of its {@code Requires} a slot does not read, by name: every top-level condition but the plain mod
         * presence gate ({@code ModGates.isPresenceGate}), each as its factor id with its {@code Param} when it writes
         * one ({@code 'hytale:permission test.node'}), then any other leaf the block writes ({@code Permission},
         * {@code Quests}, {@code Custom}, {@code AllOf}, {@code AnyOf}, {@code Not}). Empty when it writes none.
         */
        @Nonnull
        List<String> ignoredConditions() {
            if (requires == null) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (FactorCondition condition : requires.factorsOrEmpty()) {
                if (condition != null && !condition.isBlank() && !ModGates.isPresenceGate(condition)) {
                    String param = AlmanacHeroAsset.blankToNull(condition.getParam());
                    names.add("'" + condition.getFactor().trim() + (param == null ? "" : " " + param) + "'");
                }
            }
            if (AlmanacHeroAsset.blankToNull(requires.getPermission()) != null) {
                names.add("Permission");
            }
            if (requires.questsOrEmpty().length > 0) {
                names.add("Quests");
            }
            if (!requires.customOrEmpty().isEmpty()) {
                names.add("Custom");
            }
            if (requires.allOfOrEmpty().length > 0) {
                names.add("AllOf");
            }
            if (requires.anyOfOrEmpty().length > 0) {
                names.add("AnyOf");
            }
            if (requires.notOrEmpty().length > 0) {
                names.add("Not");
            }
            return List.copyOf(names);
        }

        /** The item id, trimmed, or null when blank. */
        @Nullable
        public String item() {
            return AlmanacHeroAsset.blankToNull(item);
        }

        /** Whether the slot hides its item until the player has obtained it once; false when unauthored. */
        public boolean hidden() {
            return hidden != null && hidden;
        }

        /** The key of the line saying where the item comes from, or null. */
        @Nullable
        public String sourceKey() {
            return AlmanacHeroAsset.blankToNull(sourceKey);
        }
    }
}
