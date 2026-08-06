import os
from dotenv import load_dotenv
from langchain.chat_models import init_chat_model
from typing import List, Dict, Any
import logging
from collections import defaultdict, Counter
import re
from .vector_db import vector_db
from models import PreparationResult, PRData  # PRData는 하위 호환성을 위해 유지
from .config_based_priority import calculate_config_based_priority_candidates

# .env 파일에서 환경변수 로드
load_dotenv()

logger = logging.getLogger(__name__)

# OpenAI API KEY 확인
openai_api_key = os.getenv("OPENAI_API_KEY")
if not openai_api_key:
    raise ValueError("OPENAI_API_KEY가 환경변수에 설정되지 않았습니다. .env 파일을 확인해주세요.")

# OpenAI 기본 엔드포인트(https://api.openai.com/v1)를 사용합니다.
model = init_chat_model("gpt-4o-mini", model_provider="openai")


def _extract_code_context_from_files(files: List[Any], max_files: int = 10) -> str:
    """
    파일들에서 코드 컨텍스트를 추출하여 LLM 분석용 문자열 생성
    
    Args:
        files: FileChangeInfo 객체들의 리스트
        max_files: 분석할 최대 파일 수
        
    Returns:
        str: 파일별 변경사항 요약 컨텍스트
    """
    context_parts = []
    
    for i, file in enumerate(files[:max_files]):
        file_info = []
        file_info.append(f"파일: {file.filename}")
        file_info.append(f"상태: {file.status}")
        file_info.append(f"변경: +{file.additions}/-{file.deletions}")
        
        # 파일 확장자로 언어 추정
        file_ext = file.filename.split('.')[-1].lower() if '.' in file.filename else 'unknown'
        file_info.append(f"타입: {file_ext}")
        
        # patch 내용이 있으면 주요 변경사항 추출
        if file.patch:
            key_changes = _extract_key_changes_from_patch(file.patch, file.filename)
            if key_changes:
                file_info.append(f"주요변경: {key_changes}")
        
        context_parts.append(" | ".join(file_info))
    
    return "\n".join(context_parts)


def _extract_key_changes_from_patch(patch: str, filename: str) -> str:
    """
    patch에서 핵심 변경사항을 추출
    
    Args:
        patch: git diff patch 내용
        filename: 파일명
        
    Returns:
        str: 핵심 변경사항 요약
    """
    if not patch:
        return ""
    
    key_patterns = {
        'security': [r'auth', r'token', r'password', r'jwt', r'security', r'permission', r'role'],
        'database': [r'entity', r'repository', r'@Table', r'@Entity', r'@Column', r'migration', r'schema'],
        'api': [r'@RestController', r'@RequestMapping', r'@GetMapping', r'@PostMapping', r'@Controller', r'endpoint'],
        'config': [r'@Configuration', r'application\.', r'config', r'properties'],
        'business_logic': [r'service', r'business', r'logic', r'calculate', r'process', r'validate'],
        'test': [r'@Test', r'test', r'spec', r'mock', r'assert']
    }
    
    changes = []
    lines = patch.split('\n')
    
    # 추가/삭제된 라인에서 패턴 찾기
    for line in lines:
        if line.startswith('+') or line.startswith('-'):
            line_content = line[1:].strip().lower()
            
            for category, patterns in key_patterns.items():
                for pattern in patterns:
                    if re.search(pattern, line_content):
                        changes.append(f"{category}관련")
                        break
    
    # 중복 제거 및 상위 3개만
    unique_changes = list(set(changes))[:3]
    return ", ".join(unique_changes) if unique_changes else "일반적인 코드 변경"


