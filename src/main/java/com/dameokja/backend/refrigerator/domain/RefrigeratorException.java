package com.dameokja.backend.refrigerator.domain;

import com.dameokja.backend.global.exception.CustomException;

public class RefrigeratorException extends CustomException {
    public RefrigeratorException(RefrigeratorExceptionCode code) { super(code); }
}
