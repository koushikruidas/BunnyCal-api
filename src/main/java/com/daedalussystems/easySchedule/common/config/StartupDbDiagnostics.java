package com.daedalussystems.easySchedule.common.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StartupDbDiagnostics implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupDbDiagnostics.class);

    private final DataSource dataSource;
    private final Optional<Flyway> flyway;

    public StartupDbDiagnostics(DataSource dataSource, Optional<Flyway> flyway) {
        this.dataSource = dataSource;
        this.flyway = flyway;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            log.info("startup db url={}", md.getURL());
        }
        if (flyway.isPresent()) {
            MigrationInfoService info = flyway.get().info();
            int applied = info.applied() == null ? 0 : info.applied().length;
            int pending = info.pending() == null ? 0 : info.pending().length;
            log.info("startup flyway bean=true applied={} pending={}", applied, pending);
        } else {
            log.warn("startup flyway bean=false (auto-configuration did not create Flyway)");
        }
    }
}