def _analyze_files_for_priority_context(files: List[Any]) -> Dict[str, Any]:
    """
    파일들을 분석하여 우선순위 결정에 필요한 컨텍스트 생성
    
    Args:
        files: FileChangeInfo 객체들의 리스트
        
    Returns:
        Dict[str, Any]: 파일 분석 결과 컨텍스트
    """
    analysis = {
        'high_risk_files': [],
        'medium_risk_files': [],
        'low_risk_files': [],
        'file_categories': defaultdict(list),
        'total_changes': 0,
        'security_related': [],
        'database_related': [],
        'api_related': []
    }
    
    # 고위험 파일 패턴
    high_risk_patterns = [
        r'auth', r'security', r'token', r'jwt', r'password', r'permission',
        r'config', r'properties', r'yml', r'yaml'
    ]
    
    # 중위험 파일 패턴
    medium_risk_patterns = [
        r'controller', r'service', r'repository', r'entity', r'dto',
        r'api', r'endpoint', r'business'
    ]
    
    for file in files:
        filename_lower = file.filename.lower()
        file_changes = file.additions + file.deletions
        analysis['total_changes'] += file_changes
        
        # 위험도 분류
        if any(re.search(pattern, filename_lower) for pattern in high_risk_patterns):
            analysis['high_risk_files'].append({
                'filename': file.filename,
                'changes': file_changes,
                'status': file.status
            })
        elif any(re.search(pattern, filename_lower) for pattern in medium_risk_patterns):
            analysis['medium_risk_files'].append({
                'filename': file.filename,
                'changes': file_changes,
                'status': file.status
            })
        else:
            analysis['low_risk_files'].append({
                'filename': file.filename,
                'changes': file_changes,
                'status': file.status
            })
        
        # 카테고리별 분류
        if re.search(r'auth|security|token|jwt|password', filename_lower):
            analysis['security_related'].append(file.filename)
        if re.search(r'entity|repository|migration|schema', filename_lower):
            analysis['database_related'].append(file.filename)
        if re.search(r'controller|api|endpoint|rest', filename_lower):
            analysis['api_related'].append(file.filename)
    
    return analysis


def _select_relevant_files_for_candidate(files_analysis: Dict[str, Any], candidate_type: str) -> List[str]:
    """
    후보 타입에 따라 관련된 파일들을 선택
    
    Args:
        files_analysis: 파일 분석 결과
        candidate_type: 후보 유형 (security, database, api, business_logic, etc.)
        
    Returns:
        List[str]: 관련 파일 패스들
    """
    if candidate_type == 'security':
        relevant_files = files_analysis['security_related']
        relevant_files.extend([f['filename'] for f in files_analysis['high_risk_files'][:3]])
    elif candidate_type == 'database':
        relevant_files = files_analysis['database_related']
    elif candidate_type == 'api':
        relevant_files = files_analysis['api_related']
    else:
        # 일반적인 경우: 변경사항이 큰 파일들
        all_files = (files_analysis['high_risk_files'] + 
                     files_analysis['medium_risk_files'] + 
                     files_analysis['low_risk_files'])
        all_files.sort(key=lambda x: x['changes'], reverse=True)
        relevant_files = [f['filename'] for f in all_files[:5]]
    
    # 중복 제거 및 최대 5개로 제한
    return list(set(relevant_files))[:5]


