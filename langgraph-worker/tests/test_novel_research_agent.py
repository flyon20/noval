from __future__ import annotations

import asyncio
import unittest
from datetime import datetime, timedelta, timezone

from app.config import settings
from app.models.agent_task import TaskType, ToolPlan, ToolRun
from app.models.knowledge import (
    BookCandidate,
    BookProfile,
    BookResearchPack,
    ChapterMaterial,
    KnowledgeChatRequest,
    KnowledgeChatResponse,
    KnowledgeSource,
    RankLookupResult,
    RankResearchPack,
)
from app.services.intents.domain_intents import AnswerBoundary, Intent, IntentDecision, ToolNeeds
from app.services.novel_research_agent import NovelResearchAgent
from app.services.skills.registry import SkillRegistry


CURRENT_RANK_SNAPSHOT_TIME = datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _is_specialist_call(kwargs: dict) -> bool:
    messages = kwargs.get("messages") or []
    text = "\n".join(str(message.get("content") or "") for message in messages if isinstance(message, dict))
    return "agent:" in text and "task summary:" in text


def _specialist_response() -> dict:
    return {
        "model_name": "deepseek-chat",
        "content": "Specialist guidance for final answer.",
        "token_used": 8,
    }


class FakeKnowledgeClient:
    def __init__(self) -> None:
        self.search_books_calls: list[dict] = []
        self.search_evidence_calls: list[dict] = []

    async def search_books(self, *, platform: str, keyword: str, limit: int) -> list[BookCandidate]:
        self.search_books_calls.append({"platform": platform, "keyword": keyword, "limit": limit})
        return [
            BookCandidate(
                bookId=101,
                platform="fanqie",
                platformBookId="fq-101",
                bookName="星河旧梦",
                author="青灯",
                intro="主角在星际废墟中重建文明。",
                bookUrl="https://fanqie.example/page/101",
                local=False,
            )
        ]

    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        if book_id == 101:
            return [
                KnowledgeSource(
                    chunkId=1,
                    documentId=10,
                    score=0.91,
                    bookId=101,
                    bookName="星河旧梦",
                    platform="fanqie",
                    sourceType="chapter",
                    sourceRefId=1001,
                    chapterNo=3,
                    analysisType=None,
                    title="第3章 星门残响",
                    preview="主角通过旧星门获得第一个文明坐标。",
                )
            ]
        return []


class ProjectMemoryKnowledgeClient(FakeKnowledgeClient):
    def __init__(self) -> None:
        super().__init__()
        self.project_memory_calls: list[dict] = []

    async def get_project_memory(self, *, project_id: int, user_id: int) -> dict:
        self.project_memory_calls.append({"project_id": project_id, "user_id": user_id})
        return {
            "projectId": project_id,
            "userId": user_id,
            "memories": {
                "premise": "urban feedback ability",
                "constraint": "no harem",
            },
        }


class FakeAnswerProvider:
    def __init__(self) -> None:
        self.invoke_calls: list[dict] = []
        self.specialist_invoke_calls: list[dict] = []

    async def invoke(self, **kwargs) -> dict:
        if _is_specialist_call(kwargs):
            self.specialist_invoke_calls.append(kwargs)
            return _specialist_response()
        self.invoke_calls.append(kwargs)
        return {
            "model_name": "deepseek-chat",
            "content": "这本书的爽点主要来自旧星门坐标带来的目标推进。[1]",
            "token_used": 128,
        }


class ToolCallingProvider:
    def __init__(self) -> None:
        self.invoke_calls: list[dict] = []
        self.specialist_invoke_calls: list[dict] = []
        self.tool_call_emitted_scopes: set[str] = set()

    async def invoke(self, **kwargs) -> dict:
        scope = self._scope(kwargs.get("messages") or [])
        if scope == "specialist":
            self.specialist_invoke_calls.append(kwargs)
        else:
            self.invoke_calls.append(kwargs)
        if kwargs.get("tools") and scope not in self.tool_call_emitted_scopes:
            self.tool_call_emitted_scopes.add(scope)
            return {
                "model_name": "deepseek-v4-pro",
                "content": "",
                "reasoning_content": "Need latest rank evidence.",
                "tool_calls": [
                    {"id": f"call-rank-{scope}", "name": "rank.lookup", "arguments": {"platform": "fanqie"}},
                ],
                "token_used": 20,
            }
        return {
            "model_name": "deepseek-v4-pro",
            "content": "工具补证后回答。[1]",
            "token_used": 40,
        }


    def _scope(self, messages: list[dict]) -> str:
        return "specialist" if _is_specialist_call({"messages": messages}) else "answer"


class FakeMcpClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    async def list_tools(self) -> dict:
        return {
            "tools": [
                {
                    "name": "rank.lookup",
                    "description": "rank lookup",
                    "inputSchema": {"type": "object", "required": ["platform"]},
                }
            ]
        }

    async def call_tool(
        self,
        name: str,
        arguments: dict,
        timeout: float | None = None,
        route: str | None = None,
    ) -> dict:
        self.calls.append({"name": name, "arguments": arguments, "timeout": timeout, "route": route})
        return {"items": [{"rankNo": 1, "bookName": "榜一"}]}


class ToolLoopKnowledgeClient(FakeKnowledgeClient):
    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        return [
            KnowledgeSource(
                chunkId=501,
                documentId=601,
                score=0.95,
                bookId=701,
                bookName="榜一",
                platform="fanqie",
                sourceType="rank",
                rankNo=1,
                snapshotId=9101,
                snapshotTime=CURRENT_RANK_SNAPSHOT_TIME,
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="男频新书",
                boardName="都市脑洞",
                category="都市脑洞",
                title="榜单证据",
                preview="榜一证据",
            )
        ]


class FakeCompiledStreamGraph:
    def __init__(self, response: KnowledgeChatResponse) -> None:
        self.response = response
        self.calls: list[dict] = []

    async def astream(self, initial_state, *, config=None, stream_mode=None):
        self.calls.append({
            "initial_state": dict(initial_state),
            "config": config,
            "stream_mode": stream_mode,
        })
        yield {"assemble_context": {"context_bundle": initial_state.get("context_bundle")}}
        yield {"classify_intent": {"intent": "market_scan"}}
        yield {"finalize_trace": {"response": self.response}}


class BookResearchPackKnowledgeClient(FakeKnowledgeClient):
    def __init__(self) -> None:
        super().__init__()
        self.book_pack_calls: list[dict] = []

    async def get_book_research_pack(
        self,
        *,
        platform: str,
        book_id: int | None = None,
        book_name: str | None = None,
        chapter_limit: int = 3,
        analysis_limit: int = 3,
    ) -> BookResearchPack:
        self.book_pack_calls.append({
            "platform": platform,
            "book_id": book_id,
            "book_name": book_name,
            "chapter_limit": chapter_limit,
            "analysis_limit": analysis_limit,
        })
        return BookResearchPack(
            book=BookProfile(bookId=101, platform="fanqie", bookName="星河旧梦", author="青灯", intro="文明重建题材"),
            chapters=[
                ChapterMaterial(
                    chapterId=1001,
                    bookId=101,
                    bookName="星河旧梦",
                    platform="fanqie",
                    chapterNo=1,
                    title="第一章 星门坐标",
                    content="第一章用旧星门坐标制造目标钩子，主角获得文明重建的金手指。",
                )
            ],
        )

    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        return []


class RankResearchPackKnowledgeClient(FakeKnowledgeClient):
    def __init__(self) -> None:
        super().__init__()
        self.rank_pack_calls: list[dict] = []

    async def get_rank_research_pack(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
        chapter_limit_per_book: int = 1,
    ) -> RankResearchPack:
        self.rank_pack_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
            "chapter_limit_per_book": chapter_limit_per_book,
        })
        return RankResearchPack(
            ranks=[
            RankLookupResult(
                rankId=9901,
                snapshotId=9901,
                snapshotTime=CURRENT_RANK_SNAPSHOT_TIME,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                category="都市脑洞",
                    rankNo=1,
                    bookId=701,
                    bookName="长生两千年，被妹妹直播曝光",
                    author="青铜穹",
                    intro="长生者被现代直播曝光。",
                    sourceLabel="男频新书榜 / 都市脑洞 #1",
                ),
            RankLookupResult(
                rankId=9902,
                snapshotId=9901,
                snapshotTime=CURRENT_RANK_SNAPSHOT_TIME,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                category="都市脑洞",
                    rankNo=2,
                    bookId=702,
                    bookName="都市异常档案",
                    author="北窗",
                    intro="异常调查切入都市脑洞。",
                    sourceLabel="男频新书榜 / 都市脑洞 #2",
                ),
            ],
            books=[
                BookProfile(bookId=701, platform="fanqie", bookName="长生两千年，被妹妹直播曝光", author="青铜穹", intro="长生者被现代直播曝光。"),
                BookProfile(bookId=702, platform="fanqie", bookName="都市异常档案", author="北窗", intro="异常调查切入都市脑洞。"),
            ],
            chapters=[
                ChapterMaterial(
                    bookId=701,
                    bookName="长生两千年，被妹妹直播曝光",
                    platform="fanqie",
                    chapterNo=1,
                    title="第一章 直播曝光",
                    content="榜一作品用妹妹直播快速暴露长生身份，形成现代都市反差。",
                )
            ],
        )

    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        if source_type == "RANK":
            return []
        return [
            KnowledgeSource(
                chunkId=8801,
                documentId=880,
                score=0.7,
                bookId=801,
                bookName="向量补充样本",
                platform="fanqie",
                sourceType="INTRO",
                sourceRefId=801,
                title="向量补充样本 简介",
                preview="向量补充证据提供同题材辅助观察。",
            )
        ]


class UncitedAnswerProvider:
    def __init__(self) -> None:
        self.invoke_calls: list[dict] = []
        self.specialist_invoke_calls: list[dict] = []

    async def invoke(self, **kwargs) -> dict:
        if _is_specialist_call(kwargs):
            self.specialist_invoke_calls.append(kwargs)
            return _specialist_response()
        self.invoke_calls.append(kwargs)
        return {
            "model_name": "deepseek-chat",
            "content": "Urban brainhole stories are leaning on veteran identity and enlistment pressure as fast hooks.",
            "token_used": 96,
        }


class OutOfRangeCitationProvider:
    def __init__(self) -> None:
        self.invoke_calls: list[dict] = []
        self.specialist_invoke_calls: list[dict] = []

    async def invoke(self, **kwargs) -> dict:
        if _is_specialist_call(kwargs):
            self.specialist_invoke_calls.append(kwargs)
            return _specialist_response()
        self.invoke_calls.append(kwargs)
        return {
            "model_name": "deepseek-chat",
            "content": "This answer cites a source that does not exist. [9]",
            "token_used": 96,
        }


class ScriptedProvider:
    def __init__(self, responses: list[str]) -> None:
        self.responses = list(responses)
        self.invoke_calls: list[dict] = []
        self.specialist_invoke_calls: list[dict] = []

    async def invoke(self, **kwargs) -> dict:
        if _is_specialist_call(kwargs):
            self.specialist_invoke_calls.append(kwargs)
            return _specialist_response()
        self.invoke_calls.append(kwargs)
        return {
            "model_name": "deepseek-chat",
            "content": self.responses.pop(0),
            "token_used": 64,
        }


class StreamingProvider:
    def __init__(self, chunks: list[str]) -> None:
        self.chunks = list(chunks)
        self.stream_calls: list[dict] = []

    async def stream(self, **kwargs):
        self.stream_calls.append(kwargs)
        for chunk in self.chunks:
            yield {"event": "delta", "delta": chunk}


class EmptyStreamingFallbackProvider:
    def __init__(self, content: str) -> None:
        self.content = content
        self.stream_calls: list[dict] = []
        self.invoke_calls: list[dict] = []
        self.specialist_invoke_calls: list[dict] = []

    async def stream(self, **kwargs):
        self.stream_calls.append(kwargs)
        if False:
            yield {"event": "delta", "delta": ""}

    async def invoke(self, **kwargs) -> dict:
        if _is_specialist_call(kwargs):
            self.specialist_invoke_calls.append(kwargs)
            return _specialist_response()
        self.invoke_calls.append(kwargs)
        return {
            "model_name": "deepseek-chat",
            "content": self.content,
            "token_used": 128,
        }


class ContextFollowupProvider:
    def __init__(self) -> None:
        self.invoke_calls: list[dict] = []
        self.specialist_invoke_calls: list[dict] = []

    async def invoke(self, **kwargs) -> dict:
        if _is_specialist_call(kwargs):
            self.specialist_invoke_calls.append(kwargs)
            return _specialist_response()
        self.invoke_calls.append(kwargs)
        if kwargs.get("require_json"):
            return {
                "model_name": "deepseek-chat",
                "content": (
                    '{"inScope":true,"intent":"creative_advice","bookName":null,'
                    '"needsCreativeAdvice":true,"answerBoundary":"creative_inference"}'
                ),
                "token_used": 12,
            }
        return {
            "model_name": "deepseek-chat",
            "content": (
                "完整大纲：主角是底层特效外包工，诸天万界外包特效作为生产端，"
                "三端一体系统贯穿接单、执行和交付升级。"
            ),
            "token_used": 256,
        }


class FailingAfterPartialStreamProvider(EmptyStreamingFallbackProvider):
    async def stream(self, **kwargs):
        self.stream_calls.append(kwargs)
        yield {"event": "delta", "delta": "partial stale answer [1]"}
        raise RuntimeError("stream interrupted")


class MixedCreationRepairProvider:
    def __init__(self) -> None:
        self.invoke_calls: list[dict] = []
        self.specialist_invoke_calls: list[dict] = []
        self.answers = [
            "围绕榜一身份反差做同题材大纲。[1]",
            (
                "市场判断：当前都市脑洞仍吃现实职业压力、异常系统和可传播事件，底层职业切入可以成立。[1]\n\n"
                "核心钩子：主角是城市影棚特效外包小工，接到诸天万界外包特效单，现实里做五毛特效，"
                "镜像工单里却要调度修仙、机甲、妖族演员完成真实特效。\n\n"
                "三端一体：创作者端负责拆镜头和预算，诸天万界生产端负责外包执行，观众/交付端负责把特效反馈成现金、热度和系统权限。\n\n"
                "前三章：第一章用欠薪和临时工身份压出底层职业痛点，结尾收到第一张万界特效单；"
                "第二章让外包演员误以为自己在秘境打工，交付一个低成本但震撼的镜头；"
                "第三章公开视频引爆甲方和同行怀疑，主角拿到升级权限。\n\n"
                "十章方向：围绕小单试水、行业质疑、甲方复购、万界劳务纠纷、特效源文件审查逐步升级，避免只抄榜单表层。[1]"
            ),
        ]

    async def invoke(self, **kwargs) -> dict:
        if _is_specialist_call(kwargs):
            self.specialist_invoke_calls.append(kwargs)
            return _specialist_response()
        self.invoke_calls.append(kwargs)
        content = self.answers.pop(0) if self.answers else "合格修复答案。[1]"
        return {
            "model_name": "deepseek-chat",
            "content": content,
            "token_used": 96,
            "usage": {
                "promptTokens": 130,
                "completionTokens": 40,
                "totalTokens": 170,
                "promptCacheHitTokens": 90,
                "promptCacheMissTokens": 40,
            },
            "prompt_cache_hit_tokens": 90,
            "prompt_cache_miss_tokens": 40,
        }


class FailingAnswerProvider:
    def __init__(self) -> None:
        self.invoke_calls: list[dict] = []
        self.specialist_invoke_calls: list[dict] = []

    async def invoke(self, **kwargs) -> dict:
        if _is_specialist_call(kwargs):
            self.specialist_invoke_calls.append(kwargs)
            return _specialist_response()
        self.invoke_calls.append(kwargs)
        raise RuntimeError("provider unavailable")


class StreamingAnswerProvider:
    def __init__(self, chunks: list[str], done_event: dict | None = None) -> None:
        self.chunks = list(chunks)
        self.done_event = done_event
        self.stream_calls: list[dict] = []
        self.invoke_calls: list[dict] = []
        self.specialist_invoke_calls: list[dict] = []

    async def stream(self, **kwargs):
        self.stream_calls.append(kwargs)
        for chunk in self.chunks:
            yield {"event": "delta", "delta": chunk}
        if self.done_event:
            yield self.done_event

    async def invoke(self, **kwargs) -> dict:
        if _is_specialist_call(kwargs):
            self.specialist_invoke_calls.append(kwargs)
            return _specialist_response()
        self.invoke_calls.append(kwargs)
        return {
            "model_name": "deepseek-chat",
            "content": "".join(self.chunks),
            "token_used": 128,
        }


class ForcedMixedCreationRouter:
    def classify(self, *_args, **_kwargs) -> IntentDecision:
        return IntentDecision(
            primaryIntent=Intent.mixed_creation_research,
            subIntents=[Intent.market_scan, Intent.opening_strategy, Intent.outline_building],
            confidence=0.95,
            toolNeeds=ToolNeeds(needsRankData=True, needsCreativeGeneration=True, needsSkillPack=True),
            answerBoundary=AnswerBoundary.market_evidence_plus_author_inference,
            sourcePolicy={
                "freshness": "latest",
                "allowHistorical": False,
                "requireSnapshotTime": False,
            },
            routingNotes=["test:forced-mixed-creation"],
        )


class ConcurrencyProbeProvider:
    def __init__(self, content: str = "concurrent answer [1]") -> None:
        self.content = content
        self.active = 0
        self.max_active = 0

    async def invoke(self, **kwargs) -> dict:
        self.active += 1
        self.max_active = max(self.max_active, self.active)
        await asyncio.sleep(0.02)
        self.active -= 1
        return {
            "model_name": "deepseek-chat",
            "content": self.content,
            "token_used": 64,
        }


class FailingEvidenceKnowledgeClient(FakeKnowledgeClient):
    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        raise RuntimeError("embedding backend unavailable")


class IndexedGlobalEvidenceKnowledgeClient(FakeKnowledgeClient):
    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        if source_type == "RANK":
            return [
                KnowledgeSource(
                    chunkId=20,
                    documentId=12,
                    score=0.96,
                    bookId=102,
                    bookName="Current Urban Rank One",
                    platform="fanqie",
                    sourceType="RANK",
                    sourceRefId=9001,
                    snapshotId=9001,
                    snapshotTime=CURRENT_RANK_SNAPSHOT_TIME,
                    rankNo=1,
                    title="male-new / urban #1",
                    preview="Current urban rank evidence for market trend answers.",
                )
            ]
        if book_id is None:
            return [
                KnowledgeSource(
                    chunkId=2,
                    documentId=11,
                    score=0.93,
                    bookId=101,
                    bookName="星河旧梦",
                    platform="fanqie",
                    sourceType="chapter",
                    sourceRefId=1002,
                    chapterNo=1,
                    analysisType=None,
                    title="第1章 废墟坐标",
                    preview="星河旧梦开篇用废墟坐标制造探索目标。",
                )
            ]
        return await super().search_evidence(
            query=query,
            book_id=book_id,
            platform=platform,
            analysis_type=analysis_type,
            limit=limit,
            source_type=source_type,
        )


class DuplicateEvidenceKnowledgeClient(FakeKnowledgeClient):
    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        return [
            KnowledgeSource(
                chunkId=11,
                documentId=21,
                score=0.91,
                bookId=101,
                bookName="星河旧梦",
                platform="fanqie",
                sourceType="CHAPTER",
                sourceRefId=1001,
                chapterNo=1,
                title="第一章 星门坐标",
                preview="旧星门坐标出现，主角得到探索目标。",
            ),
            KnowledgeSource(
                chunkId=12,
                documentId=21,
                score=0.9,
                bookId=101,
                bookName="星河旧梦",
                platform="fanqie",
                sourceType="CHAPTER",
                sourceRefId=1001,
                chapterNo=1,
                title="第一章 星门坐标",
                preview="旧星门坐标再次出现，主角继续探索目标。",
            ),
            KnowledgeSource(
                chunkId=11,
                documentId=21,
                score=0.7,
                bookId=101,
                bookName="星河旧梦",
                platform="fanqie",
                sourceType="CHAPTER",
                sourceRefId=1001,
                chapterNo=1,
                title="第一章 星门坐标",
                preview="重复 chunk，不应再次进入上下文。",
            ),
            KnowledgeSource(
                chunkId=13,
                documentId=22,
                score=0.86,
                bookId=101,
                bookName="星河旧梦",
                platform="fanqie",
                sourceType="INTRO",
                sourceRefId=101,
                title="星河旧梦 简介",
                preview="文明重建题材，围绕旧星门和废墟坐标展开。",
            ),
        ]


class RankEvidenceKnowledgeClient(FakeKnowledgeClient):
    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        return [
            KnowledgeSource(
                chunkId=21,
                documentId=31,
                score=0.82,
                bookId=501,
                bookName="Other Intro Book",
                platform="fanqie",
                sourceType="INTRO",
                sourceRefId=501,
                title="Other Intro Book intro",
                preview="A generic urban brainhole intro without ranking facts.",
            ),
            KnowledgeSource(
                chunkId=22,
                documentId=32,
                score=0.78,
                bookId=502,
                bookName="入伍两次！我被原部队拉进黑名单",
                platform="fanqie",
                sourceType="RANK",
                sourceRefId=9001,
                snapshotId=9001,
                snapshotTime=CURRENT_RANK_SNAPSHOT_TIME,
                rankNo=1,
                title="男频新书榜 / 都市脑洞 #1",
                preview="榜单：男频新书榜 / 都市脑洞。排名：第1名。书名：入伍两次！我被原部队拉进黑名单。作者：朝朝和。",
            ),
        ]


class RankOnlyOnFilteredSearchKnowledgeClient(FakeKnowledgeClient):
    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        if source_type == "RANK":
            return [
                KnowledgeSource(
                    chunkId=32,
                    documentId=42,
                    score=0.74,
                    bookId=702,
                    bookName="入伍两次！我被原部队拉进黑名单",
                    platform="fanqie",
                    sourceType="RANK",
                    sourceRefId=9201,
                    snapshotId=9201,
                    snapshotTime=CURRENT_RANK_SNAPSHOT_TIME,
                    rankNo=1,
                    title="男频新书榜 / 都市脑洞 #1",
                    preview="榜单：男频新书榜 / 都市脑洞。排名：第1名。书名：入伍两次！我被原部队拉进黑名单。作者：朝朝和。",
                )
            ]
        return [
            KnowledgeSource(
                chunkId=31,
                documentId=41,
                score=0.91,
                bookId=701,
                bookName="泛都市脑洞简介书",
                platform="fanqie",
                sourceType="INTRO",
                sourceRefId=701,
                title="泛都市脑洞简介书 简介",
                preview="都市脑洞热门简介，但没有榜单排名事实。",
            )
        ]


