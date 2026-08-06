package com.ssafy.ottereview.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class WebhookInboxTransactionBoundaryTest {

    @Test
    void beginAndFailureRecordingUseIndependentTransactions() throws Exception {
        assertRequiresNew(WebhookInboxTransactionService.class.getMethod(
                "begin", String.class, String.class, String.class));
        assertRequiresNew(WebhookInboxTransactionService.class.getMethod(
                "markFailed", Long.class, RuntimeException.class));
    }

    @Test
    void eventProcessingAndSuccessStatusShareOneTransaction() throws Exception {
        Method process = WebhookInboxProcessingService.class.getMethod(
                "process", Long.class);

        assertThat(process.getAnnotation(Transactional.class)).isNotNull();
    }

    private void assertRequiresNew(Method method) {
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
