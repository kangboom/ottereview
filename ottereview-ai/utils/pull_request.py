import os
from dotenv import load_dotenv
from langchain.chat_models import init_chat_model
from typing import List, Dict, Any
import logging
import json
import re
from collections import defaultdict, Counter
from .vector_db import vector_db
from models import PRData, PreparationResult

# === 설정 상수들 ===
class ReviewerRecommendationConfig:
    """리뷰어 추천 관련 설정 상수들"""
    # 벡터 DB 검색 관련
    VECTOR_DB_SEARCH_LIMIT = 15  # 벡터 DB에서 검색할 패턴 수
    FALLBACK_SEARCH_LIMIT = 10   # 폴백에서 검색할 패턴 수
    
    # 컨텍스트 생성 관련
    MAX_CONTEXT_REVIEWERS = 8    # 컨텍스트에 포함할 최대 리뷰어 수
    MAX_FILE_TYPES_DISPLAY = 3   # 표시할 최대 파일 타입 수
    
    # LLM 프롬프트 관련
    MAX_FILES_IN_SUMMARY = 30     # 요약에 포함할 최대 파일 수
    MAX_COMMITS_IN_SUMMARY = 10   # 요약에 포함할 최대 커밋 수
    
    # 기본값
    DEFAULT_RECOMMENDATION_LIMIT = 3  # 기본 추천 리뷰어 수
    DEFAULT_REASON = "전문성을 보유하고 있습니다"  # 기본 추천 이유

# .env 파일에서 환경변수 로드
load_dotenv()

logger = logging.getLogger(__name__)

# OpenAI API KEY 확인
openai_api_key = os.getenv("OPENAI_API_KEY")
if not openai_api_key:
    raise ValueError("OPENAI_API_KEY가 환경변수에 설정되지 않았습니다. .env 파일을 확인해주세요.")

# OpenAI 기본 엔드포인트(https://api.openai.com/v1)를 사용합니다.
model = init_chat_model("gpt-4o-mini", model_provider="openai")

async def recommand_pull_request_title(pr_data: PreparationResult):
    """추천할 Pull Request의 제목을 생성합니다.
    Args:
        pr_data: PreparationResult 객체 (자바 PreparationResult 구조)
    Returns:
        str: 추천할 Pull Request 제목
    """
    system_prompt = """당신은 커밋 메시지와 코드 변경사항을 분석하여 이상적인 Pull Request 제목을 생성하는 AI 전문가입니다.

    **규칙:**
    1.  **형식 준수**: `[TYPE]: 간결한 설명` 형식을 반드시 따릅니다.
    2.  **TYPE 선택**: `FEATURE`, `FIX`, `DOCS`, `STYLE`, `REFACTOR`, `TEST` 중 가장 적절한 하나를 선택합니다.
    3.  **내용 요약**: 제목은 코드의 핵심 변경 사항을 정확하고 간결하게 요약해야 합니다. `patch` 데이터를 최우선으로 참고하세요.
    4.  **언어 및 길이**: 한국어로 작성하며, 50자 이내로 유지합니다. 마침표는 사용하지 않습니다.
    5.  **예시**:
        -   `[FEATURE]: 유저 인증 로직 구현`
        -   `[FIX]: 데이터베이스 연결 오류 수정`
    """
    
    # PreparationResult 타입에서 데이터 추출
    commit_messages = "\n".join([commit.message for commit in pr_data.commits])
    # Get top 5 changed files based on additions and deletions
    top_5_files = sorted(pr_data.files, key=lambda x: x.additions + x.deletions, reverse=True)[:5]
    patch_summary = "\n".join([f"File: {file.filename}\nPatch:\n{file.patch}" for file in top_5_files if file.patch])

    user_prompt = f"""다음 정보를 바탕으로 Pull Request 제목을 생성해주세요:

    **커밋 메시지:**
    {commit_messages}

    **가장 많이 변경된 파일 및 내용(patch):**
    {patch_summary}

    위 정보를 바탕으로, 규칙에 맞는 이상적인 Pull Request 제목을 생성해주세요."""




    # 모델에 요청을 보내고 응답을 받습니다.
    try:
        response = model.invoke([
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ])
        
        title = response.content if hasattr(response, 'content') else str(response)
        
        return title
    except Exception as e:
        raise e

