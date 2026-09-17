package com.dameokja.backend.refrigerator.domain;

import com.dameokja.backend.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "refrigerators")
@DynamicInsert
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refrigerator extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refrigerator_id")
    private Long id;
    @Column(name = "name", nullable = false, length = 20)
    private String name;
    @Generated(event = EventType.INSERT)
    @Column(name = "capacity", nullable = false)
    private Short capacity;
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    @Generated(event = EventType.INSERT)
    @Column(name = "expired_count", nullable = false)
    private Integer expiredCount;
    @Column(name = "expired_count_month", nullable = false, length = 7, columnDefinition = "char(7)")
    private String expiredCountMonth;

    // The application supplies the Seoul calendar month; persistence does not read the system clock.
    public Refrigerator(String name, String expiredCountMonth) {
        this.name = name;
        this.expiredCountMonth = expiredCountMonth;
    }

    public void rename(String name) { this.name = name; }

    public void delete(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
