package com.ssafy.ottereview.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.ottereview.webhook.dto.WebhookInboxStartResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class WebhookInboxServiceTest {

    private static final String DELIVERY_ID = "delivery-1";
    private static final String EVENT_TYPE = "pull_request";
    private static final String PAYLOAD = "{}";

    @Mock
    private WebhookInboxTransactionService transactionService;

    @Mock
    private WebhookInboxProcessingService processingService;

    @InjectMocks
    private WebhookInboxService webhookInboxService;

    @Test
    void processesNewDelivery() {
        when(transactionService.begin(DELIVERY_ID, EVENT_TYPE, PAYLOAD))
                .thenReturn(WebhookInboxStartResult.process(1L));

        boolean processed = webhookInboxService.process(DELIVERY_ID, EVENT_TYPE, PAYLOAD);

        assertThat(processed).isTrue();
        verify(processingService).process(1L);
        verify(transactionService, never()).markFailed(anyLong(), any());
    }

    @Test
    void skipsDeliveryThatWasAlreadyReceived() {
        when(transactionService.begin(DELIVERY_ID, EVENT_TYPE, PAYLOAD))
                .thenReturn(WebhookInboxStartResult.duplicate(1L));

        boolean processed = webhookInboxService.process(DELIVERY_ID, EVENT_TYPE, PAYLOAD);

        assertThat(processed).isFalse();
        verify(processingService, never()).process(1L);
    }

    @Test
    void resolvesConcurrentInsertConflictAsDuplicate() {
        when(transactionService.begin(DELIVERY_ID, EVENT_TYPE, PAYLOAD))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(transactionService.findDuplicate(DELIVERY_ID))
                .thenReturn(WebhookInboxStartResult.duplicate(1L));

        boolean processed = webhookInboxService.process(DELIVERY_ID, EVENT_TYPE, PAYLOAD);

        assertThat(processed).isFalse();
        verify(processingService, never()).process(1L);
    }

    @Test
    void recordsFailureAndRethrowsProcessingException() {
        RuntimeException failure = new RuntimeException("database error");
        when(transactionService.begin(DELIVERY_ID, EVENT_TYPE, PAYLOAD))
                .thenReturn(WebhookInboxStartResult.process(1L));
        org.mockito.Mockito.doThrow(failure)
                .when(processingService).process(1L);

        assertThatThrownBy(() -> webhookInboxService.process(DELIVERY_ID, EVENT_TYPE, PAYLOAD))
                .isSameAs(failure);

        verify(transactionService).markFailed(1L, failure);
    }
}
