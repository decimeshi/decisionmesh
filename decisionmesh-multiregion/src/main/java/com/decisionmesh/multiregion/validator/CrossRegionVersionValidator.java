package com.decisionmesh.multiregion.validator;

public class CrossRegionVersionValidator {

    public void validate(long sourceVersion, long targetVersion) {
        if (targetVersion < sourceVersion) {
            throw new IllegalStateException("Version regression detected across regions");
        }
    }
}