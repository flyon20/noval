export const KNOWLEDGE_PROJECT_CHANGE_EVENT = 'noval:knowledge-project-change';
export const KNOWLEDGE_CONVERSATION_SELECT_EVENT = 'noval:knowledge-conversation-select';
export const KNOWLEDGE_CONVERSATIONS_CHANGED_EVENT = 'noval:knowledge-conversations-changed';
export const KNOWLEDGE_ACTIVE_PROJECT_STORAGE_KEY = 'noval:knowledge-chat:active-project:v1';
export const KNOWLEDGE_ACTIVE_WORK_STORAGE_PREFIX = 'noval:knowledge-chat:active-work:v1:';
export const KNOWLEDGE_REFERENCE_WORK_STORAGE_PREFIX = 'noval:knowledge-chat:reference-works:v1:';
const MAX_REFERENCE_WORKS = 8;

export interface KnowledgeProjectChangeDetail {
  projectId: number | null;
  projectName?: string;
  workId?: number | null;
  workTitle?: string;
  referenceWorkIds?: number[];
}

export interface KnowledgeConversationSelectDetail extends KnowledgeProjectChangeDetail {
  conversationId: string;
  runId?: string;
}

export type KnowledgeConversationsChangedDetail = Pick<KnowledgeProjectChangeDetail, 'projectId'>;

export function normalizeKnowledgeProjectId(value: unknown) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

export function normalizeKnowledgeWorkId(value: unknown) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

export function normalizeKnowledgeReferenceWorkIds(value: unknown) {
  if (!Array.isArray(value)) {
    return [];
  }
  return Array.from(new Set(value
    .map(normalizeKnowledgeWorkId)
    .filter((workId): workId is number => workId !== null)))
    .slice(0, MAX_REFERENCE_WORKS);
}

export function getStoredKnowledgeProjectId() {
  if (typeof window === 'undefined') {
    return null;
  }
  try {
    return normalizeKnowledgeProjectId(window.localStorage.getItem(KNOWLEDGE_ACTIVE_PROJECT_STORAGE_KEY));
  } catch {
    return null;
  }
}

export function setStoredKnowledgeProjectId(projectId: number | null) {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    if (projectId) {
      window.localStorage.setItem(KNOWLEDGE_ACTIVE_PROJECT_STORAGE_KEY, String(projectId));
    } else {
      window.localStorage.removeItem(KNOWLEDGE_ACTIVE_PROJECT_STORAGE_KEY);
    }
  } catch {
    // Server-side project and conversation state remains the source of truth.
  }
}

export function getStoredKnowledgeWorkId(projectId: number | null) {
  if (typeof window === 'undefined' || !projectId) {
    return null;
  }
  try {
    return normalizeKnowledgeWorkId(
      window.localStorage.getItem(`${KNOWLEDGE_ACTIVE_WORK_STORAGE_PREFIX}${projectId}`),
    );
  } catch {
    return null;
  }
}

export function setStoredKnowledgeWorkId(projectId: number | null, workId: number | null) {
  if (typeof window === 'undefined' || !projectId) {
    return;
  }
  const storageKey = `${KNOWLEDGE_ACTIVE_WORK_STORAGE_PREFIX}${projectId}`;
  try {
    if (workId) {
      window.localStorage.setItem(storageKey, String(workId));
    } else {
      window.localStorage.removeItem(storageKey);
    }
  } catch {
    return;
  }
}

export function getStoredKnowledgeReferenceWorkIds(projectId: number | null) {
  if (typeof window === 'undefined' || !projectId) {
    return [];
  }
  try {
    const raw = window.localStorage.getItem(`${KNOWLEDGE_REFERENCE_WORK_STORAGE_PREFIX}${projectId}`);
    return raw ? normalizeKnowledgeReferenceWorkIds(JSON.parse(raw)) : [];
  } catch {
    return [];
  }
}

export function setStoredKnowledgeReferenceWorkIds(projectId: number | null, workIds: unknown) {
  if (typeof window === 'undefined' || !projectId) {
    return;
  }
  const storageKey = `${KNOWLEDGE_REFERENCE_WORK_STORAGE_PREFIX}${projectId}`;
  const normalized = normalizeKnowledgeReferenceWorkIds(workIds);
  try {
    if (normalized.length) {
      window.localStorage.setItem(storageKey, JSON.stringify(normalized));
    } else {
      window.localStorage.removeItem(storageKey);
    }
  } catch {
    return;
  }
}

