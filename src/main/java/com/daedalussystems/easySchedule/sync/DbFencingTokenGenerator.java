package com.daedalussystems.easySchedule.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DbFencingTokenGenerator implements FencingTokenGenerator {

    private final JdbcTemplate jdbcTemplate;

    public DbFencingTokenGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long nextToken() {
        Long next = jdbcTemplate.queryForObject("SELECT nextval('sync_fencing_token_seq')", Long.class);
        if (next == null) {
            throw new IllegalStateException("Failed to allocate sync fencing token");
        }
        return next;
    }
}
