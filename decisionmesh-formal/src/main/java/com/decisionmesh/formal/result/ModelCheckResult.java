package com.decisionmesh.formal.result;

public class ModelCheckResult {

    private final boolean valid;
    private final String message;

    private ModelCheckResult(boolean valid, String message) {
        this.valid = valid;
        this.message = message;
    }

    public static ModelCheckResult success() {
        return new ModelCheckResult(true, "VALID");
    }

    public static ModelCheckResult failure(String message) {
        return new ModelCheckResult(false, message);
    }

    public boolean isValid() { return valid; }
    public String getMessage() { return message; }
}