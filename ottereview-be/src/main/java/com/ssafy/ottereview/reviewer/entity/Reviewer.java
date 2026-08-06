package com.ssafy.ottereview.reviewer.entity;

import com.ssafy.ottereview.common.entity.BaseEntity;
import com.ssafy.ottereview.pullrequest.entity.PullRequest;
import com.ssafy.ottereview.user.entity.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Entity
@Builder
@Table(
        name = "reviewer",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reviewer_pull_request_user",
                columnNames = {"pull_request_id", "user_id"}
        )
)
public class Reviewer extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "pull_request_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private PullRequest pullRequest;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewStatus status;

    public void updateStatus(ReviewStatus newStatus) {
        this.status = newStatus;
    }
}
