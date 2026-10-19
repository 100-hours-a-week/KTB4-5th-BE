package com.dameokja.backend.image.presentation;

import com.dameokja.backend.global.response.SuccessResponse;
import com.dameokja.backend.global.security.CurrentUserId;
import com.dameokja.backend.image.application.ImagePresignService;
import com.dameokja.backend.image.application.ImageUploadResult;
import com.dameokja.backend.image.presentation.request.ImagePresignRequest;
import com.dameokja.backend.image.presentation.response.ImagePresignResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ImageController implements ImageApi {
    private final ImagePresignService service;

    @Override
    @PostMapping("/api/v1/image/presigned-url")
    public ResponseEntity<SuccessResponse<ImagePresignResponse>> issue(
            @CurrentUserId Long userId, @Valid @RequestBody ImagePresignRequest request) {
        ImageUploadResult result = service.issue(userId, request.purpose(), request.contentType(), request.byteSizeAsLong(), request.sha256());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of("IMAGE-200-001", "presigned url 발급 성공", ImagePresignResponse.from(result)));
    }
}
