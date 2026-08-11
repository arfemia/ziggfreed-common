package com.ziggfreed.common.npc.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.world.WorldSelectorValidator.Issue;

/**
 * Pack-authorable base roles: the asset fold registers into {@link NpcRoleGenerator}, an asset
 * wins a same-id collision against a Java-registered base, and a base role with no usable
 * payload is reported rather than silently generating nothing.
 */
class NpcBaseRoleTest {

    private static final String JAVA_ROLE = "{\"Type\":\"Generic\",\"Source\":\"java\"}";
    private static final String ASSET_ROLE = "{\"Type\":\"Generic\",\"Source\":\"asset\"}";

    @BeforeEach
    @AfterEach
    void reset() {
        NpcRoleGenerator.clearForTests();
    }

    // ==================== validator: empty / non-object payload ====================

    @Test
    void aValidPayloadProducesNoFinding() {
        NpcBaseRoleAsset asset = NpcBaseRoleAsset.of("zc_placement_npc", JAVA_ROLE);

        List<Issue> issues = NpcBaseRoleValidator.validate(asset);

        assertTrue(issues.isEmpty());
    }

    @Test
    void aMissingPayloadIsReportedAsEmpty() {
        NpcBaseRoleAsset asset = NpcBaseRoleAsset.of("zc_placement_npc", null);

        List<Issue> issues = NpcBaseRoleValidator.validate(asset);

        assertTrue(issues.stream().anyMatch(i -> "EMPTY_BASE_ROLE_PAYLOAD".equals(i.code())));
    }

    @Test
    void anEmptyJsonObjectPayloadIsReportedAsEmpty() {
        NpcBaseRoleAsset asset = NpcBaseRoleAsset.of("zc_placement_npc", "{}");

        List<Issue> issues = NpcBaseRoleValidator.validate(asset);

        assertTrue(issues.stream().anyMatch(i -> "EMPTY_BASE_ROLE_PAYLOAD".equals(i.code())));
    }

    // ==================== config fold: register into the generator ====================

    @Test
    void aFoldedAssetWithAUsablePayloadRegistersIntoTheGenerator() {
        NpcBaseRoleAsset asset = NpcBaseRoleAsset.of("zc_gen_role", ASSET_ROLE);

        NpcBaseRoleConfig.getInstance().mergePackLayer(Map.of("zc_gen_role", asset));

        assertTrue(NpcRoleGenerator.hasBaseRole("zc_gen_role"));
        assertEquals(ASSET_ROLE, NpcRoleGenerator.currentBaseRoleJson("zc_gen_role"));
    }

    @Test
    void aFoldedAssetWithNoUsablePayloadDoesNotRegisterButIsReported() {
        NpcBaseRoleAsset asset = NpcBaseRoleAsset.of("zc_broken_role", null);

        NpcBaseRoleConfig.getInstance().mergePackLayer(Map.of("zc_broken_role", asset));

        assertFalse(NpcRoleGenerator.hasBaseRole("zc_broken_role"),
                "an asset with no usable payload must not register a broken generator entry");
        assertTrue(NpcBaseRoleConfig.getInstance().validate().stream()
                .anyMatch(i -> "EMPTY_BASE_ROLE_PAYLOAD".equals(i.code()) && "zc_broken_role".equals(i.sourceId())));
    }

    // ==================== asset-wins collision ====================

    @Test
    void anAssetRegisteredBaseRoleWinsOverAPreviousJavaRegistration() {
        NpcRoleGenerator.registerBaseRole("shared_base", () -> JAVA_ROLE);
        assertEquals(JAVA_ROLE, NpcRoleGenerator.currentBaseRoleJson("shared_base"));

        NpcBaseRoleAsset asset = NpcBaseRoleAsset.of("shared_base", ASSET_ROLE);
        NpcBaseRoleConfig.getInstance().mergePackLayer(Map.of("shared_base", asset));

        assertEquals(ASSET_ROLE, NpcRoleGenerator.currentBaseRoleJson("shared_base"),
                "the authored asset must win a same-id collision over a Java-registered base role");
    }

    @Test
    void reFoldingTheSameAssetIdTwiceIsNotTreatedAsAJavaCollision() {
        NpcBaseRoleAsset first = NpcBaseRoleAsset.of("zc_reload_role", JAVA_ROLE);
        NpcBaseRoleConfig.getInstance().mergePackLayer(Map.of("zc_reload_role", first));
        assertEquals(JAVA_ROLE, NpcRoleGenerator.currentBaseRoleJson("zc_reload_role"));

        NpcBaseRoleAsset reloaded = NpcBaseRoleAsset.of("zc_reload_role", ASSET_ROLE);
        NpcBaseRoleConfig.getInstance().mergePackLayer(Map.of("zc_reload_role", reloaded));

        assertEquals(ASSET_ROLE, NpcRoleGenerator.currentBaseRoleJson("zc_reload_role"),
                "a hot reload of the same pack file must still take effect");
    }

    @Test
    void aBaseRoleNeverAuthoredAsAnAssetIsUnaffected() {
        NpcRoleGenerator.registerBaseRole("only_java", () -> JAVA_ROLE);

        NpcBaseRoleConfig.getInstance().mergePackLayer(Map.of());

        assertEquals(JAVA_ROLE, NpcRoleGenerator.currentBaseRoleJson("only_java"));
    }

    @Test
    void anUnregisteredBaseRoleHasNoJsonBody() {
        assertNull(NpcRoleGenerator.currentBaseRoleJson("nothing_registered"));
        assertFalse(NpcRoleGenerator.hasBaseRole("nothing_registered"));
    }
}
