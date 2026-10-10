import { Context } from "@deepseek-ai/cordis";
import AgentRegistry from "@deepseek-ai/dsh-agent";
import AgentLoop from "@deepseek-ai/dsh-agent-loop";
import LlmRuntime, { LlmAdapter, CallId, createUserMessage, createAssistantMessage, createToolResultMessage } from "@deepseek-ai/dsh-llm";
import SessionStore, { SessionId } from "@deepseek-ai/dsh-session";
import JsonlSessionPersistence from "@deepseek-ai/dsh-session-persistence-jsonl";
import SkillRegistry from "@deepseek-ai/dsh-skill";
import * as SkillFileSystem from "@deepseek-ai/dsh-skill-filesystem";
import SystemPrompt from "@deepseek-ai/dsh-system-prompt";
import ToolRuntime from "@deepseek-ai/dsh-tools";
import TokenMeter from "@deepseek-ai/dsh-token-meter";
import BasicCompactionEngine from "@deepseek-ai/dsh-compaction-basic";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { createInterface } from "node:readline";

const version = JSON.parse(readFileSync(new URL("../package.json", import.meta.resolve("@deepseek-ai/dsh-agent-loop")), "utf8")).version;
const send = value => process.stdout.write(`${JSON.stringify(value)}\n`);
const pending = new Map();
let sequence = 0;
let running = false;
let active;
let stored;
let stage = "ready";

function rpc(type, payload) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    pending.set(id, { resolve, reject });
    send({ type, id, ...payload });
  });
}

const text = content => (content ?? []).filter(block => block.type === "text").map(block => block.text).join("");
function toWire(messages) {
  const names = new Map(messages.flatMap(message => message.content.filter(block => block.type === "tool-call").map(block => [block.id, block.name])));
  return messages.flatMap(message => {
    const results = message.content.filter(block => block.type === "tool-result");
    if (results.length) return results.map(block => ({ role: "tool", content: text(block.content),
      tool_call_id: block.toolCallId, name: names.get(block.toolCallId) ?? "" }));
    const result = { role: message.role, content: text(message.content) };
    const calls = message.content.filter(block => block.type === "tool-call");
    if (calls.length) result.tool_calls = calls.map(call => ({ id: call.id, type: "function",
      function: { name: call.name, arguments: call.arguments } }));
    const reasoning = message.content.filter(block => block.type === "reasoning").map(block => block.text).join("");
    if (reasoning) result.reasoning_content = reasoning;
    return [result];
  });
}

class HostAdapter extends LlmAdapter {
  async resolveModel(provider, model) {
    return { provider, id: model, name: model, context: { contextWindow: 131072 } };
  }

  async *stream(options) {
    if (active.turns >= active.input.maxTurns) {
      active.stoppedAtLimit = true;
      yield { type: "finish", reason: { kind: "error", failure: { code: "BUDGET", message: "Host model budget exhausted" } } };
      return;
    }
    active.turns += 1;
    const reply = await rpc("model", { purpose: options.purpose ?? "answer",
      messages: [{ role: "system", content: options.system ?? "" }, ...toWire(options.messages)],
      toolNames: (options.tools ?? []).map(tool => tool.name) });
    active.stopReason = reply.stopReason;
    if (reply.stopReason === "error") {
      yield { type: "finish", reason: { kind: "error", failure: { code: "HOST_STOP", message: "Host stopped evaluation" } } };
      return;
    }
    let index = 0;
    if (reply.reasoningContent) {
      yield { type: "block-start", index, blockType: "reasoning" };
      yield { type: "reasoning-delta", index, text: reply.reasoningContent };
      yield { type: "block-end", index: index++, block: { type: "reasoning", text: reply.reasoningContent } };
    }
    if (reply.content) {
      yield { type: "block-start", index, blockType: "text" };
      yield { type: "text-delta", index, text: reply.content };
      yield { type: "block-end", index: index++, block: { type: "text", text: reply.content } };
    }
    for (const call of reply.toolCalls) {
      const block = { type: "tool-call", id: CallId(call.id), name: call.name, arguments: JSON.stringify(call.arguments) };
      yield { type: "block-start", index, blockType: "tool-call" };
      yield { type: "tool-call-delta", index, id: block.id, name: block.name, argumentsDelta: block.arguments };
      yield { type: "block-end", index: index++, block };
    }
    const usage = reply.usage ?? {};
    yield { type: "usage", usage: { inputTokens: usage.inputTokens ?? 0, outputTokens: usage.outputTokens ?? 0 } };
    yield { type: "finish", reason: { kind: reply.stopReason === "length" ? "max-tokens" : reply.toolCalls.length ? "tool-calls" : "stop" } };
  }
}

