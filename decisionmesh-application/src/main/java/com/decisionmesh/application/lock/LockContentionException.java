package com.decisionmesh.application.lock;

public class LockContentionException extends RuntimeException {

    public LockContentionException(String message) {
        super(message);
    }
}