async def summary_pull_request(pr_data: PreparationResult):
    """Pull Request의 내용을 요약합니다.
    Args:
        pr_data: PreparationResult 객체 (자바 PreparationResult 구조)
    Returns:
        str: Pull Request 내용 요약
    """
    system_prompt = """당신은 코드 변경사항을 분석하여 Pull Request의 핵심 내용을 요약하는 AI 전문가입니다.

    **요약 규칙:**
    1.  **핵심 파악**: 이 PR의 목적이 무엇인지 (버그 수정, 기능 추가, 리팩토링 등) 파악합니다.
    2.  **주요 변경사항 기술**: `patch` 데이터를 기반으로 가장 중요한 코드 변경사항을 1-2개 식별하여 기술합니다.
    3.  **기대 효과**: 이 변경으로 인해 어떤 긍정적인 효과가 기대되는지 서술합니다.
    4.  **간결성**: 전체 요약은 3-4문장, 200자 이내의 완결된 문단으로 작성합니다.
    5.  **언어**: 자연스러운 한국어로 작성합니다.

    **출력 예시:**
    "이 PR은 사용자 인증 로직을 개선하여 보안을 강화합니다. 주요 변경사항으로 JWT 토큰 발급 및 검증 로직을 수정하였으며, 이를 통해 더욱 안전한 사용자 인증이 가능해집니다."
    """

    # PreparationResult 타입에서 데이터 추출
    commit_messages = "\n".join([commit.message for commit in pr_data.commits])
    top_5_files = sorted(pr_data.files, key=lambda x: x.additions + x.deletions, reverse=True)[:5]
    patch_summary = "\n".join([f"File: {file.filename}\nPatch:\n{file.patch}" for file in top_5_files if file.patch])
    descriptions_text = "No description provided."
    if pr_data.descriptions and len(pr_data.descriptions) > 0:
        # descriptions는 List[DescriptionInfo]이고, 각각의 body 필드에 실제 설명이 있음
        valid_descriptions = [desc.body for desc in pr_data.descriptions if desc.body and desc.body.strip()]
        if valid_descriptions:
            descriptions_text = " | ".join(valid_descriptions)
        else:
            descriptions_text = "Description fields are empty."
    
    user_prompt = f"""다음 정보를 바탕으로 Pull Request 내용을 요약해주세요:

    **PR 제목:** {pr_data.title}
    **작성자 설명:** {descriptions_text}
    **커밋 메시지:**
    {commit_messages}
    **가장 많이 변경된 파일 및 내용(patch):**
    {patch_summary}

    위 정보를 종합하여, 요약 규칙에 맞는 Pull Request 내용을 생성해주세요."""


    # 모델에 요청을 보내고 응답을 받습니다.
    try:
        response = model.invoke([
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ])

        review_comment = response.content if hasattr(response, 'content') else str(response)

        return review_comment
    except Exception as e:
        raise e

async def recommend_reviewers(pr_data: PreparationResult, limit: int = ReviewerRecommendationConfig.DEFAULT_RECOMMENDATION_LIMIT) -> List[Dict[str, Any]]:
    """
    RAG를 활용한 리뷰어 추천 로직
    
    Args:
        pr_data: PreparationResult 객체 (현재 PR 정보)
        limit: 추천할 리뷰어 수 (기본값: 3)
        
    Returns:
        List[Dict]: 추천된 리뷰어 목록
    """
    try:
        # 1. 벡터 DB에서 유사한 PR 패턴 검색 (RAG의 Retrieval 단계)
        similar_patterns = await vector_db.get_similar_reviewer_patterns(pr_data, limit=ReviewerRecommendationConfig.VECTOR_DB_SEARCH_LIMIT)
        
        if not similar_patterns:
            logger.warning("유사한 리뷰어 패턴을 찾을 수 없습니다")
            context = "과거 유사한 PR 패턴이 없습니다. 주어진 후보 목록에서만 선택하세요."
        else:
        
            # 2. 검색된 패턴들을 LLM용 컨텍스트로 변환
            context = _build_context_from_patterns(similar_patterns, pr_data)
        
        # 3. LLM에게 컨텍스트와 함께 추천 요청 (RAG의 Generation 단계)
        recommendations = await _generate_recommendations_with_llm(context, pr_data, similar_patterns, limit)
        
        return recommendations
        
    except Exception as e:
        logger.error(f"리뷰어 추천 중 오류 발생: {str(e)}")
        return []