async function disposeStored() {
  if (!stored) return;
  const previous = stored;
  stored = undefined;
  await previous.handle.dispose();
  await previous.ctx.fiber.dispose();
}

async function createSession(input) {
  stage = "session_create";
  const cwd = join(input.sessionDir, "workspace");
  mkdirSync(cwd, { recursive: true });
  const ctx = new Context();
  try {
    await ctx.plugin(LlmRuntime);
    await ctx.plugin(SessionStore);
    await ctx.plugin(SystemPrompt, { includeHarnessIdentity: false, includeRuntimeContext: false, persona: input.instructions });
    await ctx.plugin(ToolRuntime, { mode: "native" });
    await ctx.plugin(AgentRegistry);
    await ctx.plugin(JsonlSessionPersistence, { root: join(input.sessionDir, "sessions"), compression: "none", packChunks: false });
    await ctx.plugin(TokenMeter);
    await ctx.plugin(BasicCompactionEngine, { thresholdRatio: 0.8, retainTokens: 12000, maxTokens: 2048, compactionRetries: 0, maxOverflowRetries: 0 });
    await ctx.plugin(SkillRegistry);
    await ctx.plugin(SkillFileSystem, { includeDefaultRoots: false, customSkillDirs: [input.skillDir],
      dshHome: join(input.sessionDir, "agent-home"), agentsHome: join(input.sessionDir, "agent-home"), watch: false });
    const skills = await ctx.skills.list({ cwd });
    const catalog = skills.map(skill => ({ name: skill.name, description: skill.description,
      path: join(input.skillDir, skill.name, "SKILL.md").replaceAll("\\", "/") }));
    const skillPaths = new Map(catalog.map(skill => [skill.path.toLowerCase(), skill.name]));
    const loadedSkills = new Set();
    if (catalog.length) ctx.systemPrompt.section({ name: "authorized-skills", order: 10,
      text: "Available skills (load bodies with read using the exact path):\n" + JSON.stringify(catalog) });
    ctx.llm.registerAdapter(["noval"], new HostAdapter());
    for (const { function: spec } of input.tools) {
      ctx.tools.register({ ...spec,
        output: { schema: { type: "object", properties: { content: { type: "string" }, status: { type: "string" } },
          required: ["content", "status"], additionalProperties: false }, render: (_args, value) => [{ type: "text", text: value.content }] },
        execute: async (args, exec) => {
          const result = await rpc("tool", { call: { id: String(exec.callId), name: spec.name, arguments: args } });
          active.toolCount += 1;
          if (spec.name === "read" && result.status === "succeeded") {
            const skillName = skillPaths.get(String(args.path).replaceAll("\\", "/").toLowerCase());
            if (skillName && await ctx.skills.get(skillName, { cwd })) loadedSkills.add(skillName);
          }
          return result;
        },
      });
    }
    await ctx.plugin(AgentLoop, { agents: [], maxParallelToolCalls: 1 });
    const handle = await ctx.agents.create({ sessionId: SessionId(randomUUID()), meta: { cwd },
      agentOptions: { provider: "noval", model: input.model, maxTokens: input.maxTokens } });
    return { ctx, handle, key: input.sessionKey, catalog, loadedSkills };
  } catch (error) {
    await ctx.fiber.dispose();
    throw error;
  }
}

