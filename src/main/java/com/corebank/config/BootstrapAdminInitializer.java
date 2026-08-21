package com.corebank.config;

import com.corebank.auth.domain.AppUser;
import com.corebank.auth.domain.Role;
import com.corebank.auth.repository.AppUserRepository;
import java.util.EnumSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator when the user table is empty, because every other login is
 * created through an authenticated endpoint and the system would otherwise be unreachable.
 * It runs once: as soon as any user exists this does nothing.
 */
@Configuration
public class BootstrapAdminInitializer {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);
    private static final String ADMIN_USERNAME = "admin";

    /** Runs before any data-seeding runner. */
    public static final int BOOTSTRAP_ORDER = 0;

    /** Ordered ahead of every other runner: it must see a genuinely empty user table. */
    @Bean
    @Order(BOOTSTRAP_ORDER)
    public ApplicationRunner bootstrapAdmin(AppUserRepository users,
                                            PasswordEncoder passwordEncoder,
                                            CoreBankProperties properties) {
        return args -> createIfMissing(users, passwordEncoder, properties);
    }

    @Transactional
    void createIfMissing(AppUserRepository users, PasswordEncoder passwordEncoder, CoreBankProperties properties) {
        if (users.count() > 0) {
            return;
        }

        AppUser admin = new AppUser();
        admin.setUsername(ADMIN_USERNAME);
        admin.setPasswordHash(passwordEncoder.encode(properties.security().bootstrapAdminPassword()));
        admin.setFullName("Bootstrap Administrator");
        admin.setEnabled(true);
        admin.setRoles(EnumSet.of(Role.ADMIN));
        users.save(admin);

        log.warn("Created the bootstrap '{}' login from corebank.security.bootstrap-admin-password. "
                + "Change this password before exposing the service.", ADMIN_USERNAME);
    }
}
