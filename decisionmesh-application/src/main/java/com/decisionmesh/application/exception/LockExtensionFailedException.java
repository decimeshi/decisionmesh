package com.decisionmesh.application.exception;

public class LockExtensionFailedException extends RuntimeException {
    public LockExtensionFailedException(String message) {
        super(message);
    }
}