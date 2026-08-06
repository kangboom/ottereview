# -*- coding: utf-8 -*-
import os
import tempfile
from typing import Optional
from openai import OpenAI
from fastapi import UploadFile, HTTPException
import logging

logger = logging.getLogger(__name__)

class WhisperService:
    def __init__(self):
        self.client = OpenAI(
            api_key=os.getenv("OPENAI_API_KEY")
        )
        if not os.getenv("OPENAI_API_KEY"):
            raise ValueError("OPENAI_API_KEY environment variable is required")
    
    async def transcribe_audio(self, audio_file: UploadFile) -> dict:
        """
        음성 파일을 받아서 Whisper API를 통해 텍스트로 변환
        
        Args:
            audio_file: 업로드된 음성 파일
            language: 언어 코드 (선택사항, 예: 'ko', 'en')
            
        Returns:
            dict: 변환된 텍스트 결과
        """
        if not audio_file:
            raise HTTPException(status_code=400, detail="음성 파일이 필요합니다")
        
        # 지원하는 파일 형식 확인
        supported_formats = ['.mp3', '.mp4', '.mpeg', '.mpga', '.m4a', '.wav', '.webm']
        file_extension = os.path.splitext(audio_file.filename)[1].lower()
        
        if file_extension not in supported_formats:
            raise HTTPException(
                status_code=400, 
                detail=f"지원하지 않는 파일 형식입니다. 지원 형식: {', '.join(supported_formats)}"
            )
        
        try:
            # 임시 파일로 저장
            with tempfile.NamedTemporaryFile(delete=False, suffix=file_extension) as temp_file:
                content = await audio_file.read()
                temp_file.write(content)
                temp_file_path = temp_file.name
            
            # Whisper API 호출
            with open(temp_file_path, "rb") as audio:
                transcript_params = {
                    "file": audio,
                    "model": "whisper-1"
                }
                

                transcript_params["language"] = "ko"
                
                transcript = self.client.audio.transcriptions.create(**transcript_params)
            
            # 임시 파일 정리
            os.unlink(temp_file_path)

            return transcript.text

        except Exception as e:
            # 임시 파일이 생성되었다면 정리
            if 'temp_file_path' in locals():
                try:
                    os.unlink(temp_file_path)
                except:
                    pass
            
            logger.error(f"Whisper 음성 변환 실패: {str(e)}")
            raise HTTPException(status_code=500, detail=f"음성 변환 실패: {str(e)}")

# 싱글톤 인스턴스
whisper_service = WhisperService()
