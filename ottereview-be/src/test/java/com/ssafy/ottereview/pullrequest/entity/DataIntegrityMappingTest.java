package com.ssafy.ottereview.pullrequest.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.ottereview.reviewer.entity.Reviewer;
import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DataIntegrityMappingTest {

    @Test
    void pullRequestDeclaresGithubAndRepositoryScopedUniqueKeys() {
        assertThat(uniqueColumnSets(PullRequest.class))
                .containsExactlyInAnyOrder(
                        Set.of("github_id"),
                        Set.of("repo_id", "github_pr_number")
                );
    }

    @Test
    void reviewerDeclaresOneUserPerPullRequestUniqueKey() {
        assertThat(uniqueColumnSets(Reviewer.class))
                .containsExactly(Set.of("pull_request_id", "user_id"));
    }

    @Test
    void persistedPullRequestRequiresGithubIdentityAndRepository() throws Exception {
        assertThat(column(PullRequest.class, "githubId").nullable()).isFalse();
        assertThat(column(PullRequest.class, "githubPrNumber").nullable()).isFalse();
        assertThat(joinColumn(PullRequest.class, "repo").nullable()).isFalse();
    }

    private Set<Set<String>> uniqueColumnSets(Class<?> entityType) {
        Table table = entityType.getAnnotation(Table.class);

        return Arrays.stream(table.uniqueConstraints())
                .map(UniqueConstraint::columnNames)
                .map(Arrays::stream)
                .map(columns -> columns.collect(Collectors.toSet()))
                .collect(Collectors.toSet());
    }

    private Column column(Class<?> entityType, String fieldName) throws Exception {
        Field field = entityType.getDeclaredField(fieldName);
        return field.getAnnotation(Column.class);
    }

    private JoinColumn joinColumn(Class<?> entityType, String fieldName) throws Exception {
        Field field = entityType.getDeclaredField(fieldName);
        return field.getAnnotation(JoinColumn.class);
    }
}