def _build_context_from_patterns(patterns: List[Dict[str, Any]], current_pr: PreparationResult) -> str:
    """
    벡터 검색 결과를 LLM용 컨텍스트로 변환 - 개선된 정보 활용
    
    Args:
        patterns: 벡터 DB에서 검색된 유사 패턴들
        current_pr: 현재 PR 데이터
        
    Returns:
        str: LLM에 제공할 컨텍스트 문자열
    """
    context_parts = []
    
    # 리뷰어별로 그룹화 및 확장된 정보 수집
    reviewer_data = {}
    for pattern in patterns:
        reviewer = pattern['reviewer']
        if reviewer not in reviewer_data:
            reviewer_data[reviewer] = {
                'email': pattern['reviewer_email'],
                'patterns': [],
                'total_similarity': 0,
                'file_categories': set(),
                'functional_categories': set(),
                'commit_patterns': set(),
                'change_scales': [],
                'complexity_levels': [],
                'review_count': 0,
                'has_test_experience': False,
                'has_config_experience': False
            }
        
        data = reviewer_data[reviewer]
        data['patterns'].append(pattern)
        data['total_similarity'] += pattern['similarity_score']
        data['file_categories'].update(pattern['file_categories'])
        data['functional_categories'].add(pattern.get('functional_category', ''))
        data['commit_patterns'].add(pattern.get('commit_pattern', ''))
        data['change_scales'].append(pattern.get('change_scale', ''))
        data['complexity_levels'].append(pattern.get('complexity_level', ''))
        
        if pattern.get('has_tests'):
            data['has_test_experience'] = True
        if pattern.get('has_config'):
            data['has_config_experience'] = True
        if pattern['review_provided']:
            data['review_count'] += 1
    
    # 상위 N명만 컨텍스트에 포함
    sorted_reviewers = sorted(
        reviewer_data.items(),
        key=lambda x: x[1]['total_similarity'],
        reverse=True
    )[:ReviewerRecommendationConfig.MAX_CONTEXT_REVIEWERS]
    
    context_parts.append("=== 과거 유사한 PR들의 리뷰어 패턴 ===")
    
    for i, (reviewer, data) in enumerate(sorted_reviewers, 1):
        avg_similarity = data['total_similarity'] / len(data['patterns'])
        file_types = ', '.join(list(data['file_categories'])[:3])
        functional_areas = ', '.join([cat for cat in data['functional_categories'] if cat][:3])
        most_common_scale = max(set(data['change_scales']), key=data['change_scales'].count) if data['change_scales'] else '소규모'
        most_common_complexity = max(set(data['complexity_levels']), key=data['complexity_levels'].count) if data['complexity_levels'] else '단순'
        
        context_parts.append(f"""
{i}. 리뷰어: {reviewer}
   - 이메일: {data['email']}
   - 평균 유사도: {avg_similarity:.3f}
   - 전문 파일타입: {file_types}
   - 주요 기능영역: {functional_areas}
   - 주로 다루는 변경규모: {most_common_scale}
   - 주로 다루는 복잡도: {most_common_complexity}
   - 테스트 경험: {'있음' if data['has_test_experience'] else '없음'}
   - 설정 파일 경험: {'있음' if data['has_config_experience'] else '없음'}
   - 리뷰 제공 횟수: {data['review_count']}/{len(data['patterns'])}
        """.strip())
    
    return "\n".join(context_parts)

