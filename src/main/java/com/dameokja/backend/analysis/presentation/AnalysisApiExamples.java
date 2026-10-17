package com.dameokja.backend.analysis.presentation;

final class AnalysisApiExamples {
    static final String REQUEST = """
            {"imageObjectKeys":["example-images/analysis/7/receipt.jpg","example-images/analysis/7/product.jpg"],"inputHint":"AUTO"}
            """;
    static final String RESPONSE = """
            {"code":"IMAGE-202-001","message":"이미지 인식 시작","data":{
              "analysisId":"308eaf1d-a8b4-41a5-b45f-616d20b38da1","status":"QUEUED",
              "submittedAt":"2026-10-08T10:00:00+09:00","expiresAt":null,"pollAfterMs":1000}}
            """;
    static final String QUERY_RESPONSE = """
            {"code":"IMAGE-200-002","message":"이미지 인식 결과 조회 성공","data":{
              "analysisId":"308eaf1d-a8b4-41a5-b45f-616d20b38da1","status":"QUEUED",
              "submittedAt":"2026-10-08T10:00:00+09:00","expiresAt":null,"pollAfterMs":1000,
              "results":[{"imageObjectKey":"example-images/analysis/7/receipt.jpg","status":"QUEUED","recognitionStatus":null,
                "documentType":null,"items":[],"error":null}],"error":null}}
            """;
    static final String REVIEW_RESPONSE = """
            {"code":"IMAGE-200-002","message":"이미지 인식 결과 조회 성공","data":{
              "analysisId":"308eaf1d-a8b4-41a5-b45f-616d20b38da1","status":"COMPLETED",
              "submittedAt":"2026-10-08T10:00:00+09:00","expiresAt":"2026-10-15T10:00:00+09:00","pollAfterMs":null,
              "results":[{"imageObjectKey":"example-images/analysis/7/receipt.jpg","status":"COMPLETED","recognitionStatus":"NEEDS_REVIEW",
                "documentType":"RECEIPT","items":[{"itemId":"item-1","displayStatus":"NEEDS_REVIEW","name":"우유","category":"DAIRY",
                  "storageType":"REFRIGERATED","measureType":"COUNT","quantity":2,"weight":{"value":null,"unit":"NONE"},
                  "expiration":{"date":null,"dateType":"ESTIMATED_CONSUMPTION_WINDOW","estimatedRange":{"from":"2026-10-15","to":"2026-10-18"}},
                  "reviewReasons":[{"field":"EXPIRATION","code":"EXPIRATION_ESTIMATED",
                    "messages":["표시된 소비기한이 없어 보관 조건을 기준으로 예상했습니다."]}]}],"error":null}],"error":null}}
            """;
    static final String LIST_RESPONSE = """
            {"code":"IMAGE-200-004","message":"이미지 분석 작업 목록 조회 성공","data":{"analyses":[
              {"analysisId":"308eaf1d-a8b4-41a5-b45f-616d20b38da1","status":"PROCESSING",
               "submittedAt":"2026-10-08T10:00:00+09:00","expiresAt":null,
               "totalImageCount":3,"completedImageCount":1,"failedImageCount":1,"images":[
                 {"imageObjectKey":"example-images/analysis/7/receipt.jpg","status":"COMPLETED","recognitionStatus":"NEEDS_REVIEW","itemCount":2},
                 {"imageObjectKey":"example-images/analysis/7/blur.jpg","status":"FAILED","recognitionStatus":null,"itemCount":0},
                 {"imageObjectKey":"example-images/analysis/7/product.jpg","status":"PROCESSING","recognitionStatus":null,"itemCount":null}]}]}}
            """;
    private AnalysisApiExamples() {}
}
