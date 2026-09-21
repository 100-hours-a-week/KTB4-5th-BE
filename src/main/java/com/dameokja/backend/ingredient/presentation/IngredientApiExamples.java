package com.dameokja.backend.ingredient.presentation;

final class IngredientApiExamples {
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
