package com.ziggfreed.common.recipe;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One native crafting recipe as the engine holds it in its {@code CraftingRecipe} store, with FULL
 * quantities on both sides.
 *
 * <p><b>Both kinds of native recipe are this one shape.</b> A STANDALONE recipe is its own asset
 * file (vanilla's {@code Salvage_<ItemId>} set at the {@code Salvagebench} is the large example); an
 * INLINE recipe is authored inside an item and loaded into the same store under the id
 * {@code <ItemId>_Recipe_Generated_<n>}, and carries that item as {@link #ownerItemId()}.
 *
 * <p><b>{@code outputs} is the complete list the recipe gives</b>, each line at its authored
 * quantity, exactly as the engine's {@code getOutputs()} reports it: when a recipe authors no
 * {@code Output} list, the engine fills it with the primary output at its own quantity, so an inline
 * recipe making four planks reads one line of four. A standalone recipe that authors neither an
 * {@code Output} nor a {@code PrimaryOutput} gives the engine nothing to report and reads with no
 * outputs.
 *
 * @param id            the recipe's store id
 * @param inputs        what the recipe consumes, possibly empty
 * @param outputs       everything the recipe gives, possibly empty
 * @param primaryOutput the output the engine treats as the recipe's product, or null
 * @param benches       the benches it can be made at, possibly empty (a pocket recipe)
 * @param timeSeconds   the native craft time
 * @param ownerItemId   for an inline recipe the item it is authored in, else null
 */
public record NativeRecipe(@Nonnull String id, @Nonnull List<RecipeMaterial> inputs,
        @Nonnull List<RecipeMaterial> outputs, @Nullable RecipeMaterial primaryOutput,
        @Nonnull List<RecipeBench> benches, float timeSeconds, @Nullable String ownerItemId) {

    public NativeRecipe {
        id = id == null ? "" : id.trim();
        inputs = inputs == null ? List.of() : inputs.stream().filter(Objects::nonNull).toList();
        outputs = outputs == null ? List.of() : outputs.stream().filter(Objects::nonNull).toList();
        benches = benches == null ? List.of() : benches.stream().filter(Objects::nonNull).toList();
        timeSeconds = Math.max(0f, timeSeconds);
        ownerItemId = ownerItemId == null || ownerItemId.isBlank() ? null : ownerItemId.trim();
    }

    /** True for a recipe authored inside an item rather than as its own file. */
    public boolean isInline() {
        return ownerItemId != null;
    }

    /** True when the recipe can be made at the bench named {@code benchId} (case-insensitive). */
    public boolean madeAt(@Nullable String benchId) {
        for (RecipeBench bench : benches) {
            if (bench.is(benchId)) {
                return true;
            }
        }
        return false;
    }

    /** True when some input line would accept {@code item}. */
    public boolean consumes(@Nonnull ItemIdentity item) {
        for (RecipeMaterial input : inputs) {
            if (input.accepts(item)) {
                return true;
            }
        }
        return false;
    }

    /** True when some output line gives {@code itemId}. */
    public boolean produces(@Nullable String itemId) {
        for (RecipeMaterial output : outputs) {
            if (output.isItem(itemId)) {
                return true;
            }
        }
        return false;
    }
}
