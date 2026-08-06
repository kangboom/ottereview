import os
import asyncio
from typing import Dict, List, Optional, Any
from pydantic import BaseModel, validator
from enum import Enum
import logging
from dotenv import load_dotenv
from langchain_openai import ChatOpenAI
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.messages import SystemMessage, HumanMessage

# .env 파일에서 환경변수 로드
load_dotenv()

logger = logging.getLogger(__name__)

# OpenAI API KEY 확인
openai_api_key = os.getenv("OPENAI_API_KEY")
if not openai_api_key:
    raise ValueError("OPENAI_API_KEY가 환경변수에 설정되지 않았습니다. .env 파일을 확인해주세요.")

# OpenAI 기본 엔드포인트(https://api.openai.com/v1)를 사용합니다.
model = ChatOpenAI(model="gpt-4o-mini", api_key=openai_api_key)

class ConventionType(str, Enum):
    """지원되는 네이밍 컨벤션 타입"""
    CAMEL_CASE = "camelCase"
    PASCAL_CASE = "PascalCase"
    SNAKE_CASE = "snake_case"
    KEBAB_CASE = "kebab-case"
    CONSTANT_CASE = "CONSTANT_CASE"
    
class ConventionRule(BaseModel):
    """코딩 컨벤션 규칙"""
    file_names: Optional[ConventionType] = None
    function_names: Optional[ConventionType] = None
    variable_names: Optional[ConventionType] = None
    class_names: Optional[ConventionType] = None
    constant_names: Optional[ConventionType] = None

    @validator('*', pre=True)
    def empty_str_to_none(cls, v):
        if v == "":
            return None
        return v

    def to_prompt_string(self) -> str:
        """규칙 객체를 프롬프트에 사용될 문자열로 변환"""
        rules_text = []
        if self.file_names:
            rules_text.append(f"- 파일명: {self.file_names.value}")
        if self.function_names:
            rules_text.append(f"- 함수명: {self.function_names.value}")
        if self.variable_names:
            rules_text.append(f"- 변수명: {self.variable_names.value}")
        if self.class_names:
            rules_text.append(f"- 클래스명: {self.class_names.value}")
        if self.constant_names:
            rules_text.append(f"- 상수명: {self.constant_names.value}")
        return "\n".join(rules_text)


def detect_language_from_filename(filename: str) -> str:
    """파일명에서 언어 감지"""
    ext = filename.split('.')[-1].lower() if '.' in filename else ''
    language_map = {
        'py': 'Python',
        'js': 'JavaScript',
        'ts': 'TypeScript',
        'java': 'Java'
    }
    return language_map.get(ext, '코드')

