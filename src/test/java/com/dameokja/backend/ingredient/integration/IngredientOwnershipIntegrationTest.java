package com.dameokja.backend.ingredient.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.ingredient.application.create.IngredientCreateCommand;
import com.dameokja.backend.ingredient.application.create.IngredientCreateService;
import com.dameokja.backend.ingredient.application.update.IngredientEtag;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateFields;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateFields.UpdateField;
import com.dameokja.backend.ingredient.application.update.IngredientUpdateService;
import com.dameokja.backend.ingredient.domain.*;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import com.dameokja.backend.refrigerator.application.InvitationService;
import com.dameokja.backend.refrigerator.application.MembershipService;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IngredientOwnershipIntegrationTest extends ServiceIntegrationTest {
    @Autowired IngredientCreateService creates;
    @Autowired IngredientUpdateService updates;
    @Autowired IngredientRepository ingredients;
    @Autowired MembershipService memberships;
    @Autowired InvitationService invitations;

    @Test
    void savesAuthenticatedRegistrantAndMergesOnlyTheirInventory() {
        Fixture owner = ownerFixture("owner");
        Fixture member = sharedMember(owner);
        Ingredient ownersStock = create(owner.userId(), owner.refrigeratorId(), "우유");
        Ingredient membersStock = create(member.userId(), owner.refrigeratorId(), "우유");

        assertThat(membersStock.getId()).isNotEqualTo(ownersStock.getId());
        Ingredient merged = create(member.userId(), owner.refrigeratorId(), "우유");

        assertThat(merged.getId()).isEqualTo(membersStock.getId());
        assertThat(quantity(ownersStock)).isEqualTo(2);
        assertThat(quantity(membersStock)).isEqualTo(4);
        assertThat(ownerId(ownersStock)).isEqualTo(owner.userId());
        assertThat(ownerId(membersStock)).isEqualTo(member.userId());
    }

    @Test
    void editingAnotherMembersStockMergesByOriginalOwnerAndPreservesOwnership() {
        Fixture owner = ownerFixture("owner");
        Fixture member = sharedMember(owner);
        Ingredient ownersStock = create(owner.userId(), owner.refrigeratorId(), "우유");
        Ingredient membersMilk = create(member.userId(), owner.refrigeratorId(), "우유");
        Ingredient membersStock = create(member.userId(), owner.refrigeratorId(), "두유");

        var result = updates.update(owner.userId(), membersStock.getId(), IngredientEtag.of(membersStock), rename("우유"));

        assertThat(result.mergedItems()).hasSize(1);
        assertThat(ingredients.existsById(membersMilk.getId())).isFalse();
        assertThat(quantity(ownersStock)).isEqualTo(2);
        assertThat(quantity(membersStock)).isEqualTo(4);
        assertThat(ownerId(membersStock)).isEqualTo(member.userId());
    }

    @Test
    void editingToMatchAnotherOwnersStockKeepsBothRows() {
        Fixture owner = ownerFixture("owner");
        Fixture member = sharedMember(owner);
        Ingredient ownersStock = create(owner.userId(), owner.refrigeratorId(), "우유");
        Ingredient membersStock = create(member.userId(), owner.refrigeratorId(), "두유");

        var result = updates.update(member.userId(), membersStock.getId(), IngredientEtag.of(membersStock), rename("우유"));

        assertThat(result.mergedItems()).isEmpty();
        assertThat(quantity(ownersStock)).isEqualTo(2);
        assertThat(quantity(membersStock)).isEqualTo(2);
        assertThat(ownerId(membersStock)).isEqualTo(member.userId());
    }

    private Fixture sharedMember(Fixture owner) {
        Fixture member = ownerFixture("member");
        memberships.join(member.userId(), invitations.issueCode(owner.userId(), owner.refrigeratorId()).code());
        return member;
    }

    private Ingredient create(Long userId, Long refrigeratorId, String name) {
        return creates.create(userId, refrigeratorId, List.of(command(name))).items().getFirst().ingredient();
    }

    private IngredientCreateCommand command(String name) {
        return new IngredientCreateCommand(name, IngredientCategory.DAIRY, StorageType.REFRIGERATED,
                MeasureType.COUNT, 2, null, WeightUnit.NONE, LocalDate.of(2026, 9, 25), RegistrationSource.DIRECT);
    }

    private IngredientUpdateFields rename(String name) {
        return new IngredientUpdateFields(UpdateField.provided(name), UpdateField.omitted(), UpdateField.omitted(),
                UpdateField.omitted(), UpdateField.omitted(), UpdateField.omitted(), UpdateField.omitted());
    }

    private int quantity(Ingredient ingredient) {
        return ingredients.findById(ingredient.getId()).orElseThrow().getMeasurement().getQuantity();
    }

    private Long ownerId(Ingredient ingredient) {
        return jdbcTemplate.queryForObject("SELECT owner_id FROM ingredients WHERE ingredient_id=?", Long.class, ingredient.getId());
    }
}
