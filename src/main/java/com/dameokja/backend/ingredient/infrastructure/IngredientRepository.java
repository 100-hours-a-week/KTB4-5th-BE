package com.dameokja.backend.ingredient.infrastructure;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import com.dameokja.backend.ingredient.domain.IngredientSortType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngredientRepository extends JpaRepository<Ingredient, Long> {
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

    default List<Ingredient> findListPage(IngredientSortType sortType, IngredientPageCondition condition, int size) {
        Limit limit = Limit.of(size);
        return switch (sortType) {
            case EXPIRATION_ASC -> findExpirationAscPage(condition.refrigeratorId(), condition.expirationFrom(),
                    condition.expirationBefore(), condition.cursorExpirationDate(), condition.cursorCreatedAt(),
                    condition.cursorName(), condition.cursorId(), limit);
            case CREATED_DESC -> findCreatedDescPage(condition.refrigeratorId(), condition.expirationFrom(),
                    condition.expirationBefore(), condition.cursorExpirationDate(), condition.cursorCreatedAt(),
                    condition.cursorName(), condition.cursorId(), limit);
            case NAME_ASC -> findNameAscPage(condition.refrigeratorId(), condition.expirationFrom(),
                    condition.expirationBefore(), condition.cursorExpirationDate(), condition.cursorCreatedAt(),
                    condition.cursorName(), condition.cursorId(), limit);
        };
    }

    @Query(IngredientListJpql.EXPIRATION_ASC)
    List<Ingredient> findExpirationAscPage(@Param("refrigeratorId") Long refrigeratorId,
            @Param("expirationFrom") LocalDate expirationFrom, @Param("expirationBefore") LocalDate expirationBefore,
            @Param("cursorExpirationDate") LocalDate cursorExpirationDate, @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorName") String cursorName, @Param("cursorId") Long cursorId, Limit limit);

    @Query(IngredientListJpql.CREATED_DESC)
    List<Ingredient> findCreatedDescPage(@Param("refrigeratorId") Long refrigeratorId,
            @Param("expirationFrom") LocalDate expirationFrom, @Param("expirationBefore") LocalDate expirationBefore,
            @Param("cursorExpirationDate") LocalDate cursorExpirationDate, @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorName") String cursorName, @Param("cursorId") Long cursorId, Limit limit);

    @Query(IngredientListJpql.NAME_ASC)
    List<Ingredient> findNameAscPage(@Param("refrigeratorId") Long refrigeratorId,
            @Param("expirationFrom") LocalDate expirationFrom, @Param("expirationBefore") LocalDate expirationBefore,
            @Param("cursorExpirationDate") LocalDate cursorExpirationDate, @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorName") String cursorName, @Param("cursorId") Long cursorId, Limit limit);
}
