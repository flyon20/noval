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

export interface AgentTraceSummary {
  id: number;
  traceId: string;
  userId?: number;
  projectId?: number;
  conversationId?: string;
  question?: string;
  status?: string;
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
  selectedExperts?: string;
  expertRouter?: string;
  finalAnswerBoundary?: string;
  snapshotTime?: string;
  createdAt?: string;
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
  remainingTokens?: number;
  remainingRatio?: number;
  compressed?: boolean;
  components?: Record<string, number>;
  memoryLayers?: ContextBudgetMemoryLayer[];
  warnings?: string[];
  [key: string]: unknown;
}

export interface AgentRuntimeTrace {
  nodes?: AgentRuntimeTraceNode[];
  executedRuntimeNodes?: string[];
  [key: string]: unknown;
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

export type AgentTracePage = PageResult<AgentTraceSummary>;

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
  intents: string[];
  triggers: string[];
}

export interface SkillCandidate {
  id: number;
  skillId: string;
  title: string;
  status: string;
  evalStatus: string;
  evalResult?: SkillEvalResult;
  evalResultJson?: string;
  requiredToolPassRate?: number;
  evidencePassRate?: number;
  faithfulnessPassRate?: number;
  reviewNote?: string;
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
  maxPromptCharsPerExpert?: number;
  maxSkillPromptChars?: number;
  maxEvidenceItems?: number;
}

export interface AgentExpertProfile {
  expertName: string;
  displayName?: string;
  enabled?: boolean;
  defaultMode?: string;
  costClass?: string;
  maxTokens?: number;
  maxToolCalls?: number;
  allowedTools?: string[];
  triggerIntents?: string[];
  triggerTasks?: string[];
  priority?: number;
  promptVersion?: string;
  evalSuiteId?: string;
  guardrail?: boolean;
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

export interface KnowledgeChatRequest {
  question: string;
  conversationId?: string;
  projectId?: number;
  bookName?: string;
  bookId?: number;
  selectedCandidate?: KnowledgeBookCandidate;
  mode?: string;
  reasoningMode?: KnowledgeReasoningMode;
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
  status: 'PENDING' | 'RUNNING' | 'ANSWERED' | 'FAILED' | 'CANCELLED' | string;
  progressPhase?: string;
  progressMessage?: string;
  answer?: string;
  resultJson?: string;
  traceId?: string;
  sourceCount?: number;
  errorMessage?: string;
  cancelRequested?: boolean;
  retryCount?: number;
  maxRetries?: number;
  queuedAt?: string;
  startedAt?: string;
  finishedAt?: string;
  updatedAt?: string;
}
