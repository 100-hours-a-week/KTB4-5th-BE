package com.dameokja.backend.ingredient.presentation;

final class IngredientApiExamples {
    static final String DETAIL_RESPONSE = """
            {
              "code": "INGREDIENT-200-003",
              "message": "재고 상세 조회 성공",
              "data": {
                "ingredientId": "1",
                "name": "두부",
                "category": "TOFU_BEAN",
                "storageType": "REFRIGERATED",
                "measureType": "WEIGHT",
                "quantity": null,
                "weightValue": "300.000",
                "weightUnit": "G",
                "expirationDate": "2026-09-15",
                "createdDate": "2026-09-01",
                "registrationSource": "RECEIPT",
                "status": "EXPIRED",
                "daysUntilExpiration": -1
              }
            }
            """;

    static final String CREATE_REQUEST = """
            {
              "items": [
                {
                  "name": "두부",
                  "category": "TOFU_BEAN",
                  "storageType": "REFRIGERATED",
                  "measureType": "WEIGHT",
                  "quantity": null,
                  "weightValue": 300,
                  "weightUnit": "G",
                  "expirationDate": "2026-09-17",
                  "registrationSource": "RECEIPT",
                  "imageUploadId": null
                },
                {
                  "name": "달걀",
                  "category": "OTHER",
                  "storageType": "REFRIGERATED",
                  "measureType": "COUNT",
                  "quantity": 30,
                  "weightValue": null,
                  "weightUnit": "NONE",
                  "expirationDate": "2026-09-17",
                  "registrationSource": "DIRECT",
                  "imageUploadId": null
                }
              ]
            }
            """;

    static final String UPDATE_REQUEST = """
            {
              "name": "두부",
              "category": "TOFU_BEAN",
              "storageType": "REFRIGERATED",
              "quantity": null,
              "weightValue": "250.000",
              "weightUnit": "G",
              "expirationDate": "2026-09-15"
            }
            """;

    static final String UPDATE_RESPONSE = """
            {
              "code": "INGREDIENT-200-004",
              "message": "재고 수정 성공",
              "data": {
                "ingredientId": "1",
                "name": "두부",
                "category": "TOFU_BEAN",
                "storageType": "REFRIGERATED",
                "measureType": "WEIGHT",
                "quantity": null,
                "weightValue": "250.000",
                "weightUnit": "G",
                "expirationDate": "2026-09-15",
                "createdDate": "2026-09-01",
                "registrationSource": "RECEIPT",
                "status": "EXPIRED",
                "daysUntilExpiration": -1
              }
            }
            """;

    static final String CREATE_RESPONSE = """
            {
              "code": "INGREDIENT-201-001",
              "message": "재고 일괄등록 성공",
              "data": {
                "createdCount": 2,
                "mergedCount": 1,
                "ingredientsNum": 30,
                "refrigeratorCapacity": 100,
                "mergedItems": [
                  {
                    "ingredientId": 10,
                    "name": "두부",
                    "measureType": "WEIGHT",
                    "previousQuantity": null,
                    "addedQuantity": null,
                    "totalQuantity": null,
                    "previousWeightValue": 300.000,
                    "addedWeightValue": 300.000,
                    "totalWeightValue": 600.000,
                    "weightUnit": "G"
                  }
                ]
              }
            }
            """;

    private IngredientApiExamples() {
    }
}
