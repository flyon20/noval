import { Agent } from "@earendil-works/pi-agent-core";
import { createAssistantMessageEventStream } from "@earendil-works/pi-ai";
import { createRequire } from "node:module";
import { createInterface } from "node:readline";

const require = createRequire(import.meta.url);
const version = require("@earendil-works/pi-agent-core/package.json").version;
const send = (value) => process.stdout.write(`${JSON.stringify(value)}\n`);
const pending = new Map();
let sequence = 0;
let running = false;

function rpc(type, payload) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    pending.set(id, { resolve, reject });
    send({ type, id, ...payload });
  });
}

const zeroUsage = () => ({
  input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
});

function assistant(content, model, stopReason = "stop") {
  return {
    role: "assistant", content, api: "noval-evaluation", provider: "noval",
    model, usage: zeroUsage(), stopReason, timestamp: Date.now(),
  };
}

function fromWire(message, model) {
  if (message.role === "user") {
    return { role: "user", content: message.content, timestamp: Date.now() };
  }
  if (message.role === "tool") {
    return {
      role: "toolResult", toolCallId: message.tool_call_id, toolName: message.name,
      content: [{ type: "text", text: message.content }], isError: false, timestamp: Date.now(),
    };
  }
  if (message.role !== "assistant") throw new Error("Unsupported evaluation message role");
  const content = message.content ? [{ type: "text", text: message.content }] : [];
  for (const call of message.tool_calls ?? []) {
    content.push({
      type: "toolCall", id: call.id, name: call.function.name,
      arguments: typeof call.function.arguments === "string"
        ? JSON.parse(call.function.arguments) : call.function.arguments,
    });
  }
  return { ...assistant(content, model), novalReasoning: message.reasoning_content };
}

function toWire(message) {
  if (message.role === "user") return { role: "user", content: message.content };
  const content = message.content.filter((part) => part.type === "text").map((part) => part.text).join("");
  if (message.role === "toolResult") {
    return { role: "tool", content, tool_call_id: message.toolCallId, name: message.toolName };
  }
  const result = { role: "assistant", content };
  const calls = message.content.filter((part) => part.type === "toolCall");
  if (calls.length) {
    result.tool_calls = calls.map((call) => ({
      id: call.id, type: "function",
      function: { name: call.name, arguments: JSON.stringify(call.arguments) },
    }));
  }
  if (message.novalReasoning) result.reasoning_content = message.novalReasoning;
  return result;
}

async function run(input) {
  const system = input.messages.filter((message) => message.role === "system");
  const messages = input.messages.filter((message) => message.role !== "system").map((message) => fromWire(message, input.model));
  const prompt = messages.pop();
  if (!prompt || prompt.role !== "user") throw new Error("Expected final user message");
  let turns = 0;
  let toolCount = 0;
  let stoppedAtLimit = false;
  const tools = input.tools.map(({ function: spec }) => ({
    ...spec, label: spec.name,
    execute: async (id, args) => {
      const result = await rpc("tool", { call: { id, name: spec.name, arguments: args } });
      toolCount += 1;
      return { content: [{ type: "text", text: result.content }], details: { status: result.status } };
    },
  }));
  const agent = new Agent({
    initialState: {
      systemPrompt: system.map((message) => message.content).join("\n\n"),
      messages, tools,
      model: { id: input.model, name: input.model, api: "noval-evaluation", provider: "noval", baseUrl: "", reasoning: false,
        input: ["text"], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 1000000, maxTokens: input.maxTokens },
    },
    toolExecution: "sequential",
    streamFn: (_model, context) => {
      const stream = createAssistantMessageEventStream();
      void (async () => {
        try {
          const reply = await rpc("model", { messages: [...system, ...context.messages.map(toWire)] });
          turns += 1;
          const content = reply.content ? [{ type: "text", text: reply.content }] : [];
          content.push(...reply.toolCalls.map((call) => ({ type: "toolCall", ...call })));
          const message = assistant(content, input.model, reply.stopReason);
          message.novalReasoning = reply.reasoningContent;
          message.usage.totalTokens = reply.tokens;
          if (reply.stopReason === "error") {
            message.errorMessage = "Host stopped evaluation";
            stream.push({ type: "error", reason: "error", error: message });
          } else {
            stream.push({ type: "done", reason: reply.stopReason, message });
          }
          stream.end(message);
        } catch {
          const message = assistant([], input.model, "error");
          message.errorMessage = "Evaluation transport failed";
          stream.push({ type: "error", reason: "error", error: message });
          stream.end(message);
        }
      })();
      return stream;
    },
    afterToolCall: ({ result }) => ({ isError: result.details?.status !== "succeeded" }),
    prepareNextTurnWithContext: ({ context }) => ({
      context: { ...context, tools: toolCount >= input.maxTools ? [] : context.tools },
    }),
    shouldStopAfterTurn: ({ message }) => {
      stoppedAtLimit = turns >= input.maxTurns && message.content.some((part) => part.type === "toolCall");
      return stoppedAtLimit;
    },
  });
  await agent.prompt(prompt);
  const transcript = agent.state.messages.map(toWire);
  const last = agent.state.messages.at(-1);
  send({ type: "done", messages: [...system, ...transcript], stoppedAtLimit, stopReason: last?.stopReason ?? "error" });
}

const reader = createInterface({ input: process.stdin, crlfDelay: Infinity });
reader.on("line", (line) => {
  try {
    const message = JSON.parse(line);
    if (message.type === "reply") {
      const item = pending.get(message.id);
      if (!item) throw new Error("Unknown reply");
      pending.delete(message.id);
      item.resolve(message.result);
    } else if (message.type === "run" && !running) {
      running = true;
      void run(message).then(
        () => { running = false; },
        () => { running = false; send({ type: "fatal", errorType: "PiEvaluationError" }); },
      );
    } else {
      throw new Error("Unsupported protocol message");
    }
  } catch {
    send({ type: "fatal", errorType: "PiProtocolError" });
  }
});
reader.on("close", () => {
  for (const item of pending.values()) item.reject(new Error("Host disconnected"));
  pending.clear();
});
send({ type: "ready", version, node: process.version });
