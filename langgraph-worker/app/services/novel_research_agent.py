from __future__ import annotations

import json
import re
from collections.abc import AsyncGenerator
from typing import Any, TypedDict

from langgraph.graph import END, StateGraph

from app.config import settings
from app.models.knowledge import BookCandidate, KnowledgeChatRequest, KnowledgeChatResponse, KnowledgeSource
from app.services.knowledge_client import KnowledgeBackendClient
from app.services.provider_client import OpenAICompatibleProviderClient


class ResearchState(TypedDict, total=False):
    request: KnowledgeChatRequest
    intent: str
    book_name: str | None
    book_id: int | None
    platform: str | None
    in_scope: bool
    candidates: list[BookCandidate]
    sources: list[KnowledgeSource]
    actions: list[str]
    response: KnowledgeChatResponse


class NovelResearchAgent:
    def __init__(
        self,
        knowledge_client: KnowledgeBackendClient | None = None,
        provider_client: OpenAICompatibleProviderClient | None = None,
    ) -> None:
        self.knowledge_client = knowledge_client or KnowledgeBackendClient(
            base_url=settings.backend_base_url,
            internal_api_key=settings.backend_internal_api_key,
        )
        self.provider_client = provider_client or OpenAICompatibleProviderClient()
        self._graph = self._build_graph()

    async def run(self, request: KnowledgeChatRequest) -> KnowledgeChatResponse:
        state = await self._graph.ainvoke({"request": request, "actions": []})
        return state["response"]

    async def stream(self, request: KnowledgeChatRequest) -> AsyncGenerator[dict[str, Any], None]:
        yield {"event": "start", "phase": "langgraph", "status": "running"}

        state: ResearchState = {"request": request, "actions": []}
        state.update(await self._intent_router_node(state))

        if state.get("response") is not None:
            async for event in self._emit_response(state["response"]):
                yield event
            return

        if state.get("intent") == "creative_advice":
            async for event in self._stream_creative_answer(request):
                yield event
            return

        state.update(await self._book_resolver_node(state))
        if state.get("response") is not None:
            async for event in self._emit_response(state["response"]):
                yield event
            return

        state.update(await self._data_completer_node(state))
        state.update(await self._evidence_retriever_node(state))
        sources = state.get("sources", [])

        if not sources:
            if self._should_search_book_after_missing_evidence(state):
                state.update(await self._build_book_candidates_response(state))
            else:
                state.update(await self._answer_writer_node(state))
            async for event in self._emit_response(state["response"]):
                yield event
            return

        async for event in self._stream_answer_with_sources(state):
            yield event

    async def _emit_response(self, response: KnowledgeChatResponse) -> AsyncGenerator[dict[str, Any], None]:
        if response.answer:
            yield {"event": "delta", "delta": response.answer, "phase": "answer"}
        yield {"event": "done", "data": response.model_dump()}

    async def _emit_done(self, response: KnowledgeChatResponse) -> AsyncGenerator[dict[str, Any], None]:
        yield {"event": "done", "data": response.model_dump()}

    async def _stream_creative_answer(self, request: KnowledgeChatRequest) -> AsyncGenerator[dict[str, Any], None]:
        collected: list[str] = []
        meta: dict[str, bool] = {}
        async for event in self._stream_generated_text(
            messages=self._build_creative_messages(request),
            temperature=0.4,
            max_tokens=900,
            fallback_text="模型暂时不可用，我先按网文创作方向给出一个简版建议：先明确主角短期目标，再安排高频反馈的阻力和代价，让爽点从目标推进中自然出现。",
            collected=collected,
            meta=meta,
        ):
            yield event
        response = KnowledgeChatResponse(
            status="answered",
            answer="".join(collected).strip(),
            candidates=[],
            sources=[],
            actions=[],
            resultJson={"status": "answered", "intent": "creative_advice", "fallbackUsed": bool(meta.get("fallbackUsed"))},
        )
        verified = await self._citation_verifier_node({"request": request, "response": response})
        async for event in self._emit_done(verified["response"]):
            yield event

    async def _stream_answer_with_sources(self, state: ResearchState) -> AsyncGenerator[dict[str, Any], None]:
        request = state["request"]
        sources = state.get("sources", [])
        collected: list[str] = []
        meta: dict[str, bool] = {}
        async for event in self._stream_generated_text(
            messages=self._build_answer_messages(request, sources),
            temperature=0.2,
            max_tokens=900,
            fallback_text=self._compose_fallback_answer(request.question, sources),
            collected=collected,
            meta=meta,
        ):
            yield event
        response = KnowledgeChatResponse(
            status="answered",
            answer="".join(collected).strip(),
            candidates=[],
            sources=sources,
            actions=self._dedupe(list(state.get("actions", []))),
            resultJson={
                "status": "answered",
                "intent": state.get("intent"),
                "bookId": state.get("book_id"),
                "bookName": state.get("book_name"),
                "sourceCount": len(sources),
                "fallbackUsed": bool(meta.get("fallbackUsed")),
            },
        )
        verified = await self._citation_verifier_node({"request": request, "response": response})
        async for event in self._emit_done(verified["response"]):
            yield event

    async def _stream_generated_text(
        self,
        *,
        messages: list[dict[str, str]],
        temperature: float,
        max_tokens: int,
        fallback_text: str,
        collected: list[str],
        meta: dict[str, bool],
    ) -> AsyncGenerator[dict[str, Any], None]:
        stream_fn = getattr(self.provider_client, "stream", None)
        meta["fallbackUsed"] = False

        if callable(stream_fn):
            try:
                async for event in stream_fn(
                    messages=messages,
                    model=settings.default_model,
                    temperature=temperature,
                    max_tokens=max_tokens,
                    require_json=False,
                    timeout_millis=settings.timeout_millis,
                ):
                    if event.get("event") != "delta":
                        continue
                    delta = str(event.get("delta") or "")
                    if not delta:
                        continue
                    collected.append(delta)
                    yield {"event": "delta", "delta": delta, "phase": "answer"}
                answer = "".join(collected).strip()
                if answer:
                    return
            except Exception:
                answer = "".join(collected).strip()
                if answer:
                    meta["fallbackUsed"] = True
                    return

        try:
            result = await self.provider_client.invoke(
                messages=messages,
                model=settings.default_model,
                temperature=temperature,
                max_tokens=max_tokens,
                require_json=False,
                timeout_millis=settings.timeout_millis,
            )
            content = str(result.get("content") or "").strip()
            if content:
                if not collected:
                    collected.append(content)
                    yield {"event": "delta", "delta": content, "phase": "answer"}
                meta["fallbackUsed"] = True
                return
        except Exception:
            pass

        if fallback_text:
            if not collected:
                collected.append(fallback_text)
                yield {"event": "delta", "delta": fallback_text, "phase": "answer"}
            meta["fallbackUsed"] = True

    def _build_creative_messages(self, request: KnowledgeChatRequest) -> list[dict[str, str]]:
        return [
            {
                "role": "system",
                "content": (
                    "You are a web-novel writing and research assistant. "
                    "Only answer questions about web-novel writing, book analysis, and genre/ranking trends. "
                    "Do not answer weather, programming, medical, finance, legal, travel, food, or news questions. "
                    "Be concrete and useful from an author/editor perspective."
                ),
            },
            {
                "role": "user",
                "content": (
                    f"conversation context:\n{self._format_conversation_context(request)}\n\n"
                    f"current question:\n{request.question}"
                ),
            },
        ]

    def _build_graph(self):
        graph = StateGraph(ResearchState)
        graph.add_node("intent_router", self._intent_router_node)
        graph.add_node("book_resolver", self._book_resolver_node)
        graph.add_node("creative_answer", self._creative_answer_node)
        graph.add_node("data_completer", self._data_completer_node)
        graph.add_node("evidence_retriever", self._evidence_retriever_node)
        graph.add_node("answer_writer", self._answer_writer_node)
        graph.add_node("citation_verifier", self._citation_verifier_node)
        graph.set_entry_point("intent_router")
        graph.add_conditional_edges("intent_router", self._route_after_intent_router, {
            "terminal": "citation_verifier",
            "creative": "creative_answer",
            "book_or_retrieval": "book_resolver",
        })
        graph.add_conditional_edges("book_resolver", self._route_after_book_resolver, {
            "candidate_response": "citation_verifier",
            "continue": "data_completer",
        })
        graph.add_edge("creative_answer", "citation_verifier")
        graph.add_edge("data_completer", "evidence_retriever")
        graph.add_edge("evidence_retriever", "answer_writer")
        graph.add_edge("answer_writer", "citation_verifier")
        graph.add_edge("citation_verifier", END)
        return graph.compile()

    async def _intent_router_node(self, state: ResearchState) -> ResearchState:
        request = state["request"]
        decision = await self._classify_question(request)
        if not bool(decision.get("inScope", True)):
            return {
                "in_scope": False,
                "intent": "out_of_scope",
                "book_name": None,
                "book_id": None,
                "platform": None,
                "response": KnowledgeChatResponse(
                    status="out_of_scope",
                    answer="我只能回答网文创作、网文作品分析、榜单趋势和相关知识库问题。这个问题超出网文研究范围。",
                    candidates=[],
                    sources=[],
                    actions=["ask_web_novel_question"],
                    resultJson={"status": "out_of_scope", "intent": "out_of_scope"},
                ),
            }
        return {
            "in_scope": True,
            "intent": str(decision.get("intent") or self._route_intent(request)),
            "book_name": self._resolve_book_name(request, decision),
            "book_id": request.bookId,
            "platform": request.selectedCandidate.platform if request.selectedCandidate else None,
        }

    def _route_after_intent_router(self, state: ResearchState) -> str:
        if state.get("response") is not None:
            return "terminal"
        if state.get("intent") == "creative_advice":
            return "creative"
        return "book_or_retrieval"

    async def _creative_answer_node(self, state: ResearchState) -> ResearchState:
        request = state["request"]
        answer, fallback_used = await self._compose_creative_answer(request)
        return {
            "response": KnowledgeChatResponse(
                status="answered",
                answer=answer,
                candidates=[],
                sources=[],
                actions=[],
                resultJson={"status": "answered", "intent": "creative_advice", "fallbackUsed": fallback_used},
            )
        }

    async def _book_resolver_node(self, state: ResearchState) -> ResearchState:
        request = state["request"]
        selected = request.selectedCandidate
        if selected is not None:
            actions = list(state.get("actions", []))
            if not selected.local:
                actions.append("fetch_book")
                actions.append("index_book")
            return {
                "book_id": selected.bookId,
                "book_name": selected.bookName,
                "platform": selected.platform,
                "actions": self._dedupe(actions),
            }

        if state.get("book_id") is not None:
            return {}

        if self._should_try_global_evidence_before_book_search(state):
            return {}

        book_name = state.get("book_name")
        if not book_name:
            return {"candidates": []}

        limit = self._limit(request, "candidateLimit", default=5, maximum=20)
        candidates = await self.knowledge_client.search_books(platform="fanqie", keyword=book_name, limit=limit)
        response = KnowledgeChatResponse(
            status="candidates_required",
            answer="找到了多个可能的书籍，请选择正确作品后继续。" if candidates else "未找到匹配书籍，请补充更准确的书名。",
            candidates=candidates,
            sources=[],
            actions=["select_candidate"] if candidates else ["refine_book_name"],
            resultJson={
                "status": "candidates_required",
                "intent": state.get("intent"),
                "bookName": book_name,
                "candidateCount": len(candidates),
            },
        )
        return {"candidates": candidates, "response": response}

    def _route_after_book_resolver(self, state: ResearchState) -> str:
        return "candidate_response" if state.get("response") is not None else "continue"

    async def _data_completer_node(self, state: ResearchState) -> ResearchState:
        actions = list(state.get("actions", []))
        if state.get("book_id") is None and state.get("book_name"):
            actions.append("resolve_book")
        return {"actions": self._dedupe(actions)}

    async def _evidence_retriever_node(self, state: ResearchState) -> ResearchState:
        request = state["request"]
        try:
            sources = await self.knowledge_client.search_evidence(
                query=self._build_retrieval_query(request),
                book_id=state.get("book_id"),
                platform=state.get("platform"),
                analysis_type=self._analysis_type(request),
                limit=self._limit(request, "evidenceLimit", default=5, maximum=20),
            )
            sources = self._filter_sources_for_requested_book(state, sources)
            return {"sources": sources}
        except Exception:
            actions = self._dedupe(list(state.get("actions", [])) + ["evidence_search_failed"])
            return {"sources": [], "actions": actions}

    def _build_retrieval_query(self, request: KnowledgeChatRequest) -> str:
        context = self._format_conversation_context(request)
        if context == "(no prior context)":
            return request.question
        return self._short_text(f"{request.question}\n\n{context}", 1800)

    async def _answer_writer_node(self, state: ResearchState) -> ResearchState:
        sources = state.get("sources", [])
        if not sources:
            if self._should_search_book_after_missing_evidence(state):
                return await self._build_book_candidates_response(state)
            response = KnowledgeChatResponse(
                status="insufficient_evidence",
                answer="证据不足：当前知识库没有检索到可引用材料，无法可靠回答这个问题。",
                candidates=[],
                sources=[],
                actions=self._dedupe(list(state.get("actions", [])) + ["index_book"]),
                resultJson={
                    "status": "insufficient_evidence",
                    "intent": state.get("intent"),
                    "bookId": state.get("book_id"),
                    "bookName": state.get("book_name"),
                },
            )
            return {"response": response}

        answer, fallback_used = await self._compose_answer(state["request"], sources)
        response = KnowledgeChatResponse(
            status="answered",
            answer=answer,
            candidates=[],
            sources=sources,
            actions=self._dedupe(list(state.get("actions", []))),
            resultJson={
                "status": "answered",
                "intent": state.get("intent"),
                "bookId": state.get("book_id"),
                "bookName": state.get("book_name"),
                "sourceCount": len(sources),
                "fallbackUsed": fallback_used,
            },
        )
        return {"response": response}

    async def _build_book_candidates_response(self, state: ResearchState) -> ResearchState:
        request = state["request"]
        book_name = state.get("book_name")
        if not book_name:
            return {
                "response": KnowledgeChatResponse(
                    status="insufficient_evidence",
                    answer="证据不足：当前知识库没有检索到可引用材料，无法可靠回答这个问题。",
                    candidates=[],
                    sources=[],
                    actions=self._dedupe(list(state.get("actions", [])) + ["index_book"]),
                    resultJson={
                        "status": "insufficient_evidence",
                        "intent": state.get("intent"),
                        "bookId": state.get("book_id"),
                        "bookName": state.get("book_name"),
                    },
                )
            }
        candidates = await self.knowledge_client.search_books(
            platform="fanqie",
            keyword=book_name,
            limit=self._limit(request, "candidateLimit", default=5, maximum=20),
        )
        return {
            "response": KnowledgeChatResponse(
                status="candidates_required",
                answer=("知识库暂未命中这本书的可靠材料，请选择正确作品后继续补采和分析。" if candidates else "知识库暂未命中可用材料，也未找到匹配书籍，请补充更准确的书名。"),
                candidates=candidates,
                sources=[],
                actions=self._dedupe(list(state.get("actions", [])) + (["select_candidate"] if candidates else ["refine_book_name"])),
                resultJson={
                    "status": "candidates_required",
                    "intent": state.get("intent"),
                    "bookName": book_name,
                    "candidateCount": len(candidates),
                },
            )
        }

    async def _citation_verifier_node(self, state: ResearchState) -> ResearchState:
        response = state["response"]
        if response.status != "answered":
            return {"response": response}
        if response.resultJson.get("intent") == "creative_advice":
            return {"response": response}
        if response.sources and "[1]" not in response.answer:
            request = state.get("request")
            question = request.question if request is not None else ""
            response.answer = self._compose_fallback_answer(question, response.sources)
            response.status = "answered"
            response.resultJson["status"] = response.status
            response.resultJson["fallbackUsed"] = True
            response.resultJson["citationRepairUsed"] = True
            return {"response": response}
        if not response.sources:
            response.status = "insufficient_evidence"
            response.answer = "证据不足：回答缺少可核验引用，已拒绝生成结论。"
            response.sources = []
            response.actions = self._dedupe(response.actions + ["index_book"])
            response.resultJson["status"] = response.status
        return {"response": response}

    def _route_intent(self, request: KnowledgeChatRequest) -> str:
        question = (request.question or "").strip()
        if any(keyword in question for keyword in ("找书", "哪本", "搜索", "候选")):
            return "book_resolution"
        if self._is_single_book_question(question):
            return "single_book_research"
        if any(keyword in question for keyword in ("找书", "哪本", "搜索", "候选")):
            return "book_resolution"
        return "answer_question"

    def _should_try_global_evidence_before_book_search(self, state: ResearchState) -> bool:
        if state.get("book_id") is not None:
            return False
        request = state["request"]
        if request.selectedCandidate is not None:
            return False
        return bool(state.get("book_name"))

    def _should_search_book_after_missing_evidence(self, state: ResearchState) -> bool:
        if state.get("book_id") is not None:
            return False
        request = state["request"]
        if request.selectedCandidate is not None:
            return False
        return bool(state.get("book_name"))

    def _filter_sources_for_requested_book(
        self,
        state: ResearchState,
        sources: list[KnowledgeSource],
    ) -> list[KnowledgeSource]:
        book_name = state.get("book_name")
        if not book_name or state.get("book_id") is not None:
            return sources
        normalized_book_name = self._normalize_book_name(book_name)
        if not normalized_book_name:
            return sources
        return [
            source for source in sources
            if self._normalize_book_name(source.bookName) == normalized_book_name
        ]

    def _normalize_book_name(self, value: str | None) -> str:
        if not value:
            return ""
        return re.sub(r"[\s《》【】（）()，。！？、]+", "", value).lower()

    def _resolve_book_name(self, request: KnowledgeChatRequest, decision: dict[str, Any] | None = None) -> str | None:
        if request.bookName and request.bookName.strip():
            return request.bookName.strip()
        ai_book_name = str((decision or {}).get("bookName") or "").strip()
        if ai_book_name:
            return ai_book_name
        question = request.question or ""
        bracket_match = re.search(r"[《【(](.{1,80}?)[》】)]", question)
        if bracket_match:
            return bracket_match.group(1).strip()
        if self._is_single_book_question(question):
            return self._extract_plain_book_name(question)
        match = re.search(r"^([^《【]{2,80}?)(?:的|这本|这部)", question or "")
        if match:
            return match.group(1).strip()
        return None

    def _is_single_book_question(self, question: str) -> bool:
        if not question or self._is_trend_question(question):
            return False
        return any(keyword in question for keyword in (
            "分析",
            "拆文",
            "开篇",
            "开局",
            "怎么写",
            "怎么设计",
            "如何设计",
            "卖点",
            "爽点",
            "主角",
            "设定",
            "人设",
            "节奏",
            "剧情",
            "情节",
            "世界观",
            "金手指",
        ))

    def _is_trend_question(self, question: str) -> bool:
        return any(keyword in question for keyword in (
            "趋势",
            "题材",
            "榜单",
            "最近",
            "哪些书火",
            "什么火",
            "赛道",
            "风向",
        ))

    def _extract_plain_book_name(self, question: str) -> str | None:
        normalized = re.sub(r"\s+", "", question or "")
        if not normalized:
            return None
        split_pattern = "|".join(map(re.escape, (
            "开篇",
            "开局",
            "开头",
            "怎么写",
            "怎么设计",
            "如何设计",
            "卖点",
            "爽点",
            "主角",
            "设定",
            "人设",
            "节奏",
            "剧情",
            "情节",
            "世界观",
            "金手指",
            "分析",
            "拆文",
        )))
        match = re.match(rf"^(.{{2,30}}?)(?:的)?(?:{split_pattern})", normalized)
        if not match:
            return None
        candidate = match.group(1).strip("，。！？、")
        if candidate in {"这本书", "这本小说", "这个作品", "本书"}:
            return None
        if candidate.endswith(("问题", "题材", "赛道", "流派")):
            return None
        if self._is_generic_web_novel_topic(candidate):
            return None
        return candidate or None

    def _is_generic_web_novel_topic(self, candidate: str) -> bool:
        normalized = self._normalize_book_name(candidate)
        generic_topics = {
            "网文",
            "小说",
            "爽文",
            "修仙文",
            "玄幻文",
            "都市文",
            "娱乐圈文",
            "娱乐文",
            "明星文",
            "综艺文",
            "男频文",
            "女频文",
            "美食文",
            "旅行题材",
            "旅游题材",
            "公路文",
        }
        return normalized in {self._normalize_book_name(topic) for topic in generic_topics}

    def _analysis_type(self, request: KnowledgeChatRequest) -> str | None:
        mode = (request.mode or "").strip().lower()
        if mode in {"deconstruct", "theme", "trend_theme"}:
            return "theme" if mode == "trend_theme" else mode
        return None

    async def _classify_question(self, request: KnowledgeChatRequest) -> dict[str, Any]:
        fallback = self._rule_based_decision(request)
        if fallback.get("inScope") is False:
            return fallback
        if request.bookId is not None or request.selectedCandidate is not None or (request.bookName and request.bookName.strip()):
            return fallback
        try:
            result = await self.provider_client.invoke(
                messages=self._build_intent_messages(request, fallback),
                model=settings.default_model,
                temperature=0,
                max_tokens=240,
                require_json=True,
                timeout_millis=settings.timeout_millis,
            )
            parsed = json.loads(str(result.get("content") or "{}"))
            return self._normalize_decision(parsed, fallback)
        except Exception:
            return fallback

    def _rule_based_decision(self, request: KnowledgeChatRequest) -> dict[str, Any]:
        question = (request.question or "").strip()
        if self._is_obviously_out_of_scope(question):
            return {"inScope": False, "intent": "out_of_scope", "bookName": None}
        book_name = self._resolve_book_name_by_rules(request)
        has_explicit_book_context = (
            request.bookId is not None
            or request.selectedCandidate is not None
            or book_name is not None
        )
        if has_explicit_book_context:
            intent = "single_book_research"
        elif self._is_creative_advice_question(question):
            intent = "creative_advice"
        else:
            intent = self._route_intent(request)
        return {"inScope": True, "intent": intent, "bookName": book_name}

    def _normalize_decision(self, raw: dict[str, Any], fallback: dict[str, Any]) -> dict[str, Any]:
        allowed_intents = {
            "single_book_research",
            "book_resolution",
            "trend_research",
            "creative_advice",
            "answer_question",
            "out_of_scope",
        }
        in_scope = bool(raw.get("inScope", fallback.get("inScope", True)))
        intent = str(raw.get("intent") or fallback.get("intent") or "answer_question").strip()
        if intent not in allowed_intents:
            intent = str(fallback.get("intent") or "answer_question")
        if not in_scope:
            intent = "out_of_scope"
        book_name = raw.get("bookName")
        if book_name is None:
            book_name = fallback.get("bookName")
        book_name = str(book_name or "").strip() or None
        return {"inScope": in_scope, "intent": intent, "bookName": book_name}

    def _resolve_book_name_by_rules(self, request: KnowledgeChatRequest) -> str | None:
        if request.bookName and request.bookName.strip():
            return request.bookName.strip()
        question = request.question or ""
        bracket_match = re.search(r"[《【(](.{1,80}?)[》】)]", question)
        if bracket_match:
            return bracket_match.group(1).strip()
        if self._is_single_book_question(question):
            return self._extract_plain_book_name(question)
        match = re.search(r"^([^《【]{2,80}?)(?:的|这本|这部)", request.question or "")
        if match:
            return match.group(1).strip()
        return None

    def _is_obviously_out_of_scope(self, question: str) -> bool:
        if not question:
            return False
        out_keywords = (
            "怎么做",
            "做法",
            "菜谱",
            "食谱",
            "炒菜",
            "家常",
            "配方",
            "教程",
            "番茄炒蛋",
            "怎么煮",
            "怎么炖",
            "天气",
            "股票",
            "基金",
            "汇率",
            "航班",
            "酒店",
            "旅游",
            "旅行",
            "攻略",
            "行程",
            "景点",
            "路线",
            "机票",
            "吃什么",
            "吃啥",
            "外卖",
            "点外卖",
            "餐厅",
            "菜谱",
            "感冒",
            "法律咨询",
            "新闻",
            "今日新闻",
            "实时新闻",
            "国际新闻",
            "热搜",
            "娱乐",
            "八卦",
            "明星",
            "综艺",
            "电影",
            "电视剧",
            "体育",
            "比赛",
            "财经",
            "政治",
            "Python",
            "Java",
            "Docker",
        )
        has_out_keyword = any(keyword in question for keyword in out_keywords)
        if not has_out_keyword:
            return False
        return not self._has_web_novel_context(question)

    def _is_creative_advice_question(self, question: str) -> bool:
        creative_keywords = (
            "怎么写",
            "怎么设计",
            "如何设计",
            "开局",
            "开头",
            "爽点",
            "金手指",
            "人设",
            "大纲",
            "节奏",
            "冲突",
        )
        return self._has_web_novel_keyword(question) and any(keyword in question for keyword in creative_keywords)

    def _has_web_novel_keyword(self, question: str) -> bool:
        return any(keyword in question for keyword in (
            "网文",
            "小说",
            "男频",
            "女频",
            "玄幻",
            "修仙",
            "都市",
            "番茄",
            "起点",
            "爽文",
            "美食文",
            "旅行题材",
            "旅游题材",
            "公路文",
            "娱乐圈文",
            "娱乐文",
            "明星文",
            "综艺文",
            "开篇",
            "主角",
            "剧情",
            "题材",
        ))

    def _has_web_novel_context(self, question: str) -> bool:
        if not self._has_web_novel_keyword(question):
            return False
        if any(keyword in question for keyword in (
            "番茄小说",
            "番茄网文",
            "番茄榜",
            "番茄书",
        )):
            return True
        return "番茄" not in question

    def _build_intent_messages(self, request: KnowledgeChatRequest, fallback: dict[str, Any]) -> list[dict[str, str]]:
        conversation_context = self._format_conversation_context(request)
        return [
            {
                "role": "system",
                "content": (
                    "You classify intent for a web-novel research assistant. "
                    "Only web-novel writing, book analysis, ranking trends, and knowledge-base Q&A are in scope. "
                    "Return JSON only with fields: inScope(boolean), intent(string), bookName(string|null). "
                    "Allowed intents: single_book_research, book_resolution, trend_research, creative_advice, answer_question, out_of_scope. "
                    "Non-web-novel questions must use inScope=false and intent=out_of_scope."
                ),
            },
            {
                "role": "user",
                "content": (
                    f"question: {request.question}\n"
                    f"explicit bookName: {request.bookName or ''}\n"
                    f"conversation context:\n{conversation_context}\n"
                    f"rule fallback: {json.dumps(fallback, ensure_ascii=False)}"
                ),
            },
        ]

    async def _compose_creative_answer(self, request: KnowledgeChatRequest) -> tuple[str, bool]:
        try:
            result = await self.provider_client.invoke(
                messages=self._build_creative_messages(request),
                model=settings.default_model,
                temperature=0.4,
                max_tokens=900,
                require_json=False,
                timeout_millis=settings.timeout_millis,
            )
            content = str(result.get("content") or "").strip()
            if content:
                return content, False
        except Exception:
            pass
        return (
            "模型暂时不可用，我先按网文创作方向给出一个简版建议：先明确主角短期目标，再安排高频反馈的阻力和代价，让爽点从目标推进中自然出现。",
            True,
        )

    async def _compose_answer(self, request: KnowledgeChatRequest, sources: list[KnowledgeSource]) -> tuple[str, bool]:
        messages = self._build_answer_messages(request, sources)
        try:
            result = await self.provider_client.invoke(
                messages=messages,
                model=settings.default_model,
                temperature=0.2,
                max_tokens=900,
                require_json=False,
                timeout_millis=settings.timeout_millis,
            )
            content = str(result.get("content") or "").strip()
            if content:
                return content, False
        except Exception:
            pass
        return self._compose_fallback_answer(request.question, sources), True

    def _compose_fallback_answer(self, question: str, sources: list[KnowledgeSource]) -> str:
        lead = f"模型暂时不可用，先基于已检索证据回答：{question}。"
        points: list[str] = []
        for index, source in enumerate(sources[:3], start=1):
            title = source.title or source.bookName or "未知标题"
            preview = (source.preview or "").strip()
            if preview:
                points.append(f"{title}：{preview}[{index}]")
            else:
                points.append(f"{title}提供了相关证据[{index}]")
        return lead + " " + " ".join(points)

    def _build_answer_messages(self, request: KnowledgeChatRequest, sources: list[KnowledgeSource]) -> list[dict[str, str]]:
        evidence_lines: list[str] = []
        for index, source in enumerate(sources[:8], start=1):
            title = source.title or source.bookName or f"source {index}"
            book_name = source.bookName or "unknown book"
            source_type = source.sourceType or "unknown"
            chapter = f"chapter {source.chapterNo}" if source.chapterNo is not None else "no chapter number"
            preview = (source.preview or "").strip()
            evidence_lines.append(
                f"[{index}] book: {book_name}\n"
                f"title: {title}\n"
                f"sourceType: {source_type}, {chapter}\n"
                f"material: {preview}"
            )
        evidence = "\n\n".join(evidence_lines)
        return [
            {
                "role": "system",
                "content": (
                    "You are a web-novel research Q&A assistant. Use only the supplied evidence and conversation context. "
                    "Do not invent plots, data, or conclusions that are not supported by evidence. "
                    "Answer the user's actual question first, then give author/editor oriented analysis. "
                    "Every paragraph or bullet must end with citation markers such as [1] or [1][2]. "
                    "If a claim cannot be tied to the numbered evidence, omit that claim. "
                    "If evidence is insufficient, say what is missing."
                ),
            },
            {
                "role": "user",
                "content": (
                    f"conversation context:\n{self._format_conversation_context(request)}\n\n"
                    f"current question:\n{request.question}\n\n"
                    f"evidence:\n{evidence}"
                ),
            },
        ]

    def _format_conversation_context(self, request: KnowledgeChatRequest) -> str:
        parts: list[str] = []
        summary = (request.contextSummary or "").strip()
        if summary:
            parts.append(f"compressed summary: {self._short_text(summary, 1200)}")
        history_lines: list[str] = []
        for message in (request.history or [])[-6:]:
            role = str(message.get("role") or "user").strip()
            if role not in {"user", "assistant"}:
                role = "user"
            content = self._short_text(str(message.get("content") or ""), 700)
            if content:
                history_lines.append(f"{role}: {content}")
        if history_lines:
            parts.append("recent history:\n" + "\n".join(history_lines))
        return "\n\n".join(parts) if parts else "(no prior context)"

    def _short_text(self, value: str, max_length: int) -> str:
        compact = " ".join((value or "").split())
        if len(compact) <= max_length:
            return compact
        return compact[:max_length] + "..."

    def _limit(self, request: KnowledgeChatRequest, key: str, *, default: int, maximum: int) -> int:
        raw = request.limits.get(key, default)
        try:
            parsed = int(raw)
        except (TypeError, ValueError):
            parsed = default
        return max(1, min(parsed, maximum))

    def _dedupe(self, values: list[str]) -> list[str]:
        deduped: list[str] = []
        for value in values:
            if value and value not in deduped:
                deduped.append(value)
        return deduped
