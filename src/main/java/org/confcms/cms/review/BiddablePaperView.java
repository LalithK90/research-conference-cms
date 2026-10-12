package org.confcms.cms.review;

// Deliberately never includes author names, for any conference -- bidding is a pre-assignment
// interest signal, not paper review, so author identity isn't needed to decide whether to bid.
// This is a stricter default than PaperReviewView's own blind-review-only rule, intentionally,
// since nothing about judging interest in reviewing a paper requires knowing who wrote it.
public record BiddablePaperView(
        Long paperId,
        String title,
        String abstractText,
        String track,
        BidType currentBid
) {
}
