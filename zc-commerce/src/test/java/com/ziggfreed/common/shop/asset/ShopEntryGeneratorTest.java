package com.ziggfreed.common.shop.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.progress.asset.GeneratedBody;
import com.ziggfreed.common.progress.asset.GeneratorCore;
import com.ziggfreed.common.validation.Finding;

/**
 * The generator over the SHARED expander: one file writes a family of offers, tokens are substituted
 * everywhere (values, object KEYS and the id pattern), and a value that is exactly one token keeps
 * its own type.
 *
 * <p>Substituting a KEY is the load-bearing case here rather than a nicety: a per-skill requirement
 * has to name its own stat channel, and without key substitution that needs a bespoke escape hatch
 * on the schema. It is proved rather than assumed.
 */
class ShopEntryGeneratorTest {

    /** A value source answering two skills, so an axis can read a list rather than list it. */
    private static GeneratorCore.AxisValueSource skills(String... ids) {
        return new GeneratorCore.AxisValueSource() {

            @Override
            public boolean isRegistered(String sourceId) {
                return "yourmod:skills".equalsIgnoreCase(sourceId);
            }

            @Override
            public List<Map<String, JsonPrimitive>> rows(String sourceId, String token,
                    Map<String, String> filter) {
                List<Map<String, JsonPrimitive>> out = new ArrayList<>();
                for (String id : ids) {
                    Map<String, JsonPrimitive> row = new LinkedHashMap<>();
                    row.put(token == null ? "value" : token, new JsonPrimitive(id));
                    out.add(row);
                }
                return out;
            }
        };
    }

