package org.confcms.cms.auth.service;

import org.confcms.cms.domain.MagicLink;
import org.confcms.cms.repository.MagicLinkRepository;
import org.confcms.cms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MagicLinkServiceTest {

    @Mock
    private MagicLinkRepository magicLinkRepository;
    @Mock
    private UserRepository userRepository;

    private MagicLinkService service;

    @BeforeEach
    void setUp() {
        service = new MagicLinkService(magicLinkRepository, userRepository);
    }

    @Test
    void markUsedReturnsTrueAndFlipsLinkWhenRowWasActuallyUpdated() {
        MagicLink link = new MagicLink();
        link.setId(1L);
        link.setUsed(false);
        when(magicLinkRepository.markUsedIfUnused(1L)).thenReturn(1);

        boolean result = service.markUsed(link);

        assertThat(result).isTrue();
        assertThat(link.isUsed()).isTrue();
    }

    @Test
    void markUsedReturnsFalseWhenAnotherRequestAlreadyWonTheRace() {
        MagicLink link = new MagicLink();
        link.setId(1L);
        link.setUsed(false);
        when(magicLinkRepository.markUsedIfUnused(1L)).thenReturn(0);

        boolean result = service.markUsed(link);

        assertThat(result).isFalse();
    }
}