class StructuredRankLookupKnowledgeClient(FakeKnowledgeClient):
    def __init__(self) -> None:
        super().__init__()
        self.lookup_rank_calls: list[dict] = []

    async def lookup_rank(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
        **kwargs,
    ) -> list[RankLookupResult]:
        self.lookup_rank_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
        })
        return [
            RankLookupResult(
                rankId=9001,
                snapshotId=10,
                snapshotTime="2026-05-10T00:00:00",
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="男频新书榜",
                boardName="都市脑洞",
                category="都市脑洞",
                rankNo=1,
                bookId=201,
                bookName="入伍两次！我被原部队拉进黑名单",
                author="朝朝和",
                intro="退伍入伍都市脑洞",
                sourceLabel="男频新书榜 / 都市脑洞 #1",
            )
        ]


class StructuredRankTrendKnowledgeClient(FakeKnowledgeClient):
    def __init__(self) -> None:
        super().__init__()
        self.lookup_rank_calls: list[dict] = []

    async def lookup_rank(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
    ) -> list[RankLookupResult]:
        self.lookup_rank_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
        })
        return [
            RankLookupResult(
                rankId=9101,
                snapshotId=9101,
                snapshotTime=CURRENT_RANK_SNAPSHOT_TIME,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="男频新书榜",
                boardName="都市脑洞",
                category="都市脑洞",
                rankNo=1,
                bookId=301,
                bookName="入伍两次！我被原部队拉进黑名单",
                author="朝朝和",
                intro="退伍入伍身份钩子",
                sourceLabel="男频新书榜 / 都市脑洞 #1",
            ),
            RankLookupResult(
                rankId=9102,
                snapshotId=9101,
                snapshotTime=CURRENT_RANK_SNAPSHOT_TIME,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="男频新书榜",
                boardName="都市脑洞",
                category="都市脑洞",
                rankNo=2,
                bookId=302,
                bookName="都市异常档案",
                author="北窗",
                intro="异常调查都市脑洞",
                sourceLabel="男频新书榜 / 都市脑洞 #2",
            ),
        ]

    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        if source_type == "RANK":
            return []
        return [
            KnowledgeSource(
                chunkId=51,
                documentId=61,
                score=0.83,
                bookId=303,
                bookName="都市脑洞样本",
                platform="fanqie",
                sourceType="INTRO",
                sourceRefId=303,
                title="都市脑洞样本 简介",
                preview="样本简介显示都市脑洞在身份反转和异常设定上高频出现。",
            )
        ]


class CurrentStructuredRankTrendKnowledgeClient(FakeKnowledgeClient):
    def __init__(self) -> None:
        super().__init__()
        self.lookup_rank_calls: list[dict] = []

    async def lookup_rank(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
    ) -> list[RankLookupResult]:
        self.lookup_rank_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
        })
        return [
            RankLookupResult(
                rankId=9301,
                snapshotId=9301,
                snapshotTime=CURRENT_RANK_SNAPSHOT_TIME,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="男频新书榜",
                boardName="都市脑洞",
                category="都市脑洞",
                rankNo=1,
                bookId=401,
                bookName="我下午才营业",
                author="我是幕后煮屎人",
                intro="原本做直播运营的范理压力大失眠迟到被公司开除，意外获得神级早餐系统。",
                sourceLabel="男频新书榜 / 都市脑洞 #1",
            ),
            RankLookupResult(
                rankId=9302,
                snapshotId=9301,
                snapshotTime=CURRENT_RANK_SNAPSHOT_TIME,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="男频新书榜",
                boardName="都市脑洞",
                category="都市脑洞",
                rankNo=2,
                bookId=402,
                bookName="长生两十六亿年，被妹妹首播曝光",
                author="军爷爱上大东北",
                intro="人在2026，刚活了46亿年，只想摆烂，被妹妹直播送上全球热搜。",
                sourceLabel="男频新书榜 / 都市脑洞 #2",
            ),
            RankLookupResult(
                rankId=9303,
                snapshotId=9301,
                snapshotTime=CURRENT_RANK_SNAPSHOT_TIME,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="男频新书榜",
                boardName="都市脑洞",
                category="都市脑洞",
                rankNo=3,
                bookId=403,
                bookName="归国留洋水货？叫我芯片之父！",
                author="这肉有毒",
                intro="新世纪青年穿越到1955年的归国留洋学生，走科技强国与学霸路线。",
                sourceLabel="男频新书榜 / 都市脑洞 #3",
            ),
            RankLookupResult(
                rankId=9324,
                snapshotId=9301,
                snapshotTime=CURRENT_RANK_SNAPSHOT_TIME,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="男频新书榜",
                boardName="都市脑洞",
                category="都市脑洞",
                rankNo=24,
                bookId=424,
                bookName="灵城：从货拉拉司机到万界之主",
                author="旧城",
                intro="货拉拉司机获得万界能力。",
                sourceLabel="男频新书榜 / 都市脑洞 #24",
            ),
        ]

    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        return [
            KnowledgeSource(
                chunkId=71,
                documentId=81,
                score=0.96,
                bookId=424,
                bookName="灵城：从货拉拉司机到万界之主",
                platform="fanqie",
                sourceType="INTRO",
                sourceRefId=402,
                title="灵城：从货拉拉司机到万界之主 简介",
                preview="低排名旧证据：货拉拉司机、万界系统。",
            )
        ]


class AliasChannelCurrentStructuredRankTrendKnowledgeClient(CurrentStructuredRankTrendKnowledgeClient):
    async def lookup_rank(self, **kwargs) -> list[RankLookupResult]:
        results = await super().lookup_rank(**kwargs)
        for result in results:
            result.channelCode = "male"
            result.channelName = "男频新书榜"
            result.sourceLabel = f"男频新书榜 / {result.boardName or result.category} #{result.rankNo}"
        return results


class BoardMatchedCategoryCurrentStructuredRankTrendKnowledgeClient(CurrentStructuredRankTrendKnowledgeClient):
    async def lookup_rank(self, **kwargs) -> list[RankLookupResult]:
        results = await super().lookup_rank(**kwargs)
        for result in results:
            result.category = "热门分类"
            result.boardName = "都市脑洞"
            result.sourceLabel = f"{result.channelName} / 都市脑洞 #{result.rankNo}"
        return results


class OldVectorOnlyTrendKnowledgeClient(FakeKnowledgeClient):
    def __init__(self) -> None:
        super().__init__()
        self.lookup_rank_calls: list[dict] = []
        self.refresh_rank_board_calls: list[dict] = []

    async def lookup_rank(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
    ) -> list[RankLookupResult]:
        self.lookup_rank_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
        })
        return []

    async def refresh_rank_board(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_fetch_count: int | None = None,
        refresh_mode: str | None = None,
        force_reason: str | None = None,
    ) -> dict:
        self.refresh_rank_board_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_fetch_count": rank_fetch_count,
            "refresh_mode": refresh_mode,
            "force_reason": force_reason,
        })
        return {"total": 0, "refreshLimited": False}

    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        if source_type == "RANK":
            return [
                KnowledgeSource(
                    chunkId=72,
                    documentId=82,
                    score=0.97,
                    bookId=424,
                    bookName="灵城：从货拉拉司机到万界之主",
                    platform="fanqie",
                    sourceType="RANK",
                    sourceRefId=9324,
                    rankNo=24,
                    title="男频新书榜 / 都市脑洞 #24",
                    preview="旧榜低排名材料，不能代表当前趋势。",
                )
            ]
        return [
            KnowledgeSource(
                chunkId=71,
                documentId=81,
                score=0.99,
                bookId=424,
                bookName="灵城：从货拉拉司机到万界之主",
                platform="fanqie",
                sourceType="INTRO",
                sourceRefId=402,
                title="灵城：从货拉拉司机到万界之主 简介",
                preview="旧向量材料：货拉拉司机、万界系统。",
            )
        ]


class MissingSnapshotStructuredRankTrendKnowledgeClient(StructuredRankTrendKnowledgeClient):
    async def lookup_rank(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
    ) -> list[RankLookupResult]:
        self.lookup_rank_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
        })
        return [
            RankLookupResult(
                rankId=9401,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="男频新书榜",
                boardName="都市脑洞",
                category="都市脑洞",
                rankNo=1,
                bookId=501,
                bookName="缺失快照榜首",
                author="测试作者",
                intro="没有 snapshotTime 的结构化榜单不能代表当前趋势。",
                sourceLabel="男频新书榜 / 都市脑洞 #1",
            )
        ]


class RefreshableSnapshotlessTopTenRankTrendKnowledgeClient(MissingSnapshotStructuredRankTrendKnowledgeClient):
    def __init__(self) -> None:
        super().__init__()
        self.rank_pack_calls: list[dict] = []
        self.refresh_rank_board_calls: list[dict] = []

    async def lookup_rank(self, **kwargs) -> list[RankLookupResult]:
        self.lookup_rank_calls.append({
            "platform": kwargs.get("platform"),
            "channel_code": kwargs.get("channel_code"),
            "board_code": kwargs.get("board_code"),
            "category": kwargs.get("category"),
            "rank_no": kwargs.get("rank_no"),
            "limit": kwargs.get("limit", 10),
        })
        return [
            RankLookupResult(
                rankId=9400 + index,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="male new books",
                boardName=kwargs.get("category") or "都市脑洞",
                category=kwargs.get("category") or "都市脑洞",
                rankNo=index,
                bookId=5400 + index,
                bookName=f"Snapshotless Rank Book {index}",
                author="Test Author",
                intro=f"rank sample {index} without snapshotTime",
                sourceLabel=f"male new books / urban brain #{index}",
            )
            for index in range(1, 11)
        ]

    async def get_rank_research_pack(self, **kwargs) -> RankResearchPack:
        self.rank_pack_calls.append(dict(kwargs))
        return RankResearchPack(ranks=await self.lookup_rank(**kwargs))

    async def refresh_rank_board(self, **kwargs) -> dict:
        self.refresh_rank_board_calls.append(dict(kwargs))
        return {
            "channelCode": kwargs.get("channel_code"),
            "boardCode": kwargs.get("board_code"),
            "snapshotId": 9901,
            "snapshotTime": CURRENT_RANK_SNAPSHOT_TIME,
            "total": 10,
            "reused": False,
            "refreshLimited": False,
        }


class SnapshotIdOnlyTopTenRankTrendKnowledgeClient(RefreshableSnapshotlessTopTenRankTrendKnowledgeClient):
    async def lookup_rank(self, **kwargs) -> list[RankLookupResult]:
        self.lookup_rank_calls.append({
            "platform": kwargs.get("platform"),
            "channel_code": kwargs.get("channel_code"),
            "board_code": kwargs.get("board_code"),
            "category": kwargs.get("category"),
            "rank_no": kwargs.get("rank_no"),
            "limit": kwargs.get("limit", 10),
        })
        return [
            RankLookupResult(
                rankId=9900 + index,
                snapshotId=9901,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="male new books",
                boardName=kwargs.get("category") or "都市脑洞",
                category=kwargs.get("category") or "都市脑洞",
                rankNo=index,
                bookId=5500 + index,
                bookName=f"Snapshot Id Rank Book {index}",
                author="Test Author",
                intro=f"rank sample {index} has snapshotId but no snapshotTime",
                sourceLabel=f"male new books / urban brain #{index}",
            )
            for index in range(1, 11)
        ]


class MixedSnapshotRankToolsKnowledgeClient(RefreshableSnapshotlessTopTenRankTrendKnowledgeClient):
    async def lookup_rank(self, **kwargs) -> list[RankLookupResult]:
        self.lookup_rank_calls.append({
            "platform": kwargs.get("platform"),
            "channel_code": kwargs.get("channel_code"),
            "board_code": kwargs.get("board_code"),
            "category": kwargs.get("category"),
            "rank_no": kwargs.get("rank_no"),
            "limit": kwargs.get("limit", 10),
        })
        return [
            RankLookupResult(
                rankId=9100 + index,
                snapshotId=9101,
                snapshotTime=CURRENT_RANK_SNAPSHOT_TIME,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="male new books",
                boardName=kwargs.get("category") or "都市脑洞",
                category=kwargs.get("category") or "都市脑洞",
                rankNo=index,
                bookId=6100 + index,
                bookName=f"Lookup Mixed Snapshot Book {index}",
                author="Lookup Author",
                intro=f"rank lookup mixed snapshot sample {index}",
                sourceLabel=f"male new books / urban brain #{index}",
            )
            for index in range(1, 11)
        ]

    async def get_rank_research_pack(self, **kwargs) -> RankResearchPack:
        self.rank_pack_calls.append(dict(kwargs))
        ranks = [
            RankLookupResult(
                rankId=9200 + index,
                snapshotId=9201,
                snapshotTime=CURRENT_RANK_SNAPSHOT_TIME,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="male new books",
                boardName=kwargs.get("category") or "都市脑洞",
                category=kwargs.get("category") or "都市脑洞",
                rankNo=index,
                bookId=6200 + index,
                bookName=f"Pack Mixed Snapshot Book {index}",
                author="Pack Author",
                intro=f"rank pack mixed snapshot sample {index}",
                sourceLabel=f"male new books / urban brain #{index}",
            )
            for index in range(1, 11)
        ]
        return RankResearchPack(
            ranks=ranks,
            books=[
                BookProfile(
                    bookId=rank.bookId,
                    platform=rank.platform,
                    bookName=rank.bookName,
                    author=rank.author,
                    intro=rank.intro,
                )
                for rank in ranks
            ],
        )


class LookupOnlySnapshotlessTopTenRankTrendKnowledgeClient(MissingSnapshotStructuredRankTrendKnowledgeClient):
    async def lookup_rank(self, **kwargs) -> list[RankLookupResult]:
        limit = max(1, int(kwargs.get("limit", 10) or 10))
        self.lookup_rank_calls.append({
            "platform": kwargs.get("platform"),
            "channel_code": kwargs.get("channel_code"),
            "board_code": kwargs.get("board_code"),
            "category": kwargs.get("category"),
            "rank_no": kwargs.get("rank_no"),
            "limit": limit,
        })
        return [
            RankLookupResult(
                rankId=9800 + index,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="male new books",
                boardName=kwargs.get("category") or "閮藉競鑴戞礊",
                category=kwargs.get("category") or "閮藉競鑴戞礊",
                rankNo=index,
                bookId=5800 + index,
                bookName=f"Lookup Only Snapshotless Rank Book {index}",
                author="Test Author",
                intro=f"rank lookup sample {index} has no snapshot metadata",
                sourceLabel=f"male new books / urban brain #{index}",
            )
            for index in range(1, limit + 1)
        ]


class RetryableMissingSnapshotTrendKnowledgeClient(StructuredRankTrendKnowledgeClient):
    async def lookup_rank(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
    ) -> list[RankLookupResult]:
        self.lookup_rank_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
        })
        if len(self.lookup_rank_calls) == 1:
            return [
                RankLookupResult(
                    rankId=9501,
                    platform="fanqie",
                    channelCode="male-new",
                    boardCode="urban-brain",
                    channelName="male new books",
                    boardName=category or "都市脑洞",
                    category=category or "都市脑洞",
                    rankNo=1,
                    bookId=501,
                    bookName="Missing Snapshot Leader",
                    author="Test Author",
                    intro="first attempt has no snapshot time",
                    sourceLabel="male new books / urban brain #1",
                )
            ]
        return [
            RankLookupResult(
                rankId=9502,
                snapshotId=9502,
                snapshotTime=CURRENT_RANK_SNAPSHOT_TIME,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="male new books",
                boardName=category or "都市脑洞",
                category=category or "都市脑洞",
                rankNo=1,
                bookId=502,
                bookName="Fresh Snapshot Leader",
                author="Test Author",
                intro="second attempt has fresh snapshot time",
                sourceLabel="male new books / urban brain #1",
            )
        ]


class GovernanceStructuredRankTrendKnowledgeClient(StructuredRankTrendKnowledgeClient):
    def __init__(self) -> None:
        super().__init__()
        self.runtime_config_calls = 0
        self.expert_profile_calls = 0

    async def get_agent_runtime_config(self) -> dict:
        self.runtime_config_calls += 1
        return {
            "reasoningModeDefault": "fast",
            "maxParallelSpecialists": 2,
            "maxEvidenceItems": 30,
        }

    async def get_agent_expert_profiles(self) -> list[dict]:
        self.expert_profile_calls += 1
        return [
            {
                "expertName": "market_scan",
                "enabled": False,
            }
        ]


class RuntimePolicyGovernanceKnowledgeClient(CurrentStructuredRankTrendKnowledgeClient):
    def __init__(self) -> None:
        super().__init__()
        self.runtime_config_calls = 0
        self.expert_profile_calls = 0
        self.runtime_skill_calls = 0
        self.telemetry_calls: list[dict] = []
        self.search_evidence_limits: list[int] = []

    async def get_agent_runtime_config(self) -> dict:
        self.runtime_config_calls += 1
        return {
            "reasoningModeDefault": "fast",
            "maxParallelSpecialists": 1,
            "maxSkillPromptChars": 80,
            "maxEvidenceItems": 1,
            "enableIntentCache": False,
            "enableTaskGraphCache": False,
            "enableToolCache": False,
            "enableEvidenceCache": False,
            "enableSpecialistCache": False,
        }

    async def get_agent_expert_profiles(self) -> list[dict]:
        self.expert_profile_calls += 1
        return [
            {
                "expertName": "market_scan",
                "enabled": True,
                "maxToolCalls": 1,
                "allowedTools": ["rank.lookup", "rank.research_pack", "knowledge.vector_search"],
            }
        ]

    async def get_runtime_skills(self) -> list[dict]:
        self.runtime_skill_calls += 1
        return [
            {
                "skillId": "webnovel-market-scan",
                "version": 7,
                "status": "PUBLISHED",
                "title": "Backend Published Market Scan",
                "content": (
                    "BACKEND PUBLISHED PROMPT "
                    "with a deliberately long policy body that must be truncated "
                    "before it reaches the final answer prompt."
                ),
                "intents": ["market_scan"],
                "triggers": ["trend", "rank", "market"],
                "allowedTools": ["rank.lookup", "rank.research_pack", "knowledge.vector_search"],
                "requiredEvidence": ["current_structured_rank_topn"],
            }
        ]

    async def lookup_rank(self, **kwargs) -> list[RankLookupResult]:
        return await super().lookup_rank(
            platform=kwargs.get("platform") or "fanqie",
            channel_code=kwargs.get("channel_code"),
            board_code=kwargs.get("board_code"),
            category=kwargs.get("category"),
            rank_no=kwargs.get("rank_no"),
            limit=kwargs.get("limit") or 10,
        )

    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_limits.append(limit)
        return await super().search_evidence(
            query=query,
            book_id=book_id,
            platform=platform,
            analysis_type=analysis_type,
            limit=limit,
            source_type=source_type,
        )

    async def post_agent_telemetry(
        self,
        *,
        trace_id: str,
        cache_events: list[dict],
        token_metrics: list[dict],
    ) -> dict:
        self.telemetry_calls.append({
            "traceId": trace_id,
            "cacheEvents": cache_events,
            "tokenMetrics": token_metrics,
        })
        return {"accepted": True}


class StreamFinalizationKnowledgeClient(RuntimePolicyGovernanceKnowledgeClient):
    def __init__(self) -> None:
        super().__init__()
        self.memory_candidate_calls: list[dict] = []

    async def create_memory_candidate(self, **kwargs) -> dict:
        self.memory_candidate_calls.append(kwargs)
        return {"id": len(self.memory_candidate_calls)}


class RetryableStaleSnapshotTrendKnowledgeClient(StructuredRankTrendKnowledgeClient):
    def __init__(self) -> None:
        super().__init__()
        now = datetime.now(timezone.utc)
        self.stale_snapshot_time = (now - timedelta(days=5)).replace(microsecond=0).isoformat()
        self.fresh_snapshot_time = now.replace(microsecond=0).isoformat()

    async def lookup_rank(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
        **kwargs,
    ) -> list[RankLookupResult]:
        self.lookup_rank_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
        })
        snapshot_time = self.stale_snapshot_time if len(self.lookup_rank_calls) == 1 else self.fresh_snapshot_time
        book_id = 9601 if len(self.lookup_rank_calls) == 1 else 9602
        book_name = "Stale Snapshot Leader" if len(self.lookup_rank_calls) == 1 else "Fresh Snapshot Leader"
        return [
            RankLookupResult(
                rankId=book_id,
                snapshotId=book_id,
                snapshotTime=snapshot_time,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="male new books",
                boardName=category or "都市脑洞",
                category=category or "都市脑洞",
                rankNo=1,
                bookId=book_id,
                bookName=book_name,
                author="Test Author",
                intro=f"{book_name} snapshot {snapshot_time}",
                sourceLabel="male new books / urban brain #1",
            )
        ]


class RefreshDrivenStaleSnapshotTrendKnowledgeClient(RetryableStaleSnapshotTrendKnowledgeClient):
    def __init__(self) -> None:
        super().__init__()
        self.refresh_rank_board_calls: list[dict] = []

    async def lookup_rank(self, **kwargs) -> list[RankLookupResult]:
        self.lookup_rank_calls.append({
            "platform": kwargs.get("platform"),
            "channel_code": kwargs.get("channel_code"),
            "board_code": kwargs.get("board_code"),
            "category": kwargs.get("category"),
            "rank_no": kwargs.get("rank_no"),
            "limit": kwargs.get("limit", 10),
        })
        refreshed = bool(self.refresh_rank_board_calls)
        snapshot_time = self.fresh_snapshot_time if refreshed else self.stale_snapshot_time
        book_id = 9702 if refreshed else 9701
        book_name = "Fresh After Refresh Leader" if refreshed else "Stale Until Refresh Leader"
        return [
            RankLookupResult(
                rankId=book_id,
                snapshotId=book_id,
                snapshotTime=snapshot_time,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="male new books",
                boardName=kwargs.get("category") or "都市脑洞",
                category=kwargs.get("category") or "都市脑洞",
                rankNo=1,
                bookId=book_id,
                bookName=book_name,
                author="Test Author",
                intro=f"{book_name} snapshot {snapshot_time}",
                sourceLabel="male new books / urban brain #1",
            )
        ]

    async def refresh_rank_board(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_fetch_count: int | None = None,
        refresh_mode: str | None = None,
        force_reason: str | None = None,
    ) -> dict:
        self.refresh_rank_board_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_fetch_count": rank_fetch_count,
            "refresh_mode": refresh_mode,
            "force_reason": force_reason,
        })
        return {
            "channelCode": channel_code,
            "boardCode": board_code,
            "snapshotId": 9702,
            "snapshotTime": self.fresh_snapshot_time,
            "total": 10,
            "reused": False,
            "refreshLimited": False,
        }


