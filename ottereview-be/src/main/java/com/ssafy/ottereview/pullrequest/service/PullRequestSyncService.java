package com.ssafy.ottereview.pullrequest.service;

import com.ssafy.ottereview.pullrequest.dto.info.PullRequestSyncData;
import com.ssafy.ottereview.pullrequest.dto.info.PullRequestSyncResult;
import com.ssafy.ottereview.pullrequest.entity.PullRequest;
import com.ssafy.ottereview.pullrequest.repository.PullRequestRepository;
import com.ssafy.ottereview.repo.entity.Repo;
import com.ssafy.ottereview.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PullRequestSyncService {

    private final PullRequestRepository pullRequestRepository;

    @Transactional
    public PullRequestSyncResult synchronize(PullRequestSyncData data, Repo repo, User author) {
        return pullRequestRepository.findByGithubId(data.githubId())
                .map(existing -> update(existing, data))
                .orElseGet(() -> create(data, repo, author));
    }

    private PullRequestSyncResult update(PullRequest existing, PullRequestSyncData data) {
        existing.synchronize(data);
        return new PullRequestSyncResult(existing, false);
    }

    private PullRequestSyncResult create(PullRequestSyncData data, Repo repo, User author) {
        PullRequest created = pullRequestRepository.save(PullRequest.create(data, repo, author));
        return new PullRequestSyncResult(created, true);
    }
}
