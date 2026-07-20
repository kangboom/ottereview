package com.ssafy.ottereview.githubapp.client;

import com.ssafy.ottereview.branch.entity.Branch;
import com.ssafy.ottereview.branch.repository.BranchRepository;
import com.ssafy.ottereview.common.exception.BusinessException;
import com.ssafy.ottereview.githubapp.dto.GithubAccountResponse;
import com.ssafy.ottereview.githubapp.dto.GithubPrResponse;
import com.ssafy.ottereview.githubapp.exception.GithubAppErrorCode;
import com.ssafy.ottereview.githubapp.util.GithubAppUtil;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestCommitInfo;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestFileInfo;
import com.ssafy.ottereview.pullrequest.dto.response.PullRequestDetailResponse;
import com.ssafy.ottereview.pullrequest.entity.PullRequest;
import com.ssafy.ottereview.pullrequest.exception.PullRequestErrorCode;
import com.ssafy.ottereview.pullrequest.repository.PullRequestRepository;
import com.ssafy.ottereview.pullrequest.util.PullRequestMapper;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHBranch;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHCompare;
import org.kohsuke.github.GHCompare.Commit;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestCommitDetail;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.GHPullRequestReviewComment;
import org.kohsuke.github.GHPullRequestReviewComment.Side;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Transactional
@Service
@Slf4j
public class GithubApiClient {
    
    private final GithubAppUtil githubAppUtil;
    private final PullRequestRepository pullRequestRepository;
    private final PullRequestMapper pullRequestMapper;
    private final BranchRepository branchRepository;
    
