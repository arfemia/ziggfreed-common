package com.ziggfreed.common.npc.placement.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.npc.NpcDestinations;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The authoring mistakes that are otherwise SILENT.
 *
 * <p>In {@code Interact}: describing one press-F twice, which leaves half the file saying something
 * that never runs. In {@code Identity}: naming no {@code Role} and drawing no {@code Props}, which
 * leaves nothing to place. In {@code Props}: an entry naming no item, and one naming an item the
 * server lacks (skipped at runtime, a warning that never refuses the file), and, on a placement naming
 * no role, the NPC-only fields it authors, which do nothing there (a warning). All of them are
 * invisible at runtime - an NPC that never appears reads exactly like one nobody has walked to
 * yet - so they are pinned here as findings.
 */
class NpcPlacementValidatorTest {

    private static NpcPlacementAsset placementWith(NpcPlacementAsset.Interact interact) {
        return NpcPlacementAsset.of("test_placement", true,
                NpcPlacementAsset.Identity.of("some_role"),
                null,
                NpcPlacementAsset.Anchor.of(NpcPlacementAsset.Anchor.WorldSpawn.of(null, null), null, null, null, null),
                null, null, null,
                interact);
    }

    private static boolean has(List<Finding> issues, String code) {
        return issues.stream().anyMatch(i -> code.equals(i.code()));
    }

    // ==================== interact ====================

    @Test
    void authoringBothInteractFormsIsAnError() {
        List<Finding> issues = NpcPlacementValidator.audit(placementWith(
                NpcPlacementAsset.Interact.of("hub_intro", NpcDestinations.Quests.of(null))));

        assertTrue(has(issues, "INTERACT_BOTH_FORMS"),
                "one press-F described twice leaves half the file saying something that never runs");
        assertTrue(issues.stream()
                        .filter(i -> "INTERACT_BOTH_FORMS".equals(i.code()))
                        .allMatch(i -> i.severity() == Severity.ERROR),
                "and it is an error rather than a precedence rule");
    }

    @Test
    void eitherFormOnItsOwnIsClean() {
        assertFalse(has(NpcPlacementValidator.audit(
                        placementWith(NpcPlacementAsset.Interact.of("hub_intro"))),
                "INTERACT_BOTH_FORMS"));
        assertFalse(has(NpcPlacementValidator.audit(
                        placementWith(NpcPlacementAsset.Interact.of(null, NpcDestinations.Quests.of(null)))),
                "INTERACT_BOTH_FORMS"));
    }

    @Test
    void noInteractAtAllProducesNoInteractFindings() {
        List<Finding> issues = NpcPlacementValidator.audit(placementWith(null));

        assertFalse(has(issues, "INTERACT_BOTH_FORMS"),
                "a placement that opens nothing of its own is not an authoring mistake");
    }

    // ==================== identity ====================

    /** A placement whose Identity says who it is but never says WHAT to stand there. */
    @Test
    void anIdentityNamingNoRoleIsAnError() {
        NpcPlacementAsset noRole = NpcPlacementAsset.of("test_placement", true,
                NpcPlacementAsset.Identity.of(null, "some_character", null),
                null,
                NpcPlacementAsset.Anchor.of(NpcPlacementAsset.Anchor.WorldSpawn.of(null, null), null, null, null, null),
                null, null, null, null);

        assertTrue(has(NpcPlacementValidator.audit(noRole), "NO_ROLE"),
                "a placement with no role names nothing the engine can spawn, and says so nowhere at runtime");
    }

    @Test
    void anIdentityNamingARoleIsClean() {
        assertFalse(has(NpcPlacementValidator.audit(placementWith(null)), "NO_ROLE"));
    }

    // ==================== props ====================

    private static final NpcPlacementAsset.Anchor AT_SPAWN =
            NpcPlacementAsset.Anchor.of(NpcPlacementAsset.Anchor.WorldSpawn.of(null, null), null, null, null, null);

    /** A role-less placement that draws only its props. */
    private static NpcPlacementAsset propsOnly(NpcPlacementAsset.Prop... props) {
        return NpcPlacementAsset.of("feast_table", true, null, null, AT_SPAWN, null, null, null, null, props);
    }

    @AfterEach
    void restoreTheLoadedItems() {
        NpcPlacementValidator.useItemCatalogForTests(null);
    }

    @Test
    void aPropPlacementNeedsNoRole() {
        NpcPlacementValidator.useItemCatalogForTests(id -> true);
        List<Finding> issues = NpcPlacementValidator.audit(
                propsOnly(NpcPlacementAsset.Prop.of("Furniture_Tavern_Table", null, null, null)));

        assertFalse(has(issues, "NO_IDENTITY"), "a placement drawing props has something to place: " + issues);
        assertFalse(has(issues, "NO_ROLE"), "a placement drawing props has something to place: " + issues);
    }

    @Test
    void aRoleLessPlacementWithNoPropsIsStillAnError() {
        // The same severity a placement naming no role has always had: there is nothing to place at all.
        List<Finding> noIdentity = NpcPlacementValidator.audit(propsOnly());
        assertTrue(noIdentity.stream().anyMatch(i -> "NO_IDENTITY".equals(i.code()) && i.severity() == Severity.ERROR),
                "no role and no props: " + noIdentity);

        NpcPlacementAsset noRole = NpcPlacementAsset.of("feast_table", true,
                NpcPlacementAsset.Identity.of(null, "martha", null), null, AT_SPAWN, null, null, null, null,
                new NpcPlacementAsset.Prop[0]);
        List<Finding> issues = NpcPlacementValidator.audit(noRole);
        assertTrue(issues.stream().anyMatch(i -> "NO_ROLE".equals(i.code()) && i.severity() == Severity.ERROR),
                "an empty Props list draws nothing either: " + issues);
    }

