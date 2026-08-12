package com.ziggfreed.common.npc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.builtin.tagset.config.NPCGroup;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderManager;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.builders.BuilderRoleVariant;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import com.ziggfreed.common.npc.placement.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.NpcPlacementConfig;
import com.ziggfreed.common.npc.placement.PlacedNpcComponent;
import com.ziggfreed.common.util.SafeLog;

/**
 * WHO an NPC is, and which ids it answers to. The one authority for both questions, so a quest, a
 * conversation, a waypoint and a UI page all agree about the character standing in front of the
 * player.
 *
 * <h2>The ladder</h2>
 *
 * <p>Identity is CONVENTION FIRST: almost nothing needs authoring, and what does is a small overlay
 * rather than a table every NPC has to appear in. Asked about a live NPC, this walks:
 *
 * <ol>
 *   <li><b>Its placement.</b> An NPC put there by the placement engine carries a stamp, and the
 *       placement's {@code Identity.NpcId} is the answer. A placement that authors none answers to
 *       its own placement id, so putting an NPC somewhere is enough to give it a name content can
 *       use.</li>
 *   <li><b>An identity overlay on its role.</b> An {@link NpcIdentityAsset} naming that role, or the
 *       role a chain of native {@code Variant}s ultimately references.</li>
 *   <li><b>An identity overlay on a group it belongs to.</b> After the role because the more specific
 *       statement is the one the author wrote about that role in particular.</li>
 *   <li><b>Its role id, in lower case.</b> The convention: anything using the {@code Kweebec_Elder}
 *       role is {@code kweebec_elder}, with no file anywhere.</li>
 *   <li><b>Nobody.</b> Null, and nothing credits.</li>
 * </ol>
 *
 * <p>The convention is the FLOOR, not a rung that outranks the overlays. Both overlay forms are
 * statements an author wrote on purpose - "these two roles are the same person", "this whole family
 * answers to one name" - and a convention that beat them would leave the group form unable to ever
 * apply, since every NPC already has a role name.
 *
 * <h2>Answer sets</h2>
 *
 * <p>An NPC IS one id (its primary) and ANSWERS to several (its primary plus its aliases). Aliases go
 * one way: the primary is what a nameplate reads and what a waypoint points at, while an alias only
 * decides whether content aimed at some other id resolves here. That asymmetry is what lets the same
 * character stand in two places, each with its own primary, both answering to the shared name.
 *
 * <p>Case: authored case is PRESERVED in every answer set, because a consumer's content matching may
 * be case-sensitive; every membership test here is case-INSENSITIVE, matching how the engine itself
 * compares a role name.
 *
 * <h2>The index</h2>
 *
 * <p>Every answer-set read goes through ONE index built lazily from the folded placements and identity
 * overlays, not a scan per call. It is rebuilt on the next read after either config changes. These
 * reads sit on quest, dialogue and UI paths that run per objective, per quest and inside page sort
 * comparators, where a scan per call is a scan per row per comparison.
 *
 * <p>World-thread for {@link #npcIdOfEntity} (it reads components); everything else is pure over the
 * folded assets and safe from anywhere.
 */
public final class NpcIdentities {

    /** The immutable resolved index, rebuilt whenever either source config changes. */
    private static final AtomicReference<Index> INDEX = new AtomicReference<>();

    private NpcIdentities() {
    }

    // ==================== the ladder ====================

    /**
     * Who is this NPC? Walks the whole ladder for a live entity, or null when nothing names it.
     * World thread (it reads the placement stamp and the NPC role off the store).
     */
    @Nullable
    public static String npcIdOfEntity(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> npcRef) {
        if (npcRef == null || !npcRef.isValid()) {
            return null;
        }
        String fromPlacement = npcIdOfPlacement(placementIdOf(store, npcRef));
        if (fromPlacement != null) {
            return fromPlacement;
        }
        String roleName = roleNameOf(store, npcRef);
        String fromRoleOverlay = overlayForRole(roleName);
        if (fromRoleOverlay != null) {
            return fromRoleOverlay;
        }
        String fromGroupOverlay = npcIdOfGroupMembership(store, npcRef);
        if (fromGroupOverlay != null) {
            return fromGroupOverlay;
        }
        return roleName == null ? null : normalize(roleName);
    }

