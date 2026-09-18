package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorRepository;
import com.dameokja.backend.support.MySqlJpaTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefrigeratorRepositoryTest extends MySqlJpaTest {
    @Autowired
    RefrigeratorRepository refrigeratorRepository;

    @Test
    void savesAndFindsTheRequestedRefrigerator() {
        Refrigerator firstRefrigerator = refrigeratorRepository.save(
                new Refrigerator("첫냉장고", "2026-09"));
        Refrigerator secondRefrigerator = refrigeratorRepository.save(
                new Refrigerator("다른냉장고", "2026-08"));
        flushAndClear();
        Refrigerator foundRefrigerator = refrigeratorRepository.findById(firstRefrigerator.getId())
                .orElseThrow();
        assertThat(foundRefrigerator.getId()).isNotEqualTo(secondRefrigerator.getId());
        assertThat(foundRefrigerator.getName()).isEqualTo("첫냉장고");
        assertThat(foundRefrigerator.getExpiredCountMonth()).isEqualTo("2026-09");
        assertThat(foundRefrigerator.getCapacity()).isEqualTo((short) 100);
        assertThat(foundRefrigerator.getExpiredCount()).isZero();
        assertThat(foundRefrigerator.getDeletedAt()).isNull();
        assertThat(foundRefrigerator.getCreatedAt()).isNotNull();
        assertThat(foundRefrigerator.getUpdatedAt()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "month"})
    void rejectsMissingRequiredInput(String invalidField) {
        assertThatThrownBy(() -> {
            refrigeratorRepository.save(new Refrigerator(invalidField.equals("name") ? null : "냉장고",
                    invalidField.equals("month") ? null : "2026-09"));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void renamesWithoutChangingCapacityOrCounts() {
        Refrigerator savedRefrigerator = refrigeratorRepository.save(
                new Refrigerator("수정전", "2026-09"));
        flushAndClear();
        Refrigerator foundRefrigerator = refrigeratorRepository.findById(savedRefrigerator.getId())
                .orElseThrow();
        foundRefrigerator.rename("수정후");
        flushAndClear();
        Refrigerator updatedRefrigerator = refrigeratorRepository
                .findById(savedRefrigerator.getId())
                .orElseThrow();
        assertThat(updatedRefrigerator.getName()).isEqualTo("수정후");
        assertThat(updatedRefrigerator.getCapacity()).isEqualTo((short) 100);
        assertThat(updatedRefrigerator.getExpiredCount()).isZero();
        assertThat(updatedRefrigerator.getExpiredCountMonth()).isEqualTo("2026-09");
    }

    @Test
    void softDeletesWithoutRemovingTheRow() {
        Refrigerator savedRefrigerator = refrigeratorRepository.save(
                new Refrigerator("냉장고", "2026-09"));
        flushAndClear();
        LocalDateTime deletedAt = LocalDateTime.of(2026, 9, 17, 12, 0);
        refrigeratorRepository.findById(savedRefrigerator.getId())
                .orElseThrow().delete(deletedAt);
        flushAndClear();
        assertThat(refrigeratorRepository.findById(savedRefrigerator.getId())
                .orElseThrow().getDeletedAt()).isEqualTo(deletedAt);
    }
}
