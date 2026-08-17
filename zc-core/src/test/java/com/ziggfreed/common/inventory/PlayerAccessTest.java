package com.ziggfreed.common.inventory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Structural coverage of {@link PlayerAccess}: the ref guard every {@code Player} overload reads
 * through, and the API SHAPE its consumers compile against.
 *
 * <p><b>Scope, and why.</b> The accessors themselves cannot be CALLED here. Each one resolves an
 * engine {@code ComponentType} through the server's module registry
 * ({@code InventoryComponent.Storage#getComponentType()} is
 * {@code EntityModule.get().getStorageInventoryComponentType()}, {@code Hotbar} likewise, and
 * {@code PlayerRef} the same shape), which needs a booted server this unit JVM has none of; a
 * {@code Player} entity cannot be fabricated for the same reason, and the library has no mocking
 * framework on its test classpath. So what is asserted here is everything that does NOT need a
 * boot: {@link PlayerAccess#usable(Ref)}'s own answers, and the declared shape of all five
 * accessors plus the nullability contract three consumer call sites depend on. Reflection
 * RESOLVES those engine parameter types without INITIALIZING them, which is why the shape half is
 * reachable where a call is not. The engine-touching behaviour is smoke-tested in the consuming
 * mods, matching this library's precedent for engine-entity-touching primitives.
 */
class PlayerAccessTest {

    @Test
    void usable_isFalseForNullRef() {
        assertFalse(PlayerAccess.usable(null));
    }

    @Test
    void usable_isFalseForRefPointingAtNoEntity() {
        // A ref built with no assigned entity index is the shape refOf(Player) treats as absent
        // alongside null: an entity reference that was never added to a store, or was removed
        // since. The engine declares the store argument @Nonnull, so passing null here is a
        // deliberate stand-in the engine would never produce itself; it is the only way to reach
        // the UNASSIGNED_INDEX state without a live Store, and nothing below touches the store.
        Ref<EntityStore> ref = new Ref<>(null);
        assertFalse(ref.isValid());
        assertFalse(PlayerAccess.usable(ref));
    }

    @Test
    void everyAccessorHasBothTheTwoArgumentAndThePlayerForm() {
        for (String name : new String[] {"storage", "hotbar", "activeHotbarItem",
                "combinedBackpackStorageHotbar", "playerRef"}) {
            assertNotNull(twoArg(name), name + " is missing its (ComponentAccessor, Ref) form");
            assertNotNull(playerArg(name), name + " is missing its Player convenience overload");
        }
    }

    @Test
    void everyPlayerOverloadIsNullableSoAnUnresolvableRefNeverThrows() {
        // The contract three MMO call sites dereference against: reading straight off a Player
        // answers null when the ref cannot be resolved, it does not throw and it is not @Nonnull.
        for (String name : new String[] {"storage", "hotbar", "activeHotbarItem",
                "combinedBackpackStorageHotbar", "playerRef"}) {
            Method m = playerArg(name);
            assertNotNull(m, name + " is missing its Player convenience overload");
            assertTrue(m.isAnnotationPresent(Nullable.class), name + "(Player) must be @Nullable");
        }
    }

    @Test
    void theTwoArgumentCombinedViewStaysNonnull() {
        // The asymmetry is deliberate and load-bearing: a caller that already holds a live ref
        // gets a container it can use without a null check, while the Player form absorbs an
        // unresolvable ref. Flipping either annotation silently changes what callers must guard.
        Method twoArgForm = twoArg("combinedBackpackStorageHotbar");
        Method playerForm = playerArg("combinedBackpackStorageHotbar");
        assertNotNull(twoArgForm, "combinedBackpackStorageHotbar is missing its (ComponentAccessor, Ref) form");
        assertNotNull(playerForm, "combinedBackpackStorageHotbar is missing its Player convenience overload");
        assertTrue(twoArgForm.isAnnotationPresent(Nonnull.class),
                "combinedBackpackStorageHotbar(ComponentAccessor, Ref) must stay @Nonnull");
        assertTrue(playerForm.isAnnotationPresent(Nullable.class),
                "combinedBackpackStorageHotbar(Player) must stay @Nullable");
    }

    @Test
    void theClassIsAStaticHelperNobodyCanInstantiate() {
        assertThrows(IllegalAccessException.class, () -> PlayerAccess.class.getDeclaredConstructor()
                .newInstance());
    }

    @Nullable
    private static Method twoArg(@Nonnull String name) {
        try {
            return PlayerAccess.class.getDeclaredMethod(name, ComponentAccessor.class, Ref.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    @Nullable
    private static Method playerArg(@Nonnull String name) {
        try {
            return PlayerAccess.class.getDeclaredMethod(name, Player.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
