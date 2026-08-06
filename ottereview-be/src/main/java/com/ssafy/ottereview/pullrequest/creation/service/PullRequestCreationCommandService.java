package com.ssafy.ottereview.pullrequest.creation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ottereview.preparation.dto.PreparationResult;
import com.ssafy.ottereview.pullrequest.creation.entity.PullRequestCreationOutbox;
import com.ssafy.ottereview.pullrequest.creation.entity.PullRequestCreationTask;
import com.ssafy.ottereview.pullrequest.creation.repository.PullRequestCreationOutboxRepository;
import com.ssafy.ottereview.pullrequest.creation.repository.PullRequestCreationTaskRepository;
import com.ssafy.ottereview.repo.entity.Repo;
import com.ssafy.ottereview.user.entity.User;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PullRequestCreationCommandService {

    private final PullRequestCreationTaskRepository taskRepository;
    private final PullRequestCreationOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public Long request(Repo repo, User author, PreparationResult preparation) {
        PullRequestCreationTask task = PullRequestCreationTask.request(
                repo,
                author,
                preparation.getSource(),
                preparation.getTarget(),
                preparation.getTitle(),
                preparation.getBody(),
                serialize(preparation)
        );

        // Task와 Outbox를 함께 커밋해야 요청만 남거나 이벤트만 남는 부분 성공을 막을 수 있다.
        taskRepository.save(task);
        outboxRepository.save(PullRequestCreationOutbox.pending(task, LocalDateTime.now()));
        return task.getId();
    }

    private String serialize(PreparationResult preparation) {
        try {
            return objectMapper.writeValueAsString(preparation);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("PR 준비 정보를 저장할 수 없습니다.", exception);
        }
    }
}