class RefreshDrivenMissingTopRankTrendKnowledgeClient(RefreshDrivenStaleSnapshotTrendKnowledgeClient):
    async def lookup_rank(self, **kwargs) -> list[RankLookupResult]:
        self.lookup_rank_calls.append({
            "platform": kwargs.get("platform"),
            "channel_code": kwargs.get("channel_code"),
            "board_code": kwargs.get("board_code"),
            "category": kwargs.get("category"),
            "rank_no": kwargs.get("rank_no"),
            "limit": kwargs.get("limit", 10),
        })
        if not self.refresh_rank_board_calls:
            return []
        return [
            RankLookupResult(
                rankId=9802,
                snapshotId=9802,
                snapshotTime=self.fresh_snapshot_time,
                platform="fanqie",
                channelCode="male-new",
                boardCode="urban-brain",
                channelName="male new books",
                boardName=kwargs.get("category") or "都市脑洞",
                category=kwargs.get("category") or "都市脑洞",
                rankNo=1,
                bookId=9802,
                bookName="Fresh After Empty Refresh Leader",
                author="Test Author",
                intro="fresh result appears only after refresh",
                sourceLabel="male new books / urban brain #1",
            )
        ]


class SlowVectorCurrentRankKnowledgeClient(CurrentStructuredRankTrendKnowledgeClient):
    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        await asyncio.sleep(3600)
        return []


class SlowRankPackCurrentRankKnowledgeClient(CurrentStructuredRankTrendKnowledgeClient):
    def __init__(self) -> None:
        super().__init__()
        self.rank_pack_calls: list[dict] = []

    async def get_rank_research_pack(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
        chapter_limit_per_book: int = 1,
    ) -> RankResearchPack:
        self.rank_pack_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
            "chapter_limit_per_book": chapter_limit_per_book,
        })
        await asyncio.sleep(3600)
        return RankResearchPack()


class TrackingRankPackCurrentRankKnowledgeClient(CurrentStructuredRankTrendKnowledgeClient):
    def __init__(self) -> None:
        super().__init__()
        self.rank_pack_calls: list[dict] = []

    async def get_rank_research_pack(
        self,
        *,
        platform: str,
        channel_code: str | None = None,
        board_code: str | None = None,
        category: str | None = None,
        rank_no: int | None = None,
        limit: int = 10,
        chapter_limit_per_book: int = 1,
    ) -> RankResearchPack:
        self.rank_pack_calls.append({
            "platform": platform,
            "channel_code": channel_code,
            "board_code": board_code,
            "category": category,
            "rank_no": rank_no,
            "limit": limit,
            "chapter_limit_per_book": chapter_limit_per_book,
        })
        return RankResearchPack()


class RankOnlySpecificBookKnowledgeClient(FakeKnowledgeClient):
    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        return [
            KnowledgeSource(
                chunkId=91,
                documentId=91,
                score=0.95,
                bookId=401,
                bookName="长生两十六亿年，被妹妹首播曝光",
                platform="fanqie",
                sourceType="RANK",
                sourceRefId=9301,
                rankNo=1,
                title="男频新书榜 / 都市脑洞 #1",
                preview="榜单简介片段，不包含前三章正文。",
            )
        ]


class RankThenChapterSpecificBookKnowledgeClient(FakeKnowledgeClient):
    async def search_books(self, *, platform: str, keyword: str, limit: int) -> list[BookCandidate]:
        self.search_books_calls.append({"platform": platform, "keyword": keyword, "limit": limit})
        return [
            BookCandidate(
                bookId=401,
                platform="fanqie",
                platformBookId="fq-401",
                bookName="长生两十六亿年，被妹妹首播曝光",
                author="青铜穗",
                intro="长生者被妹妹直播曝光，引发现代都市身份反差。",
                bookUrl="https://fanqie.example/page/401",
                local=True,
            )
        ]

    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        if source_type == "CHAPTER":
            return [
                KnowledgeSource(
                    chunkId=92,
                    documentId=92,
                    score=0.93,
                    bookId=401,
                    bookName="长生两十六亿年，被妹妹首播曝光",
                    platform="fanqie",
                    sourceType="CHAPTER",
                    sourceRefId=40101,
                    chapterNo=1,
                    title="第1章 直播曝光",
                    preview="妹妹直播时拍到主角收藏的古物，弹幕质疑其真实身份。",
                )
            ]
        return [
            KnowledgeSource(
                chunkId=91,
                documentId=91,
                score=0.95,
                bookId=401,
                bookName="长生两十六亿年，被妹妹首播曝光",
                platform="fanqie",
                sourceType="RANK",
                sourceRefId=9301,
                rankNo=1,
                title="男频新书榜 / 都市脑洞 #1",
                preview="榜单简介片段，不包含前三章正文。",
            )
        ]


class MismatchedGlobalEvidenceKnowledgeClient(FakeKnowledgeClient):
    async def search_evidence(
        self,
        *,
        query: str,
        book_id: int | None,
        platform: str | None,
        analysis_type: str | None,
        limit: int,
        source_type: str | None = None,
    ) -> list[KnowledgeSource]:
        self.search_evidence_calls.append({
            "query": query,
            "book_id": book_id,
            "platform": platform,
            "analysis_type": analysis_type,
            "limit": limit,
            "source_type": source_type,
        })
        if book_id is None:
            return [
                KnowledgeSource(
                    chunkId=3,
                    documentId=12,
                    score=0.95,
                    bookId=303,
                    bookName="县城花钱日记",
                    platform="fanqie",
                    sourceType="chapter",
                    sourceRefId=1003,
                    chapterNo=1,
                    analysisType=None,
                    title="第1章 到账",
                    preview="主角获得每天六千万的消费任务。",
                )
            ]
        return []


