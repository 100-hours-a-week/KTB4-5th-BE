package com.dameokja.backend.global.config;

import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.auth.domain.AuthExceptionCode;
import com.dameokja.backend.global.exception.ErrorResponse;
import com.dameokja.backend.global.exception.ExceptionCode;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.global.security.SecurityExceptionCode;
import com.dameokja.backend.ingredient.exception.IngredientExceptionCode;
import com.dameokja.backend.image.exception.ImageAnalysisExceptionCode;
import com.dameokja.backend.notification.domain.NotificationExceptionCode;
import com.dameokja.backend.push.exception.PushExceptionCode;
import com.dameokja.backend.refrigerator.domain.RefrigeratorExceptionCode;
import com.dameokja.backend.user.domain.UserExceptionCode;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;

final class ErrorResponseOpenApiCustomizer implements GlobalOpenApiCustomizer {
    private static final List<ExceptionCode> EXCEPTION_CODES = Stream.<ExceptionCode[]>of(
            GlobalExceptionCode.values(), SecurityExceptionCode.values(), AuthExceptionCode.values(),
            UserExceptionCode.values(), RefrigeratorExceptionCode.values(), IngredientExceptionCode.values(),
            NotificationExceptionCode.values(), PushExceptionCode.values(), AnalysisExceptionCode.values(),
            ImageAnalysisExceptionCode.values()).flatMap(Arrays::stream).toList();

    @Override
    public void customise(OpenAPI openApi) {
        boolean openApi31 = openApi.getOpenapi() != null && openApi.getOpenapi().startsWith("3.1");
        ResolvedSchema errorSchema = ModelConverters.getInstance(openApi31).readAllAsResolvedSchema(ErrorResponse.class);
        errorSchema.referencedSchemas.forEach(openApi::schema);
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation ->
                operation.getResponses().forEach((status, response) -> {
                    if (status.matches("[45][0-9]{2}")) {
                        documentError(status, response);
                    }
                })));
    }

    private void documentError(String status, ApiResponse response) {
        // API에 명시된 코드만 예시로 제공해 같은 HTTP 상태의 다른 도메인 오류가 섞이지 않도록 한다.
        Map<String, Example> examples = new LinkedHashMap<>();
        for (ExceptionCode exceptionCode : EXCEPTION_CODES) {
            if (String.valueOf(exceptionCode.getStatus().value()).equals(status)
                    && response.getDescription() != null && response.getDescription().contains(exceptionCode.getCode())) {
                examples.put(exceptionCode.getCode(), new Example().summary(exceptionCode.getMessage())
                        .value(ErrorResponse.of(exceptionCode)));
            }
        }
        MediaType mediaType = new MediaType().schema(new Schema<>().$ref("#/components/schemas/ErrorResponse"));
        if (!examples.isEmpty()) {
            mediaType.examples(examples);
        }
        response.setContent(new Content().addMediaType("application/json", mediaType));
    }
}
