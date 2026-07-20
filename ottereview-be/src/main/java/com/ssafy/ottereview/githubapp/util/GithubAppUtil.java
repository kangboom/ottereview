package com.ssafy.ottereview.githubapp.util;

import com.ssafy.ottereview.common.exception.BusinessException;
import com.ssafy.ottereview.githubapp.config.GithubAppConfig;
import com.ssafy.ottereview.githubapp.exception.GithubAppErrorCode;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.io.IOException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAppInstallationToken;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Slf4j
@RequiredArgsConstructor
@Service
public class GithubAppUtil {
    
    private final GithubAppConfig githubAppConfig;
    private final byte[] githubAppPrivateKeyBytes;
    private final RestTemplate restTemplate = new RestTemplate();
    
    @Value("${github.app.oauth.client-id}")
    private String clientId;
    
    @Value("${github.app.oauth.client-secret}")
    private String clientSecret;
    
    @Value("${github.app.oauth.redirect-uri}")
    private String redirectUri;
    
    @Value("${github.app.authentication-jwt-expm}")
    private Long jwtTExpirationMillis; // 10분
    
    private PrivateKey getPrivateKey() {
        try {
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(githubAppPrivateKeyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(spec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_PRIVATE_KEY_ERROR);
        }
    }
    
    public String generateJwt(long ttlMillis) {
        long nowMillis = System.currentTimeMillis();
        Date now = new Date(nowMillis);
        
        Key signingKey = getPrivateKey();
        
        JwtBuilder builder = Jwts.builder()
                .setIssuedAt(now)
                .setIssuer(githubAppConfig.getAppId())
                .signWith(signingKey, SignatureAlgorithm.RS256);
        
        if (ttlMillis > 0) {
            long expMillis = nowMillis + ttlMillis;
            Date exp = new Date(expMillis);
            builder.setExpiration(exp);
        }
        
        return builder.compact();
    }
    
    public GitHub getGitHub(long installationId) {
        
        try {
            // 1. JWT 생성
            String jwtToken = generateJwt(jwtTExpirationMillis);
            
            // 2. Github 인증
            GitHub gitHubApp = new GitHubBuilder()
                    .withJwtToken(jwtToken)
                    .build();
            
            // 3. installation_token 발급
            GHAppInstallation installation = gitHubApp.getApp()
                    .getInstallationById(installationId);
            GHAppInstallationToken installationToken = installation.createToken()
                    .create();
            
            // 4. installation_token을 사용하여 GitHub 클라이언트 생성
            return new GitHubBuilder()
                    .withAppInstallationToken(installationToken.getToken())
                    .build();
        } catch (Exception e) {
            log.error(
                    "GitHub Installation Access Token 발급 실패. installationId={}",
                    installationId,
                    e
            );
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_CREATE_FAILED);
        }
    }
    
    public GitHub getGitHubAsApp() {
        try {
            String jwt = generateJwt(jwtTExpirationMillis); // JWT 유효 기간 10분
            return new GitHubBuilder()
                    .withJwtToken(jwt)
                    .build();
        } catch (Exception e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_CREATE_FAILED);
        }
    }
    
    public GHAppInstallation getInstallation(Long installationId) throws IOException {
        GitHub gitHub = getGitHubAsApp();
        return gitHub.getApp()
                .getInstallationById(installationId);
    }
    
    public String requestGithubAccessToken(String code) {
        
        String url = "https://github.com/login/oauth/access_token";
        
        // HTTP 헤더 설정
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "CommitAI/1.0"); // GitHub API 요구사항
        
        // 요청 파라미터 설정
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("client_id", clientId);
        params.add("client_secret", clientSecret);
        params.add("code", code);
        params.add("redirect_uri", redirectUri);
        
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
        
        try {
            ResponseEntity<Map<String, Object>> responseEntity = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    }
            );
            
            Map<String, Object> response = responseEntity.getBody();
            
            if (response == null) {
                log.error("GitHub response body is null");
                throw new BusinessException(GithubAppErrorCode.GITHUB_TOKEN_REQUEST_FAILED, "GitHub 응답이 비어 있습니다.");
            }
            
            // 에러 응답 체크
            if (response.containsKey("error")) {
                String error = (String) response.get("error");
                String errorDescription = (String) response.get("error_description");
                String errorUri = (String) response.get("error_uri");
                
                log.error("GitHub OAuth Error:");
                log.error("  Error: {}", error);
                log.error("  Description: {}", errorDescription);
                log.error("  URI: {}", errorUri);
                
                throw new BusinessException(GithubAppErrorCode.GITHUB_TOKEN_REQUEST_FAILED);
            }
            
            // Access token 추출
            String accessToken = (String) response.get("access_token");
            if (accessToken == null || accessToken.trim()
                    .isEmpty()) {
                log.error("Access token is null or empty in response: {}", response);
                throw new BusinessException(GithubAppErrorCode.GITHUB_TOKEN_REQUEST_FAILED, "access-token이 비어 있습니다.");
            }
            
            return accessToken;
            
        } catch (Exception e) {
            log.error("Unexpected error during token exchange", e);
            throw new BusinessException(GithubAppErrorCode.GITHUB_APP_OAUTH_ERROR);
        }
    }
    
    public String getInstallationToken(long installationId) {
        try {
            String jwtToken = generateJwt(jwtTExpirationMillis);
            GitHub gitHubApp = new GitHubBuilder().withJwtToken(jwtToken)
                    .build();
            GHAppInstallation installation = gitHubApp.getApp()
                    .getInstallationById(installationId);
            GHAppInstallationToken token = installation.createToken()
                    .create();
            return token.getToken();
        } catch (Exception e) {
            throw new BusinessException(GithubAppErrorCode.GITHUB_TOKEN_REQUEST_FAILED, "Installation 토큰 발급에 실패하였습니다.");
        }
    }
    
}
