package com.dameokja.backend.global.exception;

public record FieldError(
        String location,
        String field,
        String code,
        String detail
) {

    public static FieldError of(String location, String field, String code, String detail) {
        return new FieldError(location, field, code, detail);
    }
}
