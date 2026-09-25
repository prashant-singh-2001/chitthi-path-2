package com.chitthi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Testcontainers
class ChitthiApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoadsAndSchemaIsMigrated() {
        assertNotNull(jdbcTemplate, "JdbcTemplate should be injected");

        // Verify Flyway ran and created all tables
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('document', 'page', 'ocr_batch', 'stage_task', 'api_call', 'outbox')",
                Integer.class
        );
        assertEquals(6, tableCount, "All 6 core tables should exist in Postgres");

        // Verify pg_trgm extension is active
        Integer extCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'",
                Integer.class
        );
        assertEquals(1, extCount, "pg_trgm extension should be installed and enabled");
    }
}
