package com.dameokja.backend.refrigerator.domain;

import com.dameokja.backend.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.YearMonth;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "refrigerators")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refrigerator extends BaseEntity {
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

    public Refrigerator(String name, String expiredCountMonth) {
        this.name = name;
        this.capacity = 100;
        this.expiredCount = 0;
        this.expiredCountMonth = expiredCountMonth;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void delete(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public void countExpiredDeletion(YearMonth month) {
        String currentMonth = month.toString();
        expiredCount = currentMonth.equals(expiredCountMonth) ? expiredCount + 1 : 1;
        expiredCountMonth = currentMonth;
    }
}
