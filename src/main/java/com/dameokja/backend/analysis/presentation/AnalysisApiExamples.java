package com.dameokja.backend.analysis.presentation;

final class AnalysisApiExamples {
    static final String REQUEST = """
            {"imageObjectKeys":["example-images/analysis/7/receipt.jpg","example-images/analysis/7/product.jpg"],"inputHint":"AUTO"}
            """;
    static final String RESPONSE = """
            {"code":"IMAGE-202-001","message":"이미지 인식 시작","data":{
              "analysisId":"308eaf1d-a8b4-41a5-b45f-616d20b38da1","status":"QUEUED",
              "submittedAt":"2026-10-08T10:00:00+09:00","expiresAt":"2026-10-08T11:00:00+09:00","pollAfterMs":1000}}
            """;
    static final String QUERY_RESPONSE = """
            {"code":"IMAGE-200-002","message":"이미지 인식 결과 조회 성공","data":{
              "analysisId":"308eaf1d-a8b4-41a5-b45f-616d20b38da1","status":"QUEUED",
              "submittedAt":"2026-10-08T10:00:00+09:00","expiresAt":"2026-10-08T11:00:00+09:00","pollAfterMs":1000,
              "results":[{"imageObjectKey":"example-images/analysis/7/receipt.jpg","status":"QUEUED","recognitionStatus":null,
                "documentType":null,"items":[],"error":null}],"error":null}}
            """;
    private AnalysisApiExamples() {}
}
