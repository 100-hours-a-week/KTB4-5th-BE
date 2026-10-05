package com.dameokja.backend.image.presentation;

final class ImageApiExamples {
    static final String REQUEST = """
            {"purpose":"ANALYSIS","contentType":"image/png","byteSize":512000,
             "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
            """;
    static final String RESPONSE = """
            {"code":"IMAGE-200-001","message":"presigned url 발급 성공","data":{
              "objectKey":"example-images/analysis/7/example.png",
              "uploadUrl":"https://storage.example.com/signed-upload","method":"PUT",
              "headers":{"Content-Type":"image/png","x-amz-checksum-sha256":"qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqo="},
              "expiresAt":"2026-10-05T12:05:00+09:00"}}
            """;
    private ImageApiExamples() {}
}
