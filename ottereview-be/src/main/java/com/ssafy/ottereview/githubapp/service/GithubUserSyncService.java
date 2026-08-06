package com.ssafy.ottereview.githubapp.service;

import com.ssafy.ottereview.common.exception.BusinessException;
import com.ssafy.ottereview.user.entity.User;
import com.ssafy.ottereview.user.exception.UserErrorCode;
import com.ssafy.ottereview.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.kohsuke.github.GHUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GithubUserSyncService {

    private final UserRepository userRepository;

    @Transactional
    public User resolve(GHUser githubUser) {
        return userRepository.findByGithubId(githubUser.getId())
                .orElseGet(() -> register(githubUser));
    }

    private User register(GHUser githubUser) {
        try {
            return userRepository.save(User.builder()
                    .githubId(githubUser.getId())
                    .githubUsername(githubUser.getLogin())
                    .githubEmail(githubUser.getEmail())
                    .type(githubUser.getType())
                    .profileImageUrl(githubUser.getAvatarUrl() == null
                            ? null
                            : githubUser.getAvatarUrl().toString())
                    .rewardPoints(0)
                    .userGrade("BASIC")
                    .build());
        } catch (Exception exception) {
            throw new BusinessException(UserErrorCode.USER_REGISTRATION_FAILED);
        }
    }
}