async def _generate_recommendations_with_llm(context: str, pr_data: PreparationResult, similar_patterns: List[Dict[str, Any]], limit: int = ReviewerRecommendationConfig.DEFAULT_RECOMMENDATION_LIMIT) -> List[Dict[str, Any]]:
    """
    LLM을 활용해 컨텍스트 기반 리뷰어 추천
    
    Args:
        context: 벡터 검색으로 구성된 컨텍스트
        pr_data: 현재 PreparationResult 데이터
        similar_patterns: 벡터 DB에서 검색된 유사 패턴들 (폴백용)
        limit: 추천할 리뷰어 수
        
    Returns:
        List[Dict]: LLM이 추천한 리뷰어 목록
    """
    # 현재 PR 정보 요약
    files_summary = ', '.join([f.filename for f in pr_data.files[:ReviewerRecommendationConfig.MAX_FILES_IN_SUMMARY]])
    commits_summary = ' | '.join([c.message for c in pr_data.commits[:ReviewerRecommendationConfig.MAX_COMMITS_IN_SUMMARY]])
    
    system_prompt = """당신은 주어진 Pull Request(PR)의 리뷰어 후보 목록 중에서, 최적의 리뷰어를 추천하는 AI 기술 분석가입니다.

**추천 핵심 원칙:**
1.  **후보 내에서 선택**: 반드시 '현재 PR 정보'에 주어진 `리뷰어` 후보 목록 중에서만 추천해야 합니다.
2.  **기술적 전문성**: 현재 PR의 변경사항(파일, 기능 영역)과 가장 관련 높은 기술적 경험을 가진 후보를 선택합니다.
3.  **과거 경험**: '과거 유사 PR 패턴'을 참고하여, 후보가 유사한 기능 영역의 코드를 리뷰했거나 작성한 경험이 있는지 확인합니다.
4.  **구체적인 이유**: 추천 이유는 반드시 과거 경험에 기반해야 하며, 추측이나 가정은 금지합니다. 구체적이고 명확한 이유를 작성하세요.

**`reason` 작성 가이드:**
-   **구체적인 근거 제시**: "유사한 PR 경험"과 같은 모호한 표현 대신, "과거 '인증' 기능 관련 PR 리뷰 경험과 Java 파일에 대한 높은 전문성을 보유하고 있습니다."와 같이 구체적인 기능 영역과 기술 스택을 명시하세요.
-   **수치 언급 금지**: `평균 유사도`, `리뷰 제공 횟수`와 같은 수치는 내부 분석용이므로 `reason`에 절대 포함하지 마세요.
-   **추측이나 가정으로 리뷰어를 추천하지 마세요. 이유는 반드시 과거 경험을 바탕으로 해야 합니다. 이유가 마땅하지 않다면 적지 마세요.**
-   **랜덤 추천**: 과거 경험이 전혀 없는 리뷰어를 추천해야 하는 경우, 랜덤으로 1명을 추천하세요 이유는 "정보 없음"으로 작성하세요.

**응답 형식:**
-   다른 설명 없이, 반드시 아래 명시된 JSON 형식으로만 응답하세요.
-   추천할 리뷰어가 없는 경우, 랜덤으로 1명을 추천하세요.
-   추천할 리뷰어가 있는 경우, 아래 형식에 맞춰 작성하세요.
-   반드시 코드블럭 없이 JSON 형식으로 작성하세요.
-   기본적으로 최소 1명 이상의 리뷰어를 추천해야 합니다.
-   리뷰어 정보가 없으면 []을 반환하세요.

[
  {
    "github_id" : "추천할 사용자의 GitHub ID",
    "github_username": "추천할 사용자명",
    "github_email": "추천할 사용자의 이메일",
    "reason": "작성 가이드에 따라 구체적으로 작성된 추천 이유"
  }
]
"""

    user_prompt = f"""과거 경험 {context}

=== 현재 PR 정보 ===
- 제목: {pr_data.title}
- 파일들: {files_summary}
- 커밋 메시지: {commits_summary}
- 브랜치: {pr_data.source} -> {pr_data.target}
- 리뷰어: {[user for user in (pr_data.preReviewers or [])]}

위 정보를 종합하여 가장 적합한 리뷰어 {limit}명을 추천해주세요."""

    try:
        response = model.invoke([
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ])
        logger.info("리뷰어 응답 " + response.content)
        result = _parse_llm_response(response.content, limit)
        logger.info(f"LLM이 {len(result)}명의 리뷰어를 추천했습니다")
        return result
        
    except Exception as e:
        logger.error(f"LLM 추천 생성 중 오류: {str(e)}")
        # 폴백: 간단한 유사도 기반 추천 (이미 검색된 패턴 재사용)
        return _fallback_simple_recommendation(similar_patterns, limit)

