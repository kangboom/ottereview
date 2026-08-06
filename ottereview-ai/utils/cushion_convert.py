import os
from dotenv import load_dotenv
from langchain.chat_models import init_chat_model

# .env 파일에서 환경변수 로드
load_dotenv()

# OpenAI API KEY 확인
openai_api_key = os.getenv("OPENAI_API_KEY")
if not openai_api_key:
    raise ValueError("OPENAI_API_KEY가 환경변수에 설정되지 않았습니다. .env 파일을 확인해주세요.")

# OpenAI 기본 엔드포인트(https://api.openai.com/v1)를 사용합니다.
model = init_chat_model("gpt-4o-mini", model_provider="openai")

def convert_review_to_soft_tone(review_data):
    """
    리뷰어가 작성한 리뷰를 사용자가 상처받지 않도록 부드러운 어조로 변환합니다.
    
    Args:
        review_data (str): 리뷰 내용

    Returns:
        dict: {"softened_content": "부드럽게 변환된 리뷰 내용"} 형식의 결과
    """
    original_content = review_data
    
    # 부드러운 어조 변환을 위한 프롬프트
    system_prompt = """코드 리뷰를 건설적이고 간결한 어조로 변환해주세요.

변환 원칙:
1. 비판적/비꼬는 표현 → 구체적인 개선 제안으로 변환
2. 명령조 → 제안조로 변환  
3. 기술적 핵심 내용만 유지하고 간결하게 작성
4. 불필요한 인사말, 격려, 부연 설명 제거
5. 원본의 기술적 의도는 정확히 파악하여 반영
6. 어조는 조금더 부드럽고 친근하게, 그러나 전문성을 유지
예시:
- "저딴식으로 사용하면" → "JWT 토큰 구현 방식을 개선해보세요"
- "공공재로 하기로 마음 먹은거죠?" → "사용자 정보 보안을 강화해야 합니다" """

    user_prompt = f"다음 코드 리뷰를 건설적이고 간결한 어조로 변환해주세요:\n\n{original_content}"
    
    try:
        response = model.invoke([
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ])
        
        softened_content = response.content if hasattr(response, 'content') else str(response)

        return {"result": softened_content}

    except Exception as e:
       raise e
