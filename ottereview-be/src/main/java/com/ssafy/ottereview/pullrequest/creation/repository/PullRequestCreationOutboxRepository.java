package com.ssafy.ottereview.pullrequest.creation.repository;

import com.ssafy.ottereview.pullrequest.creation.entity.PullRequestCreationOutbox;
import com.ssafy.ottereview.pullrequest.creation.entity.PullRequestCreationOutboxStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PullRequestCreationOutboxRepository
        extends JpaRepository<PullRequestCreationOutbox, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o from PullRequestCreationOutbox o
            join fetch o.task t
            join fetch t.repo r
            join fetch r.account
            join fetch t.author
            where o.id = :id
            """)
    Optional<PullRequestCreationOutbox> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select o.id from PullRequestCreationOutbox o
            where o.status in :statuses
              and o.nextAttemptAt <= :now
            order by o.nextAttemptAt asc, o.id asc
            """)
    List<Long> findReadyIds(
            @Param("statuses") Collection<PullRequestCreationOutboxStatus> statuses,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Query("""
            select o.id from PullRequestCreationOutbox o
            where o.status = :status
              and o.modifiedAt < :threshold
            order by o.modifiedAt asc, o.id asc
            """)
    List<Long> findStaleIds(
            @Param("status") PullRequestCreationOutboxStatus status,
            @Param("threshold") LocalDateTime threshold,
            Pageable pageable
    );
}