function appendHistory(session, message, model) {
  const content = [{ type: "text", text: message.content ?? "" }];
  const placement = { surfaceOp: "append" };
  if (message.role === "assistant") {
    for (const call of message.tool_calls ?? []) content.push({ type: "tool-call", id: CallId(call.id),
      name: call.function.name, arguments: call.function.arguments });
    session.append("assistant/message", { turn: 0, step: 0,
      message: createAssistantMessage({ content, source: { provider: "noval", model } }) }, placement);
  } else if (message.role === "tool") {
    session.append("tool/result", { turn: 0, step: 0,
      message: createToolResultMessage({ callId: CallId(message.tool_call_id), content, isError: false }) }, placement);
  } else {
    session.append("user/message", createUserMessage({ content, source: { kind: "user" } }), placement);
  }
}

async function run(input) {
  active = { input, turns: 0, toolCount: 0, stoppedAtLimit: false, stopReason: "error" };
  const reused = Boolean(stored && stored.key === input.sessionKey && input.resumeHistory);
  if (!reused) {
    await disposeStored();
    stored = await createSession(input);
  }
  const { agent } = stored.handle;
  const incoming = input.messages.filter(message => message.role !== "system");
  if (!incoming.length || incoming.at(-1).role !== "user") throw new Error("Expected current user task");
  for (const message of incoming.slice(0, -1)) appendHistory(agent.session, message, input.model);
  stage = "prompt";
  agent.followup(createUserMessage({ content: [{ type: "text", text: incoming.at(-1).content }], source: { kind: "user" } }));
  await agent.whenIdle();
  await stored.ctx.parallel("session/flush", agent.session);
  const messages = toWire(agent.session.deriveMessages());
  const location = stored.ctx.sessionPersistence.locate(agent.session.header);
  send({ type: "done", messages, stopReason: active.stopReason, stoppedAtLimit: active.stoppedAtLimit,
    sessionInfo: { sessionReused: reused, sessionFile: location?.path ?? null, retainedMessages: messages.length,
      nativeSkillCount: stored.catalog.length, loadedSkills: [...stored.loadedSkills],
      compactions: agent.session.events.filter(event => event.type.startsWith("compaction/") && event.type.endsWith("end")).length,
      toolInvocations: active.toolCount, reviewInvocations: 0, reviewResults: [], reviewContract: null,
      composition: "native_dsh_session_skills_tools_compaction_with_noval_host" } });
  stage = "ready";
}

function fatal(error) {
  if (active?.input.sessionDir) {
    writeFileSync(join(active.input.sessionDir, "bridge-failure.json"), JSON.stringify({ stage,
      errorType: error?.name ?? "Error", message: String(error?.message ?? error), stack: error?.stack }, null, 2));
  }
  send({ type: "fatal", stage, errorType: error?.name ?? "Error", errorCode: String(error?.code ?? "runtime_error").slice(0, 80) });
}

const reader = createInterface({ input: process.stdin });
reader.on("line", line => {
  let value;
  try { value = JSON.parse(line); } catch (error) { fatal(error); return; }
  if (value.type === "reply") {
    pending.get(value.id)?.resolve(value.result);
    pending.delete(value.id);
    return;
  }
  if (value.type !== "run" || running) { fatal(new Error("Unexpected protocol message")); return; }
  running = true;
  void run(value).catch(fatal).finally(() => { running = false; });
});
reader.on("close", () => {
  stored?.handle.agent.cancel({ kind: "user" });
  for (const item of pending.values()) item.reject(new Error("Host closed evaluation"));
  pending.clear();
  void disposeStored().finally(() => process.exit(0));
});
send({ type: "ready", version, node: process.version });
