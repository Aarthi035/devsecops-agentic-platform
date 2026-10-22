package com.devsecops.userservice.service;

import com.devsecops.userservice.domain.User;
import com.devsecops.userservice.error.ResourceNotFoundException;
import com.devsecops.userservice.repository.UserRepository;
import com.devsecops.userservice.web.dto.UserRequest;
import com.devsecops.userservice.web.dto.UserResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> findAll() {
        return userRepository.findAll().stream()
                .map(UserResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse findById(Long id) {
        return UserResponse.from(requireUser(id));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> findByEmail(String email) {
        return userRepository.findByEmail(email)
                .map(UserResponse::from)
                .map(List::of)
                .orElseGet(List::of);
    }

    @Transactional
    public UserResponse create(UserRequest request) {
        User user = new User(request.fullName().trim(), request.email().trim().toLowerCase(), request.role());
        return UserResponse.from(userRepository.save(user));
    }

    @Transactional
    public UserResponse update(Long id, UserRequest request) {
        User user = requireUser(id);
        user.setFullName(request.fullName().trim());
        user.setEmail(request.email().trim().toLowerCase());
        user.setRole(request.role());
        return UserResponse.from(userRepository.save(user));
    }

    @Transactional
    public void delete(Long id) {
        User user = requireUser(id);
        userRepository.delete(user);
    }

    private User requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User " + id + " was not found"));
    }
}