async def recommend_priority(pr_data: PreparationResult) -> Dict[str, Any]:
    """
    RAG를 활용한 리뷰 우선순위 추천 로직 - 3개의 후보 반환 (파일 패스 포함)
    
    Args:
        pr_data: PRData 객체 (현재 PR 정보)
        
    Returns:
        Dict[str, Any]: 3개의 우선순위 추천 결과 (관련 파일 패스 포함)
    """
    try:
        # 🚨 수정된 부분: 분석에서 제외할 파일 확장자 정의
        IGNORE_EXTENSIONS = ['.txt', '.md', '.log', '.gitignore', '.lock', '.properties', '.yml', '.yaml']
        
        # 🚨 수정된 부분: 유의미한 코드 파일만 필터링
        valid_files = [f for f in pr_data.files if not f.filename.endswith(tuple(IGNORE_EXTENSIONS))]

        if not valid_files:
            return _get_default_error_response_with_files(pr_data.files)

        # 1. 파일 분석을 통한 컨텍스트 생성 (필터링된 파일 사용)
        files_analysis = _analyze_files_for_priority_context(valid_files)
        code_context = _extract_code_context_from_files(valid_files)
        
        # 2. 벡터 DB에서 유사한 우선순위 패턴 검색 (RAG의 Retrieval 단계)
        similar_patterns = await vector_db.get_similar_priority_patterns(pr_data, limit=15)
        
        if not similar_patterns:
            logger.warning("유사한 우선순위 패턴을 찾을 수 없습니다. 파일 기반 분석으로 대체합니다.")
            similar_patterns = " "
        
        # 3. 검색된 패턴들을 LLM용 컨텍스트로 변환
        context = _build_priority_context_from_patterns(similar_patterns)
        
        # 4. LLM에게 컨텍스트와 함께 3개의 우선순위 후보 분석 요청 (RAG의 Generation 단계)
        priority_candidates = await _generate_priority_candidates_with_llm(
            context, pr_data, code_context, files_analysis, valid_files
        )
        
        # 5. 결과 검증 및 보완
        return _validate_and_complete_candidates(priority_candidates, pr_data, files_analysis)
        
    except Exception as e:
        logger.error(f"우선순위 추천 전체 과정에서 오류 발생: {str(e)}")
        # 🚨 수정된 부분: 오류 발생 시 필터링된 파일로 기본 응답 생성
        return _get_default_error_response_with_files(valid_files if 'valid_files' in locals() else pr_data.files)


def _get_default_error_response_with_files(files: List[Any]) -> Dict[str, Any]:
    """기본 오류 응답 생성 (파일 정보 포함)"""
    # 변경사항이 큰 파일 3개 선택
    sorted_files = sorted(files, key=lambda f: f.additions + f.deletions, reverse=True)[:3]
    common_files = [f.filename for f in sorted_files] if sorted_files else []
    
    return {
        'priority': [
            {
                'title': '우선순위 추천오류',
                'priority_level': 'MEDIUM',
                'reason': 'AI 서비스에서 오류가 발생하였거나, 파일 변경이 적습니다.',
                'related_files': common_files
            },
            {
                'title': '우선순위 추천오류',
                'priority_level': 'MEDIUM',
                'reason': 'AI 서비스에서 오류가 발생하였거나, 파일 변경이 적습니다.',
                'related_files': common_files
            },
            {
                'title': '우선순위 추천오류',
                'priority_level': 'MEDIUM',
                'reason': 'AI 서비스에서 오류가 발생하였거나, 파일 변경이 적습니다.',
                'related_files': common_files
            }
        ]
    }


