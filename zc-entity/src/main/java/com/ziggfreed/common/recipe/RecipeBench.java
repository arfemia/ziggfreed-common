package com.ziggfreed.common.recipe;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One bench a native recipe can be made at, read off the engine's {@code BenchRequirement}: the
 * bench's id ({@code Salvagebench}, {@code Workbench}), its kind ({@code Crafting},
 * {@code Processing}, ...), the categories it files the recipe under, and the bench tier the recipe
 * needs.
 *
 * @param id                the bench id, never null (blank when the engine authored none)
 * @param type              the native bench kind's name, or null
 * @param categories        the bench categories the recipe is filed under, possibly empty
 * @param requiredTierLevel the bench tier the recipe needs (0 when any tier will do)
 */
public record RecipeBench(@Nonnull String id, @Nullable String type, @Nonnull List<String> categories,
        int requiredTierLevel) {

    public RecipeBench {
        id = id == null ? "" : id.trim();
        type = type == null || type.isBlank() ? null : type.trim();
        categories = categories == null ? List.of()
                : categories.stream().filter(Objects::nonNull).filter(c -> !c.isBlank()).toList();
    }

    /** True when this is the bench named {@code benchId}, matched without regard to case. */
    public boolean is(@Nullable String benchId) {
        return benchId != null && !id.isEmpty() && id.equalsIgnoreCase(benchId.trim());
    }
}
