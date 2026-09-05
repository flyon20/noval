const STATUS_LABELS: Record<string, string> = {
  answered: '已回答',
  answered_with_evidence: '已基于证据回答',
  answered_with_inference: '已结合推演回答',
  answerable: '可回答',
  approved: '已通过',
  blocked: '已阻止',
  cancelled: '已取消',
  canceled: '已取消',
  cancelling: '取消中',
  candidate: '待审核',
  candidates_required: '需要选择候选项',
  completed: '已完成',
  confirmed: '已确认',
  degraded: '已降级',
  deleted: '已删除',
  disabled: '已停用',
  draft: '草稿',
  empty: '无数据',
  failed: '失败',
  fallback_used: '已使用降级路径',
  insufficient_evidence: '证据不足',
  loaded: '已加载',
  needs_clarification: '需要补充信息',
  none: '无异常',
  not_called: '未调用',
  open: '未回收',
  out_of_scope: '超出网文领域',
  paid_off: '已回收',
  passed: '通过',
  pending: '待处理',
  placeholder: '等待项目资料',
  provided: '已提供',
  published: '已发布',
  queued: '排队中',
  rejected: '已拒绝',
  rolled_back: '已回滚',
  running: '运行中',
  skipped: '已跳过',
  succeeded: '成功',
  timed_out: '已超时',
  unavailable: '不可用',
  unknown: '未知',
  abandoned: '已废弃',
};

const DOMAIN_LABELS: Record<string, string> = {
  author: '作者策略',
  book: '单书研究',
  book_breakdown: '单书拆解',
  chapter_outline: '章节细纲',
  character_design: '人物设计',
  continuity_check: '连贯性审查',
  creative_inference: '创作推演',
  editor: '编辑审查',
  editor_risk: '编辑风险',
  evidence_only: '仅使用证据',
  followup_context: '上下文追问',
  inspiration_expand: '灵感扩展',
  market: '市场分析',
  market_evidence_plus_author_inference: '市场证据与作者推演',
  market_scan: '榜单市场分析',
  mixed_creation: '综合创作',
  mixed_creation_research: '市场研究与创作',
  opening_strategy: '开篇策略',
  outline_building: '大纲设计',
  foreshadowing_audit: '伏笔审查',
  project_knowledge: '作品知识证据',
  project_knowledge_qa: '作品知识问答',
  reader: '读者视角',
  reader_risk: '读者风险',
  revision_advice: '文本修订',
  topic_strategy: '选题策略',
  worldbuilding: '世界观设计',
};

const INTENT_LABELS: Record<string, string> = {
  book_breakdown: '拆书分析',
  chapter_outline: '细纲',
  character_design: '人设',
  continuity_check: '连贯性审查',
  creative_advice: '创作建议',
  followup_context: '追问',
  foreshadowing_audit: '伏笔审查',
  inspiration_expand: '灵感发散',
  market_scan: '扫榜研判',
  mixed_creation_research: '复合任务',
  opening_strategy: '开书策略',
  out_of_scope: '超出范围',
  outline_building: '大纲',
  project_knowledge_qa: '作品知识问答',
  rank_lookup: '榜单事实',
  revision_advice: '改稿建议',
  single_book_research: '单书研究',
  trend_research: '趋势研究',
  worldbuilding: '世界观',
};

const DEGRADATION_REASON_LABELS: Record<string, string> = {
  answer_quality_gate_failed: '回答质量校验未通过，已使用保底结果',
  evidence_commit_rejected: '证据入库被拒绝，本轮结论未落库',
  insufficient_evidence: '可用证据不足',
  provider_exception: '模型服务暂时异常',
  rank_snapshot_metadata_incomplete_after_refresh: '榜单快照信息不完整',
  run_token_budget_exceeded: '本轮生成预算已达到上限',
  tool_budget_exceeded: '本轮工具调用预算已达到上限',
};

const MEMORY_LAYER_LABELS: Record<string, string> = {
  conversation: '会话记忆',
  conversationsummary: '会话摘要',
  memory: '记忆',
  project: '项目记忆',
  projectmemories: '项目记忆',
  projectprofile: '项目资料',
  thread: '会话记忆',
  threadsummary: '会话摘要',
  user: '用户偏好',
  usermemories: '用户记忆',
  userprofile: '用户偏好',
};