function persistKnowledgeScope(detail: KnowledgeProjectChangeDetail) {
  setStoredKnowledgeProjectId(detail.projectId);
  if (detail.workId !== undefined) {
    setStoredKnowledgeWorkId(detail.projectId, normalizeKnowledgeWorkId(detail.workId));
  }
  if (detail.referenceWorkIds !== undefined) {
    setStoredKnowledgeReferenceWorkIds(detail.projectId, detail.referenceWorkIds);
  }
}

export function emitKnowledgeProjectChange(detail: KnowledgeProjectChangeDetail) {
  persistKnowledgeScope(detail);
  if (typeof window === 'undefined') {
    return;
  }
  window.dispatchEvent(new CustomEvent<KnowledgeProjectChangeDetail>(KNOWLEDGE_PROJECT_CHANGE_EVENT, { detail }));
}

export function emitKnowledgeConversationSelect(detail: KnowledgeConversationSelectDetail) {
  persistKnowledgeScope(detail);
  if (typeof window === 'undefined') {
    return;
  }
  window.dispatchEvent(
    new CustomEvent<KnowledgeConversationSelectDetail>(KNOWLEDGE_CONVERSATION_SELECT_EVENT, { detail }),
  );
}

export function emitKnowledgeConversationsChanged(detail: KnowledgeConversationsChangedDetail) {
  if (typeof window === 'undefined') {
    return;
  }
  window.dispatchEvent(
    new CustomEvent<KnowledgeConversationsChangedDetail>(KNOWLEDGE_CONVERSATIONS_CHANGED_EVENT, { detail }),
  );
}

export function getKnowledgeProjectChangeDetail(event: Event): KnowledgeProjectChangeDetail {
  const detail = (event as CustomEvent<KnowledgeProjectChangeDetail>).detail ?? {};
  const hasWorkId = Object.prototype.hasOwnProperty.call(detail, 'workId');
  const hasReferenceWorkIds = Object.prototype.hasOwnProperty.call(detail, 'referenceWorkIds');
  return {
    projectId: normalizeKnowledgeProjectId(detail.projectId),
    projectName: typeof detail.projectName === 'string' ? detail.projectName : undefined,
    workId: hasWorkId ? normalizeKnowledgeWorkId(detail.workId) : undefined,
    workTitle: typeof detail.workTitle === 'string' ? detail.workTitle : undefined,
    referenceWorkIds: hasReferenceWorkIds
      ? normalizeKnowledgeReferenceWorkIds(detail.referenceWorkIds)
      : undefined,
  };
}

export function getKnowledgeConversationSelectDetail(event: Event): KnowledgeConversationSelectDetail {
  const detail = (event as CustomEvent<KnowledgeConversationSelectDetail>).detail ?? {};
  const hasWorkId = Object.prototype.hasOwnProperty.call(detail, 'workId');
  const hasReferenceWorkIds = Object.prototype.hasOwnProperty.call(detail, 'referenceWorkIds');
  return {
    projectId: normalizeKnowledgeProjectId(detail.projectId),
    projectName: typeof detail.projectName === 'string' ? detail.projectName : undefined,
    workId: hasWorkId ? normalizeKnowledgeWorkId(detail.workId) : undefined,
    workTitle: typeof detail.workTitle === 'string' ? detail.workTitle : undefined,
    referenceWorkIds: hasReferenceWorkIds
      ? normalizeKnowledgeReferenceWorkIds(detail.referenceWorkIds)
      : undefined,
    conversationId: typeof detail.conversationId === 'string' ? detail.conversationId : '',
    runId: typeof detail.runId === 'string' ? detail.runId : undefined,
  };
}

export function getKnowledgeConversationsChangedDetail(event: Event): KnowledgeConversationsChangedDetail {
  const detail = (event as CustomEvent<KnowledgeConversationsChangedDetail>).detail ?? {};
  return {
    projectId: normalizeKnowledgeProjectId(detail.projectId),
  };
}
