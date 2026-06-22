package io.bunnycal.common.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.net.URI;
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
            log.info("startup db connection={}", sanitizeJdbcUrl(md.getURL()));
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

    private String sanitizeJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "<unknown>";
        }
        String raw = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring(5) : jdbcUrl;
        int queryIndex = raw.indexOf('?');
        String withoutQuery = queryIndex >= 0 ? raw.substring(0, queryIndex) : raw;
        try {
            URI uri = URI.create(withoutQuery);
            String host = uri.getHost();
            int port = uri.getPort();
            String path = uri.getPath();
            StringBuilder sanitized = new StringBuilder(uri.getScheme()).append("://");
            sanitized.append(host == null ? "<unknown>" : host);
            if (port >= 0) {
                sanitized.append(':').append(port);
            }
            if (path != null) {
                sanitized.append(path);
            }
            return sanitized.toString();
        } catch (IllegalArgumentException ex) {
            return "<unparseable>";
        }
    }
}
