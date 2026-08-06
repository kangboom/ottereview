package com.ssafy.ottereview.webhook.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WebhookInboxMappingTest {

    @Test
    void deliveryIdHasDatabaseUniqueConstraint() {
        Table table = WebhookInbox.class.getAnnotation(Table.class);

        assertThat(Arrays.stream(table.uniqueConstraints())
                .flatMap(constraint -> Arrays.stream(constraint.columnNames())))
                .containsExactly("delivery_id");
    }

    @Test
    void statusAndModifiedTimeHaveReconciliationIndex() {
        Table table = WebhookInbox.class.getAnnotation(Table.class);

        assertThat(Arrays.stream(table.indexes())
                .map(index -> Set.of(index.columnList().split(", "))))
                .containsExactlyInAnyOrder(
                        Set.of("status", "modified_at"),
                        Set.of("status", "next_retry_at")
                );
    }
}
