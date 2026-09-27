package org.confcms.cms.publicweb.controller;

import org.confcms.cms.repository.ConferenceRepository;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.service.ConferenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class PublicWebControllerTest {

    @Mock
    private ConferenceService conferenceService;
    @Mock
    private CommitteeService committeeService;
    @Mock
    private ClientRegistrationRepository clientRegistrationRepository;
    @Mock
    private ConferenceRepository conferenceRepository;

    private PublicWebController controller() {
        return new PublicWebController(conferenceService, committeeService, clientRegistrationRepository, conferenceRepository);
    }

    @Test
    void aboutReturnsAboutView() {
        Model model = new ExtendedModelMap();
        String view = controller().about(model);
        assertThat(view).isEqualTo("public/about");
    }

    @Test
    void contactReturnsContactView() {
        Model model = new ExtendedModelMap();
        String view = controller().contact(model);
        assertThat(view).isEqualTo("public/contact");
    }
}
