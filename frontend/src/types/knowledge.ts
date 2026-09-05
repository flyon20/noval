import type { Platform } from '@/types/crawler';

export interface KnowledgeBookCandidate {
  bookId?: number;
  platform?: Platform;
  platformBookId?: string;
  bookName: string;
  author?: string;
  intro?: string;
  bookUrl?: string;
  local?: boolean;
  contentType?: 'novel' | 'audiobook' | 'video' | 'unknown' | string;
  readableNovel?: boolean;
  unavailableReason?: string;
}
export interface KnowledgeSource {
  chunkId?: number;
  documentId?: number;
  score?: number;
  bookId?: number;
  bookName?: string;
  platform?: Platform;
  sourceType?: string;
  sourceRefId?: number;
  snapshotId?: number;
  snapshotTime?: string;
  channelCode?: string;
  boardCode?: string;
  channelName?: string;
  boardName?: string;
  chapterNo?: number;
  analysisType?: string;
  rankNo?: number;
  author?: string;
  category?: string;
  title?: string;
  preview?: string;
  retrievalBackend?: string;
}

export interface KnowledgeChatMessage {
  role: 'user' | 'assistant';
  content: string;
  status?: string;
  answerStatus?: string;
  intent?: string;
  answerBoundary?: string;
  sources?: KnowledgeSource[];
  contextBudget?: ContextBudget;
  traceId?: string;
}

export interface KnowledgeResultJson {
  conversationId?: string;
  answerStatus?: string;
  intent?: string;
  answerBoundary?: string;
  domainIntent?: string;
  domainAnswerBoundary?: string;
  [key: string]: unknown;
}

