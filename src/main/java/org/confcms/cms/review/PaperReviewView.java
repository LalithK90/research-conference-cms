package org.confcms.cms.review;

import java.time.LocalDateTime;
import java.util.List;

public record PaperReviewView(
        Long paperId,
        String title,
        String abstractText,
        String track,
        Integer latestVersionNumber,
        LocalDateTime dueDate,
        List<String> authorNames
) {
}