def _validate_and_complete_candidates(result: Dict[str, Any], pr_data: PreparationResult, files_analysis: Dict[str, Any]) -> Dict[str, Any]:
    """결과를 검증하고, 3개가 안되면 설정 기반 또는 기본값으로 채움 (파일 정보 포함)"""
    
    candidates = result.get('priority', [])
    valid_candidates = []

    for i, candidate in enumerate(candidates[:3]):
        if not isinstance(candidate, dict) or not all(k in candidate for k in ['title', 'priority_level', 'reason']):
            continue
        
        priority_level = candidate.get('priority_level', 'MEDIUM')
        if priority_level not in ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']:
            priority_level = 'MEDIUM'
        
        # related_files가 없으면 파일 분석 결과로 추가
        related_files = candidate.get('related_files', [])
        if not related_files:
            # 후보의 내용을 기반으로 관련 파일 추정
            candidate_content = (candidate.get('title', '') + ' ' + candidate.get('reason', '')).lower()
            if 'security' in candidate_content or 'auth' in candidate_content:
                related_files = _select_relevant_files_for_candidate(files_analysis, 'security')
            elif 'database' in candidate_content or 'entity' in candidate_content:
                related_files = _select_relevant_files_for_candidate(files_analysis, 'database')
            elif 'api' in candidate_content or 'controller' in candidate_content:
                related_files = _select_relevant_files_for_candidate(files_analysis, 'api')
            else:
                related_files = _select_relevant_files_for_candidate(files_analysis, 'general')
        
        valid_candidates.append({
            'title': candidate.get('title', f'우선순위 후보 {i+1}'),
            'priority_level': priority_level,
            'reason': candidate.get('reason', '분석 결과에 따른 우선순위'),
            'related_files': related_files[:5]  # 최대 5개로 제한
        })

    # 3개 미만인 경우, 먼저 설정 기반 후보로 채우고, 그래도 부족하면 기본 오류값으로 채움
    if len(valid_candidates) < 3:
        try:
            config_candidates = calculate_config_based_priority_candidates(pr_data)['candidates']
            
            # 설정 기반 후보에서 중복되지 않는 것을 추가
            for config_cand in config_candidates:
                if len(valid_candidates) >= 3:
                    break
                # 이미 있는 title과 겹치지 않도록
                if not any(vc['title'] == config_cand['title'] for vc in valid_candidates):
                    # 설정 기반 후보에 related_files 추가
                    if 'related_files' not in config_cand:
                        config_cand['related_files'] = _select_relevant_files_for_candidate(files_analysis, 'general')
                    valid_candidates.append(config_cand)

        except Exception as e:
            logger.error(f"설정 기반 후보 생성 중 오류: {str(e)}")

    # 그래도 3개가 안되면 기본 오류 응답으로 채움
    while len(valid_candidates) < 3:
        fallback_files = _select_relevant_files_for_candidate(files_analysis, 'general')
        valid_candidates.append({
            'title': '우선순위 추천오류',
            'priority_level': 'MEDIUM',
            'reason': 'AI 분석 중 일부 결과가 유효하지 않아 기본값으로 대체합니다.',
            'related_files': fallback_files
        })

    return {'priority': valid_candidates}


def _build_priority_context_from_patterns(patterns: List[Dict[str, Any]]) -> str:
    """
    벡터 검색 결과를 LLM용 우선순위 분석 컨텍스트로 변환
    """
    context_parts = []
    
    # 🚨 수정된 부분: patterns가 유효한 리스트인지 확인하는 로직 추가
    if not isinstance(patterns, list) or not all(isinstance(p, dict) for p in patterns):
        logger.warning("유효하지 않은 유사 패턴 데이터가 감지되었습니다. 컨텍스트를 생성하지 않습니다.")
        return ""

    category_data = defaultdict(lambda: {'patterns': [], 'total_similarity': 0, 'priority_indicators': set(), 'complexity_levels': [], 'change_scales': []})

    for pattern in patterns:
        # 🚨 수정된 부분: functional_category 키가 있는지 안전하게 확인
        category = pattern.get('functional_category')
        if not category:
            continue
            
        data = category_data[category]
        data['patterns'].append(pattern)
        data['total_similarity'] += pattern.get('similarity_score', 0)
        data['priority_indicators'].update(pattern.get('priority_indicators', set()))
        data['complexity_levels'].append(pattern.get('complexity_level'))
        data['change_scales'].append(pattern.get('change_scale'))
    
    sorted_categories = sorted(
        category_data.items(),
        key=lambda x: x[1]['total_similarity'],
        reverse=True
    )[:6]
    
    if not sorted_categories:
        return ""
    
    context_parts.append("=== 과거 유사한 PR들의 우선순위 패턴 ===")
    
    for i, (category, data) in enumerate(sorted_categories, 1):
        avg_similarity = data['total_similarity'] / len(data['patterns']) if data['patterns'] else 0
        priority_indicators = ', '.join(list(data['priority_indicators'])[:4])
        complexity_counter = Counter([c for c in data['complexity_levels'] if c])
        scale_counter = Counter([s for s in data['change_scales'] if s])
        
        most_common_complexity = complexity_counter.most_common(1)[0][0] if complexity_counter else "보통"
        most_common_scale = scale_counter.most_common(1)[0][0] if scale_counter else "소규모"
        
        context_parts.append(f"""
{i}. 기능영역: {category}
    - 평균 유사도: {avg_similarity:.3f}
    - 주요 우선순위 지표: {priority_indicators}
    - 일반적 복잡도: {most_common_complexity}
    - 일반적 변경규모: {most_common_scale}
    - 관련 PR 수: {len(data['patterns'])}
        """.strip())
    
    return "\n".join(context_parts)


