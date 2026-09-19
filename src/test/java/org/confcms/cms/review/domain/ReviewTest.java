package org.confcms.cms.review.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewTest {

    @Test
    void canSetAndGetDecision() {
        Review review = new Review();
        review.setDecision(ReviewDecision.STRONG_ACCEPT);

        assertThat(review.getDecision()).isEqualTo(ReviewDecision.STRONG_ACCEPT);
    }
}
