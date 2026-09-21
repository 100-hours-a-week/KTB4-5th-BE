package com.dameokja.backend.ingredient.presentation.request;

import com.dameokja.backend.ingredient.application.IngredientCreateCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record IngredientCreateRequest(
        @NotEmpty @Size(max = 20)
        List<@Valid IngredientCreateItem> items
) {

    public List<IngredientCreateCommand> toCommands() {
        return items.stream().map(IngredientCreateItem::toCommand).toList();
    }
}
