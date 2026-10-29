package org.confcms.cms.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

// Base for tests that wire the real Spring context + H2 (see application-test.properties)
// instead of mocking repositories/services -- catches wiring, query, and transaction-boundary
// bugs that pure-Mockito unit tests can't see. @Transactional rolls back every test's DB
// writes, so tests don't need to clean up after themselves or interfere with each other.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
public abstract class IntegrationTest {
}
