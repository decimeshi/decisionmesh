package com.decisionmesh.domain.common;

public final class Result<T> {

    public enum ErrorCode {
        INVALID_STATE,
        RETRY_EXCEEDED,
        VALIDATION_FAILED
    }

    private final T value;
    private final ErrorCode errorCode;
    private final String message;
    private final boolean success;

    private Result(T value, ErrorCode errorCode, String message, boolean success) {
        this.value = value;
        this.errorCode = errorCode;
        this.message = message;
        this.success = success;
    }

    public static <T> Result<T> success(T value) {
        return new Result<>(value, null, null, true);
    }

    public static <T> Result<T> failure(ErrorCode code, String message) {
        return new Result<>(null, code, message, false);
    }

    public boolean isSuccess() { return success; }
    public T getValue() { if(!success) throw new IllegalStateException(); return value; }
    public ErrorCode getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
}