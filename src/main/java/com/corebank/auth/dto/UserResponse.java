package com.corebank.auth.dto;

import com.corebank.auth.domain.AppUser;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String username,
        String fullName,
        boolean enabled,
        UUID customerId,
        List<String> roles) {

    public static UserResponse from(AppUser user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.isEnabled(),
                user.getCustomer() == null ? null : user.getCustomer().getId(),
                user.getRoles().stream().map(Enum::name).sorted().toList());
    }
}
