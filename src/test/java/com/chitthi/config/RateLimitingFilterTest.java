package com.chitthi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RateLimitingFilterTest {

    private RateLimitingFilter rateLimitingFilter;
    private FilterChain filterChain;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // limit 3 uploads/min, 5 searches/min for quick unit testing
        rateLimitingFilter = new RateLimitingFilter(true, 3, 5, objectMapper);
        filterChain = mock(FilterChain.class);
    }

    @Test
    void doFilter_whenUnderUploadLimit_shouldPassThroughAndSetHeaders() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/documents");
        request.setRemoteAddr("192.168.1.100");
        MockHttpServletResponse response = new MockHttpServletResponse();

        rateLimitingFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("3");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("2");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void doFilter_whenOverUploadLimit_shouldReturn429WithRetryAfter() throws ServletException, IOException {
        String clientIp = "192.168.1.101";

        // Perform 3 successful requests
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/documents");
            req.setRemoteAddr(clientIp);
            MockHttpServletResponse res = new MockHttpServletResponse();
            rateLimitingFilter.doFilter(req, res, filterChain);
            assertThat(res.getStatus()).isEqualTo(200);
        }

        // 4th request must be rejected with 429
        MockHttpServletRequest req4 = new MockHttpServletRequest("POST", "/api/documents");
        req4.setRemoteAddr(clientIp);
        MockHttpServletResponse res4 = new MockHttpServletResponse();
        rateLimitingFilter.doFilter(req4, res4, filterChain);

        assertThat(res4.getStatus()).isEqualTo(429);
        assertThat(res4.getHeader("Retry-After")).isNotNull();
        assertThat(res4.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(res4.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
    }

    @Test
    void doFilter_whenDifferentIps_shouldTrackSeparateBuckets() throws ServletException, IOException {
        String ip1 = "10.0.0.1";
        String ip2 = "10.0.0.2";

        // Exhaust IP 1
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/documents");
            req.setRemoteAddr(ip1);
            rateLimitingFilter.doFilter(req, new MockHttpServletResponse(), filterChain);
        }

        // 4th for IP 1 gets 429
        MockHttpServletResponse res1Blocked = new MockHttpServletResponse();
        rateLimitingFilter.doFilter(new MockHttpServletRequest("POST", "/api/documents") {{ setRemoteAddr(ip1); }}, res1Blocked, filterChain);
        assertThat(res1Blocked.getStatus()).isEqualTo(429);

        // IP 2 is fresh and must succeed
        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/api/documents");
        req2.setRemoteAddr(ip2);
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        rateLimitingFilter.doFilter(req2, res2, filterChain);

        assertThat(res2.getStatus()).isEqualTo(200);
        assertThat(res2.getHeader("X-RateLimit-Remaining")).isEqualTo("2");
    }

    @Test
    void doFilter_whenNonRateLimitedPath_shouldPassThroughImmediately() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/style.css");
        request.setRemoteAddr("192.168.1.105");
        MockHttpServletResponse response = new MockHttpServletResponse();

        rateLimitingFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getHeader("X-RateLimit-Limit")).isNull();
    }
}
