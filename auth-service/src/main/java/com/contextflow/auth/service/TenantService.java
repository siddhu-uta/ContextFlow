package com.contextflow.auth.service;

import java.util.List;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.contextflow.auth.dto.CreateUserRequest;
import com.contextflow.auth.dto.UserResponse;
import com.contextflow.auth.entity.Tenant;
import com.contextflow.auth.entity.User;
import com.contextflow.auth.exception.UserAlreadyExistsException;
import com.contextflow.auth.repository.TenantRepository;
import com.contextflow.auth.repository.UserRepository;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResponse createUser(UUID tenantId, CreateUserRequest request) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Tenant not found: " + tenantId));

        if (userRepository.existsByEmailAndTenantId(request.email(), tenantId)) {
            throw new UserAlreadyExistsException("User already exists with email: " + request.email());
        }

        User user = new User();
        user.setTenant(tenant);
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role());
        user = userRepository.save(user);

        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers(UUID tenantId) {
        return userRepository.findAllByTenantId(tenantId)
                .stream()
                .map(UserResponse::from)
                .toList();
    }
}
