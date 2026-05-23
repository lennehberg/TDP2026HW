package com.att.tdp.issueflow.user;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditService;
import com.att.tdp.issueflow.audit.EntityType;
import com.att.tdp.issueflow.common.exception.ConflictException;
import com.att.tdp.issueflow.common.exception.NotFoundException;
import com.att.tdp.issueflow.user.dto.CreateUserRequest;
import com.att.tdp.issueflow.user.dto.UpdateUserRequest;
import com.att.tdp.issueflow.user.dto.UserResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Transactional
    public UserResponse create(CreateUserRequest req) {
        // check if username or email already in repository
        if (userRepository.existsByUsername(req.username())) {
            throw new ConflictException("username already exists");
        }
        if (userRepository.existsByEmail(req.email())) {
            throw new ConflictException("email already exists");
        }

        // save user
        User saved = userRepository.save(User.builder()
                .username(req.username())
                .email(req.email())
                .fullName(req.fullName())
                .role(req.role())
                .passwordHash(passwordEncoder.encode(req.password()))
                .build());
        auditService.recordUserAction(AuditAction.CREATE, EntityType.USER, saved.getId());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        // return list of all users (wrapped in UserResponse)
        return userRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse getById(Long id) {
        // query repository for user id, return not found exception if not found
        return userRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException("user " + id + " not found"));
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest req) {
        // check if user exists
        User u = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("user " + id + " not found"));
        // Null means "field absent" — leave the existing value. UpdateUserRequest
        // only exposes nullable-safe fields, so JsonNullable is not needed here.
        if (req.fullName() != null) {
            u.setFullName(req.fullName());
        }
        if (req.role() != null) {
            u.setRole(req.role());
        }
        auditService.recordUserAction(AuditAction.UPDATE, EntityType.USER, id);
        return toResponse(u);
    }

    @Transactional
    public void delete(Long id) {
        // delete user by id if id is user
        if (!userRepository.existsById(id)) {
            throw new NotFoundException("user " + id + " not found");
        }
        userRepository.deleteById(id);
        auditService.recordUserAction(AuditAction.DELETE, EntityType.USER, id);
    }

    private UserResponse toResponse(User u) {
        return new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getFullName(), u.getRole());
    }
}