async def _generate_priority_candidates_with_llm(
    context: str, 
    pr_data: PreparationResult, 
    code_context: str, 
    files_analysis: Dict[str, Any],
    valid_files: List[Any]
) -> Dict[str, Any]:
    """
    LLM을 활용해 컨텍스트 기반으로 3개의 우선순위 후보 생성 (파일 정보 포함)
    
    Args:
        context: 벡터 검색으로 구성된 컨텍스트
        pr_data: 현재 PR 데이터
        code_context: 파일별 코드 변경사항 컨텍스트
        files_analysis: 파일 분석 결과
        valid_files: 분석 대상 파일 목록 (의미 없는 파일 제외)
        
    Returns:
        Dict[str, Any]: 3개의 우선순위 후보 결과 (관련 파일 포함)
    """
    # 🚨 수정된 부분: 유효한 파일 목록을 기반으로 PR 정보 요약
    commits_summary = ' | '.join([c.message for c in pr_data.commits[:5]])
    total_changes = sum(f.additions + f.deletions for f in valid_files)
    
    # 파일 분석 요약
    files_summary = f"""
고위험 파일: {len(files_analysis['high_risk_files'])}개
중위험 파일: {len(files_analysis['medium_risk_files'])}개 
저위험 파일: {len(files_analysis['low_risk_files'])}개
보안관련: {', '.join(files_analysis['security_related'][:3])}
DB관련: {', '.join(files_analysis['database_related'][:3])}
API관련: {', '.join(files_analysis['api_related'][:3])}
    """.strip()
    
    system_prompt = """당신은 Pull Request의 변경 사항을 분석하여 리뷰어가 놓치지 말아야 할 핵심 리뷰 포인트를 3가지 제안하는 AI 분석 시스템입니다.

**분석 목표:**
코드 변경의 잠재적 위험, 중요한 로직 수정, 아키텍처 영향도를 식별하여 리뷰어가 효율적으로 리뷰할 수 있도록 돕습니다. 각 후보에 대해 관련된 파일 패스들도 함께 제공합니다.

**분석 가이드라인:**
1.  **보안 및 인증 (CRITICAL)**: `jwt`, `token`, `auth`, `password` 등 보안 관련 키워드가 포함된 파일 변경은 최우선으로 분석합니다. 권한 부여, 토큰 생성/검증 로직의 위험성을 구체적으로 제시하세요.
2.  **데이터베이스 및 API (HIGH)**: `Entity`, `Repository`, `Controller`, `DTO` 등의 변경은 데이터 무결성 및 하위 호환성 문제를 야기할 수 있습니다. 스키마 변경, API 명세 변경의 영향을 명확히 설명하세요.
3.  **핵심 비즈니스 로직 (HIGH/MEDIUM)**: 주요 기능의 비즈니스 로직 변경을 식별합니다. '어떤 로직이 어떻게 바뀌었는지'와 '어떤 엣지 케이스를 테스트해야 하는지'를 포함하여 이유를 작성하세요.
4.  **대규모 변경 및 리팩토링 (MEDIUM)**: 여러 파일에 걸친 광범위한 변경이나 리팩토링은 아키텍처의 일관성을 해칠 수 있습니다. 변경의 일관성과 잠재적인 사이드 이펙트를 검토하도록 제안하세요.
5.  **단순 수정 및 테스트 (LOW)**: 오타 수정, 단순 버그 픽스, 테스트 코드 추가 등은 낮은 우선순위를 부여합니다.

**`reason` 작성 규칙:**
-   **구체성**: "로직 수정"과 같은 추상적인 표현 대신, "A 파일에서 사용자 등급 계산 방식이 B로 변경되었습니다. C 조건에서의 엣지 케이스를 확인해야 합니다."와 같이 구체적으로 작성하세요.
-   **실행 가능성**: 리뷰어가 무엇을 확인해야 하는지 명확한 체크포인트를 제시하세요.
-   **파일 참조**: 어떤 파일의 변경사항에 근거한 분석인지 명시하세요.

**`related_files` 작성 규칙:**
-   각 우선순위 후보와 직접적으로 관련된 파일 패스들을 배열로 제공합니다.
-   최대 3개까지의 파일 패스를 포함합니다.
-   가장 중요한 파일부터 순서대로 나열합니다.
-   관련성이 낮은 파일은 제외합니다.

**응답 형식:**
-   **반드시 3개의 후보를 생성해야 합니다.**
-   **설명 없이 아래 JSON 형식만 반환해야 합니다.**

{
  "priority": [
    {
      "title": "분석 결과에 대한 구체적인 제목",
      "priority_level": "CRITICAL|HIGH|MEDIUM|LOW",
      "reason": "분석 가이드라인과 작성 규칙에 따른 구체적인 리뷰 요청 사항입니다.",
      "related_files": ["path/to/file1.java", "path/to/file2.java", "path/to/file3.java"]
    },
    {
      "title": "두 번째 분석 결과 제목",
      "priority_level": "CRITICAL|HIGH|MEDIUM|LOW",
      "reason": "두 번째 리뷰 요청 사항입니다.",
      "related_files": ["path/to/file4.java", "path/to/file5.java"]
    },
    {
      "title": "세 번째 분석 결과 제목",
      "priority_level": "CRITICAL|HIGH|MEDIUM|LOW",
      "reason": "세 번째 리뷰 요청 사항입니다.",
      "related_files": ["path/to/file6.java", "path/to/file7.java", "path/to/file8.java"]
    }
  ]
}
"""

    user_prompt = f"""{context}

=== 현재 PR 정보 ===
- 제목: {pr_data.title}
- 커밋 메시지: {commits_summary}
- 브랜치: {pr_data.source} -> {pr_data.target}
- 총 변경라인: {total_changes}라인
- 파일 수: {len(valid_files)}개

=== 파일 분석 결과 ===
{files_summary}

=== 상세 파일 변경사항 ===
{code_context}

위 정보를 종합하여 이 PR의 리뷰 우선순위를 3가지 관점에서 분석해주세요. 각 후보마다 관련된 파일 패스들을 함께 5개 정도 제공해주세요."""

    try:
        response = model.invoke([
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ])
        
        # 🚨 수정된 부분: valid_files를 함께 전달하여 파싱 및 검증
        result = _parse_priority_candidates_response(response.content, valid_files, files_analysis)
        logger.info(f"LLM이 {len(result.get('priority', []))}개의 우선순위 후보를 생성했습니다")
        return result
        
    except Exception as e:
        logger.error(f"LLM 우선순위 후보 생성 중 오류: {str(e)}")
        # 폴백: 설정 기반 우선순위 후보 계산
        return calculate_config_based_priority_candidates(pr_data)


