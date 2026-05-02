package com.decisionmesh.contracts.security.roles;


public record Roles(String name) {

    public Roles(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Role name cannot be null or blank");
        }
        this.name = name.toUpperCase();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Roles(String name1))) return false;
        return name.equals(name1);
    }

}
