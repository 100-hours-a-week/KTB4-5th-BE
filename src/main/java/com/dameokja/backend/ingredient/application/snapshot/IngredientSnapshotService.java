package com.dameokja.backend.ingredient.application.snapshot;

import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IngredientSnapshotService {
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final IngredientRepository ingredientRepository;

    public List<IngredientSnapshotItem> getAll(Long userId, Long refrigeratorId) {
        refrigeratorAccessService.validateReadAccess(userId, refrigeratorId);
        return ingredientRepository.findAllByRefrigerator_IdOrderByIdAsc(refrigeratorId).stream()
                .map(IngredientSnapshotItem::from)
                .toList();
    }
}
