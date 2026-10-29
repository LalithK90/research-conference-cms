package org.confcms.cms.paper;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface PaperVersionRepository extends JpaRepository<PaperVersion, Long> {
    List<PaperVersion> findByContentHash(String contentHash);

    // Used by AdminController's duplicate-match resolution, which previously called findById()
    // once per flagged paper in a loop (an N+1) and then let the template access each matched
    // version's own lazy .paper.title (a second N+1 layered on top). One batched IN query with
    // the paper join fetched up front closes both.
    @Query("SELECT pv FROM PaperVersion pv JOIN FETCH pv.paper WHERE pv.id IN :ids")
    List<PaperVersion> findAllWithPaperByIdIn(Collection<Long> ids);
}
