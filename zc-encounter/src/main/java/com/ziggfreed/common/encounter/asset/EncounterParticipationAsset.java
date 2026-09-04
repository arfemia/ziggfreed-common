package com.ziggfreed.common.encounter.asset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.world.WorldSelector;

/**
 * How credit for a fight is shared out: one rule per file at
 * {@code Server/ZiggfreedCommon/EncounterParticipation/<Id>.json}, matched to a run by the subject's
 * mob id and by the world it happens in.
 *
 * <pre>{@code
 * {
 *   "Match": "*Warden*",
 *   "Where": { "GameplayConfig": ["KweebecNightmare"] },
 *   "DamageDealt": 1.0,
 *   "DamageTaken": 0.25,
 *   "Presence":    { "Base": 0.1, "Factors": [ { "Factor": "hytale:stat", "Param": "Luck", "Weight": 0.01 } ] },
 *   "MinShare": 0.05,
 *   "CreditDead": true,
 *   "CreditDisconnected": true
 * }
 * }</pre>
 *
 * <p>Three independent counters are kept per participant while the fight is on: damage they dealt
 * the subject, damage they took while a member, and seconds spent as a member. Each rule weighs the
 * three, and a participant's score is the weighted sum; shares are that score against the top
 * contributor's, so the player who did most reads 1.0 and everyone else a fraction of it. A share
 * under {@code MinShare} is credited with the attempt and paid nothing.
 *
 * <p><b>The most specific matching rule wins</b>: an exact {@code Match} outranks a pattern, a longer
 * pattern outranks a shorter one, and a rule naming the world outranks one that does not; the bare
 * {@code "*"} rule is the fallback for everything. A binding row's own {@code Participation} group
 * then overrides the matched rule leaf by leaf for that one encounter. The library ships a match-all
 * default; a pack ships narrower rules beside it and never has to touch it.
 */
public final class EncounterParticipationAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, EncounterParticipationAsset>> {

    /** Where these are authored. One folder, one file per rule, the file name being the id. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/EncounterParticipation";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String match;
    @Nullable private WorldSelector where;
    @Nullable private FactorFormula damageDealt;
    @Nullable private FactorFormula damageTaken;
    @Nullable private FactorFormula presence;
    @Nullable private Double minShare;
    @Nullable private Boolean creditDead;
    @Nullable private Boolean creditDisconnected;
    @Nullable private Boolean enabled;

    public static final AssetBuilderCodec<String, EncounterParticipationAsset> CODEC = AssetBuilderCodec.builder(
                    EncounterParticipationAsset.class,
                    EncounterParticipationAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Match", Codec.STRING, false),
                    (a, v) -> a.match = v, a -> a.match, (a, p) -> a.match = p.match)
            .documentation("Which subject, as a name pattern on its mob id: \"Warden\" exactly, "
                    + "\"Warden*\" starting with, \"*_Boss\" ending with, \"*Trork*\" anywhere in the "
                    + "name, \"*\" everything. Case is ignored. Omit it to cover every subject.")
            .add()
            .appendInherited(new KeyedCodec<>("Where", WorldSelector.CODEC, false),
                    (a, v) -> a.where = v, a -> a.where, (a, p) -> a.where = p.where)
            .documentation("Which worlds this rule applies in, as the shared selector group "
                    + "(Match / GameplayConfig / ExcludeMatch). Omit it to apply everywhere.")
            .add()
            .appendInherited(new KeyedCodec<>("DamageDealt",
                            FactorFormula.numberOrGroup(EditorDataSets.FACTORS), false),
                    (a, v) -> a.damageDealt = v, a -> a.damageDealt, (a, p) -> a.damageDealt = p.damageDealt)
            .documentation("The weight of one point of damage dealt to the subject: a bare number, or a "
                    + "factor formula read about each participant at the payout.")
            .add()
            .appendInherited(new KeyedCodec<>("DamageTaken",
                            FactorFormula.numberOrGroup(EditorDataSets.FACTORS), false),
                    (a, v) -> a.damageTaken = v, a -> a.damageTaken, (a, p) -> a.damageTaken = p.damageTaken)
            .documentation("The weight of one point of damage a member took while inside the fight.")
            .add()
            .appendInherited(new KeyedCodec<>("Presence",
                            FactorFormula.numberOrGroup(EditorDataSets.FACTORS), false),
                    (a, v) -> a.presence = v, a -> a.presence, (a, p) -> a.presence = p.presence)
            .documentation("The weight of one second spent as a member. Presence only accrues on a "
                    + "script whose Player sensor carries the EncounterMembers collector.")
            .add()
            .appendInherited(new KeyedCodec<>("MinShare", Codec.DOUBLE, false),
                    (a, v) -> a.minShare = v, a -> a.minShare, (a, p) -> a.minShare = p.minShare)
            .documentation("The share, 0 to 1 of the top contributor's, under which a participant is "
                    + "credited with the attempt but paid nothing.")
            .add()
            .appendInherited(new KeyedCodec<>("CreditDead", Codec.BOOLEAN, false),
                    (a, v) -> a.creditDead = v, a -> a.creditDead, (a, p) -> a.creditDead = p.creditDead)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether a member who died during the fight keeps their share.")
            .add()
            .appendInherited(new KeyedCodec<>("CreditDisconnected", Codec.BOOLEAN, false),
                    (a, v) -> a.creditDisconnected = v, a -> a.creditDisconnected,
                    (a, p) -> a.creditDisconnected = p.creditDisconnected)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether a member who is offline at the payout keeps their share.")
            .add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Set false to take this rule out of the table, which lets a broader rule "
                    + "cover the same subjects again.")
            .add()
            .build();

    public EncounterParticipationAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The authored name pattern, or {@code *} when the rule covers every subject. */
    @Nonnull
    public String matchOrAll() {
        return match == null || match.isBlank() ? "*" : match.trim();
    }

    @Nullable
    public String getMatch() {
        return match;
    }

    @Nullable
    public WorldSelector getWhere() {
        return where;
    }

    @Nullable
    public FactorFormula getDamageDealt() {
        return damageDealt;
    }

    @Nullable
    public FactorFormula getDamageTaken() {
        return damageTaken;
    }

    @Nullable
    public FactorFormula getPresence() {
        return presence;
    }

    @Nullable
    public Double getMinShare() {
        return minShare;
    }

    @Nullable
    public Boolean getCreditDead() {
        return creditDead;
    }

    @Nullable
    public Boolean getCreditDisconnected() {
        return creditDisconnected;
    }

    /** Whether this rule takes part in matching at all; unauthored means yes. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }
}
