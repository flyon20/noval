from __future__ import annotations

import asyncio
import json
import re
from collections.abc import AsyncGenerator
from time import perf_counter
from typing import Any, TypedDict

from langgraph.graph import END, StateGraph

from app.config import settings
from app.models.analysis import PromptConfigPayload, RunRequest, RunResponse
from app.services.provider_client import OpenAICompatibleProviderClient


class AnalysisState(TypedDict, total=False):
    request: RunRequest
    input_text: str
    use_chunking: bool
    chunks: list[dict[str, Any]]
    chunk_results: list[RunResponse]
    response: RunResponse


class JsonResolution(TypedDict):
    result_json: dict[str, Any]
    token_used: int
    provider_call_count: int
    queue_wait_ms: int
    provider_latency_ms: int


class LangGraphAnalysisService:
    def __init__(self, provider_client: OpenAICompatibleProviderClient | None = None) -> None:
        self.provider_client = provider_client or OpenAICompatibleProviderClient()
        self._llm_semaphore = asyncio.Semaphore(settings.max_active_llm_calls)
        self._graph = self._build_graph()

    async def run(self, request: RunRequest) -> RunResponse:
        started_at = perf_counter()
        state = await self._graph.ainvoke({"request": request})
        return self._finalize_response(
            request=request,
            response=state["response"],
            total_duration_ms=self._elapsed_millis(started_at),
        )

    async def stream(self, request: RunRequest) -> AsyncGenerator[dict[str, Any], None]:
        started_at = perf_counter()
        prepared = self._prepare_request(request)
        analysis_type = self._analysis_type(request.agentType)
        yield {"event": "start", "taskId": request.taskId, "analysisType": analysis_type}

        if prepared["use_chunking"]:
            chunks = prepared["chunks"]
            chunk_results: list[RunResponse] = []
            for index, chunk in enumerate(chunks, start=1):
                yield {"event": "progress", "message": f"[chunk-progress] {index}/{len(chunks)} 正在分析\n"}
                chunk_response = await self._invoke_once(
                    request=request,
                    input_text=chunk["input_text"],
                    prompt_override=self._build_chunk_prompt(request.promptConfig, index, len(chunks)),
                )
                chunk_results.append(chunk_response)
                yield {"event": "progress", "message": f"[chunk-progress] {index}/{len(chunks)} 已完成\n"}
            merged_response = await self._merge_chunk_results(request, chunk_results)
            merged_response = self._finalize_response(
                request=request,
                response=merged_response,
                total_duration_ms=self._elapsed_millis(started_at),
            )
            yield {"event": "metrics", "metrics": self._runtime_meta(merged_response)}
            yield {"event": "done", "data": merged_response.model_dump()}
            return

        accumulated = ""
        messages = self._build_messages(request.promptConfig, prepared["input_text"], analysis_type)
        token_used = 0
        semaphore_started_at = perf_counter()
        async with self._llm_semaphore:
            queue_wait_ms = self._elapsed_millis(semaphore_started_at)
            provider_started_at = perf_counter()
            async for event in self.provider_client.stream(
                messages=messages,
                model=request.promptConfig.modelName or settings.default_model,
                temperature=request.promptConfig.temperature,
                max_tokens=request.promptConfig.maxTokens,
                require_json=self._requires_json(request.promptConfig, analysis_type),
                base_url=request.promptConfig.baseUrl,
                api_key=request.promptConfig.apiKey,
                timeout_millis=self._resolve_timeout_millis(request),
            ):
                if event["event"] == "delta":
                    accumulated += event["delta"]
                    yield event
                elif event["event"] == "done":
                    token_used = int(event.get("tokenUsed") or 0)
            provider_latency_ms = self._elapsed_millis(provider_started_at)
        json_resolution = await self._resolve_result_json(
            request=request,
            prompt_config=request.promptConfig,
            analysis_type=analysis_type,
            content=accumulated,
        )
        response = self._build_response(
            request,
            accumulated,
            token_used + json_resolution["token_used"],
            request.promptConfig.modelName or settings.default_model,
            result_json=json_resolution["result_json"],
            runtime_meta=self._build_runtime_meta(
                request,
                stream_mode=True,
                use_chunking=False,
                chunk_count=0,
                provider_call_count=1 + json_resolution["provider_call_count"],
                queue_wait_ms=queue_wait_ms + json_resolution["queue_wait_ms"],
                provider_latency_ms=provider_latency_ms + json_resolution["provider_latency_ms"],
            ),
        )
        response = self._finalize_response(
            request=request,
            response=response,
            total_duration_ms=self._elapsed_millis(started_at),
        )
        yield {"event": "metrics", "metrics": self._runtime_meta(response)}
        yield {"event": "done", "data": response.model_dump()}

    def _build_graph(self):
        graph = StateGraph(AnalysisState)
        graph.add_node("prepare", self._prepare_node)
        graph.add_node("analyze_direct", self._analyze_direct_node)
        graph.add_node("split_chunks", self._split_chunks_node)
        graph.add_node("analyze_chunks", self._analyze_chunks_node)
        graph.add_node("merge_chunks", self._merge_chunks_node)
        graph.set_entry_point("prepare")
        graph.add_conditional_edges("prepare", self._route_after_prepare, {
            "direct": "analyze_direct",
            "chunk": "split_chunks",
        })
        graph.add_edge("analyze_direct", END)
        graph.add_edge("split_chunks", "analyze_chunks")
        graph.add_edge("analyze_chunks", "merge_chunks")
        graph.add_edge("merge_chunks", END)
        return graph.compile()

    async def _prepare_node(self, state: AnalysisState) -> AnalysisState:
        prepared = self._prepare_request(state["request"])
        return {
            "input_text": prepared["input_text"],
            "use_chunking": prepared["use_chunking"],
            "chunks": prepared.get("chunks", []),
        }

    def _route_after_prepare(self, state: AnalysisState) -> str:
        return "chunk" if state.get("use_chunking") else "direct"

    async def _analyze_direct_node(self, state: AnalysisState) -> AnalysisState:
        response = await self._invoke_once(
            request=state["request"],
            input_text=state["input_text"],
            prompt_override=None,
        )
        return {"response": response}

    async def _split_chunks_node(self, state: AnalysisState) -> AnalysisState:
        return {"chunks": state.get("chunks", [])}

    async def _analyze_chunks_node(self, state: AnalysisState) -> AnalysisState:
        request = state["request"]
        chunks = state.get("chunks", [])
        parallelism = max(1, min(int(request.limits.get("chunkParallelism", 2) or 2), 2))
        semaphore = asyncio.Semaphore(parallelism)

        async def run_chunk(index: int, chunk: dict[str, Any]) -> RunResponse:
            async with semaphore:
                return await self._invoke_once(
                    request=request,
                    input_text=chunk["input_text"],
                    prompt_override=self._build_chunk_prompt(request.promptConfig, index, len(chunks)),
                )

        results = await asyncio.gather(*(run_chunk(index, chunk) for index, chunk in enumerate(chunks, start=1)))
        return {"chunk_results": list(results)}

    async def _merge_chunks_node(self, state: AnalysisState) -> AnalysisState:
        response = await self._merge_chunk_results(state["request"], state.get("chunk_results", []))
        return {"response": response}

    def _prepare_request(self, request: RunRequest) -> dict[str, Any]:
        source_payload = request.sourcePayload or {}
        input_text = str(source_payload.get("inputText") or "").strip()
        chapters = source_payload.get("chapters") or []
        max_input_tokens = int(request.limits.get("chunkMaxInputTokens") or 6000)
        target_input_tokens = min(
            max_input_tokens,
            int(request.limits.get("chunkTargetInputTokens") or 3500),
        )
        estimated_tokens = self._estimate_tokens(input_text)
        if chapters and estimated_tokens > max_input_tokens:
            return {
                "input_text": input_text,
                "use_chunking": True,
                "chunks": self._split_book_chunks(source_payload, chapters, target_input_tokens),
            }
        return {"input_text": input_text, "use_chunking": False, "chunks": []}

    async def _invoke_once(self, *, request: RunRequest, input_text: str, prompt_override: str | None) -> RunResponse:
        prompt_config = request.promptConfig.model_copy()
        if prompt_override is not None:
            prompt_config.promptContent = prompt_override
        analysis_type = self._analysis_type(request.agentType)
        messages = self._build_messages(prompt_config, input_text, analysis_type)
        semaphore_started_at = perf_counter()
        async with self._llm_semaphore:
            queue_wait_ms = self._elapsed_millis(semaphore_started_at)
            provider_started_at = perf_counter()
            try:
                result = await self.provider_client.invoke(
                    request=request,
                    messages=messages,
                    model=prompt_config.modelName or settings.default_model,
                    temperature=prompt_config.temperature,
                    max_tokens=prompt_config.maxTokens,
                    require_json=self._requires_json(prompt_config, analysis_type),
                    base_url=prompt_config.baseUrl,
                    api_key=prompt_config.apiKey,
                    timeout_millis=self._resolve_timeout_millis(request),
                )
            except Exception as exc:
                result = self._build_final_fallback_result(
                    request=request,
                    analysis_type=analysis_type,
                    input_text=input_text,
                    prompt_config=prompt_config,
                    failure_reason=str(exc) or exc.__class__.__name__,
                )
            provider_latency_ms = self._elapsed_millis(provider_started_at)
        result_json = result.get("result_json")
        json_resolution = self._json_resolution(result_json=result_json) if result_json else await self._resolve_result_json(
            request=request,
            prompt_config=prompt_config,
            analysis_type=analysis_type,
            content=result["content"],
        )
        if not result_json:
            result_json = json_resolution["result_json"]
        return self._build_response(
            request,
            result["content"],
            int(result["token_used"]) + json_resolution["token_used"],
            result["model_name"],
            result_json=result_json,
            runtime_meta=self._build_runtime_meta(
                request,
                stream_mode=False,
                use_chunking=False,
                chunk_count=0,
                provider_call_count=1 + json_resolution["provider_call_count"],
                queue_wait_ms=queue_wait_ms + json_resolution["queue_wait_ms"],
                provider_latency_ms=provider_latency_ms + json_resolution["provider_latency_ms"],
            ),
        )

    async def _merge_chunk_results(self, request: RunRequest, chunk_results: list[RunResponse]) -> RunResponse:
        if len(chunk_results) == 1:
            response = chunk_results[0].model_copy(deep=True)
            return self._apply_runtime_meta(
                response,
                self._build_chunk_runtime_meta(request, chunk_results, None),
            )
        merged_input = "\n\n".join(
            f"## 分段 {index}\n{result.content}"
            for index, result in enumerate(chunk_results, start=1)
        )
        merged_prompt = self._build_merge_prompt(request.promptConfig)
        merged = await self._invoke_once(
            request=request,
            input_text=merged_input,
            prompt_override=merged_prompt,
        )
        merged.tokenUsed += sum(result.tokenUsed for result in chunk_results)
        merged.resultJson.setdefault("analysisMode", "chunk_merge")
        merged.resultJson.setdefault("segmentCount", len(chunk_results))
        return self._apply_runtime_meta(
            merged,
            self._build_chunk_runtime_meta(request, chunk_results, merged),
        )

    def _build_messages(self, prompt_config: PromptConfigPayload, input_text: str, analysis_type: str) -> list[dict[str, str]]:
        template = prompt_config.promptContent or "{{content}}"
        if "{{content}}" in template:
            system_prompt = template.replace("{{content}}", "正文内容会在下一条 user message 中提供，请只基于该正文完成分析。")
            system_prompt = self._augment_prompt_for_json(prompt_config, system_prompt, analysis_type)
            return [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": input_text},
            ]
        prompt = template + "\n\n" + input_text
        return [{"role": "user", "content": self._augment_prompt_for_json(prompt_config, prompt, analysis_type)}]

    def _augment_prompt_for_json(self, prompt_config: PromptConfigPayload, prompt: str, analysis_type: str) -> str:
        if not self._uses_structured_contract(prompt_config, analysis_type):
            return prompt
        sections = [prompt]
        if prompt_config.inputJsonSchema:
            sections.extend(["", "input schema:", prompt_config.inputJsonSchema])
            if prompt_config.inputExampleJson:
                sections.extend(["input example:", prompt_config.inputExampleJson])
        sections.extend([
            "",
            self._build_theme_contract_guidance(),
            "",
            "The admin prompt above defines the analysis scope only.",
            "Ignore any markdown/report/table formatting instructions in that prompt.",
            "Return exactly one JSON object that matches the output schema.",
            "Do not output markdown, code fences, headings, tables, commentary, or any extra text outside JSON.",
        ])
        if prompt_config.outputJsonSchema:
            sections.extend(["output schema:", prompt_config.outputJsonSchema])
        if prompt_config.outputExampleJson and self._should_include_output_example(prompt_config, analysis_type):
            sections.extend(["output example:", prompt_config.outputExampleJson])
        return "\n".join(sections)

    def _uses_structured_contract(self, prompt_config: PromptConfigPayload, analysis_type: str) -> bool:
        prompt_type = (prompt_config.promptType or analysis_type or "").strip().lower()
        return prompt_type == "theme"

    def _requires_json(self, prompt_config: PromptConfigPayload, analysis_type: str) -> bool:
        if not self._uses_structured_contract(prompt_config, analysis_type):
            return False
        if prompt_config.outputJsonSchema:
            return True
        if (prompt_config.postProcessType or "").lower() == "json_extract":
            return True
        parse_config = (prompt_config.parseConfigJson or "").lower()
        if '"parser":"json"' in parse_config or '"parser": "json"' in parse_config:
            return True
        return True

    def _build_theme_contract_guidance(self) -> str:
        return (
            "Trend output constraints:\n"
            "1. Never use broad labels such as 都市系统, 玄幻升级, 都市, or 系统流 as the final lane name.\n"
            "2. themeDistribution.theme and themeTable.theme must use 3 or 4 Chinese segments joined by '-' and "
            "must land on a concrete lane such as 都市脑洞-直播算命-惩恶扬善, 娱乐明星-老六系统-全网黑粉, "
            "玄幻-长生苟道-家族养成, or 四合院-戾气极重-截胡流.\n"
            "3. Every themeDistribution and themeTable row must expose laneLevel, systemType, systemPresence, "
            "systemPersona, interactionMode, feedbackLoop, payoffMechanism, emotionAnchor, antiRoutineDesign, "
            "avoidedPoisonPoints, and microTags. If the story is not a classic system-flow, still explain the exact "
            "golden-finger form instead of writing a broad bucket.\n"
            "4. systemArchetypes must distinguish concrete system type, systemPresence, systemPersona, interaction "
            "mode, feedback loop, and payoff mechanism, for example 签到打卡流 / 神级选择流 / 情绪值收集流 / "
            "熟练度面板 / 暴击返还流 / 听劝流.\n"
            "5. microInnovationSignals must explain the anti-cliche twist, antiRoutineDesign, avoidedPoisonPoints, "
            "and why the twist can work in the next 3-6 months.\n"
            "6. historicalWordCloud must contain concrete board-scoped terms: fine-grained lanes, system types, "
            "identity tags, emotion tags, scene tags, and micro-innovation words. Avoid umbrella words such as 系统流.\n"
            "7. hotBooks should be the highest-ranked representative title under each main lane, and each reason "
            "must explain why it represents that lane and which system/payoff loop makes it competitive.\n"
            "8. insightCards must at least cover 主赛道 and 代表热书 using the latest board-scoped sample, where "
            "主赛道 is the lane with the highest ratio and 代表热书 is the highest-ranked title inside that lane."
            "9. Keep summary, boardSummary, trendPreview, and comparisonSummary concise. summary should stay within 120 Chinese characters, "
            "boardSummary within 180 Chinese characters, trendPreview within 260 Chinese characters, and comparisonSummary within 180 Chinese characters.\n"
            "10. Keep hotBooks, themeTable, themeDistribution, systemArchetypes, microInnovationSignals, and insightCards compact. "
            "themeDistribution <= 8 rows, themeTable <= 6 rows, representativeBooks <= 2 per theme, hotBooks <= 5 items, "
            "systemArchetypes <= 6 items, microInnovationSignals <= 3 items, insightCards = 4 items, historicalWordCloud <= 20 items, "
            "and every reason/note string should stay within 60 Chinese characters.\n"
            "11. detailContent must be plain prose without markdown tables or code fences, and should stay within 600 Chinese characters."
        )

    def _should_include_output_example(self, prompt_config: PromptConfigPayload, analysis_type: str) -> bool:
        if analysis_type != "theme":
            return True
        return len(prompt_config.outputExampleJson or "") <= 2000

    async def _resolve_result_json(
        self,
        *,
        request: RunRequest,
        prompt_config: PromptConfigPayload,
        analysis_type: str,
        content: str,
    ) -> JsonResolution:
        result_json = self._parse_result_json(content)
        if result_json:
            return self._json_resolution(result_json=result_json)
        if not self._requires_json(prompt_config, analysis_type):
            return self._json_resolution()
        repair_resolution = await self._repair_result_json(
            request=request,
            prompt_config=prompt_config,
            analysis_type=analysis_type,
            content=content,
        )
        if repair_resolution["result_json"]:
            return repair_resolution
        raise ValueError(f"{analysis_type} analysis did not return valid JSON")

    async def _repair_result_json(
        self,
        *,
        request: RunRequest,
        prompt_config: PromptConfigPayload,
        analysis_type: str,
        content: str,
    ) -> JsonResolution:
        if analysis_type != "theme":
            return self._json_resolution()
        messages = self._build_json_repair_messages(prompt_config, content)
        semaphore_started_at = perf_counter()
        async with self._llm_semaphore:
            queue_wait_ms = self._elapsed_millis(semaphore_started_at)
            provider_started_at = perf_counter()
            repaired = await self.provider_client.invoke(
                request=request,
                messages=messages,
                model=prompt_config.modelName or settings.default_model,
                temperature=0,
                max_tokens=self._resolve_repair_max_tokens(prompt_config),
                require_json=True,
                base_url=prompt_config.baseUrl,
                api_key=prompt_config.apiKey,
                timeout_millis=self._resolve_repair_timeout_millis(request),
            )
            provider_latency_ms = self._elapsed_millis(provider_started_at)
        return self._json_resolution(
            result_json=self._parse_result_json(repaired["content"]),
            token_used=int(repaired["token_used"]),
            provider_call_count=1,
            queue_wait_ms=queue_wait_ms,
            provider_latency_ms=provider_latency_ms,
        )

    def _build_json_repair_messages(
        self,
        prompt_config: PromptConfigPayload,
        content: str,
    ) -> list[dict[str, str]]:
        system_sections = [
            "JSON repair task:",
            "You will receive raw trend-analysis output that failed JSON validation.",
            "Convert it into exactly one valid JSON object that matches the output schema.",
            "Do not output markdown, code fences, headings, commentary, or any text outside JSON.",
            "Do not invent books, ranks, or claims that are not supported by the raw text.",
            "If a field is missing, use concise fallback text, empty arrays, or empty strings.",
            "Keep all strings compact and plain.",
        ]
        if prompt_config.outputJsonSchema:
            system_sections.extend(["output schema:", prompt_config.outputJsonSchema])
        return [
            {"role": "system", "content": "\n".join(system_sections)},
            {"role": "user", "content": f"Raw trend analysis text:\n{content}"},
        ]

    def _resolve_repair_max_tokens(self, prompt_config: PromptConfigPayload) -> int:
        configured_max_tokens = prompt_config.maxTokens if prompt_config.maxTokens and prompt_config.maxTokens > 0 else 2200
        return max(512, min(int(configured_max_tokens), 3200))

    def _resolve_repair_timeout_millis(self, request: RunRequest) -> int:
        return min(self._resolve_timeout_millis(request), 30000)

    def _json_resolution(
        self,
        *,
        result_json: dict[str, Any] | None = None,
        token_used: int = 0,
        provider_call_count: int = 0,
        queue_wait_ms: int = 0,
        provider_latency_ms: int = 0,
    ) -> JsonResolution:
        return {
            "result_json": dict(result_json or {}),
            "token_used": max(0, int(token_used)),
            "provider_call_count": max(0, int(provider_call_count)),
            "queue_wait_ms": max(0, int(queue_wait_ms)),
            "provider_latency_ms": max(0, int(provider_latency_ms)),
        }

    def _build_response(
        self,
        request: RunRequest,
        content: str,
        token_used: int,
        model_name: str,
        result_json: dict[str, Any] | None = None,
        runtime_meta: dict[str, Any] | None = None,
    ) -> RunResponse:
        analysis_type = self._analysis_type(request.agentType)
        result_json = dict(result_json) if result_json is not None else self._parse_result_json(content)
        if self._requires_json(request.promptConfig, analysis_type) and not result_json:
            raise ValueError(f"{analysis_type} analysis did not return valid JSON")
        result_json.setdefault("analysisType", analysis_type)
        result_json.setdefault("summary", self._short_text(content, 240))
        result_json.setdefault("detailContent", content)
        if analysis_type == "theme":
            result_json.setdefault("historicalWordCloud", [])
            result_json.setdefault("themeDistribution", [])
            result_json.setdefault("themeTable", [])
            result_json.setdefault("hotBooks", [])
            result_json.setdefault("systemArchetypes", [])
            result_json.setdefault("microInnovationSignals", [])
            result_json.setdefault("insightCards", [])
            result_json.setdefault("snapshotComparisons", [])
            result_json.setdefault("boardSummary", self._short_text(content, 180))
            result_json.setdefault("trendPreview", self._short_text(content, 300))
            result_json.setdefault("comparisonSummary", "")
            result_json.setdefault("historyAnalysisCount", len(request.sourcePayload.get("snapshots") or []))
        response = RunResponse(
            taskId=request.taskId,
            modelName=model_name,
            content=content,
            tokenUsed=max(0, token_used),
            resultJson=result_json,
        )
        if runtime_meta:
            response = self._apply_runtime_meta(response, runtime_meta)
        return response

    def _parse_result_json(self, content: str) -> dict[str, Any]:
        for candidate in self._iter_json_candidates(content):
            parsed = self._try_load_json_object(candidate)
            if parsed:
                return parsed
        return {}

    def _iter_json_candidates(self, content: str) -> list[str]:
        cleaned = (content or "").strip()
        if not cleaned:
            return []

        candidates: list[str] = []
        seen: set[str] = set()

        def push(candidate: str | None) -> None:
            if candidate is None:
                return
            normalized = candidate.strip()
            if not normalized or normalized in seen:
                return
            seen.add(normalized)
            candidates.append(normalized)

        push(cleaned)

        outer_fence = self._strip_outer_fence(cleaned)
        if outer_fence != cleaned:
            push(outer_fence)

        for fenced in self._extract_fenced_blocks(cleaned):
            push(fenced)
            if not self._starts_with_json_payload(fenced):
                push(self._extract_first_json_object(fenced))

        if not self._starts_with_json_payload(cleaned):
            push(self._extract_first_json_object(cleaned))
        if outer_fence != cleaned and not self._starts_with_json_payload(outer_fence):
            push(self._extract_first_json_object(outer_fence))
        return candidates

    def _strip_outer_fence(self, content: str) -> str:
        cleaned = content.strip()
        if not cleaned.startswith("```") or not cleaned.endswith("```"):
            return cleaned
        return re.sub(r"^```(?:json)?\s*|\s*```$", "", cleaned, flags=re.IGNORECASE).strip()

    def _extract_fenced_blocks(self, content: str) -> list[str]:
        return [
            match.group(1).strip()
            for match in re.finditer(r"```(?:json)?\s*([\s\S]*?)```", content or "", flags=re.IGNORECASE)
        ]

    def _extract_first_json_object(self, content: str) -> str | None:
        text = (content or "").strip()
        if not text:
            return None

        start = text.find("{")
        if start < 0:
            return None

        depth = 0
        inside_string = False
        escaped = False
        for index in range(start, len(text)):
            char = text[index]
            if escaped:
                escaped = False
                continue
            if char == "\\":
                escaped = True
                continue
            if char == '"':
                inside_string = not inside_string
                continue
            if inside_string:
                continue
            if char == "{":
                depth += 1
                continue
            if char != "}":
                continue
            depth -= 1
            if depth == 0:
                return text[start:index + 1]
        return None

    def _starts_with_json_payload(self, content: str) -> bool:
        stripped = (content or "").lstrip()
        return stripped.startswith("{") or stripped.startswith("[")

    def _try_load_json_object(self, candidate: str) -> dict[str, Any]:
        try:
            parsed = json.loads(candidate)
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            pass

        normalized = self._escape_control_characters_inside_strings(candidate)
        if normalized == candidate:
            return {}

        try:
            parsed = json.loads(normalized)
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            pass
        return {}

    def _escape_control_characters_inside_strings(self, content: str) -> str:
        if not content:
            return ""

        builder: list[str] = []
        inside_string = False
        escaped = False
        changed = False

        for char in content:
            if escaped:
                builder.append(char)
                escaped = False
                continue
            if char == "\\":
                builder.append(char)
                escaped = True
                continue
            if char == '"':
                builder.append(char)
                inside_string = not inside_string
                continue
            if not inside_string:
                builder.append(char)
                continue
            if char == "\n":
                builder.append("\\n")
                changed = True
                continue
            if char == "\r":
                builder.append("\\r")
                changed = True
                continue
            if char == "\t":
                builder.append("\\t")
                changed = True
                continue
            if ord(char) < 0x20:
                builder.append(f"\\u{ord(char):04x}")
                changed = True
                continue
            builder.append(char)

        return "".join(builder) if changed else content

    def _split_book_chunks(self, source_payload: dict[str, Any], chapters: list[dict[str, Any]], target_tokens: int) -> list[dict[str, Any]]:
        book = source_payload.get("book") or {}
        chunks: list[list[dict[str, Any]]] = []
        current: list[dict[str, Any]] = []
        for chapter in chapters:
            candidate = current + [chapter]
            candidate_text = self._build_book_input_text(book, candidate)
            if current and self._estimate_tokens(candidate_text) > target_tokens:
                chunks.append(current)
                current = []
            current.append(chapter)
        if current:
            chunks.append(current)
        return [
            {
                "input_text": self._build_book_input_text(book, chunk),
                "chapters": chunk,
            }
            for chunk in chunks
        ]

    def _build_book_input_text(self, book: dict[str, Any], chapters: list[dict[str, Any]]) -> str:
        parts = [
            f"Book: {book.get('bookName', '')}",
            f"Author: {book.get('author', '')}",
            f"Intro: {book.get('intro', '')}",
        ]
        for chapter in chapters:
            parts.append(f"[{chapter.get('chapterTitle', '')}] {chapter.get('content', '')}")
        return "\n".join(parts)

    def _build_chunk_prompt(self, prompt_config: PromptConfigPayload, chunk_index: int, chunk_count: int) -> str:
        original = (prompt_config.promptContent or "{{content}}").replace("{{content}}", "").strip()
        return (
            f"你正在进行长篇小说分段分析。\n当前是第 {chunk_index}/{chunk_count} 段，请只基于当前分段正文输出局部分析。\n"
            f"原始分析要求：\n{original}\n\n{{content}}"
        )

    def _build_merge_prompt(self, prompt_config: PromptConfigPayload) -> str:
        original = (prompt_config.promptContent or "{{content}}").replace("{{content}}", "").strip()
        return (
            "你正在整合同一本小说的多段局部分析结果。\n请去重、补全跨章节关系，输出一份最终结论。\n"
            f"原始分析要求：\n{original}\n\n{{content}}"
        )

    def _estimate_tokens(self, text: str) -> int:
        count = 0
        for char in text or "":
            count += 2 if ord(char) > 0x2E7F else 1
        return max(1, count // 3)

    def _resolve_timeout_millis(self, request: RunRequest) -> int:
        raw_timeout = request.limits.get("timeoutMillis")
        if raw_timeout is None:
            return settings.timeout_millis
        try:
            parsed_timeout = int(raw_timeout)
        except (TypeError, ValueError):
            return settings.timeout_millis
        return parsed_timeout if parsed_timeout > 0 else settings.timeout_millis

    def _analysis_type(self, agent_type: str) -> str:
        return "theme" if agent_type == "trend_theme" else agent_type

    def _build_runtime_meta(
        self,
        request: RunRequest,
        *,
        stream_mode: bool,
        use_chunking: bool,
        chunk_count: int,
        provider_call_count: int,
        queue_wait_ms: int,
        provider_latency_ms: int,
    ) -> dict[str, Any]:
        runtime_meta: dict[str, Any] = {
            "framework": "langgraph",
            "runtimeMode": "langgraph",
            "agentType": request.agentType,
            "analysisType": self._analysis_type(request.agentType),
            "streamMode": stream_mode,
            "useChunking": use_chunking,
            "chunkCount": max(0, chunk_count),
            "providerCallCount": max(1, provider_call_count),
            "queueWaitMillis": max(0, int(queue_wait_ms)),
            "providerLatencyMillis": max(0, int(provider_latency_ms)),
        }
        if request.traceId:
            runtime_meta["traceId"] = request.traceId
        return runtime_meta

    def _build_chunk_runtime_meta(
        self,
        request: RunRequest,
        chunk_results: list[RunResponse],
        merged_response: RunResponse | None,
    ) -> dict[str, Any]:
        runtime_metas = [self._runtime_meta(result) for result in chunk_results]
        if merged_response is not None:
            runtime_metas.append(self._runtime_meta(merged_response))
        return self._build_runtime_meta(
            request,
            stream_mode=False,
            use_chunking=True,
            chunk_count=len(chunk_results),
            provider_call_count=len(runtime_metas),
            queue_wait_ms=sum(int(meta.get("queueWaitMillis") or 0) for meta in runtime_metas),
            provider_latency_ms=sum(int(meta.get("providerLatencyMillis") or 0) for meta in runtime_metas),
        )

    def _finalize_response(self, request: RunRequest, response: RunResponse, total_duration_ms: int) -> RunResponse:
        runtime_meta = self._runtime_meta(response)
        if not runtime_meta:
            runtime_meta = self._build_runtime_meta(
                request,
                stream_mode=False,
                use_chunking=False,
                chunk_count=0,
                provider_call_count=1,
                queue_wait_ms=0,
                provider_latency_ms=0,
            )
        runtime_meta["totalDurationMillis"] = max(0, int(total_duration_ms))
        return self._apply_runtime_meta(response, runtime_meta)

    def _apply_runtime_meta(self, response: RunResponse, runtime_meta: dict[str, Any]) -> RunResponse:
        result_json = dict(response.resultJson or {})
        meta = result_json.get("meta")
        if not isinstance(meta, dict):
            meta = {}
        runtime = meta.get("runtime")
        if not isinstance(runtime, dict):
            runtime = {}
        runtime.update(runtime_meta)
        meta["runtime"] = runtime
        result_json["meta"] = meta
        response.resultJson = result_json
        return response

    def _runtime_meta(self, response: RunResponse) -> dict[str, Any]:
        meta = response.resultJson.get("meta")
        if not isinstance(meta, dict):
            return {}
        runtime = meta.get("runtime")
        return dict(runtime) if isinstance(runtime, dict) else {}

    def _elapsed_millis(self, started_at: float) -> int:
        return max(0, int((perf_counter() - started_at) * 1000))

    def _short_text(self, text: str, max_length: int) -> str:
        compact = " ".join((text or "").split())
        if len(compact) <= max_length:
            return compact
        return compact[:max_length] + "..."

    def _build_final_fallback_result(
        self,
        *,
        request: RunRequest,
        analysis_type: str,
        input_text: str,
        prompt_config: PromptConfigPayload,
        failure_reason: str,
    ) -> dict[str, Any]:
        model_name = prompt_config.modelName or settings.default_model
        summary_source = input_text or prompt_config.promptContent or ""
        summary = self._short_text(summary_source, 200)
        content = f"{analysis_type} analysis result\nmodel: {model_name}\nsummary: {summary}"
        result_json: dict[str, Any] = {
            "analysisType": analysis_type,
            "modelName": model_name,
            "summary": summary,
            "content": content,
            "detailContent": content,
            "meta": {
                "providerFailures": [
                    {
                        "provider": (prompt_config.providerType or settings.provider_type or "openai-compatible"),
                        "reason": failure_reason,
                    }
                ]
            },
        }
        if analysis_type == "theme":
            result_json.setdefault("boardSummary", self._short_text(content, 180))
            result_json.setdefault("trendPreview", self._short_text(content, 260))
            result_json.setdefault("comparisonSummary", "")
            result_json.setdefault("historicalWordCloud", [])
            result_json.setdefault("themeDistribution", [])
            result_json.setdefault("themeTable", [])
            result_json.setdefault("hotBooks", [])
            result_json.setdefault("systemArchetypes", [])
            result_json.setdefault("microInnovationSignals", [])
            result_json.setdefault("insightCards", [])
            result_json.setdefault("snapshotComparisons", [])
            result_json.setdefault("historyAnalysisCount", len(request.sourcePayload.get("snapshots") or []))
        return {
            "model_name": model_name,
            "content": content,
            "token_used": max(120, len(summary_source) // 2),
            "result_json": result_json,
        }
