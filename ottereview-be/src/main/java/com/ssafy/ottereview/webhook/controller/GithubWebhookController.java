package com.ssafy.ottereview.webhook.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ottereview.common.annotation.MvcController;
import com.ssafy.ottereview.webhook.service.WebhookInboxService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhook")
@RequiredArgsConstructor
@Slf4j
@MvcController
public class GithubWebhookController {

    private final WebhookInboxService webhookInboxService;
    private final ObjectMapper objectMapper;

    @Hidden
    @PostMapping
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("X-GitHub-Event") String event,
            @RequestHeader("X-GitHub-Delivery") String delivery,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {
        try {
            JsonNode jsonNode = objectMapper.readTree(payload);
            String action = jsonNode.path("action").asText();
            log.debug("[웹훅 이벤트 수신] 이벤트: {}, Action: {}", event, action);
            
        } catch (Exception e) {
            log.error("Error parsing payload: {}", e.getMessage());
        }
        boolean processed = webhookInboxService.process(delivery, event, payload);
        return ResponseEntity.ok(processed ? "OK" : "DUPLICATE");
    }
}
