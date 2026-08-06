package com.ssafy.ottereview.pullrequest.repository;

import com.ssafy.ottereview.pullrequest.entity.PrState;
import com.ssafy.ottereview.pullrequest.entity.PullRequest;
import com.ssafy.ottereview.repo.entity.Repo;
import com.ssafy.ottereview.user.entity.User;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PullRequestRepository extends JpaRepository<PullRequest, Long> {
    
//    Page<PullRequest> findAllByRepo(Repo repo, Pageable pageable);

    List<PullRequest> findAllByRepo(Repo repo);
    
    List<PullRequest> findAllByAuthor(User author);
    
    Optional<PullRequest> findByGithubId(Long githubId);
    
    Optional<PullRequest> findByRepoAndBaseAndHeadAndState(Repo repo, String base, String head, PrState state);
    
    Boolean existsByRepoAndBaseAndHeadAndState(Repo repo, String base, String head, PrState state);

    @Query("""
  SELECT p FROM PullRequest p
  WHERE p.repo.repoId = :repoId
    AND (:cursorUpdatedAt IS NULL OR p.githubUpdatedAt < :cursorUpdatedAt
         OR (p.githubUpdatedAt= :cursorUpdatedAt AND p.id < :cursorId))
  ORDER BY p.githubUpdatedAt DESC, p.id DESC
""")
    List<PullRequest> findSlice(
            @Param("repoId") Long repoId,
            @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);
}
