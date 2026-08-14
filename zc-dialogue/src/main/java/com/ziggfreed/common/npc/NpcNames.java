package com.ziggfreed.common.npc;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.builders.BuilderRoleVariant;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
import com.hypixel.hytale.server.npc.util.expression.Scope;
import com.hypixel.hytale.server.spawning.ISpawnableWithModel;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.npc.placement.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.NpcPlacementConfig;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a character is CALLED, from the one place its name is authored: the NPC role's
 * {@code NameTranslationKey}.
 *
 * <p>A role file already has to carry that key or the NPC stands there labelled with its raw role
 * id, so every other surface reads the SAME key rather than inventing a second source. The
 * nameplate above the NPC, the header of the conversation, the "Talk to X" line in a quest and the
 * validators all end up at this class, which is what makes it structurally impossible for the
 * header and the nameplate to disagree.
 *
 * <h2>The ladder</h2>
 *
 * <ol>
 *   <li><b>A live NPC.</b> When the caller is holding a spawned entity (a press-F opened the page on
 *       the NPC's own ref), its BUILT {@link Role} already carries the resolved key, and that is
 *       byte-for-byte the value the nameplate renders.</li>
 *   <li><b>An id, resolved statically.</b> No entity needed: the id names a placement (or a
 *       character standing at one), the placement names a role, and the role's declarative builder
 *       is walked for the key it would resolve to. An id nothing has stood up is treated AS a role
 *       id, which is the same character-IS-its-role default {@link NpcIdentities} runs on.</li>
 *   <li><b>Nothing.</b> Null.</li>
 * </ol>
 *
 * <p><b>Null is the answer, never a rescue.</b> There is no prettified id, no {@code npcs.<id>.name}
 * guess and no case-fold retry: a guessed key renders as its own raw text on screen and reads to a
 * player as a name somebody chose, so a wrong one is worse than a blank one. A character with no
 * resolvable key is a validator finding ({@code NO_DISPLAY_NAME}), reported once at the late audit,
 * rather than a wrong word in front of every player who talks to it.
 *
 * <h2>Guarding and caching</h2>
 *
 * <p>The static walk touches the NPC plugin's builder registry, so it is guarded as a whole: a unit
 * JVM, a call before the plugin is up, or an engine shape this walk got wrong all degrade to null
 * plus one fine-level line, never a throw into a page render or an audit loop.
 *
 * <p>Answers are cached POSITIVELY, per role id: role builders load once per boot, so a resolved
 * key cannot go stale under a running server. Nothing negative is cached, because a pack registered
 * later brings roles with it and a remembered "no" would outlive the reason for it. {@link
 * #invalidate()} drops the cache for a role hot-reload.
 *
 * <p>World-thread for the live-entity form (it reads a component); every other form is safe from
 * anywhere.
 */
public final class NpcNames {

    /**
     * The bound on a {@code Variant} chain walk. The engine itself ships no cycle detector, so a
     * pair of roles referencing each other would spin here forever.
     */
    private static final int MAX_ROLE_CHAIN = 16;

    /** Lower-cased role id -> its resolved key. Positive answers only. */
    private static final Map<String, String> KEY_BY_ROLE = new ConcurrentHashMap<>();

    private NpcNames() {
    }

    // ==================== the display name ====================

    /**
     * The name to SHOW for the character {@code npcId}, or null when nothing names it.
     *
     * <p>A key-built {@link Message} the player's own client resolves in its own locale; the server
     * never resolves it and never stores a locale.
     */
    @Nullable
    public static Message nameFor(@Nullable String npcId) {
        return message(nameKeyFor(npcId));
    }

    /**
     * The name to SHOW for the character a caller is standing in front of. The live entity answers
     * first (its built role carries exactly what the nameplate renders); the id is the fallback for
     * a caller with no entity in hand, and either may be null.
     *
     * <p><b>World thread</b> when {@code npcRef} is given.
     */
    @Nullable
    public static Message nameFor(@Nullable String npcId, @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store) {
        return message(nameKeyFor(npcId, npcRef, store));
    }

    // ==================== the key ====================

    /**
     * The authored {@code NameTranslationKey} for the character {@code npcId}, or null.
     *
     * <p>The id is asked about as a CHARACTER first: whichever placements answer to it (its own
     * standings plus any that alias it, in the stable order {@link NpcIdentities} lists them) are
     * tried in turn, so the first one whose role carries a key wins. An id nothing has stood up
     * falls through to being read as a role id directly, which is what makes the naming convention
     * work with no file anywhere.
     */
    @Nullable
    public static String nameKeyFor(@Nullable String npcId) {
        String id = trimToNull(npcId);
        if (id == null) {
            return null;
        }
        for (String placementId : NpcIdentities.placementsForNpcId(id)) {
            String key = nameKeyOfPlacement(placementId);
            if (key != null) {
                return key;
            }
        }
        return nameKeyOfRole(id);
    }

    /**
     * The authored {@code NameTranslationKey} for the character a caller is standing in front of,
     * or null. A live entity is asked first, then the id, then - for a caller that has an entity but
     * no id - whoever {@link NpcIdentities} says that entity is.
     *
     * <p><b>World thread</b> when {@code npcRef} is given.
     */
    @Nullable
    public static String nameKeyFor(@Nullable String npcId, @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store) {
        String live = liveNameKey(npcRef, store);
        if (live != null) {
            return live;
        }
        String id = trimToNull(npcId);
        if (id == null && npcRef != null && store != null) {
            id = NpcIdentities.npcIdOfEntity(store, npcRef);
        }
        return nameKeyFor(id);
    }

    /**
     * The authored {@code NameTranslationKey} of whoever stands at the placement {@code placementId},
     * or null when no such placement is loaded, it names no role, or that role carries no key.
     *
     * <p>This is the validator's question: a placement IS its role, and the role is where a name is
     * written.
     */
    @Nullable
    public static String nameKeyOfPlacement(@Nullable String placementId) {
        String id = trimToNull(placementId);
        if (id == null) {
            return null;
        }
        return nameKeyOfRole(roleOfPlacement(id));
    }

    /**
     * The authored {@code NameTranslationKey} of an NPC role, walking a native {@code Variant} chain
     * for the key the engine itself would resolve at spawn. Null when the role is not loaded, when
     * the chain cannot be walked, or when the role carries no key at all.
     */
    @Nullable
    public static String nameKeyOfRole(@Nullable String roleName) {
        String role = trimToNull(roleName);
        if (role == null) {
            return null;
        }
        String cached = KEY_BY_ROLE.get(normalize(role));
        if (cached != null) {
            return cached;
        }
        String resolved = trimToNull(walkRoleForNameKey(role));
        if (resolved != null) {
            KEY_BY_ROLE.put(normalize(role), resolved);
        }
        return resolved;
    }

    /** Drop every cached answer. For a role hot-reload, and for tests. */
    public static void invalidate() {
        KEY_BY_ROLE.clear();
    }

    /**
     * Whether there is a loaded role registry to ask at all.
     *
     * <p>FALSE means "cannot tell", never "no role carries a name": a unit JVM, a call before the
     * NPC plugin is up and a failed read all answer false. An audit that read a null key as an
     * answer without asking this first would report a finding against every placement on the server
     * the moment it ran a millisecond too early, which is the same trap
     * {@code WorldIdentity.loadedWorlds()} returning an empty list guards against.
     */
    public static boolean canResolveNames() {
        try {
            NPCPlugin plugin = NPCPlugin.get();
            return plugin != null && plugin.getBuilderManager() != null
                    && !plugin.getBuilderManager().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Remember a resolved key for {@code roleName} without a running server: the tests' door into the
     * half of this class that is pure. The walk itself needs an engine, so everything above it - which
     * placement answers for a character, which role that placement stands, what case things were
     * spelled in - would otherwise only ever be exercised in game.
     */
    static void cacheForTests(@Nonnull String roleName, @Nonnull String nameKey) {
        KEY_BY_ROLE.put(normalize(roleName), nameKey);
    }

    // ==================== engine reads ====================

    /**
     * The key carried by a live NPC's BUILT role: the value the nameplate is already rendering, and
     * the one answer no static walk can be more right than.
     */
    @Nullable
    private static String liveNameKey(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        try {
            NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
            if (npc == null) {
                return null;
            }
            Role role = npc.getRole();
            return role == null ? null : trimToNull(role.getNameTranslationKey());
        } catch (Throwable t) {
            SafeLog.fine("[names] could not read the live role's name key: " + t.getMessage());
            return null;
        }
    }

    /**
     * The whole static resolution, in ONE guarded method on purpose.
     *
     * <p>It is assembled from the engine's own pieces rather than copied from a first-party caller
     * that does exactly this - nothing in the engine resolves a role's name without an entity - so
     * one wrong assumption about the builder framework must cost a blank name and a fine-level line,
     * never a throw into a page render. That is what the single try/catch around the entire body
     * buys, and why the walk is not spread across helpers that could each escape it.
     *
     * <p>The walk itself:
     * <ol>
     *   <li>the role id becomes a builder index, and the index a cached role BUILDER (declarative,
     *       never a built {@code Role} - building one needs a live entity);</li>
     *   <li>a {@code Variant} builds its MERGED modifier scope, which accumulates every hop's
     *       {@code Modify} block up the chain. The engine restores the context's previous scope
     *       before handing that scope back, so setting it on the context afterwards is load-bearing
     *       rather than tidy;</li>
     *   <li>the chain's reference indexes are walked to the terminal non-{@code Variant} base, since
     *       that is the builder whose field actually holds the key;</li>
     *   <li>a plain role skips both of those and simply gets its own parameters' scope, mirroring
     *       what the engine does at spawn;</li>
     *   <li>the base is asked for the key against that scope. The scope is what resolves a
     *       {@code {"Compute": "NameTranslationKey"}} binding, which is how a shared template lets
     *       each variant name itself.</li>
     * </ol>
     */
    @Nullable
    private static String walkRoleForNameKey(@Nonnull String roleName) {
        try {
            NPCPlugin plugin = NPCPlugin.get();
            if (plugin == null) {
                return null; // No server: not an error, just nothing to ask.
            }
            int index = plugin.getIndex(roleName);
            if (index < 0) {
                return null;
            }
            Builder<Role> builder = plugin.tryGetCachedValidRole(index);
            if (builder == null) {
                return null;
            }

            ExecutionContext context = new ExecutionContext();
            Scope scope;
            Builder<Role> base = builder;
            if (builder instanceof BuilderRoleVariant variant) {
                scope = variant.createModifierScope(context);
                int guard = 0;
                while (base instanceof BuilderRoleVariant hop && guard++ < MAX_ROLE_CHAIN) {
                    Builder<Role> referenced = plugin.tryGetCachedValidRole(hop.getReferenceIndex());
                    if (referenced == null) {
                        return null; // A chain that does not land anywhere names nobody.
                    }
                    base = referenced;
                }
                if (base instanceof BuilderRoleVariant) {
                    SafeLog.fine("[names] the role chain of '" + roleName + "' does not reach a base role "
                            + "within " + MAX_ROLE_CHAIN + " hops");
                    return null;
                }
            } else {
                var parameters = builder.getBuilderParameters();
                scope = parameters == null ? null : parameters.createScope();
            }
            if (!(base instanceof ISpawnableWithModel spawnable)) {
                return null;
            }
            context.setScope(scope);
            return spawnable.getNameTranslationKey(context, scope);
        } catch (Throwable t) {
            SafeLog.fine("[names] could not resolve the name key of role '" + roleName + "': " + t.getMessage());
            return null;
        }
    }

    // ==================== pure helpers ====================

    /** The role a loaded placement names, or null when no such placement is loaded or it names none. */
    @Nullable
    private static String roleOfPlacement(@Nonnull String placementId) {
        try {
            NpcPlacementAsset placement = NpcPlacementConfig.getInstance().resolve(placementId);
            if (placement == null) {
                return null;
            }
            NpcPlacementAsset.Identity identity = placement.getIdentity();
            return identity == null ? null : trimToNull(identity.getRole());
        } catch (Throwable t) {
            SafeLog.fine("[names] could not read the role of placement '" + placementId + "': " + t.getMessage());
            return null;
        }
    }

    @Nullable
    private static Message message(@Nullable String key) {
        return key == null ? null : Msg.key(key);
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Nonnull
    private static String normalize(@Nonnull String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
