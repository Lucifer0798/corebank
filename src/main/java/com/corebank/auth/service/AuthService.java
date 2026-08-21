package com.corebank.auth.service;

import com.corebank.auth.domain.AppUser;
import com.corebank.auth.domain.Role;
import com.corebank.auth.dto.CreateUserRequest;
import com.corebank.auth.dto.LoginRequest;
import com.corebank.auth.dto.LoginResponse;
import com.corebank.auth.dto.UserResponse;
import com.corebank.auth.repository.AppUserRepository;
import com.corebank.common.exception.BusinessRuleException;
import com.corebank.common.exception.ConflictException;
import com.corebank.common.exception.ResourceNotFoundException;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.repository.CustomerRepository;
import java.util.EnumSet;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final AppUserRepository users;
    private final CustomerRepository customers;
    private final PasswordEncoder passwordEncoder;

    public AuthService(AuthenticationManager authenticationManager,
                       TokenService tokenService,
                       AppUserRepository users,
                       CustomerRepository customers,
                       PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
        this.users = users;
        this.customers = customers;
        this.passwordEncoder = passwordEncoder;
    }

    public LoginResponse login(LoginRequest request) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException ex) {
            // Collapse every failure mode into one message so the response cannot be used
            // to enumerate usernames or spot disabled accounts.
            throw new BadCredentialsException("Invalid username or password");
        }

        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        TokenService.IssuedToken token = tokenService.issue(principal);

        return new LoginResponse(
                token.value(),
                "Bearer",
                token.expiresInSeconds(),
                principal.username(),
                principal.roleNames());
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (users.existsByUsernameIgnoreCase(request.username())) {
            throw new ConflictException("USERNAME_TAKEN", "Username '" + request.username() + "' is already in use");
        }

        AppUser user = new AppUser();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName());
        user.setRoles(EnumSet.copyOf(request.roles()));
        user.setEnabled(true);

        if (request.customerId() != null) {
            Customer customer = customers.findById(request.customerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer", request.customerId()));
            user.setCustomer(customer);
        } else if (request.roles().contains(Role.CUSTOMER)) {
            throw new BusinessRuleException("CUSTOMER_LINK_REQUIRED",
                    "A login with the CUSTOMER role must be linked to a customer");
        }

        return UserResponse.from(users.save(user));
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(String username) {
        return users.findByUsernameIgnoreCase(username)
                .map(UserResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("User", username));
    }
}
