package com.novelanalyzer.common.context;

import java.util.Collections;
import java.util.Set;

public final class AuthUserHolder {

    private static final ThreadLocal<AuthUser> AUTH_USER = new ThreadLocal<>();

    private AuthUserHolder() {
    }

    public static void set(AuthUser authUser) {
        AUTH_USER.set(authUser);
    }

    public static AuthUser get() {
        return AUTH_USER.get();
    }

    public static Set<String> getRoles() {
        AuthUser user = AUTH_USER.get();
        return user == null ? Collections.emptySet() : user.getRoles();
    }

    public static void clear() {
        AUTH_USER.remove();
    }
}

