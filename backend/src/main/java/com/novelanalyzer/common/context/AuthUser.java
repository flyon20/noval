package com.novelanalyzer.common.context;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class AuthUser {

    private Long userId;
    private String username;
    private Set<String> roles = new HashSet<>();

    public static AuthUser of(Long userId, String username, Set<String> roles) {
        AuthUser authUser = new AuthUser();
        authUser.setUserId(userId);
        authUser.setUsername(username);
        authUser.setRoles(roles);
        return authUser;
    }

    public boolean hasAnyRole(Set<String> requiredRoles) {
        if (requiredRoles == null || requiredRoles.isEmpty()) {
            return true;
        }
        for (String role : requiredRoles) {
            if (roles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Set<String> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    public void setRoles(Set<String> roles) {
        this.roles = roles == null ? new HashSet<>() : new HashSet<>(roles);
    }
}

