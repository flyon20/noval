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
  chapterNo?: number;
  analysisType?: string;
  rankNo?: number;
  author?: string;
  category?: string;
  title?: string;
  preview?: string;
}

export interface KnowledgeChatMessage {
  role: 'user' | 'assistant';
  content: string;
  status?: string;
  answerStatus?: string;
  intent?: string;
  answerBoundary?: string;
  sources?: KnowledgeSource[];
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
  sourcePolicy?: string;
  supervisorDecision?: string;
  memoryCandidates?: string;
  snapshotTime?: string;
  createdAt?: string;
}

export interface SkillCandidate {
  id: number;
  skillId: string;
  title: string;
  status: string;
  evalStatus: string;
  reviewNote?: string;
}

export interface KnowledgeChatRequest {
  question: string;
  conversationId?: string;
  projectId?: number;
  bookName?: string;
  bookId?: number;
  selectedCandidate?: KnowledgeBookCandidate;
  mode?: string;
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