def _parse_priority_candidates_response(
    response_text: str, 
    valid_files: List[Any], 
    files_analysis: Dict[str, Any]
) -> Dict[str, Any]:
    """
    LLM 우선순위 후보 응답을 파싱 (파일 정보 포함)
    
    Args:
        response_text: LLM 응답 텍스트
        valid_files: 필터링된 PR 데이터 파일 목록
        files_analysis: 파일 분석 결과
        
    Returns:
        Dict[str, Any]: 파싱된 3개의 우선순위 후보 결과 (관련 파일 포함)
    """
    try:
        import json
        import re
        
        # JSON 부분만 추출
        json_match = re.search(r'\{.*\}', response_text, re.DOTALL)
        if not json_match:
            raise ValueError("JSON 형식을 찾을 수 없습니다")
        
        json_str = json_match.group(0)
        result = json.loads(json_str)
        
        # 검증 및 정리
        candidates = result.get('priority', [])
        valid_candidates = []
        
        for i, candidate in enumerate(candidates[:3]):  # 최대 3개까지
            if not isinstance(candidate, dict):
                continue
                
            title = candidate.get('title', f'우선순위 후보 {i+1}')
            priority_level = candidate.get('priority_level', 'MEDIUM')
            reason = candidate.get('reason', '분석 결과에 따른 우선순위')
            related_files = candidate.get('related_files', [])
            
            # 우선순위 레벨 검증
            if priority_level not in ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']:
                priority_level = 'MEDIUM'
            
            # related_files 검증 및 보완
            if not isinstance(related_files, list):
                related_files = []
            
            # related_files가 비어있으면 파일 분석 결과로 추정
            if not related_files:
                candidate_content = (title + ' ' + reason).lower()
                if any(keyword in candidate_content for keyword in ['security', 'auth', 'token', 'password']):
                    related_files = _select_relevant_files_for_candidate(files_analysis, 'security')
                elif any(keyword in candidate_content for keyword in ['database', 'entity', 'repository']):
                    related_files = _select_relevant_files_for_candidate(files_analysis, 'database')
                elif any(keyword in candidate_content for keyword in ['api', 'controller', 'endpoint']):
                    related_files = _select_relevant_files_for_candidate(files_analysis, 'api')
                else:
                    related_files = _select_relevant_files_for_candidate(files_analysis, 'general')
            
            # 파일 패스 검증 및 정리
            final_files = []
            # 🚨 수정된 부분: 필터링된 valid_files를 사용하여 파일명 목록을 가져옴
            all_filenames = [f.filename for f in valid_files]
            
            for file_path in related_files[:5]:  # 최대 5개
                if isinstance(file_path, str):
                    # 실제 PR에 포함된 파일인지 확인
                    if file_path in all_filenames:
                        final_files.append(file_path)
                    else:
                        # 부분 매칭으로 찾기
                        matching_files = [f for f in all_filenames if file_path.split('/')[-1] in f]
                        if matching_files:
                            final_files.append(matching_files[0])
            
            # 여전히 related_files가 부족하면 변경사항이 큰 파일들로 보완
            if len(final_files) < 3:
                sorted_files = sorted(valid_files, key=lambda f: f.additions + f.deletions, reverse=True)
                for file in sorted_files:
                    if file.filename not in final_files and len(final_files) < 5:
                        final_files.append(file.filename)
            
            valid_candidates.append({
                'title': title,
                'priority_level': priority_level,
                'reason': reason,
                'related_files': final_files
            })
        
        # 3개 미만인 경우 설정 기반 후보로 채움
        while len(valid_candidates) < 3:
            try:
                config_candidates = calculate_config_based_priority_candidates(pr_data)
                missing_candidate = config_candidates['candidates'][len(valid_candidates)]
                
                # 설정 기반 후보에 related_files 추가
                if 'related_files' not in missing_candidate:
                    missing_candidate['related_files'] = _select_relevant_files_for_candidate(files_analysis, 'general')
                
                valid_candidates.append(missing_candidate)
            except:
                # 기본 후보 추가
                fallback_files = _select_relevant_files_for_candidate(files_analysis, 'general')
                valid_candidates.append({
                    'title': f'우선순위 후보 {len(valid_candidates) + 1}',
                    'priority_level': 'MEDIUM',
                    'reason': '파일 변경사항 기반 분석 결과',
                    'related_files': fallback_files
                })
                break
        
        return {
            'priority': valid_candidates,
        }
        
    except Exception as e:
        logger.error(f"우선순위 후보 응답 파싱 실패: {str(e)}")
        # 폴백: 설정 기반 후보에 파일 정보 추가
        try:
            fallback_result = calculate_config_based_priority_candidates(pr_data)
            for candidate in fallback_result['candidates']:
                if 'related_files' not in candidate:
                    candidate['related_files'] = _select_relevant_files_for_candidate(files_analysis, 'general')
            return fallback_result
        except:
            return _get_default_error_response_with_files(valid_files)