    static ShopEntryGeneratorAsset generator(String json, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(ShopEntryGeneratorAsset.class, id, null);
        return ShopEntryGeneratorAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    private static GeneratorCore.Expansion expand(ShopEntryGeneratorAsset generator,
            GeneratorCore.AxisValueSource values) {
        return GeneratorCore.expand(generator, ShopValidator.DOMAIN, "offer", values);
    }

    private static List<String> ids(GeneratorCore.Expansion expansion) {
        return expansion.bodies().stream().map(GeneratedBody::id).toList();
    }

    @Test
    void axesMultiplyAndEveryChildCarriesItsParent() throws Exception {
        GeneratorCore.Expansion expansion = expand(generator("""
                { "Base": "Xp_Packet",
                  "IdPattern": "shop_xp_{tier}_{skill}",
                  "ForEach": [ { "Token": "skill", "Source": "yourmod:skills" },
                               { "Token": "tier", "Values": ["lesser", "master"] } ],
                  "Child": { "Rewards": [ { "Kind": "Mmo_Xp", "Params": { "Skill": "{skill}" } } ] } }
                """, "xp_packets"), skills("mining", "fishing"));

        assertEquals(List.of("shop_xp_lesser_mining", "shop_xp_master_mining",
                        "shop_xp_lesser_fishing", "shop_xp_master_fishing"),
                ids(expansion));
        for (GeneratedBody body : expansion.bodies()) {
            assertEquals("xp_packet", body.body().get("Parent").getAsString(),
                    "every generated body is an ordinary child, resolved by the same inheritance");
        }
    }

    @Test
    void anObjectKeyIsSubstitutedSoAPerSkillRequirementNeedsNoWorkaround() throws Exception {
        GeneratorCore.Expansion expansion = expand(generator("""
                { "Base": "Xp_Packet",
                  "IdPattern": "shop_xp_{skill}",
                  "ForEach": [ { "Token": "skill", "Source": "yourmod:skills" } ],
                  "Child": { "Requires": { "Factors": [ { "Factor": "hytale:stat",
                                                          "Param": "MMO_Level_{skill}", "Min": 1 } ] },
                             "Meta": { "{skill}": { "Own": true } } } }
                """, "xp_packets"), skills("mining"));

        GeneratedBody body = expansion.bodies().get(0);
        assertEquals("MMO_Level_mining", body.body().getAsJsonObject("Requires")
                .getAsJsonArray("Factors").get(0).getAsJsonObject().get("Param").getAsString());
        assertTrue(body.body().getAsJsonObject("Meta").has("mining"),
                "an object KEY is substituted too, which is what the old key-substitution workaround existed for");
    }

    @Test
    void aWholeTokenValueKeepsItsOwnTypeSoANumberStaysANumber() throws Exception {
        GeneratorCore.Expansion expansion = expand(generator("""
                { "Base": "Xp_Packet",
                  "IdPattern": "shop_xp_{tier}",
                  "ForEach": [ { "Token": "tier", "Values": [ { "tier": "lesser", "tokens": 75,
                                                                "minLevel": 1 } ] } ],
                  "Child": { "Cost": { "Currencies": { "bounty_token": "{tokens}" } },
                             "Requires": { "Factors": [ { "Factor": "hytale:stat",
                                                          "Min": "{minLevel}" } ] } } }
                """, "xp_packets"), null);

        GeneratedBody body = expansion.bodies().get(0);
        assertTrue(body.body().getAsJsonObject("Cost").getAsJsonObject("Currencies")
                .get("bounty_token").getAsJsonPrimitive().isNumber(), "a whole-token value keeps its type");
        assertEquals(75, body.body().getAsJsonObject("Cost").getAsJsonObject("Currencies")
                .get("bounty_token").getAsInt());
        assertEquals(1, body.body().getAsJsonObject("Requires").getAsJsonArray("Factors")
                .get(0).getAsJsonObject().get("Min").getAsInt());
    }

    @Test
    void aGeneratedBodyDecodesThroughTheSameCodecAgainstItsBase() throws Exception {
        ShopEntryAsset base = CommerceFixtureSupport.entry("""
                { "Abstract": true, "Shop": "XpExchange", "Listing": { "Category": "conversion" } }
                """, "Xp_Packet", null, null);

        GeneratorCore.Expansion expansion = expand(generator("""
                { "Base": "Xp_Packet",
                  "IdPattern": "shop_xp_{tier}_{skill}",
                  "ForEach": [ { "Token": "skill", "Source": "yourmod:skills" },
                               { "Token": "tier", "Values": [ { "tier": "lesser", "tokens": 75,
                                                                "minLevel": 1, "daily": 3 } ] } ],
                  "Child": { "Cost": { "Currencies": { "bounty_token": "{tokens}" } },
                             "Limits": { "Daily": "{daily}" },
                             "Pool": { "Id": "XpExchange", "Tier": "{tier}" },
                             "Requires": { "Factors": [ { "Factor": "hytale:stat",
                                                          "Param": "MMO_Level_{skill}",
                                                          "Min": "{minLevel}" } ] },
                             "Rewards": [ { "Kind": "Mmo_Xp", "Params": { "Skill": "{skill}" } } ] } }
                """, "xp_packets"), skills("mining"));

        GeneratedBody body = expansion.bodies().get(0);
        ShopEntryAsset generated = CommerceFixtureSupport.entry(body.body().toString(),
                body.id(), body.baseId(), base);

        assertNotNull(generated);
        assertFalse(generated.isAbstract(), "a child of a skeleton is a real offer");
        assertEquals("xpexchange", generated.getShop(), "the skeleton's storefront carries over");
        assertEquals("conversion", generated.getListing().categoryId());
        assertEquals(75L, generated.costOrFree().currencyAmounts().get("bounty_token"));
        assertEquals(3, generated.dailyLimit());
        assertEquals("lesser", generated.getPool().getTier());
        assertEquals("MMO_Level_mining", generated.getRequires().factorsOrEmpty()[0].getParam());
    }

    @Test
    void aSourceNothingRegisteredIsReportedRatherThanSilentlyWritingNothing() throws Exception {
        GeneratorCore.Expansion expansion = expand(generator("""
                { "Base": "Xp_Packet", "IdPattern": "shop_xp_{skill}",
                  "ForEach": [ { "Token": "skill", "Source": "nobody:skills" } ],
                  "Child": {} }
                """, "xp_packets"), skills("mining"));

        assertTrue(expansion.bodies().isEmpty());
        assertTrue(hasCode(expansion.issues(), "UNKNOWN_SOURCE"));
    }

    @Test
    void anUnboundTokenSkipsThatOfferRatherThanShippingItHalfWritten() throws Exception {
        GeneratorCore.Expansion expansion = expand(generator("""
                { "Base": "Xp_Packet", "IdPattern": "shop_xp_{skill}_{missing}",
                  "ForEach": [ { "Token": "skill", "Source": "yourmod:skills" } ],
                  "Child": {} }
                """, "xp_packets"), skills("mining"));

        assertTrue(expansion.bodies().isEmpty());
        assertTrue(hasCode(expansion.issues(), "UNRESOLVED_TOKEN"));
    }

    @Test
    void aDisabledGeneratorWritesNothingAndSaysNothing() throws Exception {
        GeneratorCore.Expansion expansion = expand(generator("""
                { "Enabled": false, "Base": "Xp_Packet", "IdPattern": "shop_xp_{skill}",
                  "ForEach": [ { "Token": "skill", "Source": "yourmod:skills" } ], "Child": {} }
                """, "xp_packets"), skills("mining"));

        assertTrue(expansion.bodies().isEmpty());
        assertTrue(expansion.issues().isEmpty());
    }

    private static boolean hasCode(List<Finding> findings, String code) {
        return findings.stream().anyMatch(finding -> code.equals(finding.code()));
    }
}
