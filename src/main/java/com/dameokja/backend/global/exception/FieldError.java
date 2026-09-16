package com.dameokja.backend.global.exception;

public record FieldError(
        String location,
        String field,
        String code,
        String message
) {

    public static FieldError of(String location, String field, String code, String message) {
        return new FieldError(location, field, code, message);
    }
}
