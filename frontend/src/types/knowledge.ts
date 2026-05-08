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
  title?: string;
  preview?: string;
}

export interface KnowledgeChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

export interface KnowledgeChatRequest {
  question: string;
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
  resultJson: Record<string, unknown>;
}
