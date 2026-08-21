package com.corebank.auth.service;

import com.corebank.auth.domain.AppUser;
import com.corebank.auth.domain.Role;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Authenticated principal during login. It carries the linked customer id so the issued
 * token can assert ownership without a second lookup on every subsequent request.
 */
public record AppUserPrincipal(
        UUID userId,
        String username,
        String passwordHash,
        String fullName,
        boolean enabled,
        UUID customerId,
        Set<Role> roles) implements UserDetails {

    public static AppUserPrincipal from(AppUser user) {
        return new AppUserPrincipal(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getFullName(),
                user.isEnabled(),
                user.getCustomer() == null ? null : user.getCustomer().getId(),
                Set.copyOf(user.getRoles()));
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role.authority()))
                .toList();
    }

    public List<String> roleNames() {
        return roles.stream().map(Enum::name).sorted().toList();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
