package com.dameokja.backend.ingredient.domain;

import com.dameokja.backend.global.common.BaseEntity;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

@Getter
@Entity
@Table(name = "ingredients")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ingredient extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ingredient_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refrigerator_id", nullable = false, updatable = false)
    private Refrigerator refrigerator;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IngredientCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StorageType storageType;

    @Embedded
    private Measurement measurement;

    @Column(nullable = false)
    private LocalDate expirationDate;

    @Column(name = "ingredient_image_key", length = 512)
    private String imageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private RegistrationSource registrationSource;

    public Ingredient(Refrigerator refrigerator, IngredientDetails details,
                      RegistrationSource registrationSource) {
        this.refrigerator = refrigerator;
        this.name = details.name();
        this.category = details.category();
        this.storageType = details.storageType();
        this.measurement = details.measurement();
        this.expirationDate = details.expirationDate();
        this.registrationSource = registrationSource;
    }

    public void updateDetails(IngredientDetails details) {
        this.name = details.name();
        this.category = details.category();
        this.storageType = details.storageType();
        this.measurement = details.measurement();
        this.expirationDate = details.expirationDate();
    }

    public void mergeMeasurement(Measurement addition) {
        this.measurement = measurement.add(addition);
    }
}
