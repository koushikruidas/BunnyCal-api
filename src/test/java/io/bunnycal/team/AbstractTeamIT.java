package io.bunnycal.team;

import io.bunnycal.testsupport.TestContainers;
import io.bunnycal.TestApplication;
import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = TestApplication.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
        "spring.otel.sdk.disabled=true",
        "spring.docker.compose.enabled=false",
        "security.enabled=false",
        "scheduling.enabled=false"
})
public abstract class AbstractTeamIT {



    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        TestContainers.registerProperties(registry);
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserRepository userRepository;
    @Autowired protected PlatformTransactionManager txManager;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE users, teams, team_members, team_invitations CASCADE");
        jdbc.execute("DELETE FROM outbox_events WHERE aggregate_type IN ('TeamInvitation', 'TeamMember')");
    }

    protected User createUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .name("User " + email)
                .timezone("UTC")
                .build());
    }

    protected <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    protected void inTx(Runnable work) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> work.run());
    }
}
