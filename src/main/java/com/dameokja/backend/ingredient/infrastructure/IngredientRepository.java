package com.dameokja.backend.ingredient.infrastructure;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngredientRepository extends JpaRepository<Ingredient, Long>, IngredientListRepository {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Ingredient i where i.id = :id")
    Optional<Ingredient> findByIdForUpdate(@Param("id") Long id);

    long countByRefrigeratorId(Long refrigeratorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Ingredient i where i.refrigerator.id = :refrigeratorId "
            + "and i.name = :#{#details.name()} "
            + "and i.storageType = :#{#details.storageType()} "
            + "and i.expirationDate = :#{#details.expirationDate()} "
            + "and i.measurement.measureType = :#{#details.measurement().measureType} "
            + "and i.measurement.weightUnit = :#{#details.measurement().weightUnit} order by i.id")
    List<Ingredient> findMergeCandidates(@Param("refrigeratorId") Long refrigeratorId,
            @Param("details") IngredientDetails details);
}
