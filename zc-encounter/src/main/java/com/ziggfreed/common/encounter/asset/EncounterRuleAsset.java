package com.ziggfreed.common.encounter.asset;

import java.util.Locale;

import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * A creature whose kill counts as a boss fight: one row per file at
 * {@code Server/ZiggfreedCommon/EncounterRules/<Id>.json}, naming an NPC role.
 *
 * <pre>{@code
 * {
 *   "Match":   { "Role": "Goblin_Feastmaster" },
 *   "NameKey": "server.npcRoles.Goblin_Feastmaster.name"
 * }
 * }</pre>
 *
 * <p>For a fight the engine runs without a boss bar and no binding row names (so the library opens
 * no run for it): the player who lands the killing blow on that role is credited with the fight,
 * attempted and won, with the role id as the target, so a quest step, a contract or an achievement
 * counting boss fights counts it too. A creature that is the subject of a live run is never credited
 * here: that fight credits its own participants.
 *
 * <p>{@code Match.Role} is the role id exactly, case ignored, never a pattern, so a copy of the role
 * under another id never counts. The role is a plain string, checked by the encounter audit, so a
 * role this server does not have costs a warning and never the file.
 *
 * <p>Override a row by id from a later pack, or leaf by leaf from
 * {@code mods/ziggfreedcommon/encounter-rules.json}; reuse one with a top-level {@code "Parent"};
 * {@code Enabled: false} takes it out.
 */
public final class EncounterRuleAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, EncounterRuleAsset>> {

    /** Where these are authored. One folder, one file per rule, the file name being the id. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/EncounterRules";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Match match;
    @Nullable private String nameKey;
    @Nullable private Boolean enabled;

    public static final AssetBuilderCodec<String, EncounterRuleAsset> CODEC = AssetBuilderCodec.builder(
                    EncounterRuleAsset.class,
                    EncounterRuleAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Match", Match.CODEC, false),
                    (a, v) -> a.match = v, a -> a.match, (a, p) -> a.match = p.match)
            .documentation("Which creature's kill counts as the fight.")
            .add()
            .appendInherited(new KeyedCodec<>("NameKey", Codec.STRING, false),
                    (a, v) -> a.nameKey = v, a -> a.nameKey, (a, p) -> a.nameKey = p.nameKey)
            .documentation("A lang key naming the fight, read on each player's own client. The creature's "
                    + "own name key (server.npcRoles.<Role>.name) is the usual choice.")
            .add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Set false to take this rule out, so a kill of its creature counts as a kill "
                    + "and nothing more. The owner file flips it per id.")
            .add()
            .build();

    public EncounterRuleAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The role id this row names, trimmed, or null when it names none. */
    @Nullable
    public String getRole() {
        String role = match == null ? null : match.role;
        return role == null || role.isBlank() ? null : role.trim();
    }

    @Nullable
    public String getNameKey() {
        return nameKey == null || nameKey.isBlank() ? null : nameKey.trim();
    }

    /** Whether this row counts kills at all; unauthored means yes. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** The key a role id is matched under, the one fold every reader shares: trimmed and lower-cased, or null when blank. */
    @Nullable
    public static String roleKey(@Nullable String roleId) {
        return roleId == null || roleId.isBlank() ? null : roleId.trim().toLowerCase(Locale.ROOT);
    }

    // ==================== Match ====================

    /** Which creature the row counts. */
    public static final class Match {

        @Nullable protected String role;

        public static final BuilderCodec<Match> CODEC = BuilderCodec.builder(Match.class, Match::new)
                .appendInherited(new KeyedCodec<>("Role", Codec.STRING, false),
                        (o, v) -> o.role = v, o -> o.role, (o, p) -> o.role = p.role)
                .documentation("The NPC role id, exactly as the role's file is named (Goblin_Feastmaster); "
                        + "case is ignored, and a copy of the role under another id never matches.")
                .add()
                .build();

        public Match() {
        }
    }
}
