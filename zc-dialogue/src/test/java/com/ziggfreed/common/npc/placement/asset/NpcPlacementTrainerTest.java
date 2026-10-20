package com.ziggfreed.common.npc.placement.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.npc.NpcDestinations;
import com.ziggfreed.common.ui.page.PagePart;
import com.ziggfreed.common.ui.page.PageParts;
import com.ziggfreed.common.ui.page.PageSection;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * A placement's {@code Trainer} block: what makes the character a trainer, read off the placement itself (R608,
 * R751). It decodes its tracks, categories and page parts, inherits leaf by leaf under {@code Parent}, its
 * {@code Sections} read against the parts registered on the trainer page, and a placement without it is no trainer.
 */
class NpcPlacementTrainerTest {

    /** A part with no knobs, as the trainer page's own parts are. */
    public static final class Empty {
        static final BuilderCodec<Empty> CODEC = BuilderCodec.builder(Empty.class, Empty::new).build();
    }

    @BeforeAll
    static void registerParts() {
        NpcDestinations.register();
        for (String name : List.of("Learn", "Quests")) {
            PageParts.of(NpcPlacementAsset.Trainer.PAGE).register(PagePart.named(name, Empty.CODEC)
                    .documentation(name).paints(Object.class, "Pages/Test.ui", (canvas, plan) -> { }));
        }
    }

    private static NpcPlacementAsset decode(String json, String id, String parentId, NpcPlacementAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(NpcPlacementAsset.class, id, parentId);
        return NpcPlacementAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    @Test
    void aTrainerBlockDecodesWhatItTeachesAndItsParts() throws Exception {
        NpcPlacementAsset asset = decode("""
                { "Identity": { "Role": "Zc_Guide" },
                  "Trainer": { "Tracks": [ "Mining", " mining ", "", "Woodcutting" ], "Categories": [ "Gathering" ],
                               "Sections": [ { "Quests": {} }, { "Learn": {} } ] } }
                """, "miner", null, null);

        assertTrue(asset.isTrainer());
        NpcPlacementAsset.Trainer trainer = asset.getTrainer();
        assertNotNull(trainer);
        assertEquals(List.of("Mining", "Woodcutting"), trainer.tracks(),
                "blanks and a repeat in another case are left out, the authored spelling kept");
        assertEquals(List.of("Gathering"), trainer.categories());
        List<PageSection> sections = trainer.sections();
        assertNotNull(sections);
        assertEquals(List.of("Quests", "Learn"), sections.stream().map(PageSection::part).toList(),
                "the parts in the order written");
    }

    @Test
    void aPlacementWithoutTheBlockIsNoTrainerAndUnwrittenSectionsReadEveryPart() throws Exception {
        NpcPlacementAsset plain = decode("{ \"Identity\": { \"Role\": \"Zc_Guide\" } }", "guide", null, null);
        assertFalse(plain.isTrainer());
        assertNull(plain.getTrainer());

        NpcPlacementAsset bare = decode("{ \"Trainer\": {} }", "bare", null, null);
        assertTrue(bare.isTrainer(), "an empty block is still a trainer");
        assertNull(bare.getTrainer().sections(), "no Sections written: the page shows every part");
        assertEquals(List.of(), bare.getTrainer().tracks());
    }

    @Test
    void aChildInheritsTheBlockLeafByLeaf() throws Exception {
        NpcPlacementAsset parent = decode("""
                { "Trainer": { "Tracks": [ "Mining" ], "Sections": [ { "Learn": {} } ] } }
                """, "parent", null, null);
        NpcPlacementAsset child = decode("{ \"Trainer\": { \"Categories\": [ \"Gathering\" ] } }",
                "child", "parent", parent);

        NpcPlacementAsset.Trainer trainer = child.getTrainer();
        assertEquals(List.of("Mining"), trainer.tracks(), "an untouched leaf is inherited");
        assertEquals(List.of("Gathering"), trainer.categories(), "the written leaf is the child's");
        assertEquals(1, trainer.sections().size());
    }

    @Test
    void aSectionNamingNoPartOfTheTrainerPageIsAWarning() throws Exception {
        NpcPlacementAsset asset = decode("""
                { "Identity": { "Role": "Zc_Guide" },
                  "Trainer": { "Sections": [ { "Learn": {} }, { "Recipes": {} }, {} ] } }
                """, "odd", null, null);

        List<Finding> findings = NpcPlacementValidator.auditFileLocal(asset);
        List<String> codes = findings.stream().map(Finding::code).toList();
        assertTrue(codes.contains(NpcPlacementValidator.TRAINER_SECTION_UNKNOWN_PART), codes.toString());
        assertTrue(codes.contains(NpcPlacementValidator.TRAINER_SECTION_EMPTY), codes.toString());
        for (Finding finding : findings) {
            if (finding.code().startsWith("TRAINER_")) {
                assertEquals(Severity.WARNING, finding.severity(), "a part may be a mod's not installed");
            }
        }
    }
}
