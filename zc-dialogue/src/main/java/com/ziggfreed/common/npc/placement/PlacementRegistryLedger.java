package com.ziggfreed.common.npc.placement;

import com.ziggfreed.common.registry.RegistryLedger;

/**
 * The placement engine's registration bookkeeping: a thin naming of the shared
 * {@link RegistryLedger} that fixes the {@code [placement]} log prefix, so an overwrite warning
 * from {@link AnchorResolverRegistry} says which engine it came from.
 * ({@link PlacementFactorRegistry} logs under the same prefix, but takes it from the label it
 * passes its zc-core {@code FactorRegistry}, not from this subclass.)
 *
 * <p>Every semantic lives in the parent - normalized ids, owner attribution, identity-compared
 * warn-once overwrite, per-entry failure counting, the {@code info()} snapshot an admin listing
 * reads - and {@link RegistryLedger.RegistrationInfo} is inherited by name, so
 * {@code PlacementRegistryLedger.RegistrationInfo} keeps resolving for every caller written
 * against it.
 *
 * @param <T> the registered value type (a handler, a provider, a resolver)
 */
public final class PlacementRegistryLedger<T> extends RegistryLedger<T> {

    public PlacementRegistryLedger() {
        super("placement");
    }
}
