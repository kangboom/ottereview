package com.ssafy.ottereview.pullrequest.creation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class PullRequestCreationTransactionBoundaryTest {

    @Test
    void claimResultRecordingCompletionAndFailureUseIndependentTransactions() throws Exception {
        assertRequiresNew(PullRequestCreationTransactionService.class.getMethod(
                "claim", Long.class, LocalDateTime.class));
        assertRequiresNew(PullRequestCreationTransactionService.class.getMethod(
                "recordGithubCreated", Long.class, Long.class, Integer.class));
        assertRequiresNew(PullRequestCreationTransactionService.class.getMethod(
                "complete", Long.class, Long.class, Long.class, LocalDateTime.class));
        assertRequiresNew(PullRequestCreationTransactionService.class.getMethod(
                "fail", Long.class, RuntimeException.class, LocalDateTime.class));
    }

    private void assertRequiresNew(Method method) {
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