# 이 함수는 수정이 필요하지 않지만, 완전한 코드를 위해 포함합니다.
def _validate_and_complete_candidates(result: Dict[str, Any], pr_data: PreparationResult, files_analysis: Dict[str, Any]) -> Dict[str, Any]:
    """결과를 검증하고, 3개가 안되면 설정 기반 또는 기본값으로 채움 (파일 정보 포함)"""
    
    candidates = result.get('priority', [])
    valid_candidates = []

    for i, candidate in enumerate(candidates[:3]):
        if not isinstance(candidate, dict) or not all(k in candidate for k in ['title', 'priority_level', 'reason']):
            continue
        
        priority_level = candidate.get('priority_level', 'MEDIUM')
        if priority_level not in ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']:
            priority_level = 'MEDIUM'
        
        # related_files가 없으면 파일 분석 결과로 추가
        related_files = candidate.get('related_files', [])
        if not related_files:
            # 후보의 내용을 기반으로 관련 파일 추정
            candidate_content = (candidate.get('title', '') + ' ' + candidate.get('reason', '')).lower()
            if 'security' in candidate_content or 'auth' in candidate_content:
                related_files = _select_relevant_files_for_candidate(files_analysis, 'security')
            elif 'database' in candidate_content or 'entity' in candidate_content:
                related_files = _select_relevant_files_for_candidate(files_analysis, 'database')
            elif 'api' in candidate_content or 'controller' in candidate_content:
                related_files = _select_relevant_files_for_candidate(files_analysis, 'api')
            else:
                related_files = _select_relevant_files_for_candidate(files_analysis, 'general')
        
        valid_candidates.append({
            'title': candidate.get('title', f'우선순위 후보 {i+1}'),
            'priority_level': priority_level,
            'reason': candidate.get('reason', '분석 결과에 따른 우선순위'),
            'related_files': related_files[:5]  # 최대 5개로 제한
        })

    # 3개 미만인 경우, 먼저 설정 기반 후보로 채우고, 그래도 부족하면 기본 오류값으로 채움
    if len(valid_candidates) < 3:
        try:
            config_candidates = calculate_config_based_priority_candidates(pr_data)['candidates']
            
            # 설정 기반 후보에서 중복되지 않는 것을 추가
            for config_cand in config_candidates:
                if len(valid_candidates) >= 3:
                    break
                # 이미 있는 title과 겹치지 않도록
                if not any(vc['title'] == config_cand['title'] for vc in valid_candidates):
                    # 설정 기반 후보에 related_files 추가
                    if 'related_files' not in config_cand:
                        config_cand['related_files'] = _select_relevant_files_for_candidate(files_analysis, 'general')
                    valid_candidates.append(config_cand)

        except Exception as e:
            logger.error(f"설정 기반 후보 생성 중 오류: {str(e)}")

    # 그래도 3개가 안되면 기본 오류 응답으로 채움
    while len(valid_candidates) < 3:
        fallback_files = _select_relevant_files_for_candidate(files_analysis, 'general')
        valid_candidates.append({
            'title': '우선순위 추천오류',
            'priority_level': 'MEDIUM',
            'reason': 'AI 분석 중 일부 결과가 유효하지 않아 기본값으로 대체합니다.',
            'related_files': fallback_files
        })

    return {'priority': valid_candidates}

