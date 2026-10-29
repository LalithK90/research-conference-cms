package org.confcms.cms.auth;

import org.confcms.cms.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordHistoryServiceTest {

    @Mock
    private PasswordHistoryRepository repository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private PasswordHistoryService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PasswordHistoryService(repository, passwordEncoder);
        user = new User();
        user.setId(1L);
    }

    @Test
    void isReuseReturnsFalseWhenNoHistoryExists() {
        when(repository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        boolean result = service.isReuse(user, "NewPass123!");

        assertThat(result).isFalse();
    }

    @Test
    void isReuseReturnsTrueWhenTheNewPasswordMatchesAPastHash() {
        PasswordHistory old1 = new PasswordHistory();
        old1.setPasswordHash("hash-old-1");
        PasswordHistory old2 = new PasswordHistory();
        old2.setPasswordHash("hash-old-2");
        when(repository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(old1, old2));
        when(passwordEncoder.matches("NewPass123!", "hash-old-1")).thenReturn(false);
        when(passwordEncoder.matches("NewPass123!", "hash-old-2")).thenReturn(true);

        boolean result = service.isReuse(user, "NewPass123!");

        assertThat(result).isTrue();
    }

    @Test
    void isReuseReturnsFalseWhenTheNewPasswordMatchesNoPastHash() {
        PasswordHistory old1 = new PasswordHistory();
        old1.setPasswordHash("hash-old-1");
        when(repository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(old1));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        boolean result = service.isReuse(user, "BrandNewPass123!");

        assertThat(result).isFalse();
    }

    @Test
    void recordSavesTheNewHashedPasswordAsAHistoryRow() {
        when(repository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        service.record(user, "already-hashed-value");

        ArgumentCaptor<PasswordHistory> captor = ArgumentCaptor.forClass(PasswordHistory.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(user);
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("already-hashed-value");
    }

    // Keeps only the most recent MAX_HISTORY (5) rows per user -- an account with a long
    // password-change history shouldn't accumulate unbounded rows, and reuse-checking only
    // needs to look back that far anyway.
    @Test
    void recordTrimsHistoryBeyondTheRetentionLimit() {
        List<PasswordHistory> existing = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> {
                    PasswordHistory h = new PasswordHistory();
                    h.setId((long) i);
                    return h;
                })
                .toList();
        when(repository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(existing);
        when(repository.save(any())).thenAnswer(inv -> {
            PasswordHistory saved = inv.getArgument(0);
            saved.setId(99L);
            return saved;
        });

        service.record(user, "new-hash");

        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).deleteByUserIdAndIdNotIn(eq(1L), idsCaptor.capture());
        // Keeps the 4 most recent existing rows (ids 0-3) plus the brand-new row (99) = 5 total.
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(0L, 1L, 2L, 3L, 99L);
    }

    @Test
    void recordDoesNotTrimWhenUnderTheRetentionLimit() {
        when(repository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> {
            PasswordHistory saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        service.record(user, "new-hash");

        verify(repository, never()).deleteByUserIdAndIdNotIn(any(), any());
    }
}
