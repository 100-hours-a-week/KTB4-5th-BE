package com.dameokja.backend.refrigerator.domain;

import static com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode.REFRIGERATOR_DELETED;

import com.dameokja.backend.global.common.BaseEntity;
import com.dameokja.backend.global.exception.CustomException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.YearMonth;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "refrigerators")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refrigerator extends BaseEntity {
    public static final short DEFAULT_CAPACITY = 1500;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refrigerator_id")
    private Long id;
    @Column(name = "name", nullable = false, length = 20)
    private String name;
    @Column(name = "capacity", nullable = false)
    private Short capacity;
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    @Column(name = "expired_count", nullable = false)
    private Integer expiredCount;
    @Column(name = "expired_count_month", nullable = false, length = 7,
            columnDefinition = "char(7)")
    private String expiredCountMonth;
    @Column(name = "expired_counted_on")
    private LocalDate expiredCountedOn;

    public Refrigerator(String name, String expiredCountMonth) {
        this.name = name;
        this.capacity = DEFAULT_CAPACITY;
        this.expiredCount = 0;
        this.expiredCountMonth = expiredCountMonth;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void validateAvailable() {
        if (deletedAt != null) {
            throw new CustomException(REFRIGERATOR_DELETED);
        }
    }

    public void delete(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public void countExpiredDeletion() {
        expiredCount++;
    }

    public void countExpiredDeletions(int count) {
        expiredCount += count;
    }

    public int monthlyExpiredCount(LocalDate date) {
        return YearMonth.from(date).toString().equals(expiredCountMonth) ? expiredCount : 0;
    }

    public LocalDate unaccountedExpirationFrom(LocalDate date) {
        LocalDate monthBoundary = date.withDayOfMonth(1).minusDays(1);
        return expiredCountedOn == null || expiredCountedOn.isBefore(monthBoundary)
                ? monthBoundary : expiredCountedOn;
    }

    /**
     * 냉장고 잠금 아래 미집계 만료분과 집계 기준일을 함께 반영합니다.
     * @param date 만료 상태를 판정한 한국 날짜
     * @param count 마지막 집계 이후 이번 달에 처음 만료된 재고 행 수
     */
    public void recordExpirations(LocalDate date, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("만료 집계는 음수일 수 없습니다.");
        }
        if (expiredCountedOn != null && !expiredCountedOn.isBefore(date)) {
            return;
        }
        expiredCount = Math.addExact(monthlyExpiredCount(date), count);
        expiredCountMonth = YearMonth.from(date).toString();
        expiredCountedOn = date;
    }
}
