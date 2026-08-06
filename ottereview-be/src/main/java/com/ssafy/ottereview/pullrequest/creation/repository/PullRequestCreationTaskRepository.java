package com.ssafy.ottereview.pullrequest.creation.repository;

import com.ssafy.ottereview.pullrequest.creation.entity.PullRequestCreationTask;
import com.ssafy.ottereview.pullrequest.creation.entity.PullRequestCreationStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PullRequestCreationTaskRepository
        extends JpaRepository<PullRequestCreationTask, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PullRequestCreationTask t where t.id = :id")
    Optional<PullRequestCreationTask> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select (count(t) > 0) from PullRequestCreationTask t
            where t.repo.id = :repoId
              and t.source = :source
              and t.target = :target
              and t.status in :statuses
            """)
    boolean existsActiveTask(
            @Param("repoId") Long repoId,
            @Param("source") String source,
            @Param("target") String target,
            @Param("statuses") Collection<PullRequestCreationStatus> statuses
    );
}
