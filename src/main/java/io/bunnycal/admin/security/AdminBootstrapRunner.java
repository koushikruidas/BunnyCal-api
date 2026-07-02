package io.bunnycal.admin.security;

import io.bunnycal.auth.domain.user.User;
import io.bunnycal.auth.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the first {@link AdminRole#SUPER_ADMIN} so we are never locked out of the admin
 * portal. Guarded by {@code admin.bootstrap.email}: when set, the matching user (who must
 * already exist via normal OAuth signup) is granted SUPER_ADMIN on startup if they don't
 * already hold it. Idempotent; a no-op when the property is blank.
 *
 * <p>The grant takes effect on that user's next login/refresh, when roles are read into the
 * JWT. After the first super-admin exists, further grants flow through the (audited) admin UI.
 */
@Slf4j
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private final UserRepository userRepository;
    private final AdminRoleRepository adminRoleRepository;
    private final String bootstrapEmail;

    public AdminBootstrapRunner(UserRepository userRepository,
                                AdminRoleRepository adminRoleRepository,
                                @Value("${admin.bootstrap.email:}") String bootstrapEmail) {
        this.userRepository = userRepository;
        this.adminRoleRepository = adminRoleRepository;
        this.bootstrapEmail = bootstrapEmail;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (bootstrapEmail == null || bootstrapEmail.isBlank()) {
            return;
        }
        String email = bootstrapEmail.trim();
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            log.warn("admin_bootstrap_skipped reason=user_not_found email={} "
                    + "(the user must sign in once before they can be bootstrapped)", email);
            return;
        }
        if (adminRoleRepository
                .findByUserIdAndRoleAndRevokedAtIsNull(user.getId(), AdminRole.SUPER_ADMIN)
                .isPresent()) {
            log.info("admin_bootstrap_noop reason=already_super_admin email={}", email);
            return;
        }
        adminRoleRepository.save(AdminRoleEntity.builder()
                .userId(user.getId())
                .role(AdminRole.SUPER_ADMIN)
                .grantedBy(null) // bootstrap grant has no granting admin
                .build());
        log.info("admin_bootstrap_granted role=SUPER_ADMIN email={} userId={} "
                + "(effective on next login/refresh)", email, user.getId());
    }
}
