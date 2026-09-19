package com.dameokja.backend.global.response;

public record SuccessResponse<T>(
        String code,
        String message,
        T data
) {

    public static <T> SuccessResponse<T> of(String code, String message, T data) {
        return new SuccessResponse<>(code, message, data);
    }
}
