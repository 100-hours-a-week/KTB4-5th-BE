package com.dameokja.backend.analysis.presentation;

final class AnalysisIngredientApiExamples {
    private AnalysisIngredientApiExamples() {}
    static final String REQUEST = """
            {"analysisId":"308eaf1d-a8b4-41a5-b45f-616d20b38da1","images":[{"imageObjectKey":"example-images/analysis/7/product.jpg","items":[
              {"itemId":"item_001","name":"달걀","category":"TOFU_BEAN","storageType":"REFRIGERATED",
               "measureType":"COUNT","quantity":10,"weight":null,"expiration":"2026-10-22"},
              {"itemId":null,"name":"우유","category":"DAIRY","storageType":"REFRIGERATED",
               "measureType":"COUNT","quantity":2,"weight":null,"expiration":"2026-10-22"}]}]}
            """;
    static final String RESPONSE = """
            {"code":"INGREDIENT-201-001","message":"재고 일괄등록 성공",
             "data":{"createdCount":2,"mergedCount":0,"ingredientsNum":30,"refrigeratorCapacity":100,"mergedItems":[]}}
            """;
}
