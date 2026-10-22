package com.devsecops.userservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.devsecops.userservice.domain.User;
import com.devsecops.userservice.error.ResourceNotFoundException;
import com.devsecops.userservice.repository.UserRepository;
import com.devsecops.userservice.service.UserService;
import com.devsecops.userservice.web.dto.UserRequest;
import com.devsecops.userservice.web.dto.UserResponse;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link UserService} with a mocked repository.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void findAll_mapsEntitiesToResponses() {
        when(userRepository.findAll()).thenReturn(List.of(
                new User("Aarthi Kumar", "aarthi@example.com", "ADMIN")
        ));

        List<UserResponse> users = userService.findAll();

        assertThat(users).hasSize(1);
        assertThat(users.get(0).email()).isEqualTo("aarthi@example.com");
        assertThat(users.get(0).role()).isEqualTo("ADMIN");
    }

    @Test
    void findById_returnsUser() {
        User user = new User("Ravi Menon", "ravi@example.com", "USER");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserResponse response = userService.findById(1L);

        assertThat(response.email()).isEqualTo("ravi@example.com");
        assertThat(response.active()).isTrue();
    }

    @Test
    void findById_throwsNotFoundWhenMissing() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void create_normalisesEmailAndSaves() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.create(new UserRequest(" Meera Nair ", "  MEERA@Example.COM ", "USER"));

        assertThat(response.fullName()).isEqualTo("Meera Nair");
        assertThat(response.email()).isEqualTo("meera@example.com");
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void update_changesFieldsOfExistingUser() {
        User user = new User("Old Name", "old@example.com", "USER");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        UserResponse response = userService.update(7L, new UserRequest("New Name", "new@example.com", "ADMIN"));

        assertThat(response.fullName()).isEqualTo("New Name");
        assertThat(response.role()).isEqualTo("ADMIN");
    }

    @Test
    void delete_removesExistingUser() {
        User user = new User("Dev Anand", "dev@example.com", "SUPPORT");
        when(userRepository.findById(3L)).thenReturn(Optional.of(user));

        userService.delete(3L);

        verify(userRepository, times(1)).delete(user);
    }

    @Test
    void delete_throwsNotFoundWhenMissing() {
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.delete(42L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