async def check_single_file_convention(filename: str, patch: str, rules_str: str) -> Optional[str]:
    """
    단일 파일에 대한 코딩 컨벤션 검사를 수행하고 결과를 반환합니다.
    """
    system_prompt = """당신은 매우 꼼꼼한 시니어 개발자 역할을 하는 코딩 컨벤션 검사 전문가입니다.
    주어진 코드 변경사항(patch)을 분석하여, 명시된 네이밍 컨벤션 규칙에 어긋나는 부분을 모두 찾아내고, 자연스러운 한국어로 설명해야 합니다.

    **규칙:**
    1.  **정확성**: 명시된 규칙만을 기반으로 검사하고, 규칙에 없는 내용은 절대 지적하지 마세요.
    2.  **구체성**: 어떤 요소(파일명, 함수명, 변수명 등)가 문제인지 명확히 하고, 현재 이름과 권장하는 이름을 반드시 제시해야 합니다.
    3.  **형식 준수**: 아래의 출력 형식을 **반드시** 지켜야 합니다. 다른 설명이나 서론, 결론을 추가하지 마세요.
    4.  **규칙 준수**: 적용할 네이밍 컨벤션 규칙에 있는 요소만 검사합니다.
    **출력 형식:**

    *   **위반사항이 있는 경우:**
        -   위반 내역을 다음 형식으로 한 줄에 하나씩 나열합니다.
        -   `[요소명] '[현재 이름]'은(는) [규칙명] 규칙을 위반합니다. '[권장 이름]'으로 수정하세요.`
        -   예시:
            -   `[함수명] 'my_func'은(는) camelCase 규칙을 위반합니다. 'myFunc'으로 수정하세요.`
            -   `[변수명] 'UserName'은(는) snake_case 규칙을 위반합니다. 'user_name'으로 수정하세요.`

    *   **위반사항이 없는 경우:**
        -   `"코딩 컨벤션을 잘 준수하고 있습니다."` 라고 **정확히** 이 문장만 응답해야 합니다.
    """

    user_prompt = f"""다음 코드에서 네이밍 컨벤션 위반사항을 찾아주세요.

    적용할 네이밍 컨벤션 규칙:
    {rules_str}

    컨벤션 설명:
    - camelCase: myVariableName
    - PascalCase: MyClassName
    - snake_case: my_variable_name
    - kebab-case: my-file-name
    - CONSTANT_CASE: MY_CONSTANT_VALUE

    분석할 코드:
    ---
    # 파일: {filename}
    ```{patch}
    ```
    """

    try:
        messages = [
            SystemMessage(content=system_prompt),
            HumanMessage(content=user_prompt)
        ]
        response = await model.ainvoke(messages)
        response_text = response.content if hasattr(response, 'content') else str(response)

        # 위반사항이 없는 경우, None을 반환하여 최종 결과에서 제외
        if "코딩 컨벤션을 잘 준수하고 있습니다." in response_text:
            return None
            
        return f"- 파일: {filename}\n  {response_text.strip()}"
            
    except Exception as e:
        logger.error(f"파일 '{filename}' LLM 호출 중 오류: {str(e)}")
        return f"- 파일: {filename}\n  분석 중 오류 발생: {str(e)}"

SUPPORTED_EXTENSIONS = {'.py', '.js', '.ts', '.java'}

async def check_pr_conventions(pr_data: Any, rules: ConventionRule) -> str:
    """
    PR의 모든 파일을 병렬로 분석하여 코딩 컨벤션 위반사항을 검사합니다.
    """
    if not rules or not rules.dict(exclude_none=True):
        return "코딩 컨벤션 규칙이 설정되지 않았습니다. 검사를 수행하지 않습니다."

    rules_str = rules.to_prompt_string() if isinstance(rules, ConventionRule) else rules
    tasks = []

    try:
        files = getattr(pr_data, 'files', [])
        for file_info in files:
            filename = getattr(file_info, 'filename', '')
            patch = getattr(file_info, 'patch', '')

            # 파일 확장자 추출 및 소문자 변환
            _, file_extension = os.path.splitext(filename.lower())
            
            # 지원하는 확장자인지 확인
            if file_extension not in SUPPORTED_EXTENSIONS:
                logger.info(f"파일 '{filename}'은(는) 지원되지 않는 확장자({file_extension})입니다. 컨벤션 검사에서 제외합니다.")
                continue

            if filename and patch:
                tasks.append(check_single_file_convention(filename, patch, rules_str))
    except Exception as e:
        logger.error(f"PR 데이터 처리 중 오류: {str(e)}")
        return "PR 데이터 처리 중 오류가 발생했습니다."

    if not tasks:
        return "분석할 코드가 없습니다."

    # 모든 비동기 작업을 병렬로 실행하고 결과를 기다립니다.
    results = await asyncio.gather(*tasks)

    # 유효한(None이 아닌) 결과만 필터링합니다.
    final_result_parts = [result for result in results if result]

    if not final_result_parts:
        return "모든 파일이 코딩 컨벤션을 잘 준수하고 있습니다."
    
    return "\n\n".join(final_result_parts)
