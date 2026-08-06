import pinecone
from pinecone import Pinecone, ServerlessSpec
import json
import logging
import numpy as np
from typing import List, Dict, Optional, Any, Tuple
import hashlib
import os
from dotenv import load_dotenv
from langchain_openai import OpenAIEmbeddings
import traceback
import uuid
from datetime import datetime
from collections import defaultdict, Counter
from models import (
    PreparationResult, PRDetailData, 
    PullRequestFileInfo, PullRequestCommitInfo, PullRequestUserInfo, 
    PullRequestReviewerInfo, PullRequestPriorityInfo,
    FileChangeInfo, DiffLine, DiffHunk, DescriptionInfo, CommitInfo,
    RepoInfo, PrUserInfo, PriorityInfo,
    PRData  # 하위 호환성을 위해 유지
)

load_dotenv()

logger = logging.getLogger(__name__)


class PineconeVectorDB:
    """RAG 파이프라인을 위한 Pinecone 벡터 데이터베이스"""
    
    def __init__(self):
        """Pinecone 벡터 DB 초기화"""
        # Pinecone 클라이언트 초기화
        self.pc = Pinecone(api_key=os.getenv("PINECONE_API_KEY"))
        
        # OpenAI 임베딩 초기화
        self.embeddings = OpenAIEmbeddings(
            model="text-embedding-3-small",
            openai_api_key=os.getenv("OPENAI_API_KEY")
        )
        
        # 인덱스 설정
        self.index_configs = {
            "reviewer-patterns": {
                "dimension": 1536,
                "metric": "cosine",
                "description": "리뷰어 선택 패턴과 전문성"
            },
            "priority-patterns": {
                "dimension": 1536,
                "metric": "cosine",
                "description": "의미 단위별 리뷰 우선순위 패턴"
            }
        }
        
        # 인덱스 초기화
        self._ensure_indexes()
    
    def _ensure_indexes(self):
        """Pinecone 인덱스들 생성 및 확인"""
        existing_indexes = [index.name for index in self.pc.list_indexes()]
        
        for index_name, config in self.index_configs.items():
            if index_name not in existing_indexes:
                logger.info(f"인덱스 {index_name}을 생성합니다: {config['description']}")
                
                self.pc.create_index(
                    name=index_name,
                    dimension=config["dimension"],
                    metric=config["metric"],
                    spec=ServerlessSpec(
                        cloud='aws',
                        region='us-east-1'
                    )
                )
            else:
                logger.info(f"인덱스 {index_name}이 이미 존재합니다")
        
        # 인덱스 참조 저장
        self.reviewer_patterns_index = self.pc.Index("reviewer-patterns")
        self.priority_patterns_index = self.pc.Index("priority-patterns")
    
    def _extract_file_categories(self, files: List[PullRequestFileInfo]) -> Dict[str, int]:
        """파일 확장자별 카테고리 분류"""
        categories = {}
        for file in files:
            ext = os.path.splitext(file.filename)[1].lower()
            if ext in ['.py', '.java', '.js', '.ts', '.cpp', '.c', '.go']:
                category = 'source_code'
            elif ext in ['.md', '.txt', '.rst']:
                category = 'documentation'
            elif ext in ['.json', '.yaml', '.yml', '.xml', '.toml']:
                category = 'configuration'
            elif 'test' in file.filename.lower() or ext in ['.test.js', '.spec.js']:
                category = 'test'
            else:
                category = 'other'
            
            categories[category] = categories.get(category, 0) + 1
        return categories
    
    async def store_pr_detail_data(self, pr_detail: PRDetailData) -> bool:
        """PullRequestDetailResponse 데이터를 벡터 DB에 저장 (머지 완료 후 호출)"""
        try:
            # 1. 리뷰어 패턴 저장 (리뷰어 추천용)
            if pr_detail.reviewers:
                await self._store_reviewer_patterns_from_detail(pr_detail)
            
            # 2. 우선순위 패턴 저장 (우선순위 추천용)
            await self._store_priority_patterns_from_detail(pr_detail)
            
            logger.info(f"PR {pr_detail.id} 데이터가 성공적으로 저장되었습니다")
            return True
            
        except Exception as e:
            logger.error(f"PR {pr_detail.id} 저장 중 오류: {str(e)}\n{traceback.format_exc()}")
            return False

    async def store_pr_data(self, pr_detail: PRDetailData) -> bool:
        """PR 데이터를 벡터 DB에 저장 (머지 완료 후 호출) - PRDetailData 사용으로 변경"""
        return await self.store_pr_detail_data(pr_detail)
    
    
    
    async def _store_priority_patterns_from_detail(self, pr_detail: PRDetailData):
        """PRDetailData로부터 우선순위 패턴 저장 - 실제 데이터 기반 개선"""
        commit_messages = [c.message for c in pr_detail.commits]
        functional_groups = self._group_detail_files_by_function(pr_detail.files, commit_messages)
        
        # PR 전체 정보 분석
        pr_analysis = self._analyze_pr_characteristics(pr_detail)
        priority_analysis = self._analyze_priority_characteristics(pr_detail)
        
        # 각 기능 그룹별로 패턴 저장
        for i, group in enumerate(functional_groups):
            context_parts = [
                f"기능 영역: {group['category']}",
                f"파일 확장자: {', '.join(group['file_types'])}",
                f"변경 규모: {group['change_scale']}",
                f"복잡도: {group['complexity_level']}",
                f"커밋 패턴: {group['commit_pattern']}",
                f"작성자: {pr_detail.author.githubUsername}",
                f"위험도 지표: {', '.join(priority_analysis['risk_indicators'])}",
                f"커밋 메시지: {group['related_commits'][:100]}"  # 길이 제한
            ]
            
            context = " ".join(context_parts)
            context_vector = await self.embeddings.aembed_query(context)
            
            # 우선순위 결정에 도움이 되는 메타데이터
            metadata = {
                "pr_id": str(pr_detail.id),
                "repository_id": str(pr_detail.repo.id),
                "repository_name": pr_detail.repo.fullName,
                "pr_author": pr_detail.author.githubUsername,
                "functional_category": group['category'],
                "file_extensions": ",".join(group['file_types']),
                "change_scale": group['change_scale'],
                "complexity_level": group['complexity_level'],
                "related_files": ",".join(group['files'][:5]),
                "file_count": str(len(group['files'])),
                "total_changes": str(group['total_changes']),
                "commit_count": str(pr_detail.commitCnt),
                "changed_files_count": str(pr_detail.changedFilesCnt),
                "commit_pattern": group['commit_pattern'],
                "priority_indicators": ",".join(group['priority_indicators']),
                "risk_level": priority_analysis['risk_level'],
                "business_impact": priority_analysis['business_impact'],
                "has_security_changes": str(priority_analysis['has_security_changes']).lower(),
                "has_breaking_changes": str(priority_analysis['has_breaking_changes']).lower(),
                "has_tests": str(pr_analysis['has_tests']).lower(),
                "has_docs": str(pr_analysis['has_docs']).lower(),
                "has_config": str(pr_analysis['has_config']).lower(),
                "related_commits": group['related_commits'][:200],  # 길이 제한
                "timestamp": datetime.now().isoformat()
            }
            
            self.priority_patterns_index.upsert(vectors=[{
                "id": f"{pr_detail.id}_priority_{i}",
                "values": context_vector,
                "metadata": metadata
            }])
    
    async def _store_reviewer_patterns_from_detail(self, pr_detail: PRDetailData):
        """PRDetailData로부터 리뷰어 추천을 위한 패턴 저장 - 실제 데이터 기반 개선"""
        if not pr_detail.reviewers:
            return
            
        functional_groups = self._group_detail_files_by_function(pr_detail.files, [c.message for c in pr_detail.commits])
        
        # PR 전체 정보 분석
        pr_analysis = self._analyze_pr_characteristics(pr_detail)
        
        # 각 리뷰어별로 패턴 저장
        for i, reviewer_info in enumerate(pr_detail.reviewers):
            for j, group in enumerate(functional_groups):
                # 컨텍스트에 실제 데이터 기반 정보 포함
                context_parts = [
                    f"리뷰어: {reviewer_info.githubUsername}",
                    f"기능 영역: {group['category']}",
                    f"파일 확장자: {', '.join(group['file_types'])}",
                    f"변경 규모: {group['change_scale']}",
                    f"복잡도: {group['complexity_level']}",
                    f"커밋 패턴: {group['commit_pattern']}",
                    f"작성자: {pr_detail.author.githubUsername}"
                ]
                
                context = " ".join(context_parts)
                context_vector = await self.embeddings.aembed_query(context)
                
                # 실제 패턴 학습에 도움이 되는 메타데이터만 저장
                metadata = {
                    "pr_id": str(pr_detail.id),
                    "repository_id": str(pr_detail.repo.id),
                    "repository_name": pr_detail.repo.fullName,
                    "reviewer": reviewer_info.githubUsername,
                    "reviewer_email": reviewer_info.githubEmail,
                    "pr_author": pr_detail.author.githubUsername,
                    "functional_category": group['category'],
                    "file_extensions": ",".join(group['file_types']),
                    "change_scale": group['change_scale'],
                    "complexity_level": group['complexity_level'],
                    "files_reviewed": ",".join(group['files'][:5]),
                    "file_count": str(len(group['files'])),
                    "total_changes": str(group['total_changes']),
                    "commit_count": str(pr_detail.commitCnt),
                    "changed_files_count": str(pr_detail.changedFilesCnt),
                    "branch_name": pr_detail.head,
                    "commit_pattern": group['commit_pattern'],
                    "has_tests": str(pr_analysis['has_tests']).lower(),
                    "has_docs": str(pr_analysis['has_docs']).lower(),
                    "has_config": str(pr_analysis['has_config']).lower(),
                    "review_provided": "true",
                    "timestamp": datetime.now().isoformat()
                }
                
                self.reviewer_patterns_index.upsert(vectors=[{
                    "id": f"{pr_detail.id}_reviewer_{i}_{j}",
                    "values": context_vector,
                    "metadata": metadata
                }])
    
    def _group_detail_files_by_function(self, files: List[PullRequestFileInfo], commit_messages: List[str]) -> List[Dict[str, Any]]:
        """PRDetailFileInfo들을 상위 디렉토리 기준으로 그룹화"""
        groups = defaultdict(list)
        for file in files:
            parent_dir = os.path.dirname(file.filename)
            if not parent_dir or parent_dir == ".":
                parent_dir = "root"
            groups[parent_dir].append(file)

        functional_groups = []
        for parent_dir, dir_files in groups.items():
            functional_groups.append(self._create_detail_functional_group(
                dir_files, parent_dir, commit_messages, [parent_dir]
            ))
        
        return functional_groups
    
    def _analyze_pr_characteristics(self, pr_detail: PRDetailData) -> Dict[str, Any]:
        """PR의 전반적인 특성 분석"""
        analysis = {
            'has_tests': False,
            'has_docs': False,
            'has_config': False,
            'main_languages': [],
            'directory_spread': 0
        }
        
        directories = set()
        extensions = Counter()
        
        for file in pr_detail.files:
            # 디렉토리 분산도 계산
            directories.add(os.path.dirname(file.filename))
            
            # 파일 확장자별 통계
            ext = os.path.splitext(file.filename)[1].lower()
            if ext:
                extensions[ext] += 1
            
            filename_lower = file.filename.lower()
            
            # 테스트 파일 검사
            if any(test_pattern in filename_lower for test_pattern in ['test', 'spec', '__test__']):
                analysis['has_tests'] = True
            
            # 문서 파일 검사
            if ext in ['.md', '.txt', '.rst', '.doc']:
                analysis['has_docs'] = True
            
            # 설정 파일 검사
            if ext in ['.yml', '.yaml', '.json', '.xml', '.properties', '.toml'] or 'config' in filename_lower:
                analysis['has_config'] = True
        
        analysis['directory_spread'] = len(directories)
        analysis['main_languages'] = [ext for ext, count in extensions.most_common(3)]
        
        return analysis
    
    def _analyze_priority_characteristics(self, pr_detail: PRDetailData) -> Dict[str, Any]:
        """우선순위 결정을 위한 PR 특성 분석"""
        analysis = {
            'risk_level': 'LOW',
            'business_impact': 'LOW',
            'has_security_changes': False,
            'has_breaking_changes': False,
            'risk_indicators': []
        }
        
        security_patterns = ['auth', 'security', 'token', 'password', 'jwt', 'permission', 'role']
        breaking_patterns = ['migration', 'schema', 'api', 'interface', 'contract']
        high_impact_patterns = ['controller', 'service', 'entity', 'repository', 'config']
        
        total_changes = sum(f.additions + f.deletions for f in pr_detail.files)
        
        for file in pr_detail.files:
            filename_lower = file.filename.lower()
            
            # 보안 관련 변경 검사
            if any(pattern in filename_lower for pattern in security_patterns):
                analysis['has_security_changes'] = True
                analysis['risk_indicators'].append('보안관련')
            
            # 브레이킹 체인지 검사
            if any(pattern in filename_lower for pattern in breaking_patterns):
                analysis['has_breaking_changes'] = True
                analysis['risk_indicators'].append('호환성영향')
            
            # 비즈니스 임팩트 검사
            if any(pattern in filename_lower for pattern in high_impact_patterns):
                analysis['business_impact'] = 'HIGH'
                analysis['risk_indicators'].append('핵심로직')
        
        # 변경 규모에 따른 위험도 조정
        if total_changes > 500:
            analysis['risk_level'] = 'HIGH'
            analysis['risk_indicators'].append('대규모변경')
        elif total_changes > 100:
            analysis['risk_level'] = 'MEDIUM'
            analysis['risk_indicators'].append('중간규모변경')
        
        # 보안 변경이 있으면 위험도 상향
        if analysis['has_security_changes']:
            analysis['risk_level'] = 'CRITICAL'
        
        return analysis

    def _create_detail_functional_group(self, files: List[PullRequestFileInfo], category: str, 
                               commit_messages: List[str], priority_indicators: List[str]) -> Dict[str, Any]:
        """PRDetailFileInfo로부터 기능 그룹 생성 - 커밋 패턴 분석 추가"""
        total_changes = sum(f.additions + f.deletions for f in files)
        file_types = list(set([os.path.splitext(f.filename)[1].lower() for f in files if os.path.splitext(f.filename)[1]]))
        
        # 변경 규모 계산
        if total_changes > 200:
            change_scale = "대규모"
            priority_indicators.append("대규모변경")
        elif total_changes > 50:
            change_scale = "중간규모"
            priority_indicators.append("중간규모변경")
        else:
            change_scale = "소규모"
        
        # 복잡도 계산
        if len(files) > 5:
            complexity_level = "복잡"
            priority_indicators.append("복잡한변경")
        elif len(files) > 2:
            complexity_level = "보통"
        else:
            complexity_level = "단순"
        
        # 관련 커밋 메시지 찾기
        related_commits = " ".join([msg for msg in commit_messages 
                                  if any(keyword in msg.lower() for keyword in category.lower().split('/'))])
        
        # 커밋 패턴 분석
        commit_pattern = self._analyze_commit_pattern(commit_messages)
        
        return {
            "category": category,
            "files": [f.filename for f in files],
            "change_scale": change_scale,
            "complexity_level": complexity_level,
            "total_changes": total_changes,
            "file_types": file_types,
            "priority_indicators": priority_indicators,
            "related_commits": related_commits or " ".join(commit_messages),
            "commit_pattern": commit_pattern
        }
    
    def _analyze_commit_pattern(self, commit_messages: List[str]) -> str:
        """커밋 메시지 패턴 분석"""
        if not commit_messages:
            return "일반"
        
        all_messages = " ".join(commit_messages).lower()
        
        # 패턴별 키워드 정의
        patterns = {
            "기능추가": ["feat", "feature", "add", "implement", "새로운", "추가"],
            "버그수정": ["fix", "bug", "error", "수정", "버그", "오류"],
            "리팩토링": ["refactor", "refactoring", "개선", "정리", "구조변경"],
            "테스트": ["test", "testing", "spec", "테스트"],
            "문서": ["docs", "doc", "documentation", "문서", "readme"],
            "설정": ["config", "configuration", "설정", "환경"],
            "스타일": ["style", "formatting", "스타일", "포맷"],
            "성능": ["perf", "performance", "optimize", "성능", "최적화"]
        }
        
        # 각 패턴별로 매칭 점수 계산
        pattern_scores = {}
        for pattern_name, keywords in patterns.items():
            score = sum(1 for keyword in keywords if keyword in all_messages)
            if score > 0:
                pattern_scores[pattern_name] = score
        
        # 가장 높은 점수의 패턴 반환
        if pattern_scores:
            return max(pattern_scores.items(), key=lambda x: x[1])[0]
        
        return "일반"
    
    def _group_files_by_function(self, files: List[FileChangeInfo], commit_messages: List[str]) -> List[Dict[str, Any]]:
        """파일들을 상위 디렉토리 기준으로 그룹화"""
        groups = defaultdict(list)
        for file in files:
            # Get the parent directory of the file
            parent_dir = os.path.dirname(file.filename)
            if not parent_dir or parent_dir == ".":
                parent_dir = "root"
            groups[parent_dir].append(file)

        functional_groups = []
        for parent_dir, dir_files in groups.items():
            functional_groups.append(self._create_functional_group(
                dir_files, parent_dir, commit_messages, [parent_dir]
            ))
        
        return functional_groups
    
    def _create_functional_group(self, files: List[FileChangeInfo], category: str, 
                               commit_messages: List[str], priority_indicators: List[str]) -> Dict[str, Any]:
        """기능 그룹 생성 - 커밋 패턴 분석 추가"""
        total_changes = sum(f.additions + f.deletions for f in files)
        file_types = list(set([os.path.splitext(f.filename)[1].lower() for f in files if os.path.splitext(f.filename)[1]]))
        
        # 변경 규모 계산
        if total_changes > 200:
            change_scale = "대규모"
            priority_indicators.append("대규모변경")
        elif total_changes > 50:
            change_scale = "중간규모"
            priority_indicators.append("중간규모변경")
        else:
            change_scale = "소규모"
        
        # 복잡도 계산
        if len(files) > 5:
            complexity_level = "복잡"
            priority_indicators.append("복잡한변경")
        elif len(files) > 2:
            complexity_level = "보통"
        else:
            complexity_level = "단순"
        
        # 관련 커밋 메시지 찾기
        related_commits = " ".join([msg for msg in commit_messages 
                                  if any(keyword in msg.lower() for keyword in category.lower().split('/'))])
        
        # 커밋 패턴 분석
        commit_pattern = self._analyze_commit_pattern(commit_messages)
        
        return {
            "category": category,
            "files": [f.filename for f in files],
            "change_scale": change_scale,
            "complexity_level": complexity_level,
            "total_changes": total_changes,
            "file_types": file_types,
            "priority_indicators": priority_indicators,
            "related_commits": related_commits or " ".join(commit_messages),
            "commit_pattern": commit_pattern
        }
    
    async def get_similar_reviewer_patterns(self, pr_data: PreparationResult, limit: int = 10) -> List[Dict[str, Any]]:
        """현재 PR과 유사한 리뷰어 패턴 검색"""
        try:
            functional_groups = self._group_files_by_function(pr_data.files, [])
            
            all_patterns = []
            for group in functional_groups:
                query_parts = [
                    f"기능 영역: {group['category']}",
                    f"파일들: {', '.join(group['files'])}"
                ]
                query = " ".join(query_parts)
                patterns = await self.search_reviewer_patterns(query, pr_data.repository.id if pr_data.repository else 0, limit)
                all_patterns.extend(patterns)
            
            # Remove duplicates and sort by score
            unique_patterns = {p['reviewer']: p for p in sorted(all_patterns, key=lambda x: x['similarity_score'], reverse=True)}.values()
            return list(unique_patterns)[:limit]
            
        except Exception as e:
            logger.error(f"유사한 리뷰어 패턴 검색 중 오류: {str(e)}")
            return []

    
    async def search_reviewer_patterns(self, query: str, repository_id: int, limit: int = 20) -> List[Dict[str, Any]]:
        """유사한 리뷰어 패턴 검색 (리뷰어 추천용)"""
        try:
            query_vector = await self.embeddings.aembed_query(query)
            
            results = self.reviewer_patterns_index.query(
                vector=query_vector,
                top_k=limit,
                include_metadata=True,
                filter={"repository_id": str(repository_id)}
            )
            
            patterns = []
            for match in results.matches:
                metadata = match.metadata
                patterns.append({
                    "pr_id": metadata.get("pr_id"),
                    "repository_id": metadata.get("repository_id"),
                    "repository_name": metadata.get("repository_name"),
                    "reviewer": metadata.get("reviewer"),
                    "reviewer_email": metadata.get("reviewer_email"),
                    "pr_author": metadata.get("pr_author"),
                    "functional_category": metadata.get("functional_category"),
                    "file_categories": metadata.get("file_extensions", "").split(",") if metadata.get("file_extensions") else [],
                    "files_reviewed": metadata.get("files_reviewed", "").split(",") if metadata.get("files_reviewed") else [],
                    "change_scale": metadata.get("change_scale"),
                    "complexity_level": metadata.get("complexity_level"),
                    "commit_pattern": metadata.get("commit_pattern"),
                    "has_tests": metadata.get("has_tests") == "true",
                    "has_docs": metadata.get("has_docs") == "true",
                    "has_config": metadata.get("has_config") == "true",
                    "review_provided": metadata.get("review_provided") == "true",
                    "similarity_score": float(match.score)
                })
            
            return patterns
            
        except Exception as e:
            logger.error(f"리뷰어 패턴 검색 중 오류: {str(e)}")
            return []
    
    async def get_similar_priority_patterns(self, pr_data: PreparationResult, limit: int = 5) -> List[Dict[str, Any]]:
        """현재 PR과 유사한 우선순위 패턴 검색"""
        try:
            commit_messages = [c.message for c in pr_data.commits]
            functional_groups = self._group_files_by_function(pr_data.files, commit_messages)
            
            all_patterns = []
            for group in functional_groups:
                query_parts = [
                    f"기능 영역: {group['category']}",
                    f"관련 파일: {', '.join(group['files'])}",
                    f"변경 규모: {group['change_scale']}",
                    f"커밋 메시지: {group['related_commits']}"
                ]
                query = " ".join(query_parts)
                patterns = await self.search_priority_patterns(query, pr_data.repository.id if pr_data.repository else 0, limit)
                all_patterns.extend(patterns)
            
            # Remove duplicates and sort by score
            unique_patterns = {p['pr_id']: p for p in sorted(all_patterns, key=lambda x: x['similarity_score'], reverse=True)}.values()
            return list(unique_patterns)[:limit]
            
        except Exception as e:
            logger.error(f"유사한 우선순위 패턴 검색 중 오류: {str(e)}")
            return []
        
    async def search_priority_patterns(self, query: str, repository_id: int, limit: int = 20) -> List[Dict[str, Any]]:
        """유사한 우선순위 패턴 검색 (우선순위 추천용)"""
        try:
            query_vector = await self.embeddings.aembed_query(query)
            
            results = self.priority_patterns_index.query(
                vector=query_vector,
                top_k=limit,
                include_metadata=True,
                filter={"repository_id": str(repository_id)}
            )
            
            patterns = []
            for match in results.matches:
                metadata = match.metadata
                patterns.append({
                    "pr_id": metadata.get("pr_id"),
                    "repository_id": metadata.get("repository_id"),
                    "repository_name": metadata.get("repository_name"),
                    "pr_author": metadata.get("pr_author"),
                    "functional_category": metadata.get("functional_category"),
                    "related_files": metadata.get("related_files", "").split(",") if metadata.get("related_files") else [],
                    "change_scale": metadata.get("change_scale"),
                    "file_types": metadata.get("file_extensions", "").split(",") if metadata.get("file_extensions") else [],
                    "total_changes": int(metadata.get("total_changes", "0")),
                    "complexity_level": metadata.get("complexity_level"),
                    "commit_pattern": metadata.get("commit_pattern"),
                    "priority_indicators": metadata.get("priority_indicators", "").split(",") if metadata.get("priority_indicators") else [],
                    "risk_level": metadata.get("risk_level"),
                    "business_impact": metadata.get("business_impact"),
                    "has_security_changes": metadata.get("has_security_changes") == "true",
                    "has_breaking_changes": metadata.get("has_breaking_changes") == "true",
                    "has_tests": metadata.get("has_tests") == "true",
                    "has_docs": metadata.get("has_docs") == "true",
                    "has_config": metadata.get("has_config") == "true",
                    "related_commits": metadata.get("related_commits"),
                    "similarity_score": float(match.score)
                })
            
            return patterns
            
        except Exception as e:
            logger.error(f"우선순위 패턴 검색 중 오류: {str(e)}")
            return []
    
    def delete_index(self, index_name: str):
        """인덱스 삭제 (개발/테스트용)"""
        try:
            self.pc.delete_index(index_name)
            logger.info(f"인덱스 {index_name}이 삭제되었습니다")
        except Exception as e:
            logger.error(f"인덱스 {index_name} 삭제 중 오류: {str(e)}")
    
    def get_index_stats(self, index_name: str) -> Dict[str, Any]:
        """인덱스 통계 조회"""
        try:
            index = self.pc.Index(index_name)
            stats = index.describe_index_stats()
            return {
                "total_vector_count": stats.total_vector_count,
                "dimension": stats.dimension,
                "index_fullness": stats.index_fullness,
                "namespaces": stats.namespaces
            }
        except Exception as e:
            logger.error(f"인덱스 {index_name} 통계 조회 중 오류: {str(e)}")
            return {"error": str(e)}

# 전역 인스턴스
vector_db = PineconeVectorDB()

"""
RAG 파이프라인 사용 예시:

# 1. PR 데이터 저장 (머지 완료 후)
await vector_db.store_pr_data("pr_123", pr_data)

# 1. 리뷰어 추천을 위한 패턴 검색 (PRData 입력)
reviewer_patterns = await vector_db.get_similar_reviewer_patterns(pr_data, limit=10)

# 2. 우선순위 추천을 위한 패턴 검색 (PRData 입력)
priority_patterns = await vector_db.get_similar_priority_patterns(pr_data, limit=5)
"""
