package com.ssafy.ottereview.webhook.repository;

import com.ssafy.ottereview.webhook.entity.WebhookInbox;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookInboxRepository extends JpaRepository<WebhookInbox, Long> {

    Optional<WebhookInbox> findByDeliveryId(String deliveryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select inbox from WebhookInbox inbox where inbox.deliveryId = :deliveryId")
    Optional<WebhookInbox> findByDeliveryIdForUpdate(@Param("deliveryId") String deliveryId);
}
