package com.ziggfreed.common.subject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.inventory.PlayerAccess;

/**
 * Who an engine operation is about: a stable id, a display name, and an OPAQUE handle the consumer
 * attached.
 *
 * <p>ONE identity vocabulary for every engine in this library. A quest accept, an achievement
 * criterion, a counter tally, a loot grant and a gate evaluation are all about the same someone,
 * and they all say so with this record - so a handler written for one engine reads naturally when
 * another engine calls it, and no engine has to learn a peer's noun.
 *
 * <p>The handle is how those engines stay free of any particular player representation. A consumer
 * puts whatever it needs to act on the player in there - an engine player reference, an entity
 * handle, a session object - and gets it back, typed, inside its own store, probes, gates, and
 * reward handlers via {@link #handleAs}. Nothing in any engine ever looks inside it.
 *
 * <p>A handle that carries SEVERAL of those at once implements {@link HandleFacets} so it can still
 * answer a reader asking for one of them; see that interface for why a rich handle needs it.
 *
 * <h2>A pass-scoped collector rides the subject, by design</h2>
 *
 * <p>Some payouts only mean something inside one pass: a reward that adds to a tally the pass
 * settles once it is over, say. Such a reward needs to reach that pass's own collector, and the
 * Subject is how it does. Every grant already carries the subject, and every nested pass a grant
 * opens (a rolled table granting another table) forwards the very same one, so a collector layered
 * onto the subject with {@link #withFacets} reaches every reward of the pass, at any depth, while the
 * reward vocabulary stays ONE shared registry rather than a copy per pass. The collector is read back
 * through {@link #handleAs} like any other facet, and a payout made with no pass around it simply
 * finds none.
 *
 * <p>Cheap to build and immutable, so constructing one per call is fine.
 */
public record Subject(@Nonnull UUID id, @Nonnull String name, @Nullable Object handle) {

    /**
     * A handle that can also answer for types it is NOT.
     *
     * <p>The plain contract is "the handle comes back typed, or null", and that is enough for as
     * long as a consumer's handle IS the type every reader asks for. It stops being enough the
     * moment a consumer attaches a richer handle - a record carrying a player reference beside an
     * entity and a store, say - because a reader that asks only for the player representation then
     * gets null from a subject that plainly has one. That reader is not hypothetical: the ready-made
     * reward handlers in this library resolve their player exactly that way, so a consumer with a
     * rich handle would find every one of them refusing to pay out.
     *
     * <p>Implement this on the handle and it answers such questions itself. {@link #handleAs} tries
     * the direct cast first, unchanged, and only then asks. Nothing here learns a type, a domain or
     * a player representation: the handle alone names what it can answer for.
     */
    public interface HandleFacets {

        /**
         * What this handle offers for {@code type}, or null when it has nothing to offer. An answer
         * of the wrong type is discarded rather than trusted, so an implementation is free to answer
         * loosely and a caller can never be handed something it did not ask for.
         */
        @Nullable
        Object facet(@Nonnull Class<?> type);
    }

    /** A handle-less subject: enough for the pure state operations, not for granting or taking items. */
    @Nonnull
    public static Subject of(@Nonnull UUID id, @Nonnull String name) {
        return new Subject(id, name, null);
    }

    /**
     * The subject for {@code player}, or null when there is no live player to be about. The
     * player's own id and username come off their {@link PlayerRef} component, so nothing here has
     * to be handed one separately; the handle is the live entity itself, which is what every
     * ready-made reader in this library asks a subject for.
     *
     * <p><b>{@code null} means "nobody standing anywhere", and it is a real answer.</b> Per-player
     * engine state lives on the player's own entity, so a question about somebody who is not in a
     * world has nothing to read and an edit has nowhere to land. A caller guards rather than
     * inventing a subject that would quietly answer wrong.
     */
    @Nullable
    public static Subject of(@Nullable Player player) {
        return of(player == null ? null : PlayerAccess.playerRef(player), player);
    }

