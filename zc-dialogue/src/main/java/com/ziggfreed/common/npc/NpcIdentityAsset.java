package com.ziggfreed.common.npc;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;

/**
 * "NPCs of this role, or of this group, are this character" - one identity overlay, authored at
 * {@code Server/ZiggfreedCommon/NpcIdentities/<id>.json}.
 *
 * <p>Most NPCs need no file here at all. A character's id defaults to its own NPC role id in lower
 * case, so a quest can already say "talk to {@code kweebec_elder}" about anything using the
 * {@code Kweebec_Elder} role, and a placed NPC is already the id its placement gives it. This asset
 * is for the three cases the convention cannot express:
 *
 * <ul>
 *   <li><b>Aliases.</b> The same character answering to a second id, usually because content was
 *       written against a name before the role existed.</li>
 *   <li><b>One character, several roles.</b> Two roles that are the same person in the fiction (a
 *       day and a night variant, an armed and an unarmed one) sharing one id.</li>
 *   <li><b>A rename.</b> The role file changed name and the content that names the old one should
 *       keep working.</li>
 * </ul>
 *
 * <p>Authored shape:
 * <pre>{@code
 * { "Role": "Kweebec_Elder", "NpcId": "kweebec_elder", "Aliases": ["village_elder"] }
 * }</pre>
 *
 * <p><b>{@code Role} and {@code Group} are two ways to select, not a mode.</b> {@code Role} names one
 * NPC role and is exact. {@code Group} names a native {@code NPCGroup} (the engine's own NPC tag sets,
 * authored at {@code NPC/Groups/<id>.json}) and covers every role in it at once, which is how a whole
 * family gets one id in one file. When a role is covered by both, the {@code Role} match wins, because
 * the more specific statement is the one the author wrote about that role in particular.
 *
 * <p>Two files claiming the SAME role, or the same group, are a content collision: the one whose file
 * id sorts first wins so the answer is stable across restarts, and the validator names both. Two files
 * naming roles that differ only in capitalisation are the same collision, because the engine matches a
 * role name without regard to case.
 */
public final class NpcIdentityAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, NpcIdentityAsset>> {

    /** Where these are authored, relative to a pack's {@code Server/} root. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/NpcIdentities";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String role;
    @Nullable private String group;
    @Nullable private String npcId;
    @Nullable private String[] aliases;

    public static final AssetBuilderCodec<String, NpcIdentityAsset> CODEC = AssetBuilderCodec.builder(
                    NpcIdentityAsset.class,
                    NpcIdentityAsset::new,
                    Codec.STRING,
                    // Canonicalize the id at decode, exactly as the placement asset does: the engine's
                    // key is the verbatim filename while every read here addresses it lower-cased.
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Role", Codec.STRING, false),
                    (a, v) -> a.role = v, a -> a.role, (a, p) -> a.role = p.role)
            .documentation("The NPC role id these NPCs use. Matched without regard to case, the way the engine "
                    + "itself matches a role name. Wins over Group when a role is covered by both.")
            .add()
            .appendInherited(new KeyedCodec<>("Group", Codec.STRING, false),
                    (a, v) -> a.group = v, a -> a.group, (a, p) -> a.group = p.group)
            .documentation("A native NPCGroup id (NPC/Groups/<id>.json) whose every member role gets this "
                    + "identity, so a whole family is covered by one file.")
            .add()
            .appendInherited(new KeyedCodec<>("NpcId", Codec.STRING, false),
                    (a, v) -> a.npcId = v, a -> a.npcId, (a, p) -> a.npcId = p.npcId)
            .documentation("The character id content binds to: a quest's giver, a hand-in target, a talk "
                    + "objective's target. Without one this file selects NPCs and then says nothing about them.")
            .add()
            .appendInherited(new KeyedCodec<>("Aliases", Codec.STRING_ARRAY, false),
                    (a, v) -> a.aliases = v, a -> a.aliases, (a, p) -> a.aliases = p.aliases)
            .documentation("Further ids these NPCs also ANSWER to, one per entry. The primary is what they ARE; "
                    + "an alias is only what they respond to, and aliases go one way. Authoring this replaces "
                    + "the parent's whole list rather than adding to it.")
            .add()
            .build();

    public NpcIdentityAsset() {
    }

    /** Java-side construction (tests, a consumer declaring an identity in code). */
    @Nonnull
    public static NpcIdentityAsset of(@Nonnull String id, @Nullable String role, @Nullable String group,
            @Nullable String npcId, @Nullable String[] aliases) {
        NpcIdentityAsset a = new NpcIdentityAsset();
        a.id = id.toLowerCase(Locale.ROOT);
        a.role = role;
        a.group = group;
        a.npcId = npcId;
        a.aliases = aliases == null ? null : aliases.clone();
        return a;
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public String getRole() {
        return role;
    }

    @Nullable
    public String getGroup() {
        return group;
    }

    @Nullable
    public String getNpcId() {
        return npcId;
    }

    @Nullable
    public String[] getAliases() {
        return aliases == null ? null : aliases.clone();
    }
}