export interface KnowledgeProject {
  projectId: number;
  userId?: number;
  name: string;
  description?: string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface KnowledgeProjectRequest {
  name: string;
  description?: string;
}

export interface KnowledgeConversationMessage {
  messageId: number;
  conversationId: string;
  userId?: number;
  projectId?: number;
  runId?: string;
  role: 'USER' | 'ASSISTANT' | string;
  content: string;
  contentJson?: string;
  tokenCount?: number;
  createdAt?: string;
}

export interface KnowledgeConversation {
  conversationId: string;
  userId?: number;
  projectId?: number;
  title?: string;
  status?: string;
  lastMessageId?: number;
  lastRunId?: string;
  lastRunStatus?: string;
  createdAt?: string;
  updatedAt?: string;
  archivedAt?: string;
  messages?: KnowledgeConversationMessage[];
}

export interface ProjectWork {
  workId: number;
  userId?: number;
  projectId: number;
  title: string;
  alias?: string;
  genre?: string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface ProjectWorkRequest {
  title: string;
  alias?: string;
  genre?: string;
}

export interface ProjectChapter {
  chapterId: number;
  userId?: number;
  projectId: number;
  workId: number;
  chapterNo: number;
  title?: string;
  content?: string;
  contentHash?: string;
  wordCount?: number;
  sourceType?: string;
  version?: number;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface ProjectChapterImportRequest {
  chapterNo: number;
  title?: string;
  content: string;
  sourceType?: string;
}

export interface AgentTraceHealthSummary {
  model?: string;
  tools?: string;
  evidence?: string;
  memory?: string;
  experts?: string;
}

export interface AgentTraceListItem {
  id: number;
  traceId: string;
  userId?: number;
  projectId?: number;
  conversationId?: string;
  question?: string;
  status?: string;
  healthSummary?: AgentTraceHealthSummary;
  createdAt?: string;
}

export interface AgentTraceSummary extends AgentTraceListItem {
  taskGraph?: string;
  toolRuns?: string;
  evidencePack?: string;
  perspectiveResults?: string;
  resultJson?: string;
  intentDecision?: string;
  contextUsed?: string;
  memoryUsed?: string;
  memoryDiagnostics?: string;
  retrievalDiagnostics?: string;
  sourcePolicy?: string;
  supervisorDecision?: string;
  memoryCandidates?: string;
  mcpToolCalls?: string;
  toolPermissionDecisions?: string;
  evidenceContract?: string;
  selectedSnapshotGroup?: string;
  rejectedSnapshotGroups?: string;
  specialistAgentResults?: string;
  skillMediation?: string;
  skillBom?: string;
  selectedExperts?: string;
  expertRouter?: string;
  finalAnswerBoundary?: string;
  snapshotTime?: string;
}

export interface AgentRuntimeTraceNode {
  name: string;
  status?: string;
  sequenceNo?: number;
  durationMs?: number;
  [key: string]: unknown;
}

export interface ContextBudgetMemoryLayer {
  name: string;
  status?: string;
  itemCount?: number;
  [key: string]: unknown;
}

export interface ContextBudget {
  maxInputTokens?: number;
  estimatedUsedChars?: number;
  estimatedUsedTokens?: number;
  usedTokens?: number;
  observedInputTokens?: number;
  remainingTokens?: number;
  remainingRatio?: number;
  compressionThresholdTokens?: number;
  compressed?: boolean;
  compacting?: boolean;
  compactionGeneration?: number;
  lastCompactedFromTokens?: number;
  components?: Record<string, number>;
  conversationContinuity?: ConversationContinuity;
  memoryLayers?: ContextBudgetMemoryLayer[];
  warnings?: string[];
  [key: string]: unknown;
}

export interface AgentRuntimeTrace {
  nodes?: AgentRuntimeTraceNode[];
  executedRuntimeNodes?: string[];
  [key: string]: unknown;
}

export interface ConversationContinuity {
  historyTotalCount?: number;
  historyIncludedCount?: number;
  includedRoleCounts?: Record<string, number>;
  historyTotalChars?: number;
  historyIncludedChars?: number;
  historyTruncated?: boolean;
  contextSummaryChars?: number;
  contextSummaryIncludedChars?: number;
  contextSummaryTruncated?: boolean;
}

export interface KnowledgeRunProcessStep {
  id: string;
  label: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  durationMs?: number;
}

export interface KnowledgeRunModelCall {
  id: string;
  label: string;
  model?: string;
  status: 'succeeded' | 'failed' | 'unknown';
  durationMs?: number;
  tokenUsed?: number;
  reasoningMode?: 'fast' | 'deep';
  providerRequestCount?: number;
  /** 1 表示首次尝试就成功；大于 1 说明重试或换 key 之后才拿到响应。 */
  attemptIndex?: number;
  /** 实际发出请求的 provider profile key，路由启用后才有多个可能值。 */
  profileKeyUsed?: string;
  /** 成功之前最后一次失败的分类，例如 HTTP_401 / TIMEOUT。 */
  failureClass?: string;
  /** continuity 诊断所属的请求族，例如 intent/specialist/answer/review。 */
  requestFamily?: string;
  wireApi?: 'responses' | 'chat_completions';
  providerTransportFallback?: KnowledgeProviderTransportFallback;
  /** 上游是否真的回报了用量。false 表示"未知"，不是"用了 0"。 */
  usageReported?: boolean;
  /** 上游是否真的回报了缓存用量。false 时命中数字不可信，不能拿来算命中率。 */
  cacheUsageReported?: boolean;
  /** 实际发出请求时用的模型名。选中的模型没有对应 profile 时会落到默认档。 */
  routedModel?: string;
  /** routedModel 与选中的模型不一致，缓存与计费都记在 routedModel 上。 */
  modelSubstituted?: boolean;
  usage?: KnowledgeProviderUsage;
  requestSummary?: KnowledgeProviderRequestSummary;
  responseSummary?: KnowledgeProviderResponseSummary;
}

export interface KnowledgeProviderTransportFallback {
  from: 'responses' | 'chat_completions';
  to: 'responses' | 'chat_completions';
  reason: 'model_not_responses_capable';
  model?: string;
}

export interface KnowledgeProviderUsage {
  inputTokens?: number;
  outputTokens?: number;
  reasoningTokens?: number;
  cachedInputTokens?: number;
  promptTokens?: number;
  completionTokens?: number;
  promptCacheHitTokens?: number;
  promptCacheMissTokens?: number;
  promptCacheWriteTokens?: number;
  promptCacheMissTokensDerived?: boolean;
  cacheWriteInputTokens?: number;
  totalTokens?: number;
  usageReported?: boolean;
  cacheUsageReported?: boolean;
}

/** 会话级前缀缓存汇总。命中率只在上报过缓存用量的调用上计算。 */
export interface KnowledgeRunPromptCacheSummary {
  calls: number;
  reportingCalls: number;
  /** 至少有一次调用回报了缓存用量；false 时 hitRatioPercent 必为 null。 */
  measured: boolean;
  hitTokens: number;
  missTokens: number;
  hitRatioPercent: number | null;
}

/**
 * 上下文压缩的白名单投影。worker 顶层 `contextCompaction` 里还带着 `compactedSummary`
 * （压缩后的会话正文）和 `coverageFingerprint`，两个都**不**映射：正文不上界面，
 * 哈希不当内容渲染。未压缩时 worker 根本不写这个对象，所以它存在就意味着压过。
 */
export interface KnowledgeRunContextCompaction {
  /** `compacted` = 这次真压了；`reused` = 复用了上一代摘要。 */
  status: string;
  reason?: string;
  model?: string;
  contextWindowTokens?: number;
  thresholdTokens?: number;
  beforeInputTokens?: number;
  afterInputTokens?: number;
  retainedTurnCount?: number;
  summarizedMessageCount?: number;
  reusedMessageCount?: number;
  /** 第几代摘要，断点续跑会累加。 */
  generation?: number;
}

/** worker 侧目前会写的四种降级原因，其余 code 原样保留但渲染成通用文案。 */
export type KnowledgeRunDegradationReason =
  | 'run_token_budget_exceeded'
  | 'answer_quality_gate_failed'
  | 'provider_exception'
  | 'evidence_commit_rejected';

export interface KnowledgeProviderRequestSummary {
  messageCount?: number;
  roleCounts?: Record<string, number>;
  messageChars?: number;
  toolSchemaCount?: number;
  reasoningRequested?: boolean;
  bodyRedacted?: boolean;
  requestFamily?: string;
  /** 是否带了缓存亲和键（gpt-5.6 起没有它就退回旧前缀匹配）。 */
  cacheAffinityPresent?: boolean;
  /** 可缓存前缀的字符数。低于约 4000 字符时供应商通常根本不缓存。 */
  cachePrefixChars?: number;
  /** 前缀指纹，两次调用不同就说明前缀被改写、缓存必然落空。 */
  cachePrefixFingerprint?: string;
}

export interface KnowledgeProviderResponseSummary {
  outputChars?: number;
  toolCallCount?: number;
  emptyResponse?: boolean;
  bodyRedacted?: boolean;
}

export interface KnowledgeRunProcessSummary {
  id: 'task' | 'context' | 'model' | 'review' | string;
  label: string;
  detail: string;
}

export interface KnowledgeRunProcess {
  status: 'processing' | 'processed' | 'failed' | 'cancelled';
  startedAtMs?: number;
  finishedAtMs?: number;
  durationMs?: number;
  modelCallCount?: number;
  modelCalls?: KnowledgeRunModelCall[];
  promptCache?: KnowledgeRunPromptCacheSummary;
  /** 只在真的压缩过时存在，未压缩不占位。 */
  contextCompaction?: KnowledgeRunContextCompaction;
  /** 空数组和 undefined 同义：这次回答没有降级。 */
  degradationReasons?: string[];
  operationalSummaries?: KnowledgeRunProcessSummary[];
  currentStep?: KnowledgeRunProcessStep;
  steps: KnowledgeRunProcessStep[];
  loaded: boolean;
  loading?: boolean;
}

export interface GoldenCandidateDraft {
  status: 'DRAFT' | string;
  traceId?: string;
  question?: string;
  answer?: string;
  selectedSkills?: string[];
  selectedTools?: string[];
  evidenceContract?: unknown;
  traceSummary?: string | Record<string, unknown>;
}

export interface PageResult<T> {
  page: number;
  pageSize: number;
  total: number;
  hasNext: boolean;
  items: T[];
}

export type AgentTracePage = PageResult<AgentTraceListItem>;

export interface AgentTraceQuery {
  page?: number;
  pageSize?: number;
  status?: string;
  keyword?: string;
}

export interface AiMemory {
  id: number;
  userId?: number;
  projectId?: number;
  conversationId?: string;
  scope?: string;
  memoryType?: string;
  content?: string;
  summary?: string;
  confidence?: number;
  status?: string;
  sourceTraceId?: string;
}

export interface MemoryAdminQuery {
  userId?: number;
  projectId?: number;
  status?: string;
  scope?: string;
  limit?: number;
}

export interface RuntimeSkill {
  skillId: string;
  version?: string;
  title?: string;
  description?: string;
  intents: string[];
  triggers: string[];
  requestedCapabilities?: string[];
  skillMetadata?: Record<string, unknown>;
}

export interface SkillShortcut {
  skillId: string;
  title: string;
  description?: string;
  appliesTo: string[];
}

export interface SkillCandidate {
  id: number;
  skillId: string;
  title: string;
  description?: string;
  status: string;
  evalStatus: string;
  evalResult?: SkillEvalResult;
  evalResultJson?: string;
  requiredToolPassRate?: number;
  evidencePassRate?: number;
  faithfulnessPassRate?: number;
  reviewNote?: string;
  requestedCapabilitiesJson?: string;
  skillMetadataJson?: string;
}

export interface SkillEvalResult {
  status?: string;
  suiteId?: string;
  requiredToolPassRate?: number;
  evidencePassRate?: number;
  faithfulnessPassRate?: number;
  failures?: string[];
}

export interface SkillCandidateCreatePayload {
  skillId: string;
  title: string;
  content: string;
  requestedCapabilities?: string[];
  evalResultJson?: string;
  requiredToolPassRate?: number;
  evidencePassRate?: number;
  faithfulnessPassRate?: number;
}

export type SkillCandidatePage = PageResult<SkillCandidate>;

export interface SkillDashboardQuery {
  page?: number;
  pageSize?: number;
  status?: string;
}

export interface SkillGovernanceDashboard {
  runtimeSkills: RuntimeSkill[];
  candidates: SkillCandidatePage;
}

export interface AgentRuntimeConfig {
  reasoningModeDefault?: KnowledgeReasoningMode | string;
  maxParallelSpecialists?: number;
  maxTotalInputTokens?: number;
  maxFinalOutputTokensFast?: number;
  maxFinalOutputTokensDeep?: number;
  enableIntentCache?: boolean;
  enableTaskGraphCache?: boolean;
  enableToolCache?: boolean;
  enableEvidenceCache?: boolean;
  enableSpecialistCache?: boolean;
  specialistMcpEnabled?: boolean;
  maxPromptCharsPerExpert?: number;
  maxSkillPromptChars?: number;
  maxEvidenceItems?: number;
  contextCompactionThresholdPercent?: number;
  runTokenBudgetPercent?: number;
}

export interface AgentExpertProfile {
  expertName: string;
  displayName?: string;
  enabled?: boolean;
  defaultMode?: string;
  costClass?: string;
  maxTokens?: number;
  maxToolCalls?: number;
  capabilityIds?: string[];
  defaultSkillIds?: string[];
  requestedToolCapabilities?: string[];
  outputContract?: string | null;
  executionKind?: 'INLINE' | 'DETERMINISTIC' | 'DELEGATED';
  triggerIntents?: string[];
  triggerTasks?: string[];
  priority?: number;
  promptVersion?: string;
  evalSuiteId?: string;
  guardrail?: boolean;
  category?: 'Skill' | 'Deterministic' | 'Delegated';
  expectedQualityGain?: number;
  qualityGainVerified?: boolean;
  qualityGainSource?: string;
  qualityGainEvalRunId?: number;
  latencyCost?: number;
  tokenCost?: number;
  resourceCost?: number;
}

export interface AgentCacheTokenStats {
  traceCount: number;
  cacheHits: number;
  cacheMisses: number;
  totalTokens: number;
  promptPrefixStableRate?: number;
  tokenByNode: Record<string, number>;
  tokenByExpert: Record<string, number>;
}

export interface AgentEvalRun {
  id: number;
  runKey: string;
  suiteName?: string;
  runnerName?: string;
  evaluatorName?: string;
  modelName?: string;
  status?: string;
  totalCases?: number;
  passedCases?: number;
  failedCases?: number;
  progressCurrent?: number;
  progressTotal?: number;
  progressMessage?: string;
  cancelRequested?: boolean;
  cancelledAt?: string;
  retryCount?: number;
  maxRetries?: number;
  nextRetryAt?: string;
  lastHeartbeatAt?: string;
  errorMessage?: string;
  queuedAt?: string;
  queued?: boolean;
  metricsJson?: string;
  startedAt?: string;
  finishedAt?: string;
}

export interface AgentEvalRunRequest {
  suiteName: string;
  runKey?: string;
  runnerName?: string;
  evaluatorName?: string;
  modelName?: string;
  caseLimit?: number;
  synchronous?: boolean;
}

export interface AgentEvalCaseResult {
  id: number;
  runId: number;
  caseKey: string;
  status?: string;
  intent?: string;
  answerMode?: string;
  retrievalMetrics?: string;
  faithfulnessJson?: string;
  failures?: string;
  traceId?: string;
  durationMs?: number;
  createdAt?: string;
}

export type KnowledgeReasoningMode = 'fast' | 'deep';

/**
 * 规范档位标度；各供应商实际接受的枚举由后端/worker 方言表收敛。
 * xhigh 只有 gpt-5.6 一代报得出来，其余族的档位列表里不会出现。
 */
export type KnowledgeReasoningEffort = 'minimal' | 'low' | 'medium' | 'high' | 'xhigh' | 'max';

export interface KnowledgeChatRequest {
  question: string;
  conversationId?: string;
  projectId?: number;
  workId?: number;
  referenceWorkIds?: number[];
  preferredSkillId?: string;
  bookName?: string;
  bookId?: number;
  selectedCandidate?: KnowledgeBookCandidate;
  mode?: string;
  reasoningMode?: KnowledgeReasoningMode;
  reasoningEffort?: KnowledgeReasoningEffort;
  modelKey?: string;
  contextSummary?: string;
  history?: KnowledgeChatMessage[];
  limits?: Record<string, unknown>;
}

export interface KnowledgeChatResponse {
  status: string;
  answer: string;
  candidates: KnowledgeBookCandidate[];
  sources: KnowledgeSource[];
  actions: string[];
  resultJson: KnowledgeResultJson;
}

export interface KnowledgeChatRun {
  runId: string;
  userId?: number;
  projectId?: number;
  conversationId: string;
  question?: string;
  status: 'PENDING' | 'RUNNING' | 'CANCELLING' | 'ANSWERED' | 'FAILED' | 'CANCELLED' | string;
  progressPhase?: string;
  progressMessage?: string;
  answer?: string;
  resultJson?: string;
  traceId?: string;
  sourceCount?: number;
  snapshotSequenceNo?: number;
  errorMessage?: string;
  cancelRequested?: boolean;
  retryCount?: number;
  maxRetries?: number;
  queuedAt?: string;
  startedAt?: string;
  finishedAt?: string;
  updatedAt?: string;
}

export interface KnowledgeChatRunEvent {
  eventId?: number;
  runId: string;
  sequenceNo: number;
  sequence?: number;
  eventType: string;
  eventIdempotencyKey?: string;
  idempotencyKey?: string;
  payload?: string;
  createdAt?: string;
}

export interface KnowledgeChatRunSnapshot {
  runId: string;
  answer: string;
  snapshotSequenceNo: number;
}

export interface KnowledgeChatRunEventStreamCallbacks {
  onEvent(event: KnowledgeChatRunEvent): void;
  onSnapshot(snapshot: KnowledgeChatRunSnapshot): void;
}

export interface KnowledgeChatRunEventStreamTask {
  abort(): void;
  result: Promise<void>;
}

export interface ProjectIngestSubmitRequest {
  chapterNo: number;
  title?: string;
  content: string;
  sourceType?: string;
  idempotencyKey?: string;
  parserVersion?: string;
}

export interface ProjectIngestJob {
  ingestJobId: number;
  userId?: number;
  projectId: number;
  workId: number;
  chapterId?: number;
  generationId?: number;
  chapterNo?: number;
  idempotencyKey?: string;
  contentHash?: string;
  parserVersion?: string;
  status: string;
  statusLabel?: string;
  stage?: string;
  progress?: number;
  attempt?: number;
  maxAttempts?: number;
  fencingToken?: number;
  errorCode?: string;
  errorSummary?: string;
  title?: string;
  sourceType?: string;
  createdAt?: string;
  updatedAt?: string;
}

export type ProjectDocumentKind =
  | 'AUTO'
  | 'NOVEL_TEXT'
  | 'OUTLINE'
  | 'CHAPTER_OUTLINE'
  | 'CHARACTER_PROFILE'
  | 'WORLD_SETTING'
  | 'TIMELINE'
  | 'FORESHADOWING_NOTE'
  | 'REFERENCE'
  | 'READER_FEEDBACK';

export interface ProjectDocumentBatch {
  batchId: number;
  userId?: number;
  projectId: number;
  workId: number;
  status: string;
  statusLabel?: string;
  stage?: string;
  progress?: number;
  totalFiles?: number;
  storedFiles?: number;
  parsedFiles?: number;
  indexedFiles?: number;
  skippedFiles?: number;
  failedFiles?: number;
  pendingQuestions?: number;
  totalBytes?: number;
  attempt?: number;
  maxAttempts?: number;
  errorCode?: string;
  errorSummary?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface ProjectDocumentQuestion {
  questionId: number;
  batchId: number;
  fileId?: number;
  documentId?: number;
  relativePath?: string;
  questionType: string;
  prompt: string;
  optionsJson?: string;
  answerJson?: string;
  status: string;
  createdAt?: string;
  resolvedAt?: string;
}

export interface ProjectExtractionCandidate {
  candidateId: number;
  userId?: number;
  projectId: number;
  workId?: number;
  chapterId?: number;
  generationId?: number;
  entityType?: string;
  payloadJson?: string;
  evidenceRefsJson?: string;
  confidence?: number;
  status: string;
  reviewNote?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface ProjectExtractionReviewRequest {
  decision: 'CONFIRMED' | 'REJECTED' | 'SUPERSEDED' | string;
  payloadJson?: string;
  reviewNote?: string;
}

export interface StoryGraphNode {
  nodeId?: number;
  id?: number | string;
  nodeType?: string;
  category?: string;
  name?: string;
  displayName?: string;
  sourceChapterId?: number;
  confidence?: number;
  status?: string;
  generationId?: number;
  value?: number;
  [key: string]: unknown;
}

export interface StoryGraphEdge {
  edgeId?: number;
  id?: number | string;
  fromNodeId?: number;
  toNodeId?: number;
  source?: number | string;
  target?: number | string;
  relationType?: string;
  name?: string;
  confidence?: number;
  evidenceChapterId?: number;
  generationId?: number;
  [key: string]: unknown;
}

export interface StoryGraphResult {
  nodes: StoryGraphNode[];
  edges: StoryGraphEdge[];
  paths?: Array<Record<string, unknown>>;
  gaps?: string[];
  partial?: boolean;
}

export type ProjectMemorySummaryCoverageStatus = 'NO_CORPUS' | 'NOT_BUILT' | 'PARTIAL' | 'COMPLETE' | string;

export interface ProjectMemoryOverview {
  projectId: number;
  workId: number;
  activeChapterCount: number;
  chapterFrom?: number;
  chapterTo?: number;
  indexedDocumentCount: number;
  characterStateCount: number;
  worldRuleCount: number;
  foreshadowingCount: number;
  foreshadowingStatusCounts: Record<string, number>;
  timelineEventCount: number;
  storyNodeCount: number;
  storyEdgeCount: number;
  pendingExtractionCount: number;
  longFormFactCount: number;
  pendingLongFormFactCount: number;
  longFormFactStatusCounts: Record<string, number>;
  summaryNodeCount: number;
  summaryCoveredChapterCount: number;
  summaryCoverageStatus: ProjectMemorySummaryCoverageStatus;
  summaryNodeTypeCounts: Record<string, number>;
  recognizedRecordsOnly: boolean;
  corpusFingerprint?: string;
}
