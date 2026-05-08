package com.novelanalyzer.modules.auth.model;

public final class AuthSessionStatus {

    public static final int ACTIVE = 1;
    public static final int REVOKED = 2;
    public static final int EXPIRED = 3;
    public static final int KICKED = 4;

    private AuthSessionStatus() {
    }
}
