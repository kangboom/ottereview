package com.ssafy.ottereview.pullrequest.entity;

import com.ssafy.ottereview.common.entity.BaseEntity;
import com.ssafy.ottereview.pullrequest.dto.response.PullRequestResponse;
import com.ssafy.ottereview.repo.entity.Repo;
import com.ssafy.ottereview.user.entity.User;
import com.ssafy.ottereview.webhook.dto.PullRequestEventDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.net.URL;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Builder
@Entity
@Table(
        name = "pull_request",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_pull_request_github_id",
                        columnNames = "github_id"
                ),
                @UniqueConstraint(
                        name = "uk_pull_request_repo_number",
                        columnNames = {"repo_id", "github_pr_number"}
                )
        }
)
public class PullRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "github_pr_number", nullable = false)
    private Integer githubPrNumber;

    @Column(name = "github_id", nullable = false)
    private Long githubId;
    
    @Column
    private String commitSha;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Repo repo;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PrState state;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User author;

    @Column(nullable = false)
    private Boolean merged;

    @Column(nullable = false)
    private String base;

    @Column(nullable = false)
    private String head;

    @Column(nullable = false)
    private Boolean mergeable;

    @Column
    private LocalDateTime githubCreatedAt; // GitHub에서의 생성일시

    @Column
    private LocalDateTime githubUpdatedAt; // GitHub에서의 수정일시

    @Column
    private Integer commitCnt;

    @Column
    private Integer changedFilesCnt; // 변경된 파일 수

    @Column
    private Integer commentCnt; // 일반 코멘트 수

    @Column
    private Integer reviewCommentCnt; // 리뷰 코멘트 수

    @Column
    private URL htmlUrl;

    @Column
    private URL patchUrl; // Patch 파일 URL

    @Column
    private URL issueUrl; // Issue URL

    @Column
    private URL diffUrl; // Diff 파일 URL

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false)
    private Integer approveCnt; // 현재 승인 수

    public void changeMergeable(boolean mergeable){
        this.mergeable = mergeable;
    }

    public void enrollRepo(Repo repo) {
        this.repo = repo;
    }

    public void enrollAuthor(User author) {
        this.author = author;
    }

    public void enrollSummary(String summary) {
        this.summary = summary;
    }

    public void updateState(PrState state){
        this.state = state;
    }
    
    public void synchronizedByWebhook(PullRequestEventDto event){
        this.commitSha = event.getPullRequest().getHead().getSha();
        this.title = event.getPullRequest().getTitle();
        this.body = event.getPullRequest().getBody();
        this.state = PrState.fromGithubState(event.getPullRequest().getState(), event.getPullRequest().getMerged());
        this.merged = event.getPullRequest().getMerged() == null || event.getPullRequest().getMerged();
        this.githubUpdatedAt = event.getPullRequest().getUpdatedAt();
        this.commitCnt = event.getPullRequest().getCommits();
        this.changedFilesCnt = event.getPullRequest().getChangedFiles();
        this.commentCnt = event.getPullRequest().getComments();
        this.reviewCommentCnt = event.getPullRequest().getReviewComments();
        this.htmlUrl = event.getPullRequest().getHtmlUrl();
        this.patchUrl = event.getPullRequest().getPatchUrl();
        this.issueUrl = event.getPullRequest().getIssueUrl();
        this.diffUrl = event.getPullRequest().getDiffUrl();
        if(event.getPullRequest().getMergeable() != null) {
            this.mergeable = event.getPullRequest().getMergeable();
        }
        this.base = event.getPullRequest().getBase().getRef();
    }
    
    public void addApproveCnt() {
        this.approveCnt++;
    }

    public static PullRequest to(PullRequestResponse event){
        Repo repoEntity = null;
        if (event.getRepo() != null) {
            repoEntity = Repo.builder()
                    .id(event.getRepo().getId())
                    .build();
        }

        User authorEntity = null;
        if (event.getAuthor() != null) {
            authorEntity = User.builder()
                    .id(event.getAuthor().getId())
                    .build();
        }

        return PullRequest.builder()
                .id(event.getId())
                .githubPrNumber(event.getGithubPrNumber())
                .githubId(event.getGithubId())
                .title(event.getTitle())
                .body(event.getBody())
                .summary(event.getSummary())
                .approveCnt(event.getApproveCnt())
                .state(PrState.valueOf(event.getState())) // enum 변환
                .merged(event.getMerged())
                .mergeable(event.getMergeable())
                .head(event.getHead())
                .base(event.getBase())
                .commitCnt(event.getCommitCnt())
                .changedFilesCnt(event.getChangedFilesCnt())
                .commentCnt(event.getCommentCnt())
                .reviewCommentCnt(event.getReviewCommentCnt())
                .githubCreatedAt(event.getGithubCreatedAt())
                .githubUpdatedAt(event.getGithubUpdatedAt())
                .repo(repoEntity)
                .author(authorEntity)
                .build();
    }

}