    /**
     * The id a placement gives whoever stands at it: its authored {@code Identity.NpcId}, or the
     * placement id itself when it authors none. Null when no such placement is loaded.
     */
    @Nullable
    public static String npcIdOfPlacement(@Nullable String placementId) {
        if (placementId == null || placementId.isBlank()) {
            return null;
        }
        return index().primaryByPlacement.get(normalize(placementId));
    }

    /**
     * The id an NPC role carries: an identity overlay naming that role (or the role a chain of native
     * {@code Variant}s ultimately references), else the role id in lower case. Null only for a blank
     * role name.
     *
     * <p>A GROUP overlay cannot be consulted here: group membership is a question about an entity, not
     * about a name. {@link #npcIdOfEntity} asks it in between.
     */
    @Nullable
    public static String npcIdOfRole(@Nullable String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return null;
        }
        String overlay = overlayForRole(roleName);
        return overlay != null ? overlay : normalize(roleName);
    }

    /**
     * The identity an overlay declares for this role, walking a native {@code Variant} chain up to
     * the role it ultimately references, or null when no overlay claims any of them.
     *
     * <p>A variant carries its OWN file id as its role name, so an identity written about the template
     * every variant of it shares would otherwise never be found.
     */
    @Nullable
    private static String overlayForRole(@Nullable String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return null;
        }
        Index index = index();
        String direct = index.primaryByRole.get(normalize(roleName));
        if (direct != null) {
            return direct;
        }
        for (String ancestor : roleAncestry(roleName)) {
            String inherited = index.primaryByRole.get(normalize(ancestor));
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    // ==================== answer sets ====================

    /**
     * Every id the placement {@code placementId} answers to: its primary first, then each alias it
     * lists, in authoring order and with authored case. Empty when no such placement is loaded.
     */
    @Nonnull
    public static Set<String> answerSetOf(@Nullable String placementId) {
        if (placementId == null || placementId.isBlank()) {
            return Set.of();
        }
        return index().answersByPlacement.getOrDefault(normalize(placementId), Set.of());
    }

    /** Does the placement {@code placementId} answer to {@code npcId}? Case-insensitive. */
    public static boolean answersTo(@Nullable String placementId, @Nullable String npcId) {
        return containsIgnoreCase(answerSetOf(placementId), npcId);
    }

    /**
     * Every id answered to by whoever's PRIMARY id is {@code npcId} - the id itself plus every alias
     * listed beside it, wherever it is declared. This is the read every content site wants: given the
     * character a player is standing at, which ids does content aimed at it match?
     *
     * <p>{@code npcId} is always in the result, so a bare id nothing declares simply matches itself,
     * exactly as a single-id comparison would.
     */
    @Nonnull
    public static Set<String> answerSetForPrimary(@Nullable String npcId) {
        if (npcId == null || npcId.isBlank()) {
            return Set.of();
        }
        String wanted = npcId.trim();
        Set<String> declared = index().answersByPrimary.get(normalize(wanted));
        if (declared == null) {
            return Set.of(wanted);
        }
        Set<String> out = new LinkedHashSet<>();
        out.add(wanted);
        out.addAll(declared);
        return frozen(out);
    }

    /**
     * Does content bound to {@code candidateId} resolve at the character whose primary id is
     * {@code primaryNpcId}? Case-INSENSITIVE.
     *
     * <p>The overwhelmingly common shape - content bound to the character's OWN id - is answered
     * without touching the index at all, so the alias fold costs nothing until an alias could actually
     * decide the answer.
     */
    public static boolean primaryAnswersTo(@Nullable String primaryNpcId, @Nullable String candidateId) {
        if (primaryNpcId != null && candidateId != null && !primaryNpcId.isBlank()
                && primaryNpcId.trim().equalsIgnoreCase(candidateId.trim())) {
            return true;
        }
        return containsIgnoreCase(answerSetForPrimary(primaryNpcId), candidateId);
    }

    /**
     * The alias ids declared beside a placement's primary (the primary itself is NOT included;
     * {@link #answerSetOf} is the full set).
     */
    @Nonnull
    public static List<String> aliasesOf(@Nullable String placementId) {
        Set<String> answers = answerSetOf(placementId);
        if (answers.size() <= 1) {
            return List.of();
        }
        List<String> out = new ArrayList<>(answers);
        return List.copyOf(out.subList(1, out.size()));
    }

    /**
     * Every placement id ANSWERING to {@code npcId} (lower-cased, the ledger and position-cache key):
     * the placement whose primary it is, plus every placement listing it as an alias. Usually one, but
     * the same character may stand in several worlds, and an alias is exactly the "same character,
     * second location" case - so a waypoint for an aliased id marks all of them, which is the intent.
     *
     * <p>Sorted by placement id, so a surface listing them is stable across restarts rather than
     * following however the folded pool happened to hash.
     */
    @Nonnull
    public static List<String> placementsForNpcId(@Nullable String npcId) {
        if (npcId == null || npcId.isBlank()) {
            return List.of();
        }
        return index().placementsByAnsweredId.getOrDefault(normalize(npcId), List.of());
    }

    /**
     * Every id anything loaded answers to - each placement's primary plus its aliases, and every
     * identity overlay's - in authoring order with authored case. This is the vocabulary content may
     * bind against, so it is what a consumer's validator checks an authored giver id against.
     *
     * <p>It cannot include the CONVENTION ids (a role nobody has authored an overlay for still answers
     * to its own name), because the set of roles on a server is not enumerable from here. A consumer
     * treating an unknown id as an error would therefore be wrong; treat it as unverifiable.
     */
    @Nonnull
    public static Set<String> allDeclaredNpcIds() {
        return index().allDeclared;
    }

    /** Drop the resolved index. Called by both source configs on every layer merge. */
    public static void invalidate() {
        INDEX.set(null);
    }

    // ==================== the index ====================

    /**
     * The resolved identity tables, all keyed lower-case. Immutable once built, replaced wholesale on
     * invalidation, so a reader never sees a half-built one.
     */
    private record Index(@Nonnull Map<String, String> primaryByPlacement,
                         @Nonnull Map<String, Set<String>> answersByPlacement,
                         @Nonnull Map<String, Set<String>> answersByPrimary,
                         @Nonnull Map<String, List<String>> placementsByAnsweredId,
                         @Nonnull Map<String, String> primaryByRole,
                         @Nonnull Map<String, String> primaryByGroup,
                         @Nonnull Set<String> allDeclared) {
    }

    @Nonnull
    private static Index index() {
        Index current = INDEX.get();
        if (current != null) {
            return current;
        }
        Index built = build();
        // Two callers racing on the first read both build a snapshot of the same content; whichever
        // lands is the one everybody then reads, so the loser's copy is simply dropped.
        return INDEX.compareAndSet(null, built) ? built : index();
    }

    @Nonnull
    private static Index build() {
        Map<String, String> primaryByPlacement = new LinkedHashMap<>();
        Map<String, Set<String>> answersByPlacement = new LinkedHashMap<>();
        Map<String, Set<String>> answersByPrimary = new LinkedHashMap<>();
        Map<String, List<String>> placementsByAnsweredId = new LinkedHashMap<>();
        Map<String, String> primaryByRole = new LinkedHashMap<>();
        Map<String, String> primaryByGroup = new LinkedHashMap<>();
        Set<String> allDeclared = new LinkedHashSet<>();

        try {
            // Sorted by placement id: the folded pool is hash-ordered, so an unsorted walk would give
            // a different answer order on every restart - and a waypoint list that reshuffles between
            // restarts is a wobble nobody can explain.
            Map<String, NpcPlacementAsset> placements =
                    new TreeMap<>(NpcPlacementConfig.getInstance().all());
            for (NpcPlacementAsset placement : placements.values()) {
                String placementId = placement.getId();
                if (placementId == null || placementId.isBlank()) {
                    continue;
                }
                String key = normalize(placementId);
                String primary = primaryOf(placement, placementId);
                Set<String> answers = new LinkedHashSet<>();
                answers.add(primary);
                answers.addAll(aliasesDeclaredBy(placement));

                primaryByPlacement.put(key, primary);
                answersByPlacement.put(key, frozen(answers));
                allDeclared.addAll(answers);
                mergeAnswers(answersByPrimary, primary, answers);
                for (String answered : answers) {
                    placementsByAnsweredId.computeIfAbsent(normalize(answered), k -> new ArrayList<>())
                            .add(key);
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[identity] could not index the loaded placements: " + t.getMessage());
        }

        try {
            // Sorted by file id so a collision resolves the same way on every restart, and the
            // validator can name which file won.
            Map<String, NpcIdentityAsset> overlays =
                    new TreeMap<>(NpcIdentityConfig.getInstance().all());
            for (NpcIdentityAsset overlay : overlays.values()) {
                String primary = trimToNull(overlay.getNpcId());
                if (primary == null) {
                    continue;
                }
                Set<String> answers = new LinkedHashSet<>();
                answers.add(primary);
                answers.addAll(cleanIds(overlay.getAliases()));
                allDeclared.addAll(answers);
                mergeAnswers(answersByPrimary, primary, answers);

                String role = trimToNull(overlay.getRole());
                if (role != null) {
                    primaryByRole.putIfAbsent(normalize(role), primary);
                }
                String group = trimToNull(overlay.getGroup());
                if (group != null) {
                    primaryByGroup.putIfAbsent(normalize(group), primary);
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[identity] could not index the loaded identity overlays: " + t.getMessage());
        }

        Map<String, List<String>> frozenPlacements = new LinkedHashMap<>();
        placementsByAnsweredId.forEach((id, list) -> frozenPlacements.put(id, List.copyOf(list)));
        Map<String, Set<String>> frozenAnswers = new LinkedHashMap<>();
        answersByPrimary.forEach((id, set) -> frozenAnswers.put(id, frozen(set)));

        return new Index(Map.copyOf(primaryByPlacement), Map.copyOf(answersByPlacement),
                Map.copyOf(frozenAnswers), Map.copyOf(frozenPlacements),
                Map.copyOf(primaryByRole), Map.copyOf(primaryByGroup), frozen(allDeclared));
    }

    /**
     * A placement's primary id: its authored {@code Identity.NpcId}, else the placement id itself. The
     * fallback is what makes identity free - an NPC put somewhere is already nameable by content -
     * and it can only ever ADD an id, never take one away.
     */
    @Nonnull
    private static String primaryOf(@Nonnull NpcPlacementAsset placement, @Nonnull String placementId) {
        NpcPlacementAsset.Identity identity = placement.getIdentity();
        String authored = identity == null ? null : trimToNull(identity.getNpcId());
        return authored != null ? authored : placementId;
    }

    /** The alias ids a placement declares, cleaned: trimmed, blanks dropped, repeats folded away. */
    @Nonnull
    private static List<String> aliasesDeclaredBy(@Nonnull NpcPlacementAsset placement) {
        NpcPlacementAsset.Identity identity = placement.getIdentity();
        return identity == null ? List.of() : cleanIds(identity.getAliases());
    }

    /**
     * Fold one declaration's answer set into the by-primary table. Two declarations sharing a primary
     * (the same character standing in two worlds) contribute the UNION, which is exactly what makes
     * one quest step resolvable at either of them.
     */
    private static void mergeAnswers(@Nonnull Map<String, Set<String>> table, @Nonnull String primary,
            @Nonnull Set<String> answers) {
        table.computeIfAbsent(normalize(primary), k -> new LinkedHashSet<>()).addAll(answers);
    }

    // ==================== engine reads ====================

    /** The placement id stamped on this NPC, or null when it carries no stamp. */
    @Nullable
    private static String placementIdOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> npcRef) {
        try {
            var type = PlacedNpcComponent.getComponentType();
            if (type == null) {
                return null;
            }
            PlacedNpcComponent component = store.getComponent(npcRef, type);
            if (component == null || component.placementId == null || component.placementId.isBlank()) {
                return null;
            }
            return component.placementId;
        } catch (Throwable t) {
            SafeLog.fine("[identity] could not read the placement stamp: " + t.getMessage());
            return null;
        }
    }

    /** This entity's NPC role name, or null when it is not an NPC (or nothing is up to ask). */
    @Nullable
    private static String roleNameOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> npcRef) {
        try {
            NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
            return npc == null ? null : trimToNull(npc.getRoleName());
        } catch (Throwable t) {
            SafeLog.fine("[identity] could not read the NPC role: " + t.getMessage());
            return null;
        }
    }

    /**
     * The roles a native {@code Variant} chain references, nearest first. A variant carries its OWN
     * file id as its role name, so an identity written about the template every variant of it shares
     * would otherwise never be found. Empty outside a running server, which correctly reads as "no
     * inherited identity" rather than as an error.
     */
    @Nonnull
    private static List<String> roleAncestry(@Nonnull String roleName) {
        List<String> out = new ArrayList<>(2);
        try {
            NPCPlugin plugin = NPCPlugin.get();
            if (plugin == null) {
                return out;
            }
            BuilderManager builders = plugin.getBuilderManager();
            if (builders == null) {
                return out;
            }
            int index = plugin.getIndex(roleName);
            Builder<Role> builder = plugin.tryGetCachedValidRole(index);
            // The engine's own walk, with a bound: a variant of a variant is legal and unguarded
            // against cycles, so a malformed pair must not spin here.
            int guard = 0;
            while (builder instanceof BuilderRoleVariant variant && guard++ < 16) {
                int referenced = variant.getReferenceIndex();
                String name = trimToNull(plugin.getName(referenced));
                if (name == null || out.contains(name)) {
                    break;
                }
                out.add(name);
                builder = builders.getCachedBuilder(referenced, Role.class);
            }
        } catch (Throwable t) {
            SafeLog.fine("[identity] could not walk the role chain of '" + roleName + "': " + t.getMessage());
        }
        return out;
    }

    /**
     * The identity of the first declared GROUP this NPC belongs to, or null. Asked last, and only when
     * neither a placement, an overlay nor the role convention answered.
     */
    @Nullable
    private static String npcIdOfGroupMembership(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> npcRef) {
        Map<String, String> byGroup = index().primaryByGroup;
        if (byGroup.isEmpty()) {
            return null;
        }
        try {
            var groups = NPCGroup.getAssetMap();
            if (groups == null) {
                return null;
            }
            for (Map.Entry<String, String> entry : byGroup.entrySet()) {
                int groupIndex = groups.getIndex(entry.getKey());
                if (groupIndex == AssetMapWithIndexes.NOT_FOUND) {
                    continue;
                }
                // NOT_FOUND as the parent role index deliberately: it can never equal a real role, so
                // the engine's "$self counts as a member" shortcut cannot fire and the test is pure
                // group membership.
                if (WorldSupport.isGroupMember(AssetMapWithIndexes.NOT_FOUND, npcRef, groupIndex, store)) {
                    return entry.getValue();
                }
            }
        } catch (Throwable t) {
            SafeLog.fine("[identity] could not test group membership: " + t.getMessage());
        }
        return null;
    }

    // ==================== pure helpers ====================

    /**
     * One id per entry, each trimmed, blanks dropped, repeats folded away, authored case kept,
     * first-listed order preserved. Pure.
     */
    @Nonnull
    static List<String> cleanIds(@Nullable String[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>(values.length);
        for (String entry : values) {
            String id = trimToNull(entry);
            if (id != null && !containsIgnoreCase(out, id)) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    static boolean containsIgnoreCase(@Nonnull Collection<String> haystack, @Nullable String needle) {
        if (needle == null || needle.isBlank()) {
            return false;
        }
        String wanted = needle.trim();
        for (String candidate : haystack) {
            if (candidate != null && candidate.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
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

    /**
     * An unmodifiable view that KEEPS insertion order. {@code Set.copyOf} does not: it is unordered by
     * contract, and an answer set is primary-first-then-authored-order all the way through, because a
     * caller reading "the first id" must get the primary.
     */
    @Nonnull
    private static Set<String> frozen(@Nonnull Set<String> ordered) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(ordered));
    }
}