    public GithubAccountResponse getAccount(Long installationId) {
        try {
            
            GitHub appGitHub = githubAppUtil.getGitHubAsApp();
            GHAppInstallation installation = appGitHub.getApp()
                    .getInstallationById(installationId);
            
            return new GithubAccountResponse(installation.getId(), installation.getAccount()
                    .getLogin(), installation.getAccount()
                    .getType(), installation.getAccount()
                    .getId());
            
            
        } catch (IOException e) {
            log.error(
                    "GitHub App 설치 정보 조회 실패. installationId={}",
                    installationId,
                    e
            );
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_ACCOUNT_NOT_FOUND);
        }
    }
    
    /**
     * Repository 정보 가져오기
     */
    public GHRepository getRepository(Long installationId, String repositoryName) {
        try {
            GitHub github = githubAppUtil.getGitHub(installationId);
            return github.getRepository(repositoryName);
            
        } catch (IOException e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_REPOSITORY_NOT_FOUND);
        }
    }
    
    public List<GHRepository> getRepositories(Long installationId) {
        try {
            // InstallationTokenService를 통해 GitHub App 설치 인스턴스 가져오기
            GitHub github = githubAppUtil.getGitHub(installationId);
            
            // 해당 설치가 접근할 수 있는 저장소 목록을 가져옵니다.
            // .listInstallationRepositories()는 모든 접근 가능한 저장소를 반환합니다.
            List<GHRepository> repositories = github.getInstallation()
                    .listRepositories()
                    .toList();
            
            return repositories;
            
        } catch (IOException e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_REPOSITORY_NOT_FOUND);
        }
    }
    
    public List<GithubPrResponse> getPullRequests(Long installationId, String repositoryName) {
        try {
            GitHub github = githubAppUtil.getGitHub(installationId);
            
            GHRepository repo = github.getRepository(repositoryName);
            
            PagedIterable<GHPullRequest> pullRequests = repo.queryPullRequests()
                    .state(GHIssueState.OPEN) // GHIssueState.CLOSED, .ALL 등도 사용 가능
                    .list();
            
            return StreamSupport.stream(pullRequests.spliterator(), false)
                    .map(pr -> {
                        try {
                            return GithubPrResponse.from(pr);
                        } catch (Exception e) {
                            log.error("Error converting PR to DTO: {}", e.getMessage());
                            return null;
                        }
                    })
                    .filter(Objects::nonNull) // null 제거
                    .collect(Collectors.toList());
            
        } catch (IOException e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_PULL_REQUEST_NOT_FOUND);
        }
    }
    
    public PullRequestDetailResponse getPullRequestDetail(Long prId, String repositoryName) {
        
        PullRequest pullRequest = pullRequestRepository.findById(prId)
                .orElseThrow(() -> new BusinessException(PullRequestErrorCode.PR_NOT_FOUND));
        
        Integer githubPrNumber = pullRequest.getGithubPrNumber();
        
        Long installationId = pullRequest.getRepo()
                .getAccount()
                .getInstallationId();
        
        List<PullRequestFileInfo> pullRequestFileChanges = getPullRequestFileChanges(installationId, repositoryName, githubPrNumber);
        List<PullRequestCommitInfo> pullRequestCommitInfos = getPullRequestCommits(installationId, repositoryName, githubPrNumber);
        
        Branch baseBranch = branchRepository.findByNameAndRepo(pullRequest.getBase(), pullRequest.getRepo());
        Branch headBranch = branchRepository.findByNameAndRepo(pullRequest.getHead(), pullRequest.getRepo());
        
        return pullRequestMapper.pullRequestToDetailResponse(pullRequest, baseBranch, headBranch, pullRequestFileChanges, pullRequestCommitInfos);
    }
    
    private List<PullRequestFileInfo> getPullRequestFileChanges(Long installationId, String repositoryName, Integer githubPrNumber) {
        try {
            GitHub github = githubAppUtil.getGitHub(installationId);
            
            GHRepository repo = github.getRepository(repositoryName);
            
            GHPullRequest pullRequest = repo.getPullRequest(githubPrNumber);
            
            PagedIterable<GHPullRequestFileDetail> files = pullRequest.listFiles();
            
            return StreamSupport.stream(files.spliterator(), false)
                    .map(PullRequestFileInfo::from)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            
        } catch (IOException e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_PULL_REQUEST_FILE_NOT_FOUND);
        }
    }
    
    /**
     * Pull Request의 모든 커밋을 가져오는 메서드
     *
     * @param installationId GitHub App 설치 ID
     * @param repositoryName 저장소 전체 이름 (예: "owner/repo")
     * @param prNumber       Pull Request 번호
     * @return Pull Request 커밋 정보 리스트
     */
    private List<PullRequestCommitInfo> getPullRequestCommits(Long installationId, String repositoryName, Integer prNumber) {
        try {
            GitHub github = githubAppUtil.getGitHub(installationId);
            
            GHRepository repo = github.getRepository(repositoryName);
            
            GHPullRequest pullRequest = repo.getPullRequest(prNumber);
            
            PagedIterable<GHPullRequestCommitDetail> commits = pullRequest.listCommits();
            
            return StreamSupport.stream(commits.spliterator(), false)
                    .map(PullRequestCommitInfo::from)
                    .filter(Objects::nonNull) // null 제거
                    .collect(Collectors.toList());
            
        } catch (IOException e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_PULL_REQUEST_COMMIT_NOT_FOUND);
        }
    }
    
    /**
     * 두 커밋 간의 비교 정보 가져오기
     */
    public GHCompare getCompare(Long installationId, String repositoryName, String baseSha, String headSha) {
        try {
            
            GitHub github = githubAppUtil.getGitHub(installationId);
            
            GHRepository repo = github.getRepository(repositoryName);
            return repo.getCompare(baseSha, headSha);
            
        } catch (IOException e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_COMPARE_NOT_FOUND);
        }
    }
    
    /**
     * Pull Request 생성
     */
    public GHPullRequest createPullRequest(Long installationId, String repositoryName, String title, String body, String head, String base) {
        try {
            GitHub github = githubAppUtil.getGitHub(installationId);
            GHRepository repo = github.getRepository(repositoryName);
            
            return repo.createPullRequest(title, head, base, body);
            
        } catch (IOException e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_PULL_REQUEST_CREATE_FAILED);
        }
    }
    
    /**
     * 브랜치 정보 가져오기
     */
    public GHBranch getBranch(Long installationId, String repositoryName, String branchName) {
        try {
            GitHub github = githubAppUtil.getGitHub(installationId);
            GHRepository repo = github.getRepository(repositoryName);
            
            return repo.getBranch(branchName);
            
        } catch (IOException e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_BRANCH_NOT_FOUND);
        }
    }
    
    /**
     * Repository의 기본 브랜치 가져오기
     */
    public String getDefaultBranch(Long installationId, String repositoryName) {
        try {
            GHRepository repo = getRepository(installationId, repositoryName);
            return repo.getDefaultBranch();
            
        } catch (Exception e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_BRANCH_NOT_FOUND);
        }
    }
    
    /**
     * 파일의 상세 diff 정보 가져오기 (GitHub API 직접 호출)
     */
    /**
     * 파일의 상세 diff 정보 가져오기
     */
    public String getFileDiff(Long installationId, String repositoryName, String sha, String filename) {
        try {
            GitHub github = githubAppUtil.getGitHub(installationId);
            GHRepository repo = github.getRepository(repositoryName);
            
            // GHCommit에서는 직접 파일 정보를 가져올 수 없으므로
            // 단일 커밋을 compare로 비교해서 파일 정보 가져오기
            GHCompare compare = repo.getCompare(sha + "^", sha); // 이전 커밋과 현재 커밋 비교
            
            for (GHCommit.File file : compare.getFiles()) {
                if (file.getFileName()
                        .equals(filename)) {
                    return file.getPatch();
                }
            }
            
            return "";
            
        } catch (IOException e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_FILE_DIFF_NOT_FOUND);
        }
    }
    
    /**
     * 특정 범위의 커밋 목록 가져오기
     */
    public List<Commit> getCommits(Long installationId, String repositoryName, String baseSha, String headSha) {
        try {
            GHCompare compare = getCompare(installationId, repositoryName, baseSha, headSha);
            return Arrays.stream(compare.getCommits())
                    .toList();
            
        } catch (Exception e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_PULL_REQUEST_COMMIT_NOT_FOUND, "커밋 정보 리스트를 가져오는 데 실패했습니다.");
        }
    }
    
    public String getOrgName(GHAppInstallation installation) {
        return installation.getAccount()
                .getLogin();
    }
    
    /**
     * Pull Request 닫기
     */
    public void closePullRequest(Long installationId, String repositoryName, Integer prNumber) {
        try {
            GitHub github = githubAppUtil.getGitHub(installationId);
            GHRepository repo = github.getRepository(repositoryName);
            
            GHPullRequest pullRequest = repo.getPullRequest(prNumber);
            pullRequest.close();
            
        } catch (IOException e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_PULL_REQUEST_CLOSE_FAILED);
        }
    }
    
    /**
     * Pull Request 다시 열기
     */
    public void reopenPullRequest(Long installationId, String repositoryName, Integer prNumber) {
        try {
            GitHub github = githubAppUtil.getGitHub(installationId);
            GHRepository repo = github.getRepository(repositoryName);
            
            GHPullRequest pullRequest = repo.getPullRequest(prNumber);
            pullRequest.reopen();
            
        } catch (IOException e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_PULL_REQUEST_CLOSE_FAILED);
        }
    }
    
    /**
     * 클로드 코드
     */
    
    
    /**
     * Pull Request에 Review Comment 생성
     */
    public GHPullRequestReviewComment createReviewComment(Long installationId, String repositoryName, Integer prNumber, String body, String commitSha, String path, Integer startLine, String startSide,
            Integer line, String side) {
        try {
            GitHub github = githubAppUtil.getGitHub(installationId);
            GHRepository repo = github.getRepository(repositoryName);
            
            GHPullRequest pullRequest = repo.getPullRequest(prNumber);
            
            return pullRequest.createReviewComment()
                    .body(body)
                    .commitId(commitSha)
                    .path(path)
                    .sides(Side.from(startSide), Side.from(side))
                    .lines(startLine, line)
                    .create();
            
        } catch (IOException e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_REVIEW_COMMENT_CREATE_FAILED);
        }
    }
    
    /**
     * Pull Request Review Comment에 답글 생성 GitHub API에서 답글은 단순히 body만 있으면 되고, 위치 정보는 부모 댓글을 따름
     */
    public GHPullRequestReviewComment createReviewCommentReply(Long installationId, String repositoryName, Integer prNumber, Long parentGithubId, String body) {
        try {
            GitHub github = githubAppUtil.getGitHub(installationId);
            GHRepository repo = github.getRepository(repositoryName);
            
            GHPullRequest pullRequest = repo.getPullRequest(prNumber);
            
            // PR의 모든 Review Comment를 조회하여 부모 댓글 찾기
            GHPullRequestReviewComment parentComment = pullRequest.listReviewComments()
                    .toList()
                    .stream()
                    .filter(comment -> comment.getId() == parentGithubId)
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(GithubAppErrorCode.GITHUB_APP_REVIEW_COMMENT_NOT_FOUND));
            
            // 답글은 부모 댓글에 reply() 메서드로 생성
            return parentComment.reply(body);
            
        } catch (IOException e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_REVIEW_COMMENT_REPLY_CREATE_FAILED);
        }
    }
    
    /**
     * Review Comment 수정
     */
    public void updateReviewComment(Long installationId, String repositoryName, Integer prNumber, Long commentId, String body) {
        try {
            GitHub github = githubAppUtil.getGitHub(installationId);
            GHRepository repo = github.getRepository(repositoryName);
            
            GHPullRequest pullRequest = repo.getPullRequest(prNumber);
            
            // PR의 모든 Review Comment를 조회하여 특정 ID 찾기
            GHPullRequestReviewComment comment = pullRequest.listReviewComments()
                    .toList()
                    .stream()
                    .filter(c -> c.getId() == commentId)
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(GithubAppErrorCode.GITHUB_APP_REVIEW_COMMENT_NOT_FOUND));
            
            comment.update(body);
            
        } catch (IOException e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_REVIEW_COMMENT_UPDATE_FAILED);
        }
    }
    
    /**
     * Review Comment 삭제
     */
    public void deleteReviewComment(Long installationId, String repositoryName, Integer prNumber, Long commentId) {
        try {
            GitHub github = githubAppUtil.getGitHub(installationId);
            GHRepository repo = github.getRepository(repositoryName);
            
            GHPullRequest pullRequest = repo.getPullRequest(prNumber);
            
            // PR의 모든 Review Comment를 조회하여 특정 ID 찾기
            GHPullRequestReviewComment comment = pullRequest.listReviewComments()
                    .toList()
                    .stream()
                    .filter(c -> c.getId() == commentId)
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(GithubAppErrorCode.GITHUB_APP_REVIEW_COMMENT_NOT_FOUND));
            
            comment.delete();
            
        } catch (IOException e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_REVIEW_COMMENT_DELETE_FAILED);
        }
    }
}
