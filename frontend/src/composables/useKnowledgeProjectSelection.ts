export const KNOWLEDGE_PROJECT_CHANGE_EVENT = 'noval:knowledge-project-change';
export const KNOWLEDGE_CONVERSATION_SELECT_EVENT = 'noval:knowledge-conversation-select';
export const KNOWLEDGE_ACTIVE_PROJECT_STORAGE_KEY = 'noval:knowledge-chat:active-project:v1';

export interface KnowledgeProjectChangeDetail {
  projectId: number | null;
  projectName?: string;
}

export interface KnowledgeConversationSelectDetail extends KnowledgeProjectChangeDetail {
  conversationId: string;
  runId?: string;
}

export function normalizeKnowledgeProjectId(value: unknown) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

export function getStoredKnowledgeProjectId() {
  if (typeof window === 'undefined') {
    return null;
  }
  return normalizeKnowledgeProjectId(window.localStorage.getItem(KNOWLEDGE_ACTIVE_PROJECT_STORAGE_KEY));
}

export function setStoredKnowledgeProjectId(projectId: number | null) {
  if (typeof window === 'undefined') {
    return;
  }
  if (projectId) {
    window.localStorage.setItem(KNOWLEDGE_ACTIVE_PROJECT_STORAGE_KEY, String(projectId));
  } else {
    window.localStorage.removeItem(KNOWLEDGE_ACTIVE_PROJECT_STORAGE_KEY);
  }
}

export function emitKnowledgeProjectChange(detail: KnowledgeProjectChangeDetail) {
  setStoredKnowledgeProjectId(detail.projectId);
  if (typeof window === 'undefined') {
    return;
  }
  window.dispatchEvent(new CustomEvent<KnowledgeProjectChangeDetail>(KNOWLEDGE_PROJECT_CHANGE_EVENT, { detail }));
}

export function emitKnowledgeConversationSelect(detail: KnowledgeConversationSelectDetail) {
  setStoredKnowledgeProjectId(detail.projectId);
  if (typeof window === 'undefined') {
    return;
  }
  window.dispatchEvent(
    new CustomEvent<KnowledgeConversationSelectDetail>(KNOWLEDGE_CONVERSATION_SELECT_EVENT, { detail }),
  );
}

export function getKnowledgeProjectChangeDetail(event: Event): KnowledgeProjectChangeDetail {
  const detail = (event as CustomEvent<KnowledgeProjectChangeDetail>).detail ?? {};
  return {
    projectId: normalizeKnowledgeProjectId(detail.projectId),
    projectName: typeof detail.projectName === 'string' ? detail.projectName : undefined,
  };
}

export function getKnowledgeConversationSelectDetail(event: Event): KnowledgeConversationSelectDetail {
  const detail = (event as CustomEvent<KnowledgeConversationSelectDetail>).detail ?? {};
  return {
    projectId: normalizeKnowledgeProjectId(detail.projectId),
    projectName: typeof detail.projectName === 'string' ? detail.projectName : undefined,
    conversationId: typeof detail.conversationId === 'string' ? detail.conversationId : '',
    runId: typeof detail.runId === 'string' ? detail.runId : undefined,
  };
}
