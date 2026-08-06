package com.ssafy.ottereview.webhook.repository;

import com.ssafy.ottereview.webhook.entity.WebhookInbox;
import com.ssafy.ottereview.webhook.entity.WebhookInboxStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookInboxRepository extends JpaRepository<WebhookInbox, Long> {

    Optional<WebhookInbox> findByDeliveryId(String deliveryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select inbox from WebhookInbox inbox where inbox.deliveryId = :deliveryId")
    Optional<WebhookInbox> findByDeliveryIdForUpdate(@Param("deliveryId") String deliveryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select inbox from WebhookInbox inbox where inbox.id = :id")
    Optional<WebhookInbox> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select inbox.id
            from WebhookInbox inbox
            where inbox.status = :status
              and inbox.modifiedAt < :threshold
            order by inbox.modifiedAt
            """)
    List<Long> findStaleIds(
            @Param("status") WebhookInboxStatus status,
            @Param("threshold") LocalDateTime threshold,
            Pageable pageable
    );

    @Query("""
            select inbox.id
            from WebhookInbox inbox
            where inbox.status = :status
              and inbox.nextRetryAt <= :now
            order by inbox.nextRetryAt
            """)
    List<Long> findRetryableIds(
            @Param("status") WebhookInboxStatus status,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );
}
