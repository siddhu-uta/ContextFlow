package com.contextflow.auth.exception;

public class TenantSlugAlreadyExistsException extends RuntimeException {
    public TenantSlugAlreadyExistsException(String message) {
        super(message);
    }
}
