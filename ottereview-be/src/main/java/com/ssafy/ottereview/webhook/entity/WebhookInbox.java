package com.ssafy.ottereview.webhook.entity;

import com.ssafy.ottereview.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "webhook_inbox",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_webhook_inbox_delivery_id",
                columnNames = "delivery_id"
        ),
        indexes = @Index(
                name = "idx_webhook_inbox_status_modified_at",
                columnList = "status, modified_at"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WebhookInbox extends BaseEntity {

    private static final int MAX_ERROR_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delivery_id", nullable = false, length = 100)
    private String deliveryId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookInboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    public static WebhookInbox start(String deliveryId, String eventType, String payload) {
        return new WebhookInbox(
                null,
                deliveryId,
                eventType,
                payload,
                WebhookInboxStatus.PROCESSING,
                1,
                null,
                null
        );
    }

    public boolean isFailed() {
        return status == WebhookInboxStatus.FAILED;
    }

    public void retry() {
        status = WebhookInboxStatus.PROCESSING;
        attemptCount++;
        lastError = null;
        processedAt = null;
    }

    public void succeed(LocalDateTime processedAt) {
        status = WebhookInboxStatus.SUCCEEDED;
        lastError = null;
        this.processedAt = processedAt;
    }

    public void fail(String errorMessage) {
        status = WebhookInboxStatus.FAILED;
        lastError = truncate(errorMessage);
        processedAt = null;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }
}