class NovelResearchAgentTest(unittest.IsolatedAsyncioTestCase):
    async def test_should_return_candidates_when_question_has_book_name_without_book_id(self) -> None:
        client = FakeKnowledgeClient()
        agent = NovelResearchAgent(knowledge_client=client)
        request = KnowledgeChatRequest(
            question="分析《星河旧梦》的开篇卖点",
            bookName="星河旧梦",
            mode="research",
            limits={"candidateLimit": 3},
        )

        response = await agent.run(request)

        self.assertEqual("candidates_required", response.status)
        self.assertEqual("星河旧梦", client.search_books_calls[0]["keyword"])
        self.assertEqual(1, len(response.candidates))
        self.assertIn("请选择", response.answer)
        self.assertIn("select_candidate", response.actions)

    async def test_should_search_book_for_plain_single_book_query_without_book_name_field(self) -> None:
        client = FakeKnowledgeClient()
        agent = NovelResearchAgent(knowledge_client=client)
        request = KnowledgeChatRequest(
            question="凡人修仙传开篇卖点是什么",
            mode="research",
            limits={"candidateLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("candidates_required", response.status)
        self.assertEqual("凡人修仙传", client.search_books_calls[0]["keyword"])
        self.assertEqual(1, len(response.candidates))
        self.assertIn("select_candidate", response.actions)

    async def test_should_search_global_evidence_before_book_search_for_inferred_book_name(self) -> None:
        client = IndexedGlobalEvidenceKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="星河旧梦开篇卖点是什么",
            mode="research",
            limits={"candidateLimit": 5, "evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual([], client.search_books_calls)
        self.assertEqual(1, len(client.search_evidence_calls))
        self.assertIsNone(client.search_evidence_calls[0]["book_id"])
        self.assertEqual(1, len(response.sources))
        self.assertIn("[1]", response.answer)

    async def test_should_search_book_when_global_evidence_matches_different_book(self) -> None:
        client = MismatchedGlobalEvidenceKnowledgeClient()
        agent = NovelResearchAgent(knowledge_client=client)
        request = KnowledgeChatRequest(
            question="星河旧梦开篇卖点是什么",
            mode="research",
            limits={"candidateLimit": 5, "evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("candidates_required", response.status)
        self.assertEqual(1, len(client.search_evidence_calls))
        self.assertEqual(1, len(client.search_books_calls))
        self.assertEqual("星河旧梦", client.search_books_calls[0]["keyword"])
        self.assertEqual([], response.sources)
        self.assertIn("select_candidate", response.actions)

    async def test_should_use_existing_vector_evidence_for_trend_question_without_book_search(self) -> None:
        client = FakeKnowledgeClient()
        agent = NovelResearchAgent(knowledge_client=client)
        request = KnowledgeChatRequest(
            question="最近男频题材趋势是什么",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("insufficient_evidence", response.status)
        self.assertEqual("needs_required_evidence", response.resultJson["answerStatus"], response.resultJson)
        self.assertEqual([], client.search_books_calls)
        self.assertEqual(2, len(client.search_evidence_calls))
        self.assertIsNone(client.search_evidence_calls[0]["book_id"])
        self.assertEqual("RANK", client.search_evidence_calls[1]["source_type"])

    async def test_trend_question_should_ignore_stale_book_name_context(self) -> None:
        client = StructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="最近男频题材趋势是什么",
            bookName="凡人修仙传",
            mode="research",
            contextSummary="当前作品：凡人修仙传。上一轮做过单书分析。",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual("trend_research", response.resultJson["intent"])
        self.assertEqual("market_scan", response.resultJson["businessRoute"])
        self.assertEqual("trend", response.resultJson["answerMode"])
        self.assertIsNone(response.resultJson["bookName"])
        self.assertEqual([], client.search_books_calls)
        self.assertEqual(1, len(client.lookup_rank_calls))
        self.assertIsNone(client.lookup_rank_calls[0]["rank_no"])
        self.assertTrue(all(call["book_id"] is None for call in client.search_evidence_calls))

    async def test_trend_question_should_reject_old_vector_evidence_without_current_rank_topn(self) -> None:
        client = OldVectorOnlyTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="最近男频都市脑洞题材趋势是什么？",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("insufficient_evidence", response.status)
        self.assertEqual("needs_required_evidence", response.resultJson["answerStatus"], response.resultJson)
        self.assertEqual("needs_more_data", response.resultJson["answerBoundary"])
        self.assertEqual("trend_research", response.resultJson["intent"])
        self.assertIn("refresh_rank_board", response.actions)
        self.assertEqual([], provider.invoke_calls)
        self.assertEqual(1, len(client.refresh_rank_board_calls))
        self.assertEqual(2, len(client.lookup_rank_calls))
        self.assertEqual({"market_refresh": 1}, response.resultJson["retryCounts"])
        self.assertTrue(response.resultJson["sourcePolicy"]["trendGateFailed"])

    async def test_should_request_clarification_for_ambiguous_domain_intent(self) -> None:
        class AmbiguousRouter:
            def classify(self, *_args, **_kwargs) -> IntentDecision:
                return IntentDecision(
                    primaryIntent=Intent.followup_context,
                    confidence=0.52,
                    toolNeeds=ToolNeeds(needsRankData=True, needsCreativeGeneration=True),
                    answerBoundary=AnswerBoundary.needs_more_data,
                    routingNotes=["rule:ambiguous-intent"],
                )

        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        agent.intent_router = AmbiguousRouter()
        request = KnowledgeChatRequest(
            question="最近趋势和开头都想看一下",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("needs_clarification", response.status)
        self.assertEqual("needs_clarification", response.resultJson["businessRoute"])
        self.assertEqual("needs_data", response.resultJson["answerStatus"])
        self.assertEqual("needs_more_data", response.resultJson["answerBoundary"])
        self.assertIn("clarify_intent", response.actions)
        self.assertEqual([], provider.invoke_calls)

    async def test_opening_strategy_intent_uses_project_creation_business_route(self) -> None:
        class OpeningRouter:
            def classify(self, *_args, **_kwargs) -> IntentDecision:
                return IntentDecision(
                    primaryIntent=Intent.opening_strategy,
                    confidence=0.91,
                    toolNeeds=ToolNeeds(needsCreativeGeneration=True),
                    answerBoundary=AnswerBoundary.creative_inference,
                    routingNotes=["test:opening"],
                )

        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        agent.intent_router = OpeningRouter()

        response = await agent.run(KnowledgeChatRequest(question="help me design a new urban premise"))

        self.assertEqual("answered", response.status, response.resultJson)
        self.assertEqual("project_creation", response.resultJson["businessRoute"])
        self.assertEqual("project_creation", response.resultJson["trace"]["businessRoute"])

    async def test_request_model_name_overrides_provider_model_for_answer_generation(self) -> None:
        class OpeningRouter:
            def classify(self, *_args, **_kwargs) -> IntentDecision:
                return IntentDecision(
                    primaryIntent=Intent.opening_strategy,
                    confidence=0.91,
                    toolNeeds=ToolNeeds(needsCreativeGeneration=True),
                    answerBoundary=AnswerBoundary.creative_inference,
                    routingNotes=["test:opening"],
                )

        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        agent.intent_router = OpeningRouter()

        response = await agent.run(
            KnowledgeChatRequest(
                question="help me design a new urban premise",
                limits={"modelName": "deepseek-eval"},
            )
        )

        self.assertEqual("answered", response.status)
        self.assertGreaterEqual(len(provider.invoke_calls), 1)
        self.assertTrue(all(call["model"] == "deepseek-eval" for call in provider.invoke_calls))

    async def test_followup_context_intent_uses_followup_revision_business_route(self) -> None:
        class FollowupRouter:
            def classify(self, *_args, **_kwargs) -> IntentDecision:
                return IntentDecision(
                    primaryIntent=Intent.followup_context,
                    confidence=0.9,
                    toolNeeds=ToolNeeds(needsCreativeGeneration=True),
                    answerBoundary=AnswerBoundary.creative_inference,
                    routingNotes=["test:followup"],
                )

        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        agent.intent_router = FollowupRouter()

        response = await agent.run(
            KnowledgeChatRequest(
                question="revise the current project opening",
                projectId=900,
                userId=7,
                contextSummary="current project: urban feedback ability",
            )
        )

        self.assertEqual("answered", response.status)
        self.assertEqual("followup_revision", response.resultJson["businessRoute"])
        self.assertEqual("followup_revision", response.resultJson["trace"]["businessRoute"])

    async def test_short_outline_followup_uses_prior_thread_context_instead_of_candidate_selection(self) -> None:
        provider = ContextFollowupProvider()
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient(), provider_client=provider)

        response = await agent.run(
            KnowledgeChatRequest(
                question="给出完整的大纲设计",
                conversationId="conv-followup-outline",
                contextSummary=(
                    "最近用户目标：写一篇底层职业的都市脑洞文，都市里有诸天万界外包来做特效，"
                    "金手指采用三端一体的形态。"
                ),
                history=[
                    {
                        "role": "user",
                        "content": (
                            "现在我要写一篇底层职业的都市脑洞文，都市里有诸天万界外包来做特效，"
                            "金手指采用三端一体。"
                        ),
                    },
                    {
                        "role": "assistant",
                        "content": "上一轮已经确定底层特效外包工、诸天万界生产端和三端一体升级闭环。",
                    },
                ],
                mode="research",
                limits={"maxInputTokens": 1_000_000, "evidenceLimit": 5},
            )
        )

        self.assertEqual("answered", response.status, response.resultJson)
        self.assertNotIn("select_candidate", response.actions)
        answer_calls = [
            call for call in provider.invoke_calls
            if not call.get("require_json") and not _is_specialist_call(call)
        ]
        self.assertGreaterEqual(len(answer_calls), 1)
        prompt_text = "\n".join(
            str(message.get("content") or "")
            for message in answer_calls[-1]["messages"]
            if isinstance(message, dict)
        )
        self.assertIn("诸天万界外包", prompt_text)
        self.assertIn("三端一体", prompt_text)
        self.assertIn("诸天万界外包特效", response.answer)
        self.assertEqual("succeeded", response.resultJson["trace"]["health"]["model"])

    async def test_e2e_project_question_injects_only_current_project_memory(self) -> None:
        client = ProjectMemoryKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)

        response = await agent.run(
            KnowledgeChatRequest(
                question="帮我开一本都市脑洞新书",
                projectId=900,
                userId=7,
                mode="research",
            )
        )

        self.assertEqual("answered", response.status)
        self.assertEqual([{"project_id": 900, "user_id": 7}], client.project_memory_calls)
        context_used = response.resultJson["trace"]["contextUsed"]
        self.assertEqual(900, context_used["projectId"])
        self.assertEqual(["constraint", "premise"], context_used["projectMemoryKeys"])
        self.assertEqual(["ai_project_memory"], context_used["projectMemorySourceIds"])

    async def test_e2e_response_exposes_context_budget_and_memory_layers(self) -> None:
        client = ProjectMemoryKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)

        response = await agent.run(
            KnowledgeChatRequest(
                question="continue the urban outsourcing outline",
                projectId=900,
                userId=7,
                conversationId="conv-budget",
                contextSummary="Previous turn locked the three-terminal goldfinger.",
                history=[
                    {"role": "user", "content": "I want a bottom-occupation urban brain-hole story."},
                    {"role": "assistant", "content": "Use a visible order-delivery-payment loop."},
                ],
                mode="research",
            )
        )

        budget = response.resultJson["contextBudget"]
        self.assertGreater(budget["estimatedUsedTokens"], 0)
        self.assertGreater(budget["remainingTokens"], 0)
        self.assertIn("projectProfile", budget["memoryLayers"])
        self.assertEqual("loaded", budget["memoryLayers"]["projectProfile"]["status"])
        self.assertEqual(["premise", "constraint"], budget["memoryLayers"]["projectProfile"]["keys"])
        self.assertEqual(budget, response.resultJson["trace"]["contextBudget"])

    async def test_e2e_unrelated_new_conversation_does_not_inject_project_memory(self) -> None:
        client = ProjectMemoryKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)

        response = await agent.run(
            KnowledgeChatRequest(
                question="帮我开一本玄幻升级流新书",
                userId=7,
                mode="research",
            )
        )

        self.assertEqual("answered", response.status)
        self.assertEqual([], client.project_memory_calls)
        context_used = response.resultJson["trace"]["contextUsed"]
        self.assertIsNone(context_used["projectId"])
        self.assertEqual([], context_used["projectMemoryKeys"])

    async def test_followup_revision_without_context_is_stopped_by_supervisor(self) -> None:
        class FollowupRouter:
            def classify(self, *_args, **_kwargs) -> IntentDecision:
                return IntentDecision(
                    primaryIntent=Intent.followup_context,
                    confidence=0.9,
                    toolNeeds=ToolNeeds(needsCreativeGeneration=True),
                    answerBoundary=AnswerBoundary.creative_inference,
                    routingNotes=["test:followup"],
                )

        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        agent.intent_router = FollowupRouter()

        response = await agent.run(KnowledgeChatRequest(question="revise it again"))

        self.assertEqual("needs_clarification", response.status)
        self.assertEqual("needs_clarification", response.resultJson["businessRoute"])
        self.assertEqual("needs_clarification", response.resultJson["supervisorDecision"]["status"])
        self.assertTrue(all(call.get("require_json") for call in provider.invoke_calls))
        self.assertEqual([], client.search_evidence_calls)

    async def test_supervisor_retries_latest_rank_once_before_answering(self) -> None:
        client = RetryableMissingSnapshotTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)

        response = await agent.run(KnowledgeChatRequest(
            question="最近男频都市脑洞新书榜趋势是什么？",
            mode="research",
        ))

        self.assertEqual("answered", response.status)
        self.assertEqual(2, len(client.lookup_rank_calls))
        self.assertEqual("answerable", response.resultJson["supervisorDecision"]["status"])
        self.assertEqual({"market_refresh": 1}, response.resultJson["retryCounts"])
        self.assertEqual(CURRENT_RANK_SNAPSHOT_TIME, response.sources[0].snapshotTime)

    async def test_scan_board_opening_advice_retries_stale_rank_before_answering(self) -> None:
        client = RetryableStaleSnapshotTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)

        response = await agent.run(KnowledgeChatRequest(
            question=(
                "你帮我扫榜男频都市脑洞，给我些开文建议，还有目前都是哪些题材，"
                "我太久没看了不太清楚，我现在打算开书推荐写那种题材"
            ),
            mode="research",
        ))

        self.assertEqual("answered", response.status, response.resultJson)
        self.assertEqual(2, len(client.lookup_rank_calls))
        self.assertEqual("mixed_creation_research", response.resultJson["domainIntent"])
        self.assertEqual("answerable", response.resultJson["supervisorDecision"]["status"])
        self.assertEqual({"market_refresh": 1}, response.resultJson["retryCounts"])
        self.assertEqual(client.fresh_snapshot_time, response.sources[0].snapshotTime)
        self.assertNotEqual(client.stale_snapshot_time, response.sources[0].snapshotTime)
        self.assertFalse(response.resultJson["sourcePolicy"]["trendGateFailed"])

    async def test_scan_board_opening_advice_refreshes_stale_rank_before_retrying(self) -> None:
        client = RefreshDrivenStaleSnapshotTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)

        response = await agent.run(KnowledgeChatRequest(
            question=(
                "你帮我扫榜男频都市脑洞，给我些开文建议，还有目前都是哪些题材，"
                "我太久没看了不太清楚，我现在打算开书推荐写那种题材"
            ),
            mode="research",
        ))

        self.assertEqual("answered", response.status, response.resultJson)
        self.assertEqual(2, len(client.lookup_rank_calls))
        self.assertEqual(1, len(client.refresh_rank_board_calls))
        self.assertEqual("fanqie", client.refresh_rank_board_calls[0]["platform"])
        self.assertEqual("male-new", client.refresh_rank_board_calls[0]["channel_code"])
        self.assertEqual("都市脑洞", client.refresh_rank_board_calls[0]["category"])
        self.assertEqual("AUTO", client.refresh_rank_board_calls[0]["refresh_mode"])
        self.assertEqual(client.fresh_snapshot_time, response.sources[0].snapshotTime)
        self.assertFalse(response.resultJson["sourcePolicy"]["trendGateFailed"])

    async def test_scan_board_opening_advice_refreshes_empty_rank_before_retrying(self) -> None:
        client = RefreshDrivenMissingTopRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)

        response = await agent.run(KnowledgeChatRequest(
            question=(
                "你帮我扫榜男频都市脑洞，给我些开文建议，还有目前都是哪些题材，"
                "我太久没看了不太清楚，我现在打算开书推荐写那种题材"
            ),
            mode="research",
        ))

        self.assertEqual("answered", response.status)
        self.assertEqual(2, len(client.lookup_rank_calls))
        self.assertEqual(1, len(client.refresh_rank_board_calls))
        self.assertEqual("male-new", client.refresh_rank_board_calls[0]["channel_code"])
        self.assertEqual("都市脑洞", client.refresh_rank_board_calls[0]["category"])
        self.assertEqual("Fresh After Empty Refresh Leader", response.sources[0].bookName)
        self.assertFalse(response.resultJson["sourcePolicy"]["trendGateFailed"])

    async def test_opening_strategy_topic_recommendation_should_answer_without_clarification(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([
            "## 推荐方向\n1. 县城婆罗门爽文：本地资源、人情网络和阶层反差制造爽点。"
        ])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="我如果要开新书都市脑洞的，你推荐什么题材",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual("creative_advice", response.resultJson["intent"])
        self.assertEqual("opening_strategy", response.resultJson["domainIntent"])
        self.assertEqual("creative", response.resultJson["answerMode"])
        self.assertEqual("creative_answer", response.resultJson["answerStatus"])
        self.assertEqual("creative_inference", response.resultJson["answerBoundary"])
        self.assertNotIn("clarify_intent", response.actions)
        self.assertEqual([], client.search_books_calls)
        self.assertEqual([], client.search_evidence_calls)
        self.assertGreaterEqual(len(provider.invoke_calls), 1)
        self.assertFalse(provider.invoke_calls[-1]["require_json"])

    async def test_should_answer_with_citations_for_indexed_book(self) -> None:
        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="星河旧梦的爽点来自哪里？",
            bookId=101,
            bookName="星河旧梦",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual("answered_with_evidence", response.resultJson["answerStatus"])
        self.assertIn("旧星门坐标", response.answer)
        self.assertIn("[1]", response.answer)
        self.assertEqual(1, len(response.sources))
        self.assertEqual(1, len(provider.invoke_calls))
        self.assertIn("第3章 星门残响", provider.invoke_calls[0]["messages"][1]["content"])
        self.assertEqual(101, client.search_evidence_calls[0]["book_id"])
        self.assertEqual("answered", response.resultJson["status"])

    async def test_should_use_book_research_pack_for_chapter_level_question(self) -> None:
        client = BookResearchPackKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="星河旧梦前三章的金手指和钩子是什么？",
            bookId=101,
            bookName="星河旧梦",
            mode="research",
            limits={"evidenceLimit": 5, "chapterLimit": 3, "analysisLimit": 2},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual("answered_with_evidence", response.resultJson["answerStatus"])
        self.assertEqual(1, len(client.book_pack_calls))
        self.assertEqual(1, len(client.search_evidence_calls))
        self.assertEqual("CHAPTER_PACK", response.sources[0].sourceType)
        self.assertIn("旧星门坐标", response.sources[0].preview)
        self.assertIn("[1]", response.answer)

    async def test_should_send_research_pack_material_to_answer_prompt(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient(), provider_client=FakeAnswerProvider())
        request = KnowledgeChatRequest(question="Analyze first chapters", mode="research")
        source = KnowledgeSource(
            bookId=101,
            bookName="Pack Book",
            sourceType="CHAPTER_PACK",
            title="Chapter 1",
            preview="short preview",
            material="long chapter content with hook and setup",
        )

        messages = agent._build_answer_messages(request, [source], "single_book")

        self.assertIn("long chapter content with hook and setup", messages[1]["content"])
        self.assertNotIn("material: short preview", messages[1]["content"])
        self.assertNotIn("material", source.model_dump())

    async def test_should_use_rank_research_pack_for_trend_advice_and_keep_rank_one_first(self) -> None:
        client = RankResearchPackKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="给我都市脑洞最近热门题材，要男频的，以及对应开书建议",
            mode="research",
            limits={"evidenceLimit": 8, "rankLimit": 5, "chapterLimitPerBook": 1},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual(1, len(client.rank_pack_calls))
        self.assertGreaterEqual(len(client.search_evidence_calls), 1)
        self.assertEqual("RANK", response.sources[0].sourceType)
        self.assertEqual(1, response.sources[0].rankNo)
        self.assertEqual("长生两千年，被妹妹直播曝光", response.sources[0].bookName)
        self.assertIn("长生两千年，被妹妹直播曝光", response.answer)
        self.assertIn("INTRO", [source.sourceType for source in response.sources])
        self.assertLess(
            provider.invoke_calls[0]["messages"][1]["content"].index("长生两千年，被妹妹直播曝光"),
            provider.invoke_calls[0]["messages"][1]["content"].index("向量补充样本"),
        )

    async def test_should_keep_rank_pack_chapter_and_vector_sources_under_tight_limit(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient(), provider_client=FakeAnswerProvider())
        request = KnowledgeChatRequest(question="trend advice", mode="research", limits={"evidenceLimit": 5})
        sources = [
            KnowledgeSource(score=1.0, sourceType="RANK", rankNo=1, bookId=701, sourceRefId=9901, title="rank one"),
            KnowledgeSource(score=0.99, sourceType="RANK", rankNo=2, bookId=702, sourceRefId=9902, title="rank two"),
            KnowledgeSource(score=0.98, sourceType="RANK", rankNo=3, bookId=703, sourceRefId=9903, title="rank three"),
            KnowledgeSource(score=0.97, sourceType="RANK", rankNo=4, bookId=704, sourceRefId=9904, title="rank four"),
            KnowledgeSource(score=0.96, sourceType="RANK", rankNo=5, bookId=705, sourceRefId=9905, title="rank five"),
            KnowledgeSource(score=0.84, sourceType="INTRO", bookId=701, sourceRefId=701, title="rank one intro"),
            KnowledgeSource(score=0.83, sourceType="INTRO", bookId=702, sourceRefId=702, title="rank two intro"),
            KnowledgeSource(score=0.82, sourceType="INTRO", bookId=703, sourceRefId=703, title="rank three intro"),
            KnowledgeSource(score=0.81, sourceType="CHAPTER_PACK", bookId=701, sourceRefId=1701, chapterNo=1, title="rank one chapter"),
            KnowledgeSource(score=0.7, chunkId=8801, documentId=880, sourceType="INTRO", bookId=801, sourceRefId=801, title="vector supplement"),
        ]

        ranked = agent._rerank_sources(request, {"intent": "trend_research"}, sources)

        source_types = [source.sourceType for source in ranked]
        self.assertEqual(5, len(ranked))
        self.assertEqual("RANK", ranked[0].sourceType)
        self.assertEqual(1, ranked[0].rankNo)
        self.assertEqual(["RANK", "RANK", "RANK"], source_types[:3])
        self.assertIn("INTRO", source_types)
        self.assertIn("CHAPTER_PACK", source_types)
        self.assertFalse(any(source.sourceRefId == 801 for source in ranked))

    async def test_trend_rank_sources_are_not_limited_by_chapter_count_or_evidence_limit(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient(), provider_client=FakeAnswerProvider())
        request = KnowledgeChatRequest(
            question="最近男频都市脑洞题材趋势是什么？",
            mode="research",
            limits={"chapterCount": 5, "evidenceLimit": 5},
        )
        sources = [
            KnowledgeSource(
                score=1.0 - index / 100,
                sourceType="RANK",
                rankNo=index,
                bookId=700 + index,
                sourceRefId=9900 + index,
                title=f"男频新书榜 / 都市脑洞 #{index}",
                bookName=f"榜单书{index}",
            )
            for index in range(1, 11)
        ]

        ranked = agent._rerank_sources(request, {"intent": "trend_research"}, sources)

        rank_numbers = [source.rankNo for source in ranked if source.sourceType == "RANK"]
        self.assertEqual(list(range(1, 11)), rank_numbers)

    async def test_rank_first_trend_fallback_lists_top_ten_rank_evidence(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient(), provider_client=FakeAnswerProvider())
        sources = [
            KnowledgeSource(
                score=1.0 - index / 100,
                sourceType="RANK",
                rankNo=index,
                bookId=800 + index,
                sourceRefId=9800 + index,
                title=f"rank board #{index}",
                bookName=f"Rank Book {index}",
                preview=f"rank evidence {index}",
            )
            for index in range(1, 11)
        ]

        answer = agent._compose_rank_first_trend_answer(sources)

        self.assertIn("Rank Book 1", answer)
        self.assertIn("Rank Book 10", answer)

    async def test_citation_repair_uses_rank_first_fallback_for_trend_answers(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient(), provider_client=FakeAnswerProvider())
        sources = [
            KnowledgeSource(
                score=1.0 - index / 100,
                sourceType="RANK",
                rankNo=index,
                bookId=850 + index,
                sourceRefId=9900 + index,
                title=f"rank board #{index}",
                bookName=f"Rank Book {index}",
                preview=f"rank evidence {index}",
            )
            for index in range(1, 11)
        ]
        response = KnowledgeChatResponse(
            status="answered",
            answer="uncited trend claim without source markers",
            sources=sources,
            resultJson={"answerMode": "trend", "intent": "trend_research"},
        )
        request = KnowledgeChatRequest(question="trend advice", mode="research", limits={"rankLimit": 10})

        result = await agent._citation_verifier_node({
            "request": request,
            "response": response,
            "sources": sources,
            "intent": "trend_research",
        })

        repaired = result["response"]
        self.assertTrue(repaired.resultJson["citationRepairUsed"])
        self.assertIn("Rank Book 10", repaired.answer)

    async def test_mixed_creation_postprocess_does_not_keep_top_five_when_top_ten_rank_sources_exist(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient(), provider_client=FakeAnswerProvider())
        sources = [
            KnowledgeSource(
                score=1.0 - index / 100,
                sourceType="RANK",
                rankNo=index,
                bookId=900 + index,
                sourceRefId=10000 + index,
                title=f"rank board #{index}",
                bookName=f"Rank Book {index}",
                preview=f"rank evidence {index}",
            )
            for index in range(1, 11)
        ]

        processed = agent._postprocess_answer_for_mode(
            "## Market Evidence\nTop5 trend summary with only a narrow view. [1]",
            sources,
            "mixed_creation",
        )

        self.assertIn("Top10", processed)
        self.assertIn("Rank Book 10", processed)

    async def test_mixed_creation_prompt_includes_top_ten_rank_evidence(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient(), provider_client=FakeAnswerProvider())
        request = KnowledgeChatRequest(question="Top10 trend advice", mode="research", limits={"rankLimit": 10})
        sources = [
            KnowledgeSource(
                score=1.0 - index / 100,
                sourceType="RANK",
                rankNo=index,
                bookId=950 + index,
                sourceRefId=11000 + index,
                title=f"rank board #{index}",
                bookName=f"Rank Book {index}",
                preview=f"rank evidence {index}",
            )
            for index in range(1, 11)
        ]

        messages = agent._build_answer_messages(request, sources, "mixed_creation", state={})

        prompt = messages[1]["content"]
        self.assertIn("Rank Book 1", prompt)
        self.assertIn("Rank Book 10", prompt)
        self.assertIn("chapter count is not the rank cutoff", prompt)

    async def test_mixed_creation_prompt_includes_top_thirty_rank_evidence_when_requested(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient(), provider_client=FakeAnswerProvider())
        request = KnowledgeChatRequest(question="Top30 trend advice", mode="research", limits={"rankLimit": 30})
        sources = [
            KnowledgeSource(
                score=1.0 - index / 100,
                sourceType="RANK",
                rankNo=index,
                bookId=12000 + index,
                sourceRefId=13000 + index,
                title=f"rank board #{index}",
                bookName=f"Rank Book {index}",
                preview=f"rank evidence {index}",
            )
            for index in range(1, 31)
        ]

        messages = agent._build_answer_messages(request, sources, "mixed_creation", state={})

        prompt = messages[1]["content"]
        self.assertIn("Rank Book 1", prompt)
        self.assertIn("Rank Book 30", prompt)

    async def test_mixed_creation_citation_repair_preserves_outline_structure(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient(), provider_client=FakeAnswerProvider())
        sources = [
            KnowledgeSource(
                score=1.0 - index / 100,
                sourceType="RANK",
                rankNo=index,
                bookId=14000 + index,
                sourceRefId=15000 + index,
                title=f"rank board #{index}",
                bookName=f"Rank Book {index}",
                preview=f"rank evidence {index}",
            )
            for index in range(1, 31)
        ]
        original = (
            "## 榜单依据\n"
            "Rank Book 1 and Rank Book 30 show the board range.\n\n"
            "## 细纲方案\n"
            "- 第1章：底层职业主角接到诸天外包特效单。\n"
            "- 第2章：三端一体金手指第一次失控。"
        )
        response = KnowledgeChatResponse(
            status="answered",
            answer=original,
            sources=sources,
            resultJson={"answerMode": "mixed_creation", "intent": "trend_research"},
        )
        request = KnowledgeChatRequest(question="Top30 market plus outline", mode="research", limits={"rankLimit": 30})

        result = await agent._citation_verifier_node({
            "request": request,
            "response": response,
            "sources": sources,
            "intent": "trend_research",
            "intent_decision": {
                "primaryIntent": "mixed_creation_research",
                "toolNeeds": {"needsCreativeGeneration": True},
            },
        })

        repaired = result["response"]
        self.assertTrue(repaired.resultJson["citationRepairUsed"])
        self.assertIn("## 榜单依据", repaired.answer)
        self.assertIn("## 细纲方案", repaired.answer)
        self.assertIn("Rank Book 30", repaired.answer)
        self.assertIn("第2章", repaired.answer)
        self.assertIn("[1]", repaired.answer)

    async def test_should_rerank_and_dedupe_retrieved_sources_before_answering(self) -> None:
        client = DuplicateEvidenceKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="星河旧梦的旧星门坐标设定是什么？",
            bookId=101,
            bookName="星河旧梦",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual([11, 12, 13], [source.chunkId for source in response.sources])
        self.assertIn("旧星门坐标", provider.invoke_calls[0]["messages"][1]["content"])
        self.assertEqual(3, provider.invoke_calls[0]["messages"][1]["content"].count("sourceType:"))

    async def test_should_prioritize_rank_evidence_for_board_ranking_question(self) -> None:
        client = RankEvidenceKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="最近男频新书榜都市脑洞排名第一的书是什么",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual("RANK", response.sources[0].sourceType)
        self.assertEqual("入伍两次！我被原部队拉进黑名单", response.sources[0].bookName)
        answer_prompt = provider.invoke_calls[0]["messages"][1]["content"]
        self.assertLess(answer_prompt.index("sourceType: RANK"), answer_prompt.index("sourceType: INTRO"))

    async def test_should_use_structured_rank_lookup_for_exact_rank_question(self) -> None:
        client = StructuredRankLookupKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="最近男频新书榜都市脑洞排名第一的书是什么",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual("answered_with_evidence", response.resultJson["answerStatus"])
        self.assertEqual([], client.search_books_calls)
        self.assertEqual([], client.search_evidence_calls)
        self.assertEqual(1, len(client.lookup_rank_calls))
        self.assertEqual("fanqie", client.lookup_rank_calls[0]["platform"])
        self.assertEqual("male-new", client.lookup_rank_calls[0]["channel_code"])
        self.assertEqual("都市脑洞", client.lookup_rank_calls[0]["category"])
        self.assertEqual(1, client.lookup_rank_calls[0]["rank_no"])
        self.assertIn("入伍两次！我被原部队拉进黑名单", response.answer)
        self.assertIn("朝朝和", response.answer)
        self.assertEqual("rank_lookup", response.resultJson["intent"])
        self.assertEqual(10, response.sources[0].snapshotId)
        self.assertEqual("2026-05-10T00:00:00", response.sources[0].snapshotTime)
        self.assertEqual("male-new", response.sources[0].channelCode)
        self.assertEqual("urban-brain", response.sources[0].boardCode)

    async def test_trend_question_should_reject_rank_sources_without_snapshot_time(self) -> None:
        client = MissingSnapshotStructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="最近男频都市脑洞题材趋势是什么？",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("insufficient_evidence", response.status)
        self.assertEqual("needs_required_evidence", response.resultJson["answerStatus"])
        self.assertIn("refresh_rank_board", response.actions)
        self.assertEqual([], provider.invoke_calls)
        self.assertTrue(response.resultJson["sourcePolicy"]["trendGateFailed"])
        self.assertEqual("missing_structured_rank_snapshot", response.resultJson["sourcePolicy"]["trendGateReason"])

    async def test_mixed_creation_should_degrade_when_rank_rows_lack_snapshot_time(self) -> None:
        client = RefreshableSnapshotlessTopTenRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question=(
                "现在我要写一篇底层职业的都市脑洞文，结合当前榜单趋势你觉得如何，"
                "可以给出我一些大纲吗，我设计是都市里有诸天万界外包来做特效"
            ),
            mode="research",
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertGreaterEqual(len(client.rank_pack_calls), 1)
        self.assertTrue(response.resultJson["sourcePolicy"]["latestRankEvidenceDegraded"])
        self.assertEqual("missing_structured_rank_snapshot", response.resultJson["sourcePolicy"]["trendGateReason"])
        self.assertEqual("mixed_creation", response.resultJson["answerMode"])
        self.assertEqual({}, response.resultJson.get("retryCounts") or {})
        self.assertGreaterEqual(len(response.sources), 10)
        self.assertGreaterEqual(len(provider.invoke_calls), 1)

    async def test_mixed_creation_should_accept_snapshot_id_only_rank_snapshot(self) -> None:
        client = SnapshotIdOnlyTopTenRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question=(
                "现在我要写一篇底层职业的都市脑洞文，结合当前榜单趋势你觉得如何，"
                "可以给出我一些大纲吗，我设计是都市里有诸天万界外包来做特效，"
                "金手指采用“三端一体”的形态。"
            ),
            mode="research",
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status, response.resultJson)
        self.assertEqual("mixed_creation", response.resultJson["answerMode"])
        self.assertFalse(response.resultJson["sourcePolicy"]["trendGateFailed"])
        self.assertEqual(9901, response.resultJson["sourcePolicy"]["snapshotId"])
        self.assertFalse(response.resultJson["sourcePolicy"].get("latestRankEvidenceDegraded", False))
        self.assertGreaterEqual(len(response.sources), 10)
        self.assertGreaterEqual(len(provider.invoke_calls), 1)

    async def test_mixed_creation_should_not_block_when_lookup_only_rank_lacks_snapshot_metadata(self) -> None:
        client = LookupOnlySnapshotlessTopTenRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question=(
                "现在我要写一篇底层职业的都市脑洞文，结合当前榜单趋势你觉得如何，"
                "可以给出我一些大纲吗，我设计是都市里有诸天万界外包来做特效，"
                "金手指采用“三端一体”的形态。"
            ),
            mode="research",
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status, response.resultJson)
        self.assertEqual("mixed_creation", response.resultJson["answerMode"])
        self.assertTrue(response.resultJson["sourcePolicy"]["latestRankEvidenceDegraded"])
        self.assertEqual("missing_structured_rank_snapshot", response.resultJson["sourcePolicy"]["trendGateReason"])
        self.assertEqual({}, response.resultJson.get("retryCounts") or {})
        self.assertGreaterEqual(len(response.sources), 10)
        self.assertGreaterEqual(len(provider.invoke_calls), 1)

    async def test_mixed_creation_should_arbitrate_mixed_snapshot_rank_tools(self) -> None:
        client = MixedSnapshotRankToolsKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question=(
                "现在我要写一篇底层职业的都市脑洞文，结合当前榜单趋势给我一些大纲，"
                "金手指采用三端一体的形态。"
            ),
            mode="research",
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status, response.resultJson)
        self.assertEqual("mixed_creation", response.resultJson["answerMode"])
        source_policy = response.resultJson["sourcePolicy"]
        contract = source_policy["evidenceContract"]
        self.assertFalse(source_policy["trendGateFailed"])
        self.assertEqual("mixed_structured_rank_snapshot", source_policy["trendGateOriginalReason"])
        self.assertEqual("degraded_directional", contract["status"])
        self.assertEqual(9201, contract["selectedSnapshotGroup"]["snapshotId"])
        self.assertTrue(any(warning["code"] == "mixed_structured_rank_snapshot" for warning in contract["warnings"]))
        self.assertEqual({9201}, {
            source.snapshotId
            for source in response.sources
            if (source.sourceType or "").upper() == "RANK"
        })
        self.assertGreaterEqual(len(provider.invoke_calls), 1)

    async def test_should_add_filtered_rank_search_for_board_trend_question(self) -> None:
        client = RankOnlyOnFilteredSearchKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="给我都市脑洞最近热门题材，要男频的，以及最近热门的书和对应开书建议",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual(["RANK", "INTRO"], [source.sourceType for source in response.sources])
        self.assertEqual([None, "RANK"], [call["source_type"] for call in client.search_evidence_calls])
        answer_prompt = provider.invoke_calls[0]["messages"][1]["content"]
        self.assertLess(answer_prompt.index("sourceType: RANK"), answer_prompt.index("sourceType: INTRO"))

    async def test_should_add_structured_topn_rank_sources_for_trend_question(self) -> None:
        client = StructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="给我都市脑洞最近热门题材，要男频的，以及最近热门的书和对应开书建议",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual(1, len(client.lookup_rank_calls))
        self.assertEqual("male-new", client.lookup_rank_calls[0]["channel_code"])
        self.assertEqual("都市脑洞", client.lookup_rank_calls[0]["category"])
        self.assertIsNone(client.lookup_rank_calls[0]["rank_no"])
        self.assertEqual("partial_answer", response.resultJson["answerStatus"])
        self.assertEqual(["RANK", "RANK", "INTRO"], [source.sourceType for source in response.sources])
        self.assertEqual("入伍两次！我被原部队拉进黑名单", response.sources[0].bookName)
        self.assertEqual(1, response.sources[0].rankNo)
        self.assertEqual("朝朝和", response.sources[0].author)
        self.assertEqual("都市脑洞", response.sources[0].category)

    async def test_should_attach_domain_intent_decision_for_market_scan(self) -> None:
        client = StructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="帮我看番茄男频都市脑洞新书榜Top10，榜一有什么趋势？",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        decision = response.resultJson["intentDecision"]
        self.assertEqual("trend_research", response.resultJson["intent"])
        self.assertEqual("market_scan", response.resultJson["domainIntent"])
        self.assertEqual("market_scan", decision["primaryIntent"])
        self.assertEqual("男频", decision["entities"]["channel"])
        self.assertEqual("都市脑洞", decision["entities"]["category"])
        self.assertTrue(decision["toolNeeds"]["needsRankData"])

    async def test_should_route_mixed_creation_research_to_market_evidence_first(self) -> None:
        client = StructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="先看番茄男频都市脑洞新书榜Top10，再帮我开一本同题材新书",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual("trend_research", response.resultJson["intent"])
        self.assertEqual("mixed_creation_research", response.resultJson["domainIntent"])
        self.assertEqual("mixed_creation_research", response.resultJson["businessRoute"])
        self.assertEqual("evidence_plus_author_inference", response.resultJson["answerBoundary"])
        self.assertEqual("market_evidence_plus_author_inference", response.resultJson["domainAnswerBoundary"])
        self.assertEqual("RANK", response.sources[0].sourceType)
        self.assertEqual(1, len(client.lookup_rank_calls))
        self.assertEqual("mixed_creation", response.resultJson["answerMode"])
        self.assertEqual("mixed_creation_research", response.resultJson["intentDecision"]["primaryIntent"])
        self.assertIn("market_scan", response.resultJson["intentDecision"]["subIntents"])
        self.assertIn("opening_strategy", response.resultJson["intentDecision"]["subIntents"])

    async def test_mixed_rank_reference_and_chapter_outline_uses_creative_evidence_mode(self) -> None:
        client = CurrentStructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question=(
                "根据当前男频新书榜都市脑洞第一的书，"
                "我要模仿出对应的题材和细纲，该怎么设计"
            ),
            mode="research",
            limits={"evidenceLimit": 8},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual("mixed_creation_research", response.resultJson["domainIntent"])
        self.assertEqual("mixed_creation", response.resultJson["answerMode"])
        self.assertEqual("market_evidence_plus_author_inference", response.resultJson["domainAnswerBoundary"])
        self.assertIn("chapter_outline", response.resultJson["intentDecision"]["subIntents"])
        self.assertEqual(1, len(client.lookup_rank_calls))
        self.assertGreaterEqual(len(client.search_evidence_calls), 1)
        rank_numbers = [source.rankNo for source in response.sources if source.sourceType == "RANK"]
        self.assertNotIn(24, rank_numbers)
        trace = response.resultJson["trace"]
        self.assertEqual("rank_first_market_then_author_inference", trace["promptPolicy"])
        self.assertEqual("RANK", trace["sourcePriority"][0])
        prompt = provider.invoke_calls[0]["messages"][1]["content"]
        self.assertIn("answerMode: mixed_creation", prompt)
        self.assertIn("rank-first", prompt)
        self.assertIn("chapter outline", prompt)

    async def test_domain_market_scan_should_drive_execution_when_legacy_prefers_creative(self) -> None:
        class StubMarketRouter:
            def classify(self, *_args, **_kwargs) -> IntentDecision:
                return IntentDecision(
                    primaryIntent=Intent.market_scan,
                    confidence=0.91,
                    toolNeeds=ToolNeeds(needsRankData=True),
                    answerBoundary=AnswerBoundary.market_evidence,
                    routingNotes=["test:domain-market"],
                )

        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        agent.intent_router = StubMarketRouter()
        request = KnowledgeChatRequest(
            question="opening opportunity",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("trend_research", response.resultJson["intent"])
        self.assertEqual("market_scan", response.resultJson["domainIntent"])
        self.assertGreaterEqual(len(client.search_evidence_calls), 1)
        self.assertIsNone(client.search_evidence_calls[0]["book_id"])
        self.assertEqual([], provider.invoke_calls)

    async def test_final_answer_boundary_should_override_domain_diagnostic_boundary(self) -> None:
        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="帮我看番茄男频都市脑洞新书榜Top10，榜一有什么机会？",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("insufficient_evidence", response.status)
        self.assertEqual("needs_more_data", response.resultJson["answerBoundary"])
        self.assertEqual("market_evidence", response.resultJson["domainAnswerBoundary"])

    async def test_rank_research_pack_uses_configured_chapter_limit_default(self) -> None:
        client = RankResearchPackKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        original = settings.agent_chapters_per_rank_book
        settings.agent_chapters_per_rank_book = 3
        try:
            request = KnowledgeChatRequest(
                question="给我都市脑洞最近热门题材，要男频的，以及对应开书建议",
                mode="research",
                limits={"evidenceLimit": 8, "rankLimit": 5},
            )

            response = await agent.run(request)
        finally:
            settings.agent_chapters_per_rank_book = original

        self.assertEqual("answered", response.status)
        self.assertEqual(1, len(client.rank_pack_calls))
        self.assertEqual(3, client.rank_pack_calls[0]["chapter_limit_per_book"])

    async def test_should_inject_runtime_skills_and_specialist_agent_context(self) -> None:
        client = StructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="先看番茄男频都市脑洞新书榜Top10，再帮我开一本同题材新书",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertIn("webnovel-market-scan", response.resultJson["selectedSkills"])
        self.assertIn("webnovel-opening-strategy", response.resultJson["selectedSkills"])
        self.assertIn("market_scan", response.resultJson["specialistAgents"])
        self.assertIn("opening_strategy", response.resultJson["specialistAgents"])
        self.assertEqual(
            ["market_scan", "author_strategy", "opening_strategy"],
            [expert["name"] for expert in response.resultJson["selectedExperts"][:3]],
        )
        self.assertEqual("fast", response.resultJson["expertRouter"]["reasoningMode"])
        self.assertEqual(3, response.resultJson["expertRouter"]["maxParallel"])
        self.assertEqual(3, response.resultJson["budgets"]["maxParallelSpecialists"])
        self.assertEqual(
            response.resultJson["expertRouter"],
            response.resultJson["trace"]["expertRouter"],
        )
        route_node = next(
            node
            for node in response.resultJson["trace"]["nodes"]
            if node["name"] == "route_experts"
        )
        self.assertEqual("completed", route_node["status"])
        self.assertGreater(route_node["selectedExpertCount"], 0)
        diagnostics = response.resultJson["specialistDiagnostics"]
        self.assertTrue(all(result["diagnostics"]["runner"] == "parallel" for result in diagnostics))
        self.assertTrue(all(result["diagnostics"]["parallelLimit"] == 3 for result in diagnostics))
        self.assertTrue(all(result["diagnostics"]["expertRouterReason"] for result in diagnostics))
        prompt = next(
            call["messages"][1]["content"]
            for call in provider.invoke_calls
            if "runtime skills:" in call["messages"][1]["content"]
            and "specialist agent plan:" in call["messages"][1]["content"]
        )
        self.assertIn("runtime skills", prompt)
        self.assertIn("specialist agent plan", prompt)
        self.assertIn("webnovel-market-scan", prompt)
        self.assertIn("开篇钩子", prompt)

    async def test_should_apply_backend_agent_governance_to_expert_routing(self) -> None:
        client = GovernanceStructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="鍏堢湅鐣寗鐢烽閮藉競鑴戞礊鏂颁功姒淭op10锛屽啀甯垜寮€涓€鏈悓棰樻潗鏂颁功",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        selected_names = [expert["name"] for expert in response.resultJson["selectedExperts"]]
        self.assertEqual(1, client.runtime_config_calls)
        self.assertEqual(1, client.expert_profile_calls)
        self.assertIsInstance(selected_names, list)
        self.assertEqual(2, response.resultJson["expertRouter"]["maxParallel"])
        self.assertEqual(2, response.resultJson["budgets"]["maxParallelSpecialists"])
        self.assertEqual("backend", response.resultJson["runtimeConfig"]["source"])
        self.assertEqual(
            response.resultJson["runtimeConfig"],
            response.resultJson["trace"]["runtimeConfig"],
        )

    async def test_should_enforce_runtime_policy_published_skills_and_emit_telemetry(self) -> None:
        client = RuntimePolicyGovernanceKnowledgeClient()
        provider = ScriptedProvider([
            (
                '{"primaryIntent": "market_scan", "subIntents": ["opening_strategy"], '
                '"entities": {"category": "urban"}, "missingSlots": [], '
                '"toolNeeds": {"needsRankData": true, "needsVectorEvidence": true, '
                '"needsCreativeGeneration": true}, '
                '"sourcePolicy": {"freshness": "latest", "requireSnapshotTime": true}, '
                '"memoryPolicy": {"useProjectProfile": false, "useThreadSummary": true}, '
                '"answerBoundary": "market_evidence_plus_author_inference", '
                '"confidence": 0.86, "routingNotes": ["llm:v3-fallback"]}'
            ),
            "Trend conclusion [1]",
        ])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="analyze recent urban brainhole ranking trend and give opening advice",
            traceId="trace-runtime-policy-001",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual(1, client.runtime_config_calls)
        self.assertEqual(1, client.expert_profile_calls)
        self.assertEqual(1, client.runtime_skill_calls)
        self.assertLessEqual(len(response.sources), 1)
        self.assertEqual(1, response.resultJson["runtimeConfig"]["maxEvidenceItems"])
        prompt = next(
            call["messages"][1]["content"]
            for call in provider.invoke_calls
            if "runtime skills:" in call["messages"][1]["content"]
        )
        self.assertIn("BACKEND PUBLISHED PROMPT", prompt)
        self.assertNotIn("deliberately long policy body", prompt)
        tool_runs = response.resultJson.get("toolRuns") or []
        self.assertTrue(any(run.get("errorType") == "ToolNotAllowed" for run in tool_runs))
        self.assertTrue(any(run.get("errorType") == "ToolBudgetExceeded" for run in tool_runs))
        self.assertEqual(1, len(client.telemetry_calls))
        telemetry = client.telemetry_calls[0]
        self.assertEqual("trace-runtime-policy-001", telemetry["traceId"])
        self.assertTrue(any(event.get("cacheStatus") == "BYPASS" for event in telemetry["cacheEvents"]))
        self.assertTrue(any(metric.get("nodeName") == "answer_writer" for metric in telemetry["tokenMetrics"]))

    def test_should_not_hard_require_user_memory_from_selected_local_skills(self) -> None:
        SkillRegistry.clear_cache()
        agent = object.__new__(NovelResearchAgent)
        agent.skill_registry = SkillRegistry()
        state = {
            "selected_skills": ["webnovel-opening-hook"],
            "runtime_skills": [],
        }

        required = NovelResearchAgent._required_evidence_for_state(agent, state)

        self.assertNotIn("user_premise_or_project_memory", required)

    async def test_should_execute_llm_requested_mcp_tools_in_main_answer_path(self) -> None:
        client = ToolLoopKnowledgeClient()
        provider = ToolCallingProvider()
        mcp_client = FakeMcpClient()
        agent = NovelResearchAgent(
            knowledge_client=client,
            provider_client=provider,
            mcp_client=mcp_client,
        )
        request = KnowledgeChatRequest(
            question="最近男频都市脑洞榜单趋势如何，再给我开书建议",
            mode="research",
            reasoningMode="deep",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status, response.resultJson)
        self.assertEqual("rank.lookup", mcp_client.calls[0]["name"])
        self.assertEqual("rank.lookup", response.resultJson["mcpToolCalls"][0]["name"])
        self.assertEqual("succeeded", response.resultJson["mcpToolCalls"][0]["status"])
        tool_calls = [call for call in provider.invoke_calls if call.get("tools")]
        self.assertGreaterEqual(len(tool_calls), 2)
        self.assertEqual("deep", tool_calls[0]["reasoning_mode"])
        self.assertTrue(tool_calls[0]["tools"])
        second_messages = tool_calls[1]["messages"]
        self.assertTrue(any(message.get("role") == "tool" for message in second_messages))
        self.assertTrue(any(message.get("reasoning_content") for message in second_messages if message.get("role") == "assistant"))

    async def test_should_execute_llm_requested_mcp_tools_inside_specialist_agents(self) -> None:
        client = CurrentStructuredRankTrendKnowledgeClient()
        provider = ToolCallingProvider()
        mcp_client = FakeMcpClient()
        agent = NovelResearchAgent(
            knowledge_client=client,
            provider_client=provider,
            mcp_client=mcp_client,
        )
        request = KnowledgeChatRequest(
            question="先看番茄男频都市脑洞新书榜Top10，再帮我开一本同题材新书",
            mode="research",
            reasoningMode="fast",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        diagnostics = response.resultJson["specialistDiagnostics"]
        market_diagnostic = next(item for item in diagnostics if item["agentName"] == "market_scan")
        self.assertTrue(market_diagnostic["diagnostics"]["llmBacked"])
        self.assertTrue(market_diagnostic["diagnostics"]["mcpToolLoop"])
        self.assertTrue(any(call.get("name") == "rank.lookup" for call in market_diagnostic["toolCalls"]))
        self.assertEqual("market_scan", response.resultJson["specialistToolCalls"][0]["agentName"])
        self.assertEqual("rank.lookup", response.resultJson["specialistToolCalls"][0]["name"])
        self.assertEqual("rank.lookup", mcp_client.calls[0]["name"])
        self.assertTrue(any(call["reasoning_mode"] == "fast" for call in provider.specialist_invoke_calls if call.get("tools")))

    def test_langgraph_should_use_explicit_runtime_state_nodes(self) -> None:
        agent = NovelResearchAgent(
            knowledge_client=StructuredRankTrendKnowledgeClient(),
            provider_client=FakeAnswerProvider(),
        )

        graph = agent._graph.get_graph()
        edges = {(edge.source, edge.target) for edge in graph.edges}

        expected_nodes = {
            "assemble_context",
            "classify_intent",
            "plan_tasks",
            "validate_preconditions",
            "execute_tools",
            "supervise_evidence",
            "compose_answer",
            "extract_memory_candidates",
            "finalize_trace",
        }
        self.assertTrue(expected_nodes.issubset(set(graph.nodes)))
        self.assertIn(("__start__", "assemble_context"), edges)
        self.assertIn(("assemble_context", "classify_intent"), edges)
        self.assertIn(("classify_intent", "plan_tasks"), edges)
        self.assertIn(("plan_tasks", "validate_preconditions"), edges)
        self.assertIn(("validate_preconditions", "execute_tools"), edges)
        self.assertIn(("execute_tools", "supervise_evidence"), edges)
        self.assertIn(("compose_answer", "extract_memory_candidates"), edges)
        self.assertIn(("extract_memory_candidates", "finalize_trace"), edges)
        self.assertIn(("finalize_trace", "__end__"), edges)

    async def test_should_report_tool_plan_and_material_budget_diagnostics(self) -> None:
        client = StructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="帮我看番茄男频都市脑洞新书榜Top10，榜一有什么趋势？",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        tool_names = [step["name"] for step in response.resultJson["toolPlan"]]
        self.assertIn("rank_research_pack", tool_names)
        self.assertIn("rank_lookup", tool_names)
        self.assertGreater(response.resultJson["materialChars"], 0)
        self.assertEqual(3, response.resultJson["budgets"]["maxParallelToolCalls"])
        self.assertEqual(3000, response.resultJson["budgets"]["maxSkillChars"])

    async def test_should_expose_trace_metadata_for_observability(self) -> None:
        client = StructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="帮我看番茄男频都市脑洞新书榜Top10，榜一有什么趋势？",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        trace = response.resultJson["trace"]
        self.assertEqual("trend_research", trace["intent"])
        self.assertEqual(response.resultJson["answerMode"], trace["answerMode"])
        self.assertEqual(response.resultJson["answerBoundary"], trace["answerBoundary"])
        self.assertEqual(response.resultJson["sourceCount"], trace["sourceCount"])
        self.assertEqual(response.resultJson["materialChars"], trace["materialChars"])
        self.assertEqual(response.resultJson["fallbackUsed"], trace["fallbackUsed"])
        self.assertIn("toolPlan", trace)
        tool_runs = trace["toolRuns"]
        tool_run_names = [run["name"] for run in tool_runs]
        self.assertNotIn("rank_research_pack", tool_run_names)
        self.assertIn("rank.lookup", tool_run_names)
        self.assertIn("generic_vector_search", tool_run_names)
        self.assertIn("knowledge.vector_search", tool_run_names)
        self.assertTrue(
            any(run["name"] == "generic_vector_search" and run.get("plane") == "system_internal" for run in tool_runs)
        )
        self.assertTrue(all(run["status"] in {"succeeded", "skipped", "failed"} for run in tool_runs))
        self.assertIn("selectedSkills", trace)
        self.assertIn("diagnostics", trace)
        self.assertIn(trace["checkpointStore"], {"memory", "mysql"})
        self.assertGreater(trace["contextChars"], 0)
        self.assertGreater(trace["evidenceChars"], 0)
        self.assertEqual("evidence_first_fact_grounding", trace["promptPolicy"])
        self.assertEqual(["RANK", "CHAPTER", "CHAPTER_PACK", "INTRO", "ANALYSIS"], trace["sourcePriority"][:5])

    async def test_project_tool_runs_are_summarized_for_trace(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient())
        tool_runs = [
            {
                "name": "project.resolve",
                "status": "succeeded",
                "input": {"userId": 7, "query": "Project Vector Novel"},
                "output": {"status": "resolved", "projectId": 910, "workId": 920, "title": "Project Vector Novel"},
            },
            {
                "name": "project.chapter_search",
                "status": "succeeded",
                "input": {"userId": 7, "projectId": 910, "workId": 920, "query": "signal"},
                "output": {"items": [{"chapterNo": 12, "title": "delivery"}]},
            },
            {
                "name": "project.chunk_search",
                "status": "succeeded",
                "input": {"userId": 7, "projectId": 910, "workId": 920, "query": "signal"},
                "output": {"items": [{"sourceType": "scene", "chunkText": "admin signal"}]},
            },
            {
                "name": "project.foreshadowing.list",
                "status": "succeeded",
                "input": {"userId": 7, "projectId": 910, "workId": 920, "status": "OPEN"},
                "output": {"items": [{"title": "moon-admin", "status": "OPEN"}]},
            },
            {
                "name": "project.world_rule_lookup",
                "status": "succeeded",
                "input": {"userId": 7, "projectId": 910, "workId": 920, "query": "settlement"},
                "output": {"items": [{"title": "three-terminal"}]},
            },
        ]

        summary = agent._project_knowledge_trace_for_tool_runs(tool_runs)

        self.assertEqual(910, summary["projectId"])
        self.assertEqual(920, summary["workId"])
        self.assertEqual("resolved", summary["resolutionStatus"])
        self.assertEqual("Project Vector Novel", summary["resolvedTitle"])
        self.assertEqual([{"chapterNo": 12, "title": "delivery"}], summary["retrievedChapters"])
        self.assertEqual([{"sourceType": "scene", "chunkText": "admin signal"}], summary["retrievedChunks"])
        self.assertEqual([{"title": "moon-admin", "status": "OPEN"}], summary["matchedForeshadowings"])
        self.assertEqual([{"title": "three-terminal"}], summary["matchedWorldRules"])

    async def test_rank_refresh_mode_is_auto_unless_user_explicitly_forces_refresh(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient())

        self.assertEqual(
            "AUTO",
            agent._rank_refresh_mode_for_request(KnowledgeChatRequest(question="当前男频都市脑洞榜单趋势如何")),
        )
        self.assertEqual(
            "FORCE",
            agent._rank_refresh_mode_for_request(KnowledgeChatRequest(question="请实时刷新男频都市脑洞榜单")),
        )
        self.assertEqual(
            "FORCE",
            agent._rank_refresh_mode_for_request(KnowledgeChatRequest(question="不要缓存，重新抓榜单")),
        )

    async def test_should_expose_validate_preconditions_stage_in_trace(self) -> None:
        client = CurrentStructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="甯垜鐪嬬暘鑼勭敺棰戦兘甯傝剳娲炴柊涔︽Top10锛屾涓€鏈変粈涔堣秼鍔匡紵",
            mode="research",
            projectId=900,
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        trace = response.resultJson["trace"]
        nodes = trace["nodes"]
        node_names = [node["name"] for node in nodes]
        self.assertIn("validate_preconditions", node_names)
        precondition_node = next(node for node in nodes if node["name"] == "validate_preconditions")
        self.assertEqual("completed", precondition_node["status"])
        self.assertGreater(precondition_node["sequenceNo"], 0)
        if "durationMs" in precondition_node:
            self.assertGreaterEqual(precondition_node["durationMs"], 0)
        self.assertIn("preconditions", precondition_node)
        preconditions = trace["preconditions"]
        self.assertTrue(preconditions["domainAllowed"])
        self.assertFalse(preconditions["needsBookSelection"])
        self.assertTrue(preconditions["needsLatestRankEvidence"])
        self.assertFalse(preconditions["projectMemoryAllowed"])
        self.assertEqual(
            response.resultJson["sourcePolicy"]["trendGateReason"],
            preconditions["evidenceInsufficiencyMode"],
        )
        self.assertEqual(response.resultJson["businessRoute"], preconditions["businessRoute"])
        self.assertEqual(response.resultJson["sourcePolicy"]["freshness"], preconditions["sourcePolicy"]["freshness"])

    def test_task_tool_context_should_include_tool_timeout_budget(self) -> None:
        agent = NovelResearchAgent()
        request = KnowledgeChatRequest(
            question="根据当前男频新书榜都市脑洞第一的书设计大纲",
            limits={"toolTimeoutMillis": 20},
        )

        context = agent._task_tool_context(request, {"request": request, "actions": []})

        self.assertEqual(20, context["toolTimeoutMillis"])

    async def test_should_report_tool_plan_for_mixed_rank_and_creative_request(self) -> None:
        client = StructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="根据当前男频新书榜都市脑洞第一的书，我要模仿出对应的题材和大纲，该怎么设计",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        tool_names = [step["name"] for step in response.resultJson["toolPlan"]]
        self.assertEqual("mixed_creation_research", response.resultJson["domainIntent"])
        self.assertIn("rank_research_pack", tool_names)
        self.assertIn("rank_lookup", tool_names)
        self.assertIn("vector_rank_search", tool_names)
        self.assertIn("creative_generation", tool_names)
        self.assertTrue(response.resultJson["intentDecision"]["toolNeeds"]["needsRankData"])
        self.assertTrue(response.resultJson["intentDecision"]["toolNeeds"]["needsCreativeGeneration"])
        self.assertFalse(response.resultJson["intentDecision"]["toolNeeds"]["needsBookResearch"])
        self.assertFalse(response.resultJson["intentDecision"]["toolNeeds"]["needsCandidateSelection"])

    def test_agent_rerank_should_preserve_distinct_chunks_with_same_chapter_identity(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient())
        request = KnowledgeChatRequest(
            question="拆一下这本书前三章钩子",
            mode="research",
            limits={"evidenceLimit": 5},
        )
        sources = [
            KnowledgeSource(
                chunkId=101,
                score=0.82,
                bookId=401,
                bookName="样本书",
                sourceType="CHAPTER",
                sourceRefId=9001,
                chapterNo=1,
                title="第1章 开局",
                preview="第一段展示主角困境。",
            ),
            KnowledgeSource(
                chunkId=102,
                score=0.81,
                bookId=401,
                bookName="样本书",
                sourceType="CHAPTER",
                sourceRefId=9001,
                chapterNo=1,
                title="第1章 开局",
                preview="第二段展示金手指触发。",
            ),
        ]

        reranked = agent._rerank_sources(request, {"intent": "single_book_research"}, sources)

        self.assertEqual([101, 102], [source.chunkId for source in reranked])

    async def test_should_use_structured_topn_for_mixed_top1_trend_and_advice_question(self) -> None:
        client = StructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="给我都市脑洞最近热门当前男频都市新书榜第一名题材，要男频的，以及最近热门的书和对应开书建议",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual(1, len(client.lookup_rank_calls))
        self.assertEqual("male-new", client.lookup_rank_calls[0]["channel_code"])
        self.assertEqual("都市脑洞", client.lookup_rank_calls[0]["category"])
        self.assertIsNone(client.lookup_rank_calls[0]["rank_no"])
        self.assertEqual("RANK", response.sources[0].sourceType)
        self.assertEqual(1, response.sources[0].rankNo)
        self.assertEqual("入伍两次！我被原部队拉进黑名单", response.sources[0].bookName)
        self.assertEqual("partial_answer", response.resultJson["answerStatus"])

    async def test_should_make_current_structured_rank_top_one_dominate_old_vector_evidence(self) -> None:
        client = CurrentStructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="给我都市脑洞最近热门题材，要男频的，以及最近热门的书和对应开书建议",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual(1, len(client.lookup_rank_calls))
        self.assertEqual("male-new", client.lookup_rank_calls[0]["channel_code"])
        self.assertEqual("都市脑洞", client.lookup_rank_calls[0]["category"])
        self.assertEqual("RANK", response.sources[0].sourceType)
        self.assertEqual(1, response.sources[0].rankNo)
        self.assertEqual("我下午才营业", response.sources[0].bookName)
        self.assertEqual(CURRENT_RANK_SNAPSHOT_TIME, response.sources[0].snapshotTime)
        self.assertFalse(response.resultJson.get("sourcePolicy", {}).get("trendGateFailed", False))
        self.assertEqual("RANK", response.resultJson["trace"]["sourcePriority"][0])
        self.assertIn("我下午才营业", response.answer)
        self.assertEqual(
            ["我下午才营业", "长生两十六亿年，被妹妹首播曝光", "归国留洋水货？叫我芯片之父！"],
            [source.bookName for source in response.sources[:3]],
        )
        self.assertNotIn("仅检索到一本在榜作品《灵城", response.answer)
        answer_prompt = provider.invoke_calls[0]["messages"][1]["content"]
        self.assertLess(
            answer_prompt.index("归国留洋水货？叫我芯片之父！"),
            answer_prompt.index("灵城：从货拉拉司机到万界之主"),
        )

    async def test_plain_trend_question_should_skip_rank_pack_and_use_structured_rank_fast_path(self) -> None:
        client = TrackingRankPackCurrentRankKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="最近男频都市脑洞题材趋势是什么？",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual([], client.rank_pack_calls)
        self.assertEqual(1, len(client.lookup_rank_calls))
        self.assertEqual("RANK", response.sources[0].sourceType)
        self.assertEqual(1, response.sources[0].rankNo)
        self.assertEqual("我下午才营业", response.sources[0].bookName)
        self.assertEqual(
            ["我下午才营业", "长生两十六亿年，被妹妹首播曝光", "归国留洋水货？叫我芯片之父！"],
            [source.bookName for source in response.sources[:3]],
        )
        self.assertNotIn("sourceType: CHAPTER_PACK", provider.invoke_calls[0]["messages"][1]["content"])
        self.assertNotIn("灵城：从货拉拉司机到万界之主", provider.invoke_calls[0]["messages"][1]["content"])

    async def test_plain_trend_question_should_not_wait_for_slow_rank_pack(self) -> None:
        client = SlowRankPackCurrentRankKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="最近男频都市脑洞题材趋势是什么？",
            mode="research",
            limits={"evidenceLimit": 5, "toolTimeoutMillis": 20},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual([], client.rank_pack_calls)
        self.assertEqual(1, len(client.lookup_rank_calls))
        self.assertEqual("RANK", response.sources[0].sourceType)
        self.assertEqual(1, response.sources[0].rankNo)

    async def test_plain_trend_question_should_accept_male_new_alias_rank_rows(self) -> None:
        client = AliasChannelCurrentStructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="最近男频都市脑洞题材趋势是什么？",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual(1, len(client.lookup_rank_calls))
        self.assertEqual("male-new", client.lookup_rank_calls[0]["channel_code"])
        self.assertEqual("male", response.sources[0].channelCode)
        self.assertFalse(response.resultJson["sourcePolicy"]["trendGateFailed"])

    async def test_plain_trend_question_should_accept_board_name_category_match(self) -> None:
        client = BoardMatchedCategoryCurrentStructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="最近男频都市脑洞题材趋势是什么？",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual(1, len(client.lookup_rank_calls))
        self.assertEqual("都市脑洞", client.lookup_rank_calls[0]["category"])
        self.assertEqual("都市脑洞", response.sources[0].boardName)
        self.assertFalse(response.resultJson["sourcePolicy"]["trendGateFailed"])

    async def test_should_not_answer_chapter_level_book_question_from_rank_only_evidence(self) -> None:
        client = RankOnlySpecificBookKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="长生两十六亿年，被妹妹首播曝光，金手指是什么，前三章主要是什么剧情，用了什么手法，埋了什么钩子，有用三幕式吗或者三翻四震",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("candidates_required", response.status)
        self.assertEqual([], provider.invoke_calls)
        self.assertIn("select_candidate", response.actions)
        self.assertEqual("长生两十六亿年，被妹妹首播曝光", client.search_books_calls[0]["keyword"])

    async def test_should_follow_rank_hit_to_chapter_evidence_for_chapter_level_book_question(self) -> None:
        client = RankThenChapterSpecificBookKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="长生两十六亿年，被妹妹首播曝光，金手指是什么，前三章主要是什么剧情，用了什么手法，埋了什么钩子",
            bookId=401,
            bookName="长生两十六亿年，被妹妹首播曝光",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual("CHAPTER", response.sources[0].sourceType)
        self.assertEqual(401, client.search_evidence_calls[0]["book_id"])
        self.assertEqual(1, len(provider.invoke_calls))
        self.assertFalse(provider.invoke_calls[0]["require_json"])

    async def test_should_resolve_exact_book_name_and_use_chapter_evidence_for_chapter_level_question(self) -> None:
        client = RankThenChapterSpecificBookKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="长生两十六亿年，被妹妹首播曝光，金手指是什么，前三章主要是什么剧情，用了什么手法，埋了什么钩子",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual("长生两十六亿年，被妹妹首播曝光", client.search_books_calls[0]["keyword"])
        self.assertEqual("CHAPTER", response.sources[0].sourceType)
        self.assertEqual(401, client.search_evidence_calls[0]["book_id"])
        self.assertEqual("CHAPTER", client.search_evidence_calls[0]["source_type"])
        self.assertEqual("长生两十六亿年，被妹妹首播曝光", response.resultJson["bookName"])
        self.assertEqual(1, len(provider.invoke_calls))
        self.assertFalse(provider.invoke_calls[0]["require_json"])

    async def test_should_repair_uncited_trend_answer_when_sources_exist(self) -> None:
        client = IndexedGlobalEvidenceKnowledgeClient()
        provider = UncitedAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="recent fanqie urban brainhole veteran enlistment trend",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual(1, len(response.sources))
        self.assertIn("[1]", response.answer)
        self.assertTrue(response.resultJson["citationRepairUsed"])
        self.assertTrue(response.resultJson["fallbackUsed"])
        self.assertEqual([], client.search_books_calls)

    async def test_should_request_author_facing_trend_answer_structure(self) -> None:
        client = IndexedGlobalEvidenceKnowledgeClient()
        provider = ScriptedProvider([
            (
                '{"primaryIntent": "market_scan", "subIntents": [], '
                '"entities": {"category": "urban"}, "missingSlots": [], '
                '"toolNeeds": {"needsRankData": true, "needsVectorEvidence": true}, '
                '"sourcePolicy": {"freshness": "latest", "requireSnapshotTime": true}, '
                '"memoryPolicy": {"useProjectProfile": false, "useThreadSummary": true}, '
                '"answerBoundary": "market_evidence", '
                '"confidence": 0.84, "routingNotes": ["llm:v3-fallback"]}'
            ),
            "Trend conclusion [1]",
        ])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="analyze the urban brainhole veteran enlistment trend for web novel authors",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual("partial_answer", response.resultJson["answerStatus"])
        self.assertEqual("trend", response.resultJson["answerMode"])
        answer_messages = provider.invoke_calls[1]["messages"]
        self.assertIn("answerMode: trend", answer_messages[1]["content"])
        self.assertIn("结论", answer_messages[1]["content"])
        self.assertIn("证据", answer_messages[1]["content"])
        self.assertIn("开文机会", answer_messages[1]["content"])
        self.assertIn("风险与规避", answer_messages[1]["content"])
        self.assertIn("author-side inference", answer_messages[0]["content"])

    async def test_should_repair_trend_answer_when_one_factual_sentence_lacks_citation(self) -> None:
        client = IndexedGlobalEvidenceKnowledgeClient()
        provider = ScriptedProvider([
            (
                '{"primaryIntent": "market_scan", "subIntents": [], '
                '"entities": {"category": "urban"}, "missingSlots": [], '
                '"toolNeeds": {"needsRankData": true, "needsVectorEvidence": true}, '
                '"sourcePolicy": {"freshness": "latest", "requireSnapshotTime": true}, '
                '"memoryPolicy": {"useProjectProfile": false, "useThreadSummary": true}, '
                '"answerBoundary": "market_evidence", '
                '"confidence": 0.84, "routingNotes": ["llm:v3-fallback"]}'
            ),
            "## 结论\nTop1 is stable [1]. This uncited trend claim should be repaired.\n\n## 证据\n- rank evidence [1]",
        ])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="analyze the urban brainhole veteran enlistment trend for web novel authors",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertTrue(response.resultJson["citationRepairUsed"])
        self.assertNotIn("uncited trend claim", response.answer)
        self.assertIn("证据", response.answer)

    async def test_should_use_full_intent_classifier_contract_for_ambiguous_questions(self) -> None:
        client = IndexedGlobalEvidenceKnowledgeClient()
        provider = ScriptedProvider([
            (
                '{"primaryIntent": "market_scan", "subIntents": ["opening_strategy"], '
                '"entities": {"category": "urban"}, "missingSlots": [], '
                '"toolNeeds": {"needsRankData": true, "needsVectorEvidence": true, '
                '"needsCreativeGeneration": true}, '
                '"sourcePolicy": {"freshness": "latest", "requireSnapshotTime": true}, '
                '"memoryPolicy": {"useProjectProfile": true, "useThreadSummary": true}, '
                '"answerBoundary": "market_evidence_plus_author_inference", '
                '"confidence": 0.86, "routingNotes": ["llm:v3-fallback"]}'
            ),
            "Trend conclusion [1]",
        ])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="which recent urban web novel topics should I write next",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual("trend_research", response.resultJson["intent"])
        self.assertEqual("market_scan", response.resultJson["domainIntent"])
        self.assertEqual("market_evidence_plus_author_inference", response.resultJson["domainAnswerBoundary"])
        self.assertTrue(response.resultJson["intentDecision"]["memoryPolicy"]["useProjectProfile"])
        intent_messages = provider.invoke_calls[0]["messages"]
        self.assertTrue(provider.invoke_calls[0]["require_json"])
        self.assertIn("primaryIntent", intent_messages[0]["content"])
        self.assertIn("subIntents", intent_messages[0]["content"])
        self.assertIn("sourcePolicy", intent_messages[0]["content"])
        self.assertIn("memoryPolicy", intent_messages[0]["content"])
        self.assertIn("missingSlots", intent_messages[0]["content"])

    async def test_should_request_single_book_technique_extraction_structure(self) -> None:
        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="What is the core setting technique of this book?",
            bookId=101,
            bookName="Star River Old Dream",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual("single_book", response.resultJson["answerMode"])
        self.assertEqual("book_breakdown", response.resultJson["businessRoute"])
        answer_messages = provider.invoke_calls[0]["messages"]
        self.assertIn("answerMode: single_book", answer_messages[1]["content"])
        self.assertIn("直接回答", answer_messages[1]["content"])
        self.assertIn("证据依据", answer_messages[1]["content"])
        self.assertIn("写法拆解", answer_messages[1]["content"])
        self.assertIn("可借鉴点", answer_messages[1]["content"])

    async def test_should_keep_system_prompt_cache_stable_across_answer_modes(self) -> None:
        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        trend_messages = agent._build_answer_messages(
            KnowledgeChatRequest(question="recent web novel trend", mode="research"),
            [
                KnowledgeSource(
                    chunkId=30,
                    documentId=30,
                    score=0.91,
                    bookId=301,
                    bookName="Trend Book",
                    sourceType="INTRO",
                    preview="Trend evidence",
                )
            ],
            "trend",
        )
        book_messages = agent._build_answer_messages(
            KnowledgeChatRequest(
                question="What is the setting technique?",
                bookId=101,
                bookName="Star River Old Dream",
                mode="research",
            ),
            [
                KnowledgeSource(
                    chunkId=31,
                    documentId=31,
                    score=0.9,
                    bookId=101,
                    bookName="Star River Old Dream",
                    sourceType="CHAPTER",
                    preview="Book evidence",
                )
            ],
            "single_book",
        )

        self.assertEqual(trend_messages[0]["content"], book_messages[0]["content"])
        self.assertIn("answerMode: trend", trend_messages[1]["content"])
        self.assertIn("answerMode: single_book", book_messages[1]["content"])

    def test_answer_boundary_prompt_includes_runtime_policy_for_latest_market(self) -> None:
        agent = NovelResearchAgent(provider_client=FakeAnswerProvider())
        messages = agent._build_answer_messages(
            KnowledgeChatRequest(question="recent urban trend", mode="research"),
            [
                KnowledgeSource(
                    chunkId=810,
                    documentId=810,
                    score=0.98,
                    bookId=810,
                    bookName="Fresh Rank Book",
                    sourceType="RANK",
                    rankNo=1,
                    snapshotTime="2026-06-22T00:00:00",
                    title="male-new / urban #1",
                    preview="fresh rank fact",
                )
            ],
            "trend",
            state={
                "source_policy": {"freshness": "latest", "requireSnapshotTime": True},
                "supervisor": {"status": "answerable", "freshnessSatisfied": True},
                "skill_prompt": "market skill",
            },
        )

        prompt = messages[1]["content"]
        self.assertIn("answer policy:", prompt)
        self.assertIn('"freshness": "latest"', prompt)
        self.assertIn('"status": "answerable"', prompt)
        self.assertIn("snapshotTime: 2026-06-22T00:00:00", prompt)

    def test_answer_boundary_prompt_separates_mixed_market_and_author_advice(self) -> None:
        agent = NovelResearchAgent(provider_client=FakeAnswerProvider())
        messages = agent._build_answer_messages(
            KnowledgeChatRequest(question="rank then outline", mode="research"),
            [
                KnowledgeSource(
                    chunkId=811,
                    documentId=811,
                    score=0.97,
                    bookId=811,
                    bookName="Fresh Rank Book",
                    sourceType="RANK",
                    rankNo=1,
                    snapshotTime="2026-06-22T00:00:00",
                    title="male-new / urban #1",
                    preview="fresh rank fact",
                )
            ],
            "mixed_creation",
            state={
                "source_policy": {"freshness": "latest", "requireSnapshotTime": True},
                "supervisor": {"status": "answerable"},
            },
        )

        prompt = messages[1]["content"]
        self.assertIn("boundaryRule: separate cited market evidence from author-side recommendations", prompt)
        self.assertIn("sourcePolicy:", prompt)

    def test_answer_boundary_prompt_states_historical_time_window(self) -> None:
        agent = NovelResearchAgent(provider_client=FakeAnswerProvider())
        messages = agent._build_answer_messages(
            KnowledgeChatRequest(question="last 30 days urban trend", mode="research"),
            [
                KnowledgeSource(
                    chunkId=812,
                    documentId=812,
                    score=0.9,
                    bookId=812,
                    bookName="Historical Rank Book",
                    sourceType="RANK",
                    rankNo=3,
                    snapshotTime="2026-05-22T00:00:00",
                    title="male-new / urban #3",
                    preview="historical rank fact",
                )
            ],
            "trend",
            state={
                "source_policy": {
                    "freshness": "time_window",
                    "allowHistorical": True,
                    "timeWindowDays": 30,
                    "requireSnapshotTime": True,
                },
                "supervisor": {"status": "answerable"},
            },
        )

        prompt = messages[1]["content"]
        self.assertIn("boundaryRule: state the historical time window before trend conclusions", prompt)
        self.assertIn('"timeWindowDays": 30', prompt)

    async def test_should_return_retrieval_and_answer_quality_diagnostics(self) -> None:
        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="What is the core setting technique?",
            bookId=101,
            bookName="Star River Old Dream",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        diagnostics = response.resultJson["diagnostics"]
        self.assertTrue(diagnostics["ragUsed"])
        self.assertEqual(1, diagnostics["sourceCount"])
        self.assertEqual(1, diagnostics["citationCount"])
        self.assertTrue(diagnostics["citationSatisfied"])
        self.assertGreaterEqual(diagnostics["maxSourceScore"], 0.9)
        self.assertIn("CHAPTER", diagnostics["sourceTypes"])
        retrieval_diagnostics = response.resultJson["retrievalDiagnostics"]
        self.assertGreaterEqual(retrieval_diagnostics["inputCount"], retrieval_diagnostics["selectedCount"])
        self.assertGreaterEqual(retrieval_diagnostics["dedupedCount"], retrieval_diagnostics["selectedCount"])
        self.assertGreaterEqual(retrieval_diagnostics["selectedSourceTypeCounts"]["CHAPTER"], 1)
        self.assertEqual(
            retrieval_diagnostics,
            response.resultJson["trace"]["diagnostics"]["retrieval"],
        )

    async def test_should_repair_uncited_answer_with_structured_cited_fallback(self) -> None:
        client = IndexedGlobalEvidenceKnowledgeClient()
        provider = UncitedAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="analyze recent web novel trends",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertIn("## 结论", response.answer)
        self.assertIn("## 证据", response.answer)
        self.assertIn("## 作者侧建议", response.answer)
        self.assertIn("[1]", response.answer)
        self.assertTrue(response.resultJson["citationRepairUsed"])
        diagnostics = response.resultJson["diagnostics"]
        self.assertEqual(1, diagnostics["citationCount"])
        self.assertTrue(diagnostics["citationSatisfied"])

    async def test_should_repair_out_of_range_citation_and_refresh_diagnostics(self) -> None:
        client = IndexedGlobalEvidenceKnowledgeClient()
        provider = OutOfRangeCitationProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="analyze recent web novel trends",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertIn("[1]", response.answer)
        self.assertNotIn("[9]", response.answer)
        self.assertTrue(response.resultJson["citationRepairUsed"])
        diagnostics = response.resultJson["diagnostics"]
        self.assertEqual(1, diagnostics["citationCount"])
        self.assertTrue(diagnostics["citationSatisfied"])

    async def test_stream_uses_compiled_graph_events_instead_of_manual_runtime_nodes(self) -> None:
        response = KnowledgeChatResponse(
            status="answered",
            answer="Graph generated final answer. [1]",
            candidates=[],
            sources=[
                KnowledgeSource(
                    chunkId=1,
                    documentId=2,
                    score=0.9,
                    sourceType="rank",
                    title="rank evidence",
                    preview="Graph generated final answer.",
                )
            ],
            actions=["graph_stream"],
            resultJson={
                "trace": {
                    "traceId": "trace-graph-stream",
                    "nodes": [],
                }
            },
        )
        graph = FakeCompiledStreamGraph(response)
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient(), provider_client=FakeAnswerProvider())
        agent._graph = graph

        async def fail_manual_node(_state):
            raise AssertionError("stream must not call manual runtime nodes")

        agent._intent_router_node = fail_manual_node

        events = [event async for event in agent.stream(KnowledgeChatRequest(question="recent market scan"))]

        self.assertEqual(1, len(graph.calls))
        self.assertEqual("updates", graph.calls[0]["stream_mode"])
        self.assertEqual("done", events[-1]["event"])
        self.assertEqual("answered", events[-1]["data"]["status"])
        deltas = [event["delta"] for event in events if event["event"] == "delta"]
        self.assertEqual(events[-1]["data"]["answer"], "".join(deltas))
        self.assertGreaterEqual(len(deltas), 1)
        trace = events[-1]["data"]["resultJson"]["trace"]
        self.assertEqual(
            ["assemble_context", "classify_intent", "finalize_trace"],
            trace["executedRuntimeNodes"],
        )
        nodes = {node["name"]: node["status"] for node in trace["nodes"]}
        self.assertEqual("completed", nodes["assemble_context"])
        self.assertEqual("skipped", nodes["validate_preconditions"])
        self.assertEqual("completed", nodes["finalize_trace"])

    async def test_stream_should_emit_graph_final_answer_before_done_for_indexed_book(self) -> None:
        client = FakeKnowledgeClient()
        provider = EmptyStreamingFallbackProvider("Graph final answer for indexed book. [1]")
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="Book Alpha setting?",
            bookId=101,
            bookName="鏄熸渤鏃фⅵ",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        events = [event async for event in agent.stream(request)]

        self.assertEqual("start", events[0]["event"])
        deltas = [event["delta"] for event in events if event["event"] == "delta"]
        done = events[-1]
        self.assertEqual("done", done["event"])
        self.assertEqual("answered", done["data"]["status"])
        self.assertEqual(done["data"]["answer"], "".join(deltas))
        self.assertGreaterEqual(len(deltas), 1)
        self.assertEqual(1, len(provider.stream_calls))
        self.assertEqual(1, len(provider.invoke_calls))

    async def test_stream_should_mark_route_experts_completed_when_experts_are_selected(self) -> None:
        client = StructuredRankTrendKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="鍏堢湅鐢烽閮藉競鑴戞礊鏂颁功姒淭op10锛屽啀甯垜寮€涓€鏈悓棰樻潗鏂颁功",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        events = [event async for event in agent.stream(request)]

        done = events[-1]["data"]
        selected_experts = done["resultJson"].get("selectedExperts") or []
        self.assertGreater(len(selected_experts), 0)
        trace = done["resultJson"]["trace"]
        self.assertIn("route_experts", trace["executedRuntimeNodes"])
        route_node = next(node for node in trace["nodes"] if node["name"] == "route_experts")
        self.assertEqual("completed", route_node["status"])
        self.assertGreater(route_node["selectedExpertCount"], 0)

    async def test_stream_should_pass_request_timeout_to_provider_for_long_knowledge_answer(self) -> None:
        client = FakeKnowledgeClient()
        provider = EmptyStreamingFallbackProvider("Graph timeout answer. [1]")
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="Book Alpha setting?",
            bookId=101,
            bookName="Book Alpha",
            mode="research",
            limits={"evidenceLimit": 5, "timeoutMillis": 543210},
        )

        events = [event async for event in agent.stream(request)]

        self.assertEqual("done", events[-1]["event"])
        self.assertEqual(1, len(provider.stream_calls))
        self.assertEqual(1, len(provider.invoke_calls))
        self.assertEqual(543210, provider.stream_calls[0]["timeout_millis"])
        self.assertEqual(543210, provider.invoke_calls[0]["timeout_millis"])

    async def test_stream_should_emit_progress_before_long_knowledge_generation(self) -> None:
        client = FakeKnowledgeClient()
        provider = StreamingProvider(["chunk one ", "chunk two [1]"])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="Book Alpha setting?",
            bookId=101,
            bookName="Book Alpha",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        events = [event async for event in agent.stream(request)]

        progress_phases = [event.get("phase") for event in events if event["event"] == "progress"]
        self.assertIn("intent", progress_phases)
        self.assertIn("evidence", progress_phases)
        self.assertIn("generate", progress_phases)
        progress_messages = [event.get("message") for event in events if event["event"] == "progress"]
        self.assertIn("正在整理会话上下文", progress_messages)
        self.assertIn("正在生成回答", progress_messages)
        self.assertLess(
            next(index for index, event in enumerate(events) if event.get("phase") == "generate"),
            next(index for index, event in enumerate(events) if event["event"] == "delta"),
        )

    async def test_stream_should_send_current_structured_rank_in_done_when_model_stream_is_stale(self) -> None:
        client = CurrentStructuredRankTrendKnowledgeClient()
        stale_stream_answer = "STALE_STREAM_ONLY [1]"
        provider = StreamingProvider([stale_stream_answer])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question=(
                "\u7ed9\u6211\u90fd\u5e02\u8111\u6d1e\u6700\u8fd1\u70ed\u95e8\u9898\u6750\uff0c"
                "\u8981\u7537\u9891\u7684\uff0c\u4ee5\u53ca\u6700\u8fd1\u70ed\u95e8\u7684\u4e66\u548c\u5bf9\u5e94\u5f00\u4e66\u5efa\u8bae"
            ),
            mode="research",
            limits={"evidenceLimit": 5},
        )

        events = [event async for event in agent.stream(request)]

        done = events[-1]
        self.assertEqual("done", done["event"])
        self.assertEqual("answered", done["data"]["status"])
        delta_text = "".join(event["delta"] for event in events if event["event"] == "delta")
        self.assertEqual(done["data"]["answer"], delta_text)
        self.assertEqual("RANK", done["data"]["sources"][0]["sourceType"])
        self.assertEqual(1, done["data"]["sources"][0]["rankNo"])
        self.assertEqual(client.lookup_rank_calls[0]["category"], done["data"]["sources"][0]["category"])
        self.assertIn(done["data"]["sources"][0]["bookName"], done["data"]["answer"])
        self.assertNotIn(stale_stream_answer, done["data"]["answer"])

    async def test_stream_should_refresh_stale_rank_before_answering_scan_and_outline(self) -> None:
        class ForcedMixedCreationRouter:
            def classify(self, *_args, **_kwargs) -> IntentDecision:
                return IntentDecision(
                    primaryIntent=Intent.mixed_creation_research,
                    subIntents=[Intent.market_scan, Intent.opening_strategy, Intent.outline_building],
                    confidence=0.95,
                    toolNeeds=ToolNeeds(needsRankData=True, needsCreativeGeneration=True, needsSkillPack=True),
                    answerBoundary=AnswerBoundary.market_evidence_plus_author_inference,
                    sourcePolicy={
                        "freshness": "latest",
                        "allowHistorical": False,
                        "requireSnapshotTime": True,
                    },
                    routingNotes=["test:forced-mixed-creation"],
                )

        client = RefreshDrivenStaleSnapshotTrendKnowledgeClient()
        provider = StreamingProvider(["fresh streamed trend outline [1]"])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        agent.intent_router = ForcedMixedCreationRouter()
        agent._parse_trend_rank_lookup_for_request = lambda _request: {
            "platform": "fanqie",
            "channel_code": "male-new",
            "board_code": "urban-brain",
            "category": "urban-brain",
            "rank_no": None,
            "limit": 10,
        }
        request = KnowledgeChatRequest(
            question=(
                "浣犲府鎴戞壂姒滅敺棰戦兘甯傝剳娲烇紝缁欐垜浜涘紑鏂囧缓璁紝"
                "杩樻湁鐩墠閮芥槸鍝簺棰樻潗锛屾垜瑕佸啓搴曞眰鑱屼笟鐨勫ぇ绾?"
            ),
            mode="research",
        )

        events = [event async for event in agent.stream(request)]

        done = events[-1]["data"]
        self.assertEqual("answered", done["status"])
        self.assertEqual(1, len(client.refresh_rank_board_calls))
        self.assertEqual(2, len(client.lookup_rank_calls))
        self.assertEqual({"market_refresh": 1}, done["resultJson"]["retryCounts"])
        self.assertFalse(done["resultJson"]["sourcePolicy"]["trendGateFailed"])
        self.assertEqual(client.fresh_snapshot_time, done["sources"][0]["snapshotTime"])

    async def test_stream_should_not_block_lookup_only_snapshotless_rank_for_mixed_creation(self) -> None:
        client = LookupOnlySnapshotlessTopTenRankTrendKnowledgeClient()
        provider = StreamingProvider(["fresh streamed trend outline [1]"])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question=(
                "现在我要写一篇底层职业的都市脑洞文，结合当前榜单趋势你觉得如何，"
                "可以给出我一些大纲吗，我设计是都市里有诸天万界外包来做特效，"
                "金手指采用“三端一体”的形态。"
            ),
            mode="research",
        )

        events = [event async for event in agent.stream(request)]

        done = events[-1]["data"]
        self.assertEqual("answered", done["status"], done["resultJson"])
        self.assertEqual("mixed_creation", done["resultJson"]["answerMode"])
        self.assertTrue(done["resultJson"]["sourcePolicy"]["latestRankEvidenceDegraded"])
        self.assertEqual({}, done["resultJson"].get("retryCounts") or {})
        self.assertGreaterEqual(len(done["sources"]), 10)

    async def test_exact_mixed_creation_prompt_should_repair_shallow_model_answer_with_quality_gate(self) -> None:
        client = LookupOnlySnapshotlessTopTenRankTrendKnowledgeClient()
        provider = MixedCreationRepairProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        agent.intent_router = ForcedMixedCreationRouter()
        agent._parse_trend_rank_lookup_for_request = lambda _request: {
            "platform": "fanqie",
            "channel_code": "male-new",
            "board_code": "urban-brain",
            "category": "urban-brain",
            "rank_no": None,
            "limit": 10,
        }
        request = KnowledgeChatRequest(
            question=(
                "现在我要写一篇底层职业的都市脑洞文，结合当前榜单趋势你觉得如何，可以给出我一些大纲吗，"
                "我设计是都市里有诸天万界外包来做特效，金手指采用“三端一体”的形态。"
            ),
            mode="research",
            reasoningMode="deep",
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status, response.resultJson)
        self.assertFalse(response.resultJson.get("fallbackUsed"))
        self.assertEqual("passed", response.resultJson.get("answerQuality", {}).get("status"))
        self.assertTrue(response.resultJson.get("answerQuality", {}).get("repaired"))
        self.assertGreaterEqual(len(provider.invoke_calls), 2)
        self.assertGreaterEqual(len(response.resultJson.get("selectedExperts") or []), 1)
        for term in ["底层职业", "都市脑洞", "诸天万界", "外包", "特效", "三端一体", "前三章", "十章"]:
            self.assertIn(term, response.answer)
        self.assertNotIn("围绕榜一身份反差", response.answer)
        tool_runs = response.resultJson.get("toolRuns") or []
        for run in tool_runs:
            name = str(run.get("name") or "")
            if "." not in name and "_" in name:
                self.assertEqual("system_internal", run.get("plane"))
            if run.get("legacyName"):
                self.assertEqual("system_internal", run.get("plane"))
        health = response.resultJson.get("trace", {}).get("health") or {}
        self.assertEqual("succeeded", health.get("model"))
        self.assertIn("providerCalls", response.resultJson)
        provider_calls = response.resultJson.get("providerCalls") or []
        first_answer_call = next(call for call in provider_calls if call.get("node") == "compose_answer")
        self.assertEqual("deep", first_answer_call.get("requestedReasoningMode"))
        self.assertEqual("deepseek-chat", first_answer_call.get("actualModel"))
        self.assertTrue(first_answer_call.get("thinkingEnabled"))
        self.assertEqual(90, first_answer_call.get("promptCacheHitTokens"))
        self.assertEqual(40, first_answer_call.get("promptCacheMissTokens"))

    async def test_reported_top30_prompt_should_preserve_full_rank_limit_and_not_claim_only_top10(self) -> None:
        client = LookupOnlySnapshotlessTopTenRankTrendKnowledgeClient()
        provider = MixedCreationRepairProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        agent.intent_router = ForcedMixedCreationRouter()
        request = KnowledgeChatRequest(
            question=(
                "现在我要写一篇底层职业的都市脑洞文，结合当前榜单趋势你觉得如何，"
                "整体30名榜单各种题材趋势如何，男频都市脑洞的新书榜，"
                "可以给出我一些大纲吗，我设计是都市里有诸天万界外包来做特效，"
                "金手指采用“三端一体”的形态。还有我这个题材贴合市场吗，类似题材的书有哪些"
            ),
            mode="research",
            reasoningMode="deep",
            limits={"rankLimit": 30, "evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status, response.resultJson)
        self.assertGreaterEqual(client.lookup_rank_calls[0]["limit"], 30)
        self.assertGreaterEqual(len([source for source in response.sources if source.sourceType == "RANK"]), 30)
        self.assertNotIn("未获取完整30名榜单", response.answer)
        self.assertNotIn("Top10", response.answer)
        self.assertIn("#30", response.answer)

    async def test_top30_phrase_without_top_prefix_should_parse_to_thirty_rank_items(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient())
        request = KnowledgeChatRequest(
            question="帮我看男频都市脑洞新书榜整体30名榜单各种题材趋势",
            mode="research",
        )

        lookup = agent._parse_trend_rank_lookup_for_request(request)

        self.assertIsNotNone(lookup)
        self.assertGreaterEqual(lookup["limit"], 30)

    async def test_deep_reasoning_mode_should_use_deep_model_for_main_answer_by_default(self) -> None:
        client = LookupOnlySnapshotlessTopTenRankTrendKnowledgeClient()
        provider = MixedCreationRepairProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        agent.intent_router = ForcedMixedCreationRouter()
        request = KnowledgeChatRequest(
            question="现在我要写都市脑洞，先看男频都市脑洞新书榜Top30，再给我大纲方向",
            mode="research",
            reasoningMode="deep",
            limits={"rankLimit": 30},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status, response.resultJson)
        first_answer_call = next(call for call in provider.invoke_calls if not call.get("require_json"))
        self.assertEqual("deepseek-v4-pro", first_answer_call["model"])
        provider_trace = next(call for call in response.resultJson["providerCalls"] if call.get("node") == "compose_answer")
        self.assertEqual("deepseek-v4-pro", provider_trace.get("requestedModel"))
        self.assertEqual("deep", provider_trace.get("requestedReasoningMode"))

    async def test_memory_health_should_be_partial_when_thread_summary_loaded_but_project_memory_unavailable(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient())
        result = {
            "sourceCount": 1,
            "memoryDiagnostics": {
                "layers": {
                    "conversationSummary": {"status": "loaded"},
                    "projectMemory": {"status": "unavailable", "reason": "RuntimeError"},
                }
            },
            "contextUsed": {
                "hasThreadSummary": True,
                "memoryContext": {
                    "conversationSummary": {"summary": "上一轮讨论了底层特效师和三端一体"}
                },
            },
            "contextBudget": {
                "memoryLayers": {
                    "threadSummary": {"status": "loaded"},
                    "projectMemory": {"status": "unavailable"},
                }
            },
        }

        health = agent._trace_health_for_result(result, {})

        self.assertEqual("partial", health["memory"])

    async def test_mixed_creation_provider_failure_should_be_degraded_and_trace_visible(self) -> None:
        client = LookupOnlySnapshotlessTopTenRankTrendKnowledgeClient()
        provider = FailingAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        agent.intent_router = ForcedMixedCreationRouter()
        agent._parse_trend_rank_lookup_for_request = lambda _request: {
            "platform": "fanqie",
            "channel_code": "male-new",
            "board_code": "urban-brain",
            "category": "urban-brain",
            "rank_no": None,
            "limit": 10,
        }
        request = KnowledgeChatRequest(
            question=(
                "现在我要写一篇底层职业的都市脑洞文，结合当前榜单趋势你觉得如何，可以给出我一些大纲吗，"
                "我设计是都市里有诸天万界外包来做特效，金手指采用“三端一体”的形态。"
            ),
            mode="research",
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status, response.resultJson)
        self.assertTrue(response.resultJson.get("fallbackUsed"))
        self.assertTrue(response.resultJson.get("degraded"))
        self.assertEqual("degraded_model_fallback", response.resultJson.get("answerStatus"))
        provider_calls = response.resultJson.get("providerCalls") or []
        self.assertTrue(any(call.get("status") == "failed" for call in provider_calls), provider_calls)
        self.assertTrue(any(call.get("errorType") == "RuntimeError" for call in provider_calls), provider_calls)
        health = response.resultJson.get("trace", {}).get("health") or {}
        self.assertEqual("fallback_used", health.get("model"))

    async def test_stream_should_emit_provider_answer_deltas_from_compiled_graph(self) -> None:
        client = FakeKnowledgeClient()
        provider = StreamingAnswerProvider([
            "底层职业特效小工接单，",
            "诸天万界外包团队入场，",
            "三端一体系统把交付热度变成升级权限。[1]",
        ], done_event={
            "event": "done",
            "tokenUsed": 180,
            "promptCacheHitTokens": 120,
            "promptCacheMissTokens": 30,
            "usage": {
                "promptTokens": 150,
                "completionTokens": 30,
                "totalTokens": 180,
                "promptCacheHitTokens": 120,
                "promptCacheMissTokens": 30,
            },
        })
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="Book Alpha setting?",
            bookId=101,
            bookName="Book Alpha",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        events = [event async for event in agent.stream(request)]

        deltas = [event["delta"] for event in events if event["event"] == "delta"]
        done = events[-1]
        self.assertEqual("done", done["event"])
        self.assertGreater(len(deltas), 1)
        self.assertEqual(done["data"]["answer"], "".join(deltas))
        self.assertEqual(1, len(provider.stream_calls))
        provider_calls = done["data"]["resultJson"].get("providerCalls") or []
        answer_call = next(call for call in provider_calls if call.get("node") == "compose_answer")
        self.assertEqual(120, answer_call.get("promptCacheHitTokens"))
        self.assertEqual(30, answer_call.get("promptCacheMissTokens"))

    async def test_stream_should_not_wait_forever_on_slow_vector_search_for_rank_imitation_outline(self) -> None:
        client = SlowVectorCurrentRankKnowledgeClient()
        provider = StreamingProvider(["围绕榜一身份反差做同题材大纲。[1]"])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="根据当前男频新书榜都市脑洞第一的书，我要模仿出对应的题材和大纲，该怎么设计",
            mode="research",
            limits={"evidenceLimit": 5, "toolTimeoutMillis": 20, "timeoutMillis": 600000},
        )

        events = [event async for event in agent.stream(request)]

        self.assertEqual("done", events[-1]["event"])
        self.assertEqual("answered", events[-1]["data"]["status"])
        self.assertEqual("RANK", events[-1]["data"]["sources"][0]["sourceType"])
        self.assertEqual(1, events[-1]["data"]["sources"][0]["rankNo"])
        self.assertIn("长生两十六亿年，被妹妹首播曝光", events[-1]["data"]["answer"])
        progress_phases = [event.get("phase") for event in events if event["event"] == "progress"]
        self.assertIn("evidence", progress_phases)
        self.assertIn("generate", progress_phases)
        self.assertGreaterEqual(len(client.lookup_rank_calls), 1)
        self.assertGreaterEqual(len(client.search_evidence_calls), 1)
        self.assertEqual(1, len(provider.stream_calls))
        self.assertEqual(600000, provider.stream_calls[0]["timeout_millis"])

    async def test_stream_should_emit_single_graph_delta_when_provider_stream_is_empty(self) -> None:
        client = FakeKnowledgeClient()
        provider = EmptyStreamingFallbackProvider(
            "This fallback answer is intentionally long enough to be split into several visible chunks for the frontend. [1]"
        )
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="Book Alpha setting?",
            bookId=101,
            bookName="鏄熸渤鏃фⅵ",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        events = [event async for event in agent.stream(request)]

        deltas = [event["delta"] for event in events if event["event"] == "delta"]
        done = events[-1]
        self.assertEqual("done", done["event"])
        self.assertEqual(done["data"]["answer"], "".join(deltas))
        self.assertGreater(len(deltas), 1)
        self.assertEqual(1, len(provider.stream_calls))
        self.assertEqual(1, len(provider.invoke_calls))

    async def test_stream_should_pass_request_timeout_to_invoke_fallback_after_empty_stream(self) -> None:
        client = FakeKnowledgeClient()
        provider = EmptyStreamingFallbackProvider(
            "Fallback answer should inherit the same long timeout budget as the stream call. [1]"
        )
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="Book Alpha setting?",
            bookId=101,
            bookName="Book Alpha",
            mode="research",
            limits={"evidenceLimit": 5, "timeoutMillis": 456789},
        )

        events = [event async for event in agent.stream(request)]

        self.assertEqual("done", events[-1]["event"])
        self.assertEqual(1, len(provider.stream_calls))
        self.assertEqual(1, len(provider.invoke_calls))
        self.assertEqual(456789, provider.stream_calls[0]["timeout_millis"])
        self.assertEqual(456789, provider.invoke_calls[0]["timeout_millis"])

    async def test_stream_should_not_emit_partial_provider_stream_when_graph_driven(self) -> None:
        client = FakeKnowledgeClient()
        provider = FailingAfterPartialStreamProvider(
            "Recovered complete fallback answer after stream interruption. [1]"
        )
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="Book Alpha setting?",
            bookId=101,
            bookName="鏄熸渤鏃фⅵ",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        events = [event async for event in agent.stream(request)]

        delta_text = "".join(event["delta"] for event in events if event["event"] == "delta")
        done = events[-1]
        self.assertNotIn("partial stale answer [1]", delta_text)
        self.assertNotIn("partial stale answer [1]", done["data"]["answer"])
        self.assertEqual(done["data"]["answer"], delta_text)
        self.assertTrue(done["data"]["resultJson"].get("fallbackUsed"))
        self.assertTrue(done["data"]["resultJson"].get("degraded"))
        self.assertEqual(1, len(provider.stream_calls))

    async def test_stream_done_should_match_blocking_metadata_for_mixed_rank_creative_request(self) -> None:
        question = "根据当前男频新书榜都市脑洞第一的书，我要模仿出对应的题材和大纲，该怎么设计"
        request = KnowledgeChatRequest(
            question=question,
            mode="research",
            limits={"evidenceLimit": 5},
        )
        blocking_agent = NovelResearchAgent(
            knowledge_client=CurrentStructuredRankTrendKnowledgeClient(),
            provider_client=FakeAnswerProvider(),
        )
        streaming_agent = NovelResearchAgent(
            knowledge_client=CurrentStructuredRankTrendKnowledgeClient(),
            provider_client=StreamingProvider(["围绕榜一身份反差做同题材大纲。[1]"]),
        )

        blocking_response = await blocking_agent.run(request)
        events = [event async for event in streaming_agent.stream(request)]
        done = events[-1]["data"]

        for key in (
            "intent",
            "domainIntent",
            "answerMode",
            "answerStatus",
            "answerBoundary",
            "domainAnswerBoundary",
            "toolPlan",
            "selectedSkills",
            "specialistAgents",
            "budgets",
            "materialChars",
        ):
            self.assertEqual(blocking_response.resultJson[key], done["resultJson"][key])
        self.assertEqual(blocking_response.status, done["status"])
        self.assertEqual(
            [source.sourceType for source in blocking_response.sources],
            [source["sourceType"] for source in done["sources"]],
        )
        self.assertEqual(blocking_response.sources[0].rankNo, done["sources"][0]["rankNo"])

    async def test_stream_should_finalize_memory_telemetry_and_truthful_trace(self) -> None:
        client = StreamFinalizationKnowledgeClient()
        provider = StreamingProvider(["Market-backed answer with a citation [1]"])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="long-term preference: urban brain stories with fast public feedback",
            mode="research",
            userId=77,
            projectId=901,
            traceId="trace-stream-finalize-001",
            limits={"evidenceLimit": 5},
        )

        events = [event async for event in agent.stream(request)]

        done = events[-1]["data"]
        self.assertEqual("done", events[-1]["event"])
        self.assertEqual(1, done["resultJson"].get("memoryCandidatesPersisted"))
        memory_diagnostics = done["resultJson"]["memoryDiagnostics"]
        self.assertEqual(1, memory_diagnostics["candidatePersistence"]["saved"])
        self.assertEqual(0, memory_diagnostics["candidatePersistence"]["failed"])
        self.assertEqual(
            memory_diagnostics,
            done["resultJson"]["trace"]["diagnostics"]["memory"],
        )
        self.assertEqual(1, len(client.memory_candidate_calls))
        self.assertEqual(1, len(client.telemetry_calls))
        self.assertEqual("trace-stream-finalize-001", client.telemetry_calls[0]["traceId"])
        self.assertTrue(any(event.get("cacheStatus") == "BYPASS" for event in client.telemetry_calls[0]["cacheEvents"]))
        nodes = {
            node["name"]: node
            for node in done["resultJson"]["trace"]["nodes"]
        }
        self.assertEqual("completed", nodes["validate_preconditions"]["status"])
        self.assertEqual("completed", nodes["extract_memory_candidates"]["status"])
        self.assertEqual("completed", nodes["finalize_trace"]["status"])
        self.assertIn("validate_preconditions", done["resultJson"]["trace"]["executedRuntimeNodes"])

    async def test_trace_nodes_should_not_claim_unexecuted_runtime_stages(self) -> None:
        agent = NovelResearchAgent(
            knowledge_client=CurrentStructuredRankTrendKnowledgeClient(),
            provider_client=FakeAnswerProvider(),
        )
        request = KnowledgeChatRequest(
            question="Trace status?",
            traceId="trace-status-001",
        )
        response = KnowledgeChatResponse(
            status="answered",
            answer="answer [1]",
            candidates=[],
            sources=[
                KnowledgeSource(
                    sourceType="RANK",
                    bookName="Rank One",
                    preview="Rank evidence",
                    rankNo=1,
                )
            ],
            actions=[],
            resultJson={
                "intent": "trend_research",
                "taskGraph": {"tasks": []},
                "sourcePolicy": {},
                "contextUsed": {},
            },
        )

        nodes = agent._runtime_nodes_for_trace(
            response,
            response.resultJson,
            {
                "request": request,
                "executed_runtime_nodes": ["assemble_context", "classify_intent"],
            },
        )

        by_name = {node["name"]: node for node in nodes}
        self.assertEqual("completed", by_name["assemble_context"]["status"])
        self.assertEqual("completed", by_name["classify_intent"]["status"])
        self.assertEqual("skipped", by_name["validate_preconditions"]["status"])
        self.assertEqual("skipped", by_name["finalize_trace"]["status"])

    async def test_runtime_nodes_should_not_default_unknown_duration_to_zero(self) -> None:
        agent = NovelResearchAgent(
            knowledge_client=CurrentStructuredRankTrendKnowledgeClient(),
            provider_client=FakeAnswerProvider(),
        )
        request = KnowledgeChatRequest(question="Trace timing?", traceId="trace-timing-001")
        response = KnowledgeChatResponse(
            status="answered",
            answer="answer [1]",
            candidates=[],
            sources=[
                KnowledgeSource(
                    sourceType="RANK",
                    bookName="Rank One",
                    preview="Rank evidence",
                    rankNo=1,
                )
            ],
            actions=[],
            resultJson={
                "intent": "trend_research",
                "taskGraph": {"tasks": []},
                "sourcePolicy": {},
                "contextUsed": {},
            },
        )

        nodes = agent._runtime_nodes_for_trace(
            response,
            response.resultJson,
            {
                "request": request,
                "executed_runtime_nodes": ["assemble_context", "classify_intent"],
            },
        )

        self.assertTrue(all("durationMs" not in node for node in nodes), nodes)

    async def test_should_include_compressed_context_in_rag_retrieval_query(self) -> None:
        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="那它的设定是什么？",
            bookId=101,
            bookName="星河旧梦",
            mode="research",
            contextSummary="current book: 星河旧梦; previous goal: explain opening hook",
            history=[
                {"role": "user", "content": "星河旧梦的开篇卖点是什么？"},
                {"role": "assistant", "content": "它围绕旧星门坐标推进目标。[1]"},
            ],
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        query = client.search_evidence_calls[0]["query"]
        self.assertIn("那它的设定是什么", query)
        self.assertIn("current book: 星河旧梦", query)
        self.assertIn("星河旧梦的开篇卖点", query)

    async def test_should_contextualize_followup_pronoun_query_without_full_history_bloat(self) -> None:
        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="它的核心设定是什么？",
            bookId=101,
            bookName="星河旧梦",
            mode="research",
            contextSummary="当前作品：星河旧梦\n最近用户目标：研究开篇卖点\n上一轮结论：" + ("旧星门坐标推动目标。" * 120),
            history=[
                {"role": "user", "content": "星河旧梦的开篇卖点是什么？" + ("补充文本" * 80)},
                {"role": "assistant", "content": "它围绕旧星门坐标建立探索目标。[1]" + ("详细说明" * 80)},
            ],
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        query = client.search_evidence_calls[0]["query"]
        self.assertIn("星河旧梦", query)
        self.assertIn("核心设定", query)
        self.assertLessEqual(len(query), 900)
        self.assertNotIn("详细说明" * 20, query)

    async def test_should_return_conversation_memory_metadata(self) -> None:
        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="星河旧梦的核心设定是什么？",
            conversationId="conv-123",
            bookId=101,
            bookName="星河旧梦",
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("conv-123", response.resultJson["conversationId"])
        self.assertIn("星河旧梦", response.resultJson["memorySummary"])
        self.assertIn("最近用户目标", response.resultJson["memorySummary"])

    async def test_should_build_topic_focused_retrieval_query_for_trend_question(self) -> None:
        client = FakeKnowledgeClient()
        agent = NovelResearchAgent(knowledge_client=client)
        request = KnowledgeChatRequest(
            question="分析一下番茄最近大火的退伍入伍题材，都市脑洞方向",
            mode="research",
            contextSummary="上一轮在聊凡人修仙传，不应污染本轮趋势检索",
            history=[
                {"role": "user", "content": "凡人修仙传的设定是什么？"},
                {"role": "assistant", "content": "凡人修仙传是修仙题材。[1]"},
            ],
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("insufficient_evidence", response.status)
        self.assertEqual("needs_required_evidence", response.resultJson["answerStatus"])
        query = client.search_evidence_calls[0]["query"]
        self.assertIn("退伍入伍", query)
        self.assertIn("都市脑洞", query)
        self.assertIn("题材趋势", query)
        self.assertNotIn("凡人修仙传", query)

    async def test_should_prioritize_explicit_book_id_over_creative_advice_route(self) -> None:
        client = FakeKnowledgeClient()
        provider = FakeAnswerProvider()
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="凡人修仙传前三章主线冲突是什么？",
            bookId=101,
            mode="research",
            limits={"evidenceLimit": 5},
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual(101, client.search_evidence_calls[0]["book_id"])
        self.assertEqual(1, len(response.sources))
        self.assertEqual(1, len(provider.invoke_calls))
        self.assertIn("[1]", response.answer)

    async def test_should_refuse_when_no_evidence_is_available(self) -> None:
        client = FakeKnowledgeClient()
        agent = NovelResearchAgent(knowledge_client=client)
        request = KnowledgeChatRequest(
            question="这本书后续会怎么写？",
            bookId=202,
            bookName="未知书",
            mode="research",
        )

        response = await agent.run(request)

        self.assertEqual("insufficient_evidence", response.status)
        self.assertEqual("needs_data", response.resultJson["answerStatus"])
        self.assertEqual([], response.sources)
        self.assertIn("证据不足", response.answer)
        self.assertIn("index_book", response.actions)

    async def test_should_return_insufficient_evidence_when_evidence_search_fails(self) -> None:
        client = FailingEvidenceKnowledgeClient()
        agent = NovelResearchAgent(knowledge_client=client)
        request = KnowledgeChatRequest(
            question="continue analysis",
            bookId=101,
            bookName="Book A",
            mode="research",
        )

        response = await agent.run(request)

        self.assertEqual("insufficient_evidence", response.status)
        self.assertEqual([], response.sources)
        self.assertIn("index_book", response.actions)
        self.assertEqual(101, response.resultJson["bookId"])

    async def test_should_refuse_out_of_scope_question_with_ai_guardrail(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([
            '{"inScope": false, "intent": "out_of_scope", "bookName": null}'
        ])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(question="明天香港天气怎么样？", mode="research")

        response = await agent.run(request)

        self.assertEqual("out_of_scope", response.status)
        self.assertEqual("out_of_scope", response.resultJson["businessRoute"])
        self.assertIn("网文", response.answer)
        self.assertEqual([], client.search_books_calls)
        self.assertEqual([], client.search_evidence_calls)
        self.assertEqual([], provider.invoke_calls)

    async def test_should_refuse_obvious_non_novel_daily_life_question_without_ai(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(question="番茄炒蛋怎么做？", mode="research")

        response = await agent.run(request)

        self.assertEqual("out_of_scope", response.status)
        self.assertEqual([], client.search_books_calls)
        self.assertEqual([], client.search_evidence_calls)
        self.assertEqual([], provider.invoke_calls)

    async def test_should_refuse_news_and_entertainment_questions_without_rag_or_ai(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        for question in ("今天有什么新闻？", "最近娱乐圈有什么八卦？"):
            with self.subTest(question=question):
                response = await agent.run(KnowledgeChatRequest(question=question, mode="research"))

                self.assertEqual("out_of_scope", response.status)
                self.assertIn("网文", response.answer)
                self.assertEqual([], client.search_books_calls)
                self.assertEqual([], client.search_evidence_calls)
                self.assertEqual([], provider.invoke_calls)

    async def test_should_refuse_travel_and_food_questions_without_rag_or_ai(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        for question in ("帮我规划明天旅游攻略", "明天吃什么比较好？", "推荐附近餐厅"):
            with self.subTest(question=question):
                response = await agent.run(KnowledgeChatRequest(question=question, mode="research"))

                self.assertEqual("out_of_scope", response.status)
                self.assertIn("网文", response.answer)
                self.assertEqual([], client.search_books_calls)
                self.assertEqual([], client.search_evidence_calls)
                self.assertEqual([], provider.invoke_calls)

    async def test_should_refuse_professional_advice_outside_web_novel_without_rag_or_ai(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        for question in ("帮我写一个 Python 接口", "股票明天能买吗？", "感冒吃什么药？", "帮我写一份借款合同"):
            with self.subTest(question=question):
                response = await agent.run(KnowledgeChatRequest(question=question, mode="research"))

                self.assertEqual("out_of_scope", response.status)
                self.assertIn("网文", response.answer)
                self.assertEqual([], client.search_books_calls)
                self.assertEqual([], client.search_evidence_calls)
                self.assertEqual([], provider.invoke_calls)

    async def test_should_keep_entertainment_novel_topics_in_scope(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([
            '{"inScope": true, "intent": "creative_advice", "bookName": null}',
            "娱乐圈文开局要先建立职业目标、舆论压力和情绪反馈。"
        ])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(question="娱乐圈文开局怎么设计爽点？", mode="research")

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual([], client.search_books_calls)
        self.assertEqual([], client.search_evidence_calls)
        self.assertEqual(2, len(provider.invoke_calls))
        self.assertTrue(provider.invoke_calls[0]["require_json"])
        self.assertFalse(provider.invoke_calls[1]["require_json"])

    async def test_should_use_larger_generation_budget_for_outline_creative_requests(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([
            "## 核心定位\n男频都市脑洞长篇大纲。\n\n## 三卷大纲\n第一卷...",
        ])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question="我要写男频都市脑洞，帮我做一个30万字三卷大纲",
            mode="research",
        )

        answer, fallback_used = await agent._compose_creative_answer(request)

        self.assertFalse(fallback_used)
        self.assertIn("三卷大纲", answer)
        self.assertGreaterEqual(provider.invoke_calls[0]["max_tokens"], 64_000)
        self.assertIn("完整大纲", provider.invoke_calls[0]["messages"][1]["content"])
        self.assertIn("30万字", provider.invoke_calls[0]["messages"][1]["content"])

    async def test_should_limit_concurrent_llm_calls_for_knowledge_agent(self) -> None:
        original = settings.max_active_llm_calls
        settings.max_active_llm_calls = 1
        provider = ConcurrencyProbeProvider()
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient(), provider_client=provider)
        request = KnowledgeChatRequest(
            question="给我三个都市脑洞新书方向",
            mode="research",
        )
        try:
            await asyncio.gather(agent._compose_creative_answer(request), agent._compose_creative_answer(request))
        finally:
            settings.max_active_llm_calls = original

        self.assertEqual(1, provider.max_active)

    async def test_should_treat_example_title_outline_request_as_creative_not_book_selection(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([
            "## 前三章正文开头\n第一章从林北被开除写起，第二章写技能反击，第三章写前女友回头和系统倒计时。"
        ])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(
            question=(
                "帮我写3000字开头（前三章）\n"
                "大纲设计（以“模拟器+逆袭”为例）\n"
                "书名示例：《我的逆袭模拟器》\n\n"
                "一句话简介：被所有人当成废物的林北，激活了人生模拟器。\n"
                "第1章：主角林北被公司开除，女友提出分手，绝望之际激活人生模拟器。\n"
                "第2章：现实中使用高级编程技能解决前公司技术难题。\n"
                "第3章：前女友想复合，主角冷漠拒绝。"
            ),
            mode="research",
        )

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertEqual([], client.search_books_calls)
        self.assertEqual([], client.search_evidence_calls)
        self.assertEqual("creative_advice", response.resultJson["intent"])
        self.assertEqual("chapter_outline", response.resultJson["domainIntent"])
        self.assertEqual("creative", response.resultJson["answerMode"])
        self.assertIn("webnovel-chapter-outline", response.resultJson["selectedSkills"])
        self.assertIn("chapter_outline", response.resultJson["specialistAgents"])
        self.assertNotIn("我的逆袭模拟器", str(response.resultJson.get("bookName", "")))
        self.assertFalse(provider.invoke_calls[0]["require_json"])

    def test_should_preserve_larger_conversation_context_for_long_followups(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient())
        request = KnowledgeChatRequest(
            question="继续上一版男频大纲，把反派线加进去",
            contextSummary="男频都市脑洞主线".ljust(700_000, "甲"),
            history=[
                {"role": "user", "content": f"第{i}轮讨论".ljust(40_000, "乙")}
                for i in range(10)
            ],
        )

        formatted = agent._format_conversation_context(request)

        self.assertIn("compressed summary", formatted)
        self.assertIn("第9轮讨论", formatted)
        self.assertGreater(len(formatted), 850_000)
        self.assertLessEqual(len(formatted), 920_000)

    async def test_trace_should_distinguish_long_context_creative_policy_from_fact_grounding(self) -> None:
        trend_client = StructuredRankTrendKnowledgeClient()
        trend_provider = FakeAnswerProvider()
        trend_agent = NovelResearchAgent(knowledge_client=trend_client, provider_client=trend_provider)
        trend_response = await trend_agent.run(KnowledgeChatRequest(
            question="帮我看番茄男频都市脑洞新书榜Top10，最近什么题材上升？",
            mode="research",
            contextSummary="上一轮聊过另一本修仙文，不应成为榜单事实来源。",
            limits={"evidenceLimit": 5},
        ))

        creative_provider = ScriptedProvider([
            "## 三卷大纲\n第一卷建立都市身份反差，第二卷扩展势力，第三卷收束主线。",
        ])
        creative_agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient(), provider_client=creative_provider)
        creative_response = await creative_agent.run(KnowledgeChatRequest(
            question="继续上一版，扩成完整三卷大纲",
            mode="research",
            contextSummary="男频都市脑洞主线".ljust(700_000, "甲"),
            history=[
                {"role": "user", "content": f"第{i}轮讨论".ljust(40_000, "乙")}
                for i in range(10)
            ],
        ))

        self.assertEqual("evidence_first_fact_grounding", trend_response.resultJson["trace"]["promptPolicy"])
        self.assertEqual(["RANK", "CHAPTER", "CHAPTER_PACK"], trend_response.resultJson["trace"]["sourcePriority"][:3])
        self.assertEqual("long_context_creative_continuation", creative_response.resultJson["trace"]["promptPolicy"])
        self.assertGreater(creative_response.resultJson["trace"]["contextChars"], 850_000)
        self.assertEqual([], creative_response.resultJson["trace"]["sourceTypes"])

    def test_should_layer_context_without_losing_sticky_webnovel_intent(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient())
        request = KnowledgeChatRequest(
            question="继续上一版，把第三卷细纲和开文钩子补全",
            contextSummary=(
                "旧摘要".ljust(760_000, "甲")
                + "\n最近用户目标：男频都市脑洞扫榜后做三卷大纲"
                + "\n最近意图：outline_creation"
                + "\n上一轮结论：长生自首流适合男频新书榜开文。"
            ),
            history=[
                {"role": "user", "content": "上一轮明确只看男频，不切女频。"},
                {"role": "assistant", "content": "已按男频都市脑洞方向给出大纲。"},
            ],
        )

        formatted = agent._format_conversation_context(request)

        self.assertIn("current question:", formatted)
        self.assertIn("继续上一版，把第三卷细纲和开文钩子补全", formatted)
        self.assertIn("sticky context:", formatted)
        self.assertIn("男频", formatted)
        self.assertIn("都市脑洞", formatted)
        self.assertIn("大纲", formatted)
        self.assertIn("细纲", formatted)
        self.assertLessEqual(len(formatted), 900_000 + 3)

    def test_should_inherit_market_channel_from_context_for_followup_trend_lookup(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient())
        request = KnowledgeChatRequest(
            question="最近新书榜题材趋势是什么？",
            contextSummary="最近用户目标：男频都市脑洞扫榜和开书建议\n上一轮结论：男频新书榜重点看都市脑洞。",
        )

        lookup = agent._parse_trend_rank_lookup_for_request(request)

        self.assertIsNotNone(lookup)
        self.assertEqual("male-new", lookup["channel_code"])
        self.assertEqual("都市脑洞", lookup["category"])

    def test_should_merge_skill_lookup_results_into_runtime_skill_prompt(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient())
        state = {
            "selected_skills": ["webnovel-market-scan"],
            "skill_prompt": "market skill",
            "tool_runs": [],
        }
        run = ToolRun(
            name="skill.lookup",
            status="succeeded",
            toolset="skill",
            output={
                "selectedSkills": ["webnovel-outline-building"],
                "prompt": "outline skill",
            },
            resultCount=1,
        )

        agent._merge_task_tool_runs(state, [run])

        self.assertEqual(["webnovel-market-scan", "webnovel-outline-building"], state["selected_skills"])
        self.assertIn("market skill", state["skill_prompt"])
        self.assertIn("outline skill", state["skill_prompt"])

    def test_should_reserve_required_skill_and_memory_tools_from_task_plans(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient())
        plans = [
            ToolPlan(
                taskId="task-1",
                taskType=TaskType.market_scan,
                tools=["rank.lookup", "skill.lookup"],
                required=True,
            ),
            ToolPlan(
                taskId="task-2",
                taskType=TaskType.outline_building,
                tools=["memory.project_context"],
                required=True,
            ),
        ]

        reserved = agent._reserved_required_tools_for_plans(plans)

        self.assertEqual({"skill.lookup", "memory.project_context"}, reserved)

    def test_runtime_policy_zero_final_output_cap_disables_local_max_tokens(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient())
        request = KnowledgeChatRequest(
            question="write a long webnovel outline",
            mode="research",
            reasoningMode="deep",
        )

        max_tokens = agent._answer_max_tokens(
            request,
            "mixed_creation",
            state={"runtime_config": {"maxFinalOutputTokensDeep": 0}},
        )

        self.assertIsNone(max_tokens)

    def test_should_request_streaming_segmented_long_creative_output(self) -> None:
        agent = NovelResearchAgent(knowledge_client=FakeKnowledgeClient())
        request = KnowledgeChatRequest(
            question="帮我做一个男频都市脑洞三卷大纲和细纲，越完整越好",
            mode="research",
        )

        rule = agent._creative_output_rule(request)

        self.assertIn("本次输出结构", rule)
        self.assertIn("分段", rule)
        self.assertIn("收到增量", rule)

    async def test_should_keep_travel_and_food_novel_topics_in_scope(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([
            '{"inScope": true, "intent": "creative_advice", "bookName": null}',
            "旅行题材和美食文都要把目标、反馈和阻力写进具体场景。"
        ])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        for question in ("旅行题材小说开局怎么写？", "美食文开局怎么设计爽点？"):
            with self.subTest(question=question):
                response = await agent.run(KnowledgeChatRequest(question=question, mode="research"))

                self.assertEqual("answered", response.status)
                self.assertEqual([], client.search_books_calls)
                self.assertEqual([], client.search_evidence_calls)

    async def test_should_keep_web_novel_trend_topic_in_scope_for_retrieval(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(question="分析一下番茄最近大火的退伍入伍题材，都市脑洞方向", mode="research")

        response = await agent.run(request)

        self.assertEqual("insufficient_evidence", response.status)
        self.assertEqual([], client.search_books_calls)
        self.assertEqual(2, len(client.search_evidence_calls))
        self.assertIsNone(client.search_evidence_calls[0]["book_id"])
        self.assertEqual("RANK", client.search_evidence_calls[1]["source_type"])

    async def test_should_answer_web_novel_creative_chat_without_rag_evidence(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([
            '{"inScope": true, "intent": "creative_advice", "bookName": null}',
            "修仙文开局可以先给主角一个短期目标，再用代价明确的金手指制造期待。",
        ])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(question="修仙文开局怎么设计爽点？", mode="research")

        response = await agent.run(request)

        self.assertEqual("answered", response.status)
        self.assertIn("短期目标", response.answer)
        self.assertEqual([], response.sources)
        self.assertEqual([], client.search_books_calls)
        self.assertEqual([], client.search_evidence_calls)
        self.assertTrue(provider.invoke_calls[0]["require_json"])
        self.assertFalse(provider.invoke_calls[1]["require_json"])
        self.assertEqual("creative", response.resultJson["answerMode"])
        self.assertIn(
            "Do not present creative suggestions as knowledge-base evidence",
            provider.invoke_calls[1]["messages"][0]["content"],
        )

    async def test_should_use_ai_extracted_book_name_for_ambiguous_single_book_question(self) -> None:
        client = FakeKnowledgeClient()
        provider = ScriptedProvider([
            '{"inScope": true, "intent": "single_book_research", "bookName": "凡人修仙传"}'
        ])
        agent = NovelResearchAgent(knowledge_client=client, provider_client=provider)
        request = KnowledgeChatRequest(question="帮我研究一下凡人修仙传", mode="research")

        response = await agent.run(request)

        self.assertEqual("candidates_required", response.status)
        self.assertEqual("凡人修仙传", client.search_books_calls[0]["keyword"])
        self.assertIn("select_candidate", response.actions)


if __name__ == "__main__":
    unittest.main()
