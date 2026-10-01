package com.chitthi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-IP request rate limiting filter for public ingestion and search endpoints (FR15).
 * Implements a sliding 60-second token bucket per client IP.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class RateLimitingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private final boolean enabled;
    private final int uploadLimitPerMinute;
    private final int searchLimitPerMinute;
    private final ObjectMapper objectMapper;

    private final Map<String, IpBucket> uploadBuckets = new ConcurrentHashMap<>();
    private final Map<String, IpBucket> searchBuckets = new ConcurrentHashMap<>();

    public RateLimitingFilter(
            @Value("${chitthi.ratelimit.enabled:true}") boolean enabled,
            @Value("${chitthi.ratelimit.upload-limit-per-minute:10}") int uploadLimitPerMinute,
            @Value("${chitthi.ratelimit.search-limit-per-minute:60}") int searchLimitPerMinute,
            ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.uploadLimitPerMinute = uploadLimitPerMinute;
        this.searchLimitPerMinute = searchLimitPerMinute;
        this.objectMapper = objectMapper;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!enabled || !(request instanceof HttpServletRequest httpRequest) || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        String path = httpRequest.getRequestURI();
        String method = httpRequest.getMethod();

        // 1. Upload rate limit: POST /api/documents (excluding sub-paths)
        if ("POST".equalsIgnoreCase(method) && ("/api/documents".equals(path) || "/api/documents/".equals(path))) {
            String clientIp = extractClientIp(httpRequest);
            if (!checkRateLimit(clientIp, uploadBuckets, uploadLimitPerMinute, httpResponse)) {
                return;
            }
        }

        // 2. Search rate limit: GET /api/documents/search
        if ("GET".equalsIgnoreCase(method) && path.startsWith("/api/documents/search")) {
            String clientIp = extractClientIp(httpRequest);
            if (!checkRateLimit(clientIp, searchBuckets, searchLimitPerMinute, httpResponse)) {
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean checkRateLimit(String clientIp, Map<String, IpBucket> bucketMap, int limit, HttpServletResponse response) throws IOException {
        long now = System.currentTimeMillis();
        IpBucket bucket = bucketMap.compute(clientIp, (ip, existing) -> {
            if (existing == null || now - existing.windowStartMs >= 60_000L) {
                return new IpBucket(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });

        int currentCount = bucket.count.get();
        long elapsedMs = now - bucket.windowStartMs;
        int remainingSeconds = Math.max(1, (int) Math.ceil((60_000L - elapsedMs) / 1000.0));

        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));

        if (currentCount > limit) {
            log.warn("Rate limit exceeded for client IP {} ({} requests in window, limit: {})", clientIp, currentCount, limit);
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", String.valueOf(remainingSeconds));
            response.setHeader("X-RateLimit-Remaining", "0");

            Map<String, Object> errorBody = Map.of(
                    "error", "RATE_LIMIT_EXCEEDED",
                    "message", "Too many requests from IP " + clientIp + ". Please try again in " + remainingSeconds + " seconds.",
                    "retryAfterSeconds", remainingSeconds,
                    "timestamp", Instant.now().toString()
            );

            response.getWriter().write(objectMapper.writeValueAsString(errorBody));
            return false;
        }

        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - currentCount)));
        return true;
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "127.0.0.1";
    }

    private static class IpBucket {
        final long windowStartMs;
        final AtomicInteger count;

        IpBucket(long windowStartMs, AtomicInteger count) {
            this.windowStartMs = windowStartMs;
            this.count = count;
        }
    }
}
