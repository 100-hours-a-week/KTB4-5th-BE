package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.support.MySqlJpaTest;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.domain.RefrigeratorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

class RefrigeratorRepositoryTest extends MySqlJpaTest {
    @Autowired RefrigeratorRepository refrigerators;

    @Test void savesAndFindsTheRequestedRefrigerator() {
        Refrigerator first = refrigerators.save(new Refrigerator("첫냉장고", "2026-09"));
        Refrigerator second = refrigerators.save(new Refrigerator("다른냉장고", "2026-08"));
        flushAndClear();
        Refrigerator found = refrigerators.findById(first.getId()).orElseThrow();
        assertThat(found.getId()).isNotEqualTo(second.getId());
        assertThat(found.getName()).isEqualTo("첫냉장고");
        assertThat(found.getExpiredCountMonth()).isEqualTo("2026-09");
        assertThat(found.getCapacity()).isEqualTo((short) 100);
        assertThat(found.getExpiredCount()).isZero();
        assertThat(found.getDeletedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @ParameterizedTest @ValueSource(strings = {"name", "month"})
    void rejectsMissingRequiredInput(String field) {
        assertThatThrownBy(() -> {
            refrigerators.save(new Refrigerator(field.equals("name") ? null : "냉장고",
                    field.equals("month") ? null : "2026-09"));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void renamesWithoutChangingCapacityOrCounts() {
        Refrigerator saved = refrigerators.save(new Refrigerator("수정전", "2026-09"));
        flushAndClear();
        Refrigerator found = refrigerators.findById(saved.getId()).orElseThrow();
        found.rename("수정후");
        flushAndClear();
        Refrigerator result = refrigerators.findById(saved.getId()).orElseThrow();
        assertThat(result.getName()).isEqualTo("수정후");
        assertThat(result.getCapacity()).isEqualTo((short) 100);
        assertThat(result.getExpiredCount()).isZero();
        assertThat(result.getExpiredCountMonth()).isEqualTo("2026-09");
    }

    @Test void softDeletesWithoutRemovingTheRow() {
        Refrigerator saved = refrigerators.save(new Refrigerator("냉장고", "2026-09"));
        flushAndClear();
        LocalDateTime deletedAt = LocalDateTime.of(2026, 9, 17, 12, 0);
        refrigerators.findById(saved.getId()).orElseThrow().delete(deletedAt);
        flushAndClear();
        assertThat(refrigerators.findById(saved.getId()).orElseThrow().getDeletedAt()).isEqualTo(deletedAt);
    }
}
