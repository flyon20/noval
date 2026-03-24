from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class PromptConfigPayload(BaseModel):
    promptType: str | None = None
    promptName: str | None = None
    promptContent: str | None = None
    modelName: str | None = None
    temperature: float | None = None
    maxTokens: int | None = None
    outputJsonSchema: str | None = None
    outputExampleJson: str | None = None
    postProcessType: str | None = None
    parseConfigJson: str | None = None


class RunRequest(BaseModel):
    taskId: str
    traceId: str | None = None
    agentType: str
    stream: bool = False
    promptConfig: PromptConfigPayload
    sourcePayload: dict[str, Any] = Field(default_factory=dict)
    limits: dict[str, Any] = Field(default_factory=dict)
    contextMeta: dict[str, Any] = Field(default_factory=dict)


class RunResponse(BaseModel):
    taskId: str
    modelName: str
    content: str
    tokenUsed: int
    resultJson: dict[str, Any] = Field(default_factory=dict)
