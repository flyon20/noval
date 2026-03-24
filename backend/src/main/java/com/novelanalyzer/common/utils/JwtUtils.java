package com.novelanalyzer.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

@Component
public class JwtUtils {

    public String generateToken(String subject, String secret, long expireSeconds, Map<String, Object> claims) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(subject)
            .claims(claims)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(expireSeconds)))
            .signWith(buildKey(secret))
            .compact();
    }

    public Claims parseClaims(String token, String secret) {
        return Jwts.parser()
            .verifyWith(buildKey(secret))
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    private SecretKey buildKey(String secret) {
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(secret);
        } catch (RuntimeException ex) {
            keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