const RUNTIME_NODE_LABELS: Record<string, string> = {
  assemble_context: '组装上下文',
  classify_intent: '识别意图',
  plan_tasks: '规划任务',
  validate_preconditions: '校验前置条件',
  route_experts: '路由专家',
  execute_tools: '调用工具',
  supervise_evidence: '审核证据',
  compose_answer: '生成回答',
  extract_memory_candidates: '提取记忆候选',
  finalize_trace: '完成运行记录',
  intent_router: '意图路由',
  task_planner: '任务规划',
  answer_writer: '回答生成',
};

const MEMORY_SCOPE_LABELS: Record<string, string> = {
  project: '项目',
  user: '用户',
  thread: '会话',
};

const MEMORY_TYPE_LABELS: Record<string, string> = {
  constraint: '创作约束',
  decision: '创作决策',
  fact: '事实设定',
  preference: '用户偏好',
  profile: '用户画像',
  summary: '内容摘要',
};

const CAPABILITY_LABELS: Record<string, string> = {
  skill: '技能能力',
  deterministic: '确定性能力',
  delegated: '委派专家',
};

const EXPERT_LABELS: Record<string, string> = {
  market_scan: '市场扫描专家',
  author_strategy: '作者策略专家',
  opening_strategy: '开篇策略专家',
  book_breakdown: '单书拆解专家',
  outline: '大纲专家',
  chapter_outline: '章节细纲专家',
  inspiration: '灵感专家',
  character: '人物专家',
  worldbuilding: '世界观专家',
  revision: '文本修订专家',
  reader_risk: '读者风险专家',
  editor: '编辑专家',
  supervisor: '监督专家',
};

function normalized(value: unknown) {
  return String(value ?? '').trim().toLowerCase();
}

function mappedLabel(labels: Record<string, string>, value: unknown, fallback = '-') {
  const raw = String(value ?? '').trim();
  if (!raw) return fallback;
  return labels[normalized(raw)] ?? raw;
}

export function knowledgeStatusLabel(value: unknown, fallback = '-') {
  return mappedLabel(STATUS_LABELS, value, fallback);
}

export function knowledgeUserStatusLabel(value: unknown, fallback = '状态已更新') {
  const raw = String(value ?? '').trim();
  if (!raw) return fallback;
  if (/[^\u0000-\u007f]/.test(raw)) return raw;
  return STATUS_LABELS[normalized(raw)] ?? fallback;
}

export function knowledgeDomainLabel(value: unknown, fallback = '-') {
  return mappedLabel(DOMAIN_LABELS, value, fallback);
}

export function knowledgeIntentLabel(value: unknown, fallback = '网文任务') {
  const raw = String(value ?? '').trim();
  if (!raw) return '';
  if (/[^\u0000-\u007f]/.test(raw)) return raw;
  return INTENT_LABELS[normalized(raw)] ?? fallback;
}

export function degradationReasonLabel(value: unknown, fallback = '系统能力暂时降级') {
  const raw = String(value ?? '').trim();
  if (!raw) return '';
  if (/[^\u0000-\u007f]/.test(raw)) return raw;
  return DEGRADATION_REASON_LABELS[normalized(raw)] ?? fallback;
}

export function memoryLayerLabel(value: unknown, fallback = '其他记忆') {
  const raw = String(value ?? '').trim();
  if (!raw) return fallback;
  if (/[^\u0000-\u007f]/.test(raw)) return raw;
  return MEMORY_LAYER_LABELS[normalized(raw)] ?? fallback;
}

export function runtimeNodeLabel(value: unknown, fallback = '-') {
  return mappedLabel(RUNTIME_NODE_LABELS, value, fallback);
}

export function memoryScopeLabel(value: unknown, fallback = '-') {
  return mappedLabel(MEMORY_SCOPE_LABELS, value, fallback);
}

export function memoryTypeLabel(value: unknown, fallback = '-') {
  return mappedLabel(MEMORY_TYPE_LABELS, value, fallback);
}

export function capabilityLabel(value: unknown, fallback = '-') {
  return mappedLabel(CAPABILITY_LABELS, value, fallback);
}

export function expertLabel(expertName: unknown, displayName?: unknown) {
  const name = normalized(expertName);
  return EXPERT_LABELS[name] ?? String(displayName ?? expertName ?? '-');
}
