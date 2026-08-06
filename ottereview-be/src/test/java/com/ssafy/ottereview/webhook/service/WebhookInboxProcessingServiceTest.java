package com.ssafy.ottereview.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.ottereview.webhook.entity.WebhookInbox;
import com.ssafy.ottereview.webhook.entity.WebhookInboxStatus;
import com.ssafy.ottereview.webhook.repository.WebhookInboxRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WebhookInboxProcessingServiceTest {

    @Mock
    private WebhookInboxRepository webhookInboxRepository;

    @Mock
    private WebhookEventDispatcher webhookEventDispatcher;

    @InjectMocks
    private WebhookInboxProcessingService processingService;

    @Test
    void dispatchesPersistedInboxContentAndMarksItSucceeded() {
        WebhookInbox inbox = WebhookInbox.start("delivery-1", "pull_request", "{\"action\":\"opened\"}");
        when(webhookInboxRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(inbox));

        processingService.process(1L);

        verify(webhookEventDispatcher).dispatch("pull_request", "{\"action\":\"opened\"}");
        assertThat(inbox.getStatus()).isEqualTo(WebhookInboxStatus.SUCCEEDED);
        assertThat(inbox.getProcessedAt()).isNotNull();
    }
}
