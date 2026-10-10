package com.novelanalyzer.modules.crawler.service;

import com.novelanalyzer.modules.crawler.dto.CrawlerRankRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public final class CrawlerRankIdempotencyKeyFactory {

    private CrawlerRankIdempotencyKeyFactory() {
    }

    public static String generate(String boundary, CrawlerRankRequest request, String requestScope) {
        Objects.requireNonNull(request, "request");
        String canonical = field(boundary)
            + field(requestScope)
            + field(request.getPlatform())
            + field(request.getChannelCode())
            + field(request.getBoardCode())
            + field(request.getCategory())
            + field(request.getRefreshMode())
            + field(request.getForceReason())
            + field(request.getRankFetchCount());
        return "rank-refresh-generated:" + sha256(canonical);
    }

    private static String field(Object value) {
        if (value == null) {
            return "-1:";
        }
        String text = String.valueOf(value);
        return text.length() + ":" + text;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