def _parse_llm_response(response_text: str, limit: int) -> List[Dict[str, Any]]:
    """
    LLM 응답을 파싱해서 리뷰어 목록으로 변환 (개선된 파싱 로직)
    
    Args:
        response_text: LLM 응답 텍스트
        limit: 최대 리뷰어 수
        
    Returns:
        List[Dict]: 파싱된 리뷰어 목록
    """
    try:
        # ... (이전 단계 코드는 동일) ...
        # 1단계, 2단계, 3단계 생략
        cleaned_text = response_text.strip()
        
        json_candidates = []
        try:
            parsed = json.loads(cleaned_text)
            if isinstance(parsed, list):
                json_candidates.append(parsed)
        except:
            pass
        
        json_matches = re.findall(r'\[.*?\]', cleaned_text, re.DOTALL)
        for match in json_matches:
            try:
                parsed = json.loads(match)
                if isinstance(parsed, list):
                    json_candidates.append(parsed)
            except:
                continue
        
        code_block_matches = re.findall(r'```(?:json)?\s*(\[.*?\])\s*```', cleaned_text, re.DOTALL)
        for match in code_block_matches:
            try:
                parsed = json.loads(match)
                if isinstance(parsed, list):
                    json_candidates.append(parsed)
            except:
                continue
        
        best_candidate = None
        for candidate in json_candidates:
            if isinstance(candidate, list):
                if len(candidate) == 0:
                    best_candidate = candidate
                    break
                elif (len(candidate) > 0 and 
                      isinstance(candidate[0], dict) and 
                      'github_username' in candidate[0]):
                    best_candidate = candidate
                    break
        
        if best_candidate is None:
            raise ValueError("유효한 JSON 형식을 찾을 수 없습니다")

        # 4단계: 검증 및 정리
        valid_recommendations = []
        
        if len(best_candidate) == 0:
            return []
        
        for rec in best_candidate[:limit]:
            if isinstance(rec, dict) and 'github_username' in rec:
                username = rec.get('github_username', '').strip()
                if not username:
                    continue
                
                # ----- 수정된 부분 시작 -----
                
                # github_id 값을 안전하게 int로 변환 (Long 타입 처리)
                github_id = None
                raw_github_id = rec.get('github_id')
                if raw_github_id is not None:
                    try:
                        github_id = int(raw_github_id)
                    except (ValueError, TypeError):
                        # 값이 있으나 int로 변환할 수 없는 경우 None으로 처리
                        github_id = None

                # github_email을 안전하게 처리 (null, 빈 문자열 처리)
                github_email = rec.get('github_email')
                if github_email is None or not isinstance(github_email, str):
                    github_email = None
                else:
                    github_email = github_email.strip()
                    if not github_email:  # 빈 문자열인 경우
                        github_email = None

                valid_recommendations.append({
                    'github_username': username,
                    'github_id': github_id,  # int 또는 None으로 처리된 값
                    'github_email': github_email,  # null이나 빈 문자열을 None으로 처리
                    'reason': rec.get('reason', ReviewerRecommendationConfig.DEFAULT_REASON).strip()
                })
                # ----- 수정된 부분 끝 -----
        
        return valid_recommendations
        
    except Exception as e:
        logger.error(f"LLM 응답 파싱 실패: {str(e)}")
        logger.debug(f"파싱 실패한 응답 내용: {response_text[:500]}...")
        return []

def _fallback_simple_recommendation(similar_patterns: List[Dict[str, Any]], limit: int) -> List[Dict[str, Any]]:
    """
    LLM 실패 시 사용할 간단한 폴백 추천 (이미 검색된 패턴 재사용)
    
    Args:
        similar_patterns: 이미 검색된 유사 패턴들
        limit: 추천할 리뷰어 수
        
    Returns:
        List[Dict]: 간단한 추천 결과
    """
    try:
        if not similar_patterns:
            logger.warning("폴백 추천을 위한 패턴이 없습니다")
            return []
        
        reviewer_scores = {}
        for pattern in similar_patterns:
            reviewer = pattern['reviewer']
            if reviewer not in reviewer_scores:
                reviewer_scores[reviewer] = {
                    'score': 0,
                    'email': pattern['reviewer_email']
                }
            reviewer_scores[reviewer]['score'] += pattern['similarity_score']
        
        # 상위 추천
        recommendations = []
        sorted_reviewers = sorted(reviewer_scores.items(), key=lambda x: x[1]['score'], reverse=True)
        
        for reviewer, data in sorted_reviewers[:limit]:
            # github_email을 안전하게 처리
            github_email = data.get('email')
            if github_email is None or not isinstance(github_email, str):
                github_email = None
            else:
                github_email = github_email.strip()
                if not github_email:  # 빈 문자열인 경우
                    github_email = None
            
            recommendations.append({
                'github_username': reviewer,
                'github_email': github_email,
                'reason': '유사한 작업 경험을 보유하고 있습니다'
            })
        
        logger.info(f"폴백 로직으로 {len(recommendations)}명의 리뷰어를 추천했습니다")
        return recommendations
        
    except Exception as e:
        logger.error(f"폴백 추천도 실패: {str(e)}")
        return []