    /**
     * The same with the {@link PlayerRef} already in hand, for a caller that resolved one anyway.
     * Both halves are needed: the reference names the player, the entity is what state is read and
     * written through.
     */
    @Nullable
    public static Subject of(@Nullable PlayerRef playerRef, @Nullable Player player) {
        if (playerRef == null || player == null) {
            return null;
        }
        UUID id = playerRef.getUuid();
        if (id == null) {
            return null;
        }
        String name = playerRef.getUsername();
        return new Subject(id, name == null ? "" : name, player);
    }

    /**
     * The same subject (same id, same name) with {@code extras} layered beside its handle, so a
     * reader asking for one of their types finds it.
     *
     * <p>The existing handle keeps the first word: a lookup answers from it exactly as
     * {@link #handleAs} always has (the direct cast, then its own {@link HandleFacets}), and only
     * when it has nothing for the asked type does the first extra that IS that type answer. An
     * extra can therefore never shadow what the handle already offers. A null extra is ignored, and
     * layering an already layered subject keeps everything: the original handle first, then the
     * older extras, then the newer ones. Nothing here learns a type or a domain; the extras alone
     * say what they can answer for.
     *
     * <p>This is how a pass hands its collector to every reward it pays (see the class javadoc).
     * {@link #handle()} then holds the layered wrapper, so read a handle through {@link #handleAs},
     * never through the raw component.
     *
     * @return this subject unchanged when there is nothing non-null to add
     */
    @Nonnull
    public Subject withFacets(@Nullable Object... extras) {
        if (extras == null || extras.length == 0) {
            return this;
        }
        List<Object> added = new ArrayList<>(extras.length);
        for (Object extra : extras) {
            if (extra != null) {
                added.add(extra);
            }
        }
        if (added.isEmpty()) {
            return this;
        }
        Object base = handle;
        List<Object> layers = new ArrayList<>();
        if (handle instanceof Layered layered) {
            base = layered.base();
            layers.addAll(layered.extras());
        }
        layers.addAll(added);
        return new Subject(id, name, new Layered(base, List.copyOf(layers)));
    }

    /**
     * The attached handle cast to {@code type}, or null when there is none and nothing offers one.
     * The one read path, so a consumer never has to write an unchecked cast of its own.
     *
     * <p>The direct cast wins, so a handle can never shadow itself; only when it is something else
     * is a {@link HandleFacets} handle asked what it has for {@code type}. A subject built by
     * {@link #withFacets} answers from its original handle by those same rules first, then from its
     * extras.
     */
    @Nullable
    public <T> T handleAs(@Nonnull Class<T> type) {
        if (handle instanceof Layered layered) {
            Object answer = layered.facet(type);
            return type.isInstance(answer) ? type.cast(answer) : null;
        }
        return read(handle, type);
    }

    /** The plain read: the direct cast, then the handle's own facets, a wrong-typed answer discarded. */
    @Nullable
    private static <T> T read(@Nullable Object handle, @Nonnull Class<T> type) {
        if (type.isInstance(handle)) {
            return type.cast(handle);
        }
        if (handle instanceof HandleFacets facets) {
            Object facet = facets.facet(type);
            return type.isInstance(facet) ? type.cast(facet) : null;
        }
        return null;
    }

    /**
     * The handle {@link #withFacets} builds: the original handle, then the extras in the order they
     * were layered. Private, so nobody can ask a subject for the wrapper itself.
     */
    private record Layered(@Nullable Object base, @Nonnull List<Object> extras) implements HandleFacets {

        @Override
        @Nullable
        public Object facet(@Nonnull Class<?> type) {
            Object fromBase = read(base, type);
            if (fromBase != null) {
                return fromBase;
            }
            for (Object extra : extras) {
                if (type.isInstance(extra)) {
                    return extra;
                }
            }
            return null;
        }
    }
}
