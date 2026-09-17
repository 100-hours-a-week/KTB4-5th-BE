package com.dameokja.backend.user.domain;

import com.dameokja.backend.global.exception.CustomException;

public class UserException extends CustomException {
    public UserException(UserExceptionCode code) { super(code); }
}