    @Test
    void aPropNamingAnItemTheServerLacksIsAWarningAndTheFileStillLoads() {
        NpcPlacementValidator.useItemCatalogForTests(id -> !"Furniture_No_Such_Table".equals(id));
        List<Finding> issues = NpcPlacementValidator.audit(propsOnly(
                NpcPlacementAsset.Prop.of("Furniture_No_Such_Table", null, null, null),
                NpcPlacementAsset.Prop.of("Furniture_Tavern_Bench", null, null, null)));

        List<Finding> unknown = issues.stream()
                .filter(i -> NpcPlacementValidator.UNKNOWN_PROP_ITEM.equals(i.code())).toList();
        assertEquals(1, unknown.size(), "only the entry the server lacks: " + issues);
        assertEquals(Severity.WARNING, unknown.get(0).severity(),
                "an unknown id is a warning: its pack may load later, and the rest of the props still draw");
        assertTrue(unknown.get(0).message().contains("Furniture_No_Such_Table"), unknown.get(0).message());
    }

    @Test
    void aPropItemCheckThatCannotTellSaysNothing() {
        // No items loaded (a unit JVM, or an audit before the packs): no answer is not an answer of "unknown".
        NpcPlacementValidator.useItemCatalogForTests(id -> null);

        assertFalse(has(NpcPlacementValidator.audit(
                        propsOnly(NpcPlacementAsset.Prop.of("Furniture_Tavern_Table", null, null, null))),
                NpcPlacementValidator.UNKNOWN_PROP_ITEM));
    }

    private static final NpcPlacementAsset.Prop TABLE =
            NpcPlacementAsset.Prop.of("Furniture_Tavern_Table", null, null, null);

    /** Every NPC-only field a role-less placement could author, beside the props it draws. */
    @Test
    void aRoleLessPlacementAuthoringNpcOnlyFieldsIsAWarningNamingThem() {
        NpcPlacementAsset table = NpcPlacementAsset.of("feast_table", true,
                NpcPlacementAsset.Identity.of(null, "martha", new String[] {"feast_cook"}), null, AT_SPAWN, null, null,
                NpcPlacementAsset.Lifecycle.of(true, null, null, null), NpcPlacementAsset.Interact.of("feast_intro"),
                TABLE);

        List<Finding> found = NpcPlacementValidator.auditFileLocal(table).stream()
                .filter(i -> NpcPlacementValidator.PROPS_ONLY_NPC_FIELDS.equals(i.code())).toList();

        assertEquals(1, found.size(), "one finding naming every NPC-only field: " + found);
        assertEquals(Severity.WARNING, found.get(0).severity(),
                "the props still draw; the fields only do nothing, so the file loads");
        for (String field : List.of("Lifecycle", "Interact", "Identity.NpcId", "Identity.Aliases")) {
            assertTrue(found.get(0).message().contains(field), field + " named in: " + found.get(0).message());
        }
    }

    @Test
    void theWarningNamesOnlyTheFieldsAuthored() {
        NpcPlacementAsset table = NpcPlacementAsset.of("feast_table", true, null, null, AT_SPAWN, null, null,
                NpcPlacementAsset.Lifecycle.of(true, null, null, null), null, TABLE);

        List<Finding> found = NpcPlacementValidator.auditFileLocal(table).stream()
                .filter(i -> NpcPlacementValidator.PROPS_ONLY_NPC_FIELDS.equals(i.code())).toList();

        assertEquals(1, found.size(), "Lifecycle alone: " + found);
        String message = found.get(0).message();
        assertTrue(message.contains("Lifecycle"), message);
        assertFalse(message.contains("Interact") || message.contains("NpcId") || message.contains("Aliases"),
                "a field the file never wrote is not reported: " + message);
    }

    @Test
    void propsAloneOrTheSameFieldsBesideARoleAreClean() {
        assertFalse(has(NpcPlacementValidator.auditFileLocal(propsOnly(TABLE)),
                NpcPlacementValidator.PROPS_ONLY_NPC_FIELDS), "a placement that only decorates authors nothing idle");

        NpcPlacementAsset cook = NpcPlacementAsset.of("feast_cook", true,
                NpcPlacementAsset.Identity.of("Harvest_Feast_Cook", "martha", new String[] {"feast_cook"}), null,
                AT_SPAWN, null, null, NpcPlacementAsset.Lifecycle.of(true, null, null, null),
                NpcPlacementAsset.Interact.of("feast_intro"), TABLE);
        assertFalse(has(NpcPlacementValidator.auditFileLocal(cook), NpcPlacementValidator.PROPS_ONLY_NPC_FIELDS),
                "with a Role the same fields are its NPC's own");
    }

    @Test
    void aPropNamingNoItemIsAnError() {
        NpcPlacementValidator.useItemCatalogForTests(id -> true);

        List<Finding> issues = NpcPlacementValidator.auditFileLocal(propsOnly(
                NpcPlacementAsset.Prop.of("  ", null, null, null),
                NpcPlacementAsset.Prop.of("Furniture_Tavern_Bench", null, null, null)));

        assertTrue(issues.stream().anyMatch(i -> NpcPlacementValidator.PROP_NO_ITEM.equals(i.code())
                        && i.severity() == Severity.ERROR),
                "an entry naming nothing draws nothing, whatever loads: " + issues);
    }
}
