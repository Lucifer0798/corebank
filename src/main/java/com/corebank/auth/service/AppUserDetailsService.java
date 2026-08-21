package com.corebank.auth.service;

import com.corebank.auth.repository.AppUserRepository;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public AppUserPrincipal loadUserByUsername(String username) {
        return users.findByUsernameIgnoreCase(username)
                .map(AppUserPrincipal::from)
                // Deliberately vague: the caller must not learn whether the username exists.
                .orElseThrow(() -> new UsernameNotFoundException("Bad credentials"));
    }
}
