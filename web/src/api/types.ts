// 由 docs/api-contract.md 手写而来。接口变更时先改约定，再同步这里。

export type AppType = '日常开发' | '开发网站' | '开发skill' | '技术文档';
export const APP_TYPES: AppType[] = ['日常开发', '开发网站', '开发skill', '技术文档'];
/** stopped：生成时被主动停止，还没有任何版本（和 failed 分开显示） */
export type AppPhase = 'planning' | 'plan' | 'generating' | 'ready' | 'failed' | 'stopped';
export type StyleId = 'lavender' | 'mint' | 'sand' | 'rose' | 'ink' | 'cyber';
export type Mode = '快速' | '适中' | '深度';

export interface VersionRef {
  id: string;
  number: number;
  title: string;
  createdAt: string;
}

export type TaskType = 'plan' | 'generate' | 'modify' | 'visual_save' | 'import' | 'code_save' | 'clone';
export type TaskStatus =
  | 'QUEUED'
  | 'PREPARING'
  | 'RUNNING'
  | 'VERIFYING'
  | 'COMMITTING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLING'
  | 'CANCELLED';

export const TERMINAL_STATUSES: TaskStatus[] = ['SUCCEEDED', 'FAILED', 'CANCELLED'];
export const isTerminal = (s: TaskStatus | undefined | null) => !!s && TERMINAL_STATUSES.includes(s);

export interface TaskRef {
  id: string;
  type: TaskType;
  status: TaskStatus;
}

export interface Task extends TaskRef {
  appId: string;
  phaseLabel: string;
  progress: number;
  error: { code: string; message: string } | null;
  resultVersionId: string | null;
  resumable: boolean;
  /** 最新的待办清单（todo 事件） */
  todos?: TodoItem[] | null;
  retryable: boolean;
  createdAt: string;
  /** 开始执行的时间（排队期间为空/早于 createdAt 的情况都可能出现） */
  startedAt?: string | null;
  finishedAt: string | null;
}

export interface AppSummary {
  id: string;
  name: string;
  type: AppType;
  description: string;
  favorite: boolean;
  phase: AppPhase;
  source: 'create' | 'import';
  framework: string | null;
  createdAt: string;
  updatedAt: string;
  currentVersion: VersionRef | null;
  publishedVersionId: string | null;
  previewUrl: string | null;
  siteUrl: string | null;
  hasDraft: boolean;
  /** 草稿基于的版本（与 currentVersion 不同表示草稿已过期） */
  draftBaseVersionId?: string | null;
  visualEditable: boolean;
  /** 是否已启用云服务（数据表/登录/云函数） */
  cloudEnabled?: boolean;
  /** 项目形态：template=生成的 Web 项目；static/vite=导入项目；skill=技能包（只有文件，不能预览和发布） */
  projectKind?: 'template' | 'vue' | 'static' | 'vite' | 'script' | 'skill';
  activeTask: TaskRef | null;
  /** SkillHub 发布状态（仅技能包） */
  skillhub?: { enabled: boolean; lastPublishedAt: string | null; url: string | null } | null;
}

export interface Asset {
  id: string;
  name: string;
  mime: string;
  size: number;
  url: string;
  createdAt: string;
}

export interface AppDetail extends AppSummary {
  prompt: string;
  planDoc: string;
  style: StyleId;
  materials: boolean;
  mode: Mode;
  model: string;
  assets: Asset[];
  /** 应用上挂载的技能（持久保存） */
  skills?: { id: string; name: string }[];
}

export type TaskEventType =
  | 'status'
  | 'agent_text'
  | 'tool'
  | 'verify'
  | 'log'
  | 'finished'
  | 'todo'
  | 'thinking'
  | 'thinking_delta'
  | 'thinking_end'
  | 'steer'
  | 'confirm';

/** agent 请求用户确认的操作（目前只有启用云服务） */
export interface ConfirmRequest {
  id: string;
  /** cloud：启用云服务；continue：做了一段（步数检查点），问要不要继续 */
  kind: 'cloud' | 'continue' | string;
  state: 'pending' | 'accepted' | 'declined' | 'timeout';
  reason?: string;
  title?: string;
  items?: string[];
  acceptLabel?: string;
  declineLabel?: string;
}

export type TodoStatus = 'pending' | 'in_progress' | 'completed';
export interface TodoItem {
  content: string;
  status: TodoStatus;
}

/** tool 结束事件的详情（runner/src/steps.mjs stepDetail） */
export type StepDetail =
  | { kind: 'write'; path: string; created?: boolean; lines?: number; preview?: string; truncated?: boolean; error?: string }
  | { kind: 'edit'; path: string; added?: number; removed?: number; diff?: string; truncated?: boolean; error?: string }
  | { kind: 'read'; path: string; lines?: number; error?: string }
  | { kind: 'bash'; command: string; exitCode?: number | null; output?: string; truncated?: boolean; blocked?: boolean };

/** GET /api/apps/{id}/versions/{vid}/changes */
export interface FileChange {
  path: string;
  status: 'A' | 'M' | 'D';
  added: number;
  removed: number;
}

export interface StatusPayload {
  status: TaskStatus;
  phaseLabel: string;
  progress: number;
}
export interface AgentTextPayload {
  delta: string;
}
export interface ToolPayload {
  id: string;
  name: string;
  summary: string;
  state: 'start' | 'end';
  ok?: boolean;
  detail?: StepDetail | string;
  detailOmitted?: boolean;
}
export interface VerifyCheck {
  name: string;
  ok: boolean;
  detail?: string;
  /** true 表示这一项本次被跳过（如技能包不做构建），界面上不应显示为失败 */
  skipped?: boolean;
}
export interface VerifyPayload {
  checks: VerifyCheck[];
}
export interface LogPayload {
  line: string;
}
export interface FinishedPayload {
  status: TaskStatus;
  versionId?: string;
  error?: { code: string; message: string };
}

export interface TaskEvent {
  seq: number;
  type: TaskEventType;
  createdAt: string;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  payload: any;
}

export interface ElementContext {
  appId: string;
  baseVersionId: string;
  loc: string;
  tag: string;
  text: string;
  outerHTML?: string;
  styles?: Record<string, string>;
}

/** 当前任务结束后自动执行的补充要求。 */
export interface QueuedMessage {
  id: string;
  taskId: string;
  appId: string;
  content: string;
  status: string;
  createdAt: string;
  /** 轮到这条消息时先启用云服务（任务进行中点「共享云服务」排进来的） */
  enableCloud?: boolean;
}

export interface Message {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  elementContext: ElementContext | null;
  taskId: string | null;
  status: 'ok' | 'pending' | 'failed';
  createdAt: string;
  /** 本条消息使用的技能（开发skill 应用 id） */
  skillAppIds?: string[];
  /** 这条消息附带的素材（图片 / 文档） */
  assets?: { id: string; name: string; mime?: string }[];
  /** 生成中途补充的要求 */
  steer?: boolean;
  /** 这条消息对应的版本（助手消息；后端未提供时用 taskId 查任务的 resultVersionId） */
  versionId?: string | null;
}

export interface Version {
  id: string;
  appId: string;
  number: number;
  title: string;
  source: 'generate' | 'modify' | 'visual' | 'restore' | 'import' | 'code';
  createdAt: string;
  favorite: boolean;
  note: string | null;
  isCurrent: boolean;
  isPublished: boolean;
  restoredFromNumber: number | null;
  previewUrl: string | null;
}

export interface HistoryRow {
  app: { id: string; name: string; type: AppType };
  version: Version;
}

export interface Patch {
  loc: string;
  text?: string;
  styles?: Record<string, string>;
  href?: string;
  removed?: boolean;
  /** 仅编辑桥：只改同 loc 的第 index 个实例；这类补丁不进入服务端草稿 */
  index?: number;
  /** 仅编辑桥：该实例的原始文字，用于列表重排后找回 */
  match?: string;
  /** 仅本地：元素标签，用于生成对话指令 */
  tag?: string;
}

export interface Draft {
  baseVersionId: string;
  patches: Patch[];
  updatedAt: string;
}

export interface ImportManifest {
  uploadId: string;
  name: string;
  format: 'html' | 'zip';
  framework: string;
  deployable: boolean;
  unsupportedReason: string | null;
  fileCount: number;
  totalSize: number;
  files: { path: string; size: number }[];
  rootDir?: string;
  candidates?: ImportCandidate[];
  warnings?: ImportWarning[];
}

export interface ImportCandidate {
  dir: string;
  kind: 'static' | 'vite';
  framework: string;
  label: string;
  prebuilt: boolean;
}

export interface ImportWarning {
  level: 'risk' | 'info';
  code: string;
  title: string;
  detail: string;
}

export interface Meta {
  models: { id: string; label: string; desc?: string; cost?: number }[];
  modes: Mode[];
  styles: { id: StyleId; label: string; accent: string }[];
  limits: { uploadMaxBytes: number; uploadMaxFiles: number; assetMaxBytes: number };
  /** 登录方式。缺省视为 anonymous（免登录），旧服务端不返回该字段时也能正常使用 */
  auth?: AuthInfo;
  /** 织页内置技能（编码、问答时按需自动加载） */
  builtinSkills?: { name: string; title: string; description: string }[];
  /** 当前工作空间已安装的 SkillHub 技能 */
  hubSkills?: { id: string; skillId: string; name: string; title: string; description: string; version: string; source: 'skillhub' }[];
  /** 是否已配置公司 SkillHub */
  skillhub?: boolean;
  /** 模型与额度以五星AI岛为准：模型列表改用 /api/llm/models，并显示个人额度 */
  fivestar?: boolean;
}

export interface LlmModel {
  /** auto（五星默认）或「供应商ID:模型ID」 */
  id: string;
  label: string;
  desc?: string;
  provider?: string;
  multimodal?: boolean;
}

export interface LlmModels {
  source: 'local' | 'fivestar';
  models: LlmModel[];
  defaultId: string | null;
}

/** 五星AI岛个人套餐与额度（字段含义同 openclaw 的 TokenCostUsageVO） */
export interface LlmQuota {
  enabled: boolean;
  packageName?: string | null;
  /** ACTIVE / ENROLLING（默认套餐建档中）/ INACTIVE */
  status?: string | null;
  usedTokens?: number | null;
  limitTokens?: number | null;
  remainingTokens?: number | null;
  /** UTC ISO 时间 */
  resetAt?: string | null;
  usableModels?: string[];
}

export interface AuthUser {
  id: string;
  loginName: string;
  realName: string;
  email?: string | null;
}

export interface AuthInfo {
  mode: 'anonymous' | 'sso';
  user: AuthUser | null;
  admin?: boolean;
}

export interface Workspace {
  id: string;
  createdAt: string;
  recoveryUrl: string;
}

export interface FileEntry {
  path: string;
  size: number;
}

export interface ApiErrorBody {
  code: string;
  message: string;
}

// ---- 预览编辑桥 ----
export interface Selection {
  loc: string;
  tag: string;
  text: string;
  canEditText: boolean;
  canInlineEdit: boolean;
  dynamic: boolean;
  sameLocCount: number;
  instanceIndex?: number;
  instanceText?: string;
  href: string | null;
  styles: Record<string, string>;
  rect: { left: number; top: number; width: number; height: number };
  parentLoc: string | null;
  outerHTML: string;
}

export type BridgeInbound =
  | { channel: 'zhiye-editor'; type: 'ready'; editable: boolean }
  | { channel: 'zhiye-editor'; type: 'selected'; selection: Selection }
  | { channel: 'zhiye-editor'; type: 'selection-position'; selection: Selection }
  | { channel: 'zhiye-editor'; type: 'deselect' }
  | { channel: 'zhiye-editor'; type: 'inline-edit'; loc: string; text: string; index?: number; match?: string };

export type BridgeOutbound =
  | { type: 'toggle'; enabled: boolean; loc?: string }
  | { type: 'apply-patches'; patches: Patch[] }
  | { type: 'select-parent' }
  | { type: 'deselect' };

/** 技能包应用：只有文件，没有预览与发布 */
export const isSkillApp = (a: { projectKind?: string; type: string }) => a.projectKind === 'skill' || (a.projectKind === undefined && a.type === '开发skill');
/** 纯脚本项目：没有页面预览，和技能包一样只交付代码 */
export const isScriptApp = (a: { projectKind?: string }) => a.projectKind === 'script';
export const hasNoPreview = (a: { projectKind?: string; type: string }) => isSkillApp(a) || isScriptApp(a);

// ---- 应用访问统计 ----
export interface AppsSummary {
  total: number;
  published: number;
  totalPv: number;
  /** 每人最多拥有的应用数（不含已删除） */
  appLimit?: number;
  appsUsed?: number;
  /** 同时进行的任务数上限 */
  taskLimit?: number;
}
export type StatsRange = 'all' | 'today' | '7d' | '3d';
export interface AppStats {
  todayPv: number;
  totalPv: number;
  rangePv: number;
  daily: { date: string; pv: number }[];
}

// ---- SkillHub ----
export type SkillVisibility = 'private' | 'team' | 'public';
export interface SkillhubPublication {
  id?: string;
  versionId: string;
  visibility: SkillVisibility;
  changelog?: string;
  url: string;
  publishedAt?: string;
}
export interface SkillHubCategory {
  id: string;
  name: string;
  description?: string;
  count?: number;
}
export interface SkillHubMarketItem {
  skillId: string;
  slug: string;
  name: string;
  summary?: string;
  owner?: string;
  version?: string;
  updatedAt?: string;
}
export interface SkillHubMarket {
  items: SkillHubMarketItem[];
  total: number;
  pageNo: number;
  limit: number;
}
export interface SkillHubInstall {
  id: string;
  skillId: string;
  name: string;
  description?: string;
  version: string;
  installed: boolean;
}

/** GET /api/tasks/active：当前用户进行中的任务（每人同时 1 个） */
export interface ActiveTaskInfo {
  task: Task;
  appId: string;
  appName: string;
}

export interface ConversationSummary {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  lastMessage?: string | null;
}

export interface ConversationMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt: string;
}

export interface ConversationDetail extends ConversationSummary {
  messages: ConversationMessage[];
}

/** 数据源参数定义 */
export interface DataSourceParam {
  name: string;
  type: 'string' | 'number' | 'integer' | 'boolean' | 'date';
  required: boolean;
  description: string;
  example: string;
  position?: 'query' | 'path' | 'header' | 'body' | null;
}

/** 登记的数据源（凭证永远不会返回，只有 hasSecret） */
export interface DataSourceInfo {
  id: string;
  key: string;
  name: string;
  description: string;
  method: 'GET' | 'POST';
  url: string;
  params: DataSourceParam[];
  sample: string | null;
  authType: 'none' | 'bearer' | 'header' | 'basic' | 'agw';
  authHeader: string | null;
  hasSecret: boolean;
  /** 平台登记（全员可用，只读） */
  platform: boolean;
  editable: boolean;
  provider: 'manual' | 'agw' | string;
  externalId: string | null;
  createdAt: string;
}

export interface AgwApiItem {
  id: string;
  name: string;
  code: string;
  description: string;
  method: string;
  path: string;
  appName: string | null;
  appCode: string | null;
  dataSourceName: string | null;
  paramCount: number;
  supported: boolean;
  unsupportedReason: string | null;
  importedSourceId: string | null;
}
export interface AgwApiPage {
  items: AgwApiItem[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface DataSourceInput {
  key: string;
  name: string;
  description: string;
  method: 'GET' | 'POST';
  url: string;
  params: Partial<DataSourceParam>[];
  sample: string;
  authType: DataSourceInfo['authType'];
  authHeader?: string;
  /** 只在填写/更换时提交 */
  secret?: string;
}

export interface DataSourceCall {
  appId: string | null;
  origin: 'preview' | 'site' | 'test';
  status: number | null;
  ok: boolean;
  durationMs: number;
  error: string | null;
  at: string;
}

export interface TemplateItem {
  id: string;
  name: string;
  description: string;
  type: AppType;
  projectKind: string | null;
  framework: string | null;
  sourceVersion: number | null;
  fileCount: number;
  mine: boolean;
  author: string | null;
  previewUrl: string | null;
  cloneCount: number;
  viewCount: number;
  likeCount: number;
  liked: boolean;
  createdAt: string;
}

export interface TemplateDetail extends TemplateItem {
  siteUrl: string | null;
  related: TemplateItem[];
}

export type TemplateTab = 'all' | 'liked' | 'mine';
export type TemplateSort = 'new' | 'likes' | 'uses';
export interface TemplateQuery {
  tab?: TemplateTab;
  sort?: TemplateSort;
  type?: string;
  q?: string;
}

export type DashboardRange = 'today' | '7d' | '30d' | '90d' | 'all' | 'custom';
export type DashboardScope = 'platform' | 'workspace';

export interface DashboardActor {
  kind: 'user' | 'workspace';
  value: string;
  userId: string | null;
  workspaceId: string | null;
  loginName: string;
  realName: string;
  apps: number;
  tasks: number;
  label: string;
}

export interface DashboardCountItem {
  key: string;
  count: number;
  sample?: string;
}

export interface DashboardDailyRow {
  date: string;
  pv: number;
  appsCreated: number;
  activeWorkspaces: number;
  tasksTotal: number;
  tasksSucceeded: number;
  tasksFailed: number;
  versions: number;
  deployments: number;
  tokens: number;
  modelCalls: number;
  cost: number;
  messages: number;
  fnCalls: number;
  sourceCalls: number;
}

export interface DashboardTaskItem {
  id: string;
  workspaceId: string;
  appId: string;
  appName: string;
  type: string;
  status: string;
  phaseLabel: string;
  progress: number;
  errorCode: string | null;
  errorMessage: string | null;
  tokenUsed: number;
  durationMs: number;
  createdAt: string;
}

export interface DashboardData {
  meta: {
    scope: DashboardScope;
    range: DashboardRange;
    startDate: string;
    endDate: string;
    days: number;
    filterWorkspaceId: string | null;
    filterUserId: string | null;
    filterLabel: string;
    generatedAt: string;
    workspaceId: string;
    fivestarEnabled: boolean;
    skillhubEnabled: boolean;
  };
  actors: DashboardActor[];
  overview: {
    workspacesTotal: number;
    workspacesProductive: number;
    workspacesActiveRange: number;
    ssoUsersTotal: number;
    appsTotal: number;
    appsRange: number;
    appsPrevRange: number;
    appsLimit: number;
    appsUsedInMyWs: number;
    appsPublished: number;
    appsFavorite: number;
    appsDeleted: number;
    appsReady: number;
    appsFailed: number;
    appsStopped: number;
    appsInProgress: number;
    pvTotal: number;
    pvRange: number;
    pvPrevRange: number;
    pvToday: number;
    pvYesterday: number;
    pvAvgDaily: number;
    appsWithPv: number;
    tasksTotal: number;
    tasksRange: number;
    tasksPrevRange: number;
    tasksSucceeded: number;
    tasksFailed: number;
    tasksCancelled: number;
    tasksActive: number;
    taskSuccessRate: number;
    taskAvgDurationSec: number;
    taskP50DurationSec: number;
    taskP90DurationSec: number;
    taskP95DurationSec: number;
    versionsTotal: number;
    versionsRange: number;
    deploymentsTotal: number;
    buildsTotal: number;
    modelCallsTotal: number;
    modelCallsRange: number;
    tokensInput: number;
    tokensOutput: number;
    tokensTotal: number;
    tokensRange: number;
    tokensPrevRange: number;
    costTotal: number;
    conversationsTotal: number;
    conversationsConverted: number;
    workbenchMessagesTotal: number;
    helpMessagesTotal: number;
    helpFeedbackTotal: number;
    cloudEnabledApps: number;
    cloudTablesTotal: number;
    fnCallsTotal: number;
    dataSourcesTotal: number;
    sourceCallsTotal: number;
    assetsTotal: number;
    assetsSizeBytes: number;
    templatesPublished: number;
    skillhubInstalled: number;
  };
  funnel: { key: string; label: string; count: number }[];
  trends: {
    daily: DashboardDailyRow[];
  };
  hourly: { hour: number; total: number; succeeded: number; failed: number }[];
  workspaces: {
    workspaceId: string;
    userId: string | null;
    ownerName: string;
    loginName: string;
    apps: number;
    published: number;
    tasks: number;
    rangeTasks: number;
    successRate: number;
    tokens: number;
    cost: number;
    pv: number;
    todayPv: number;
    lastActiveAt: string | null;
  }[];
  apps: {
    byType: { key: string; count: number; published: number; pv: number }[];
    byProjectKind: DashboardCountItem[];
    byPhase: DashboardCountItem[];
    bySource: DashboardCountItem[];
    byMode: DashboardCountItem[];
    byStyle: DashboardCountItem[];
    topApps: {
      id: string;
      workspaceId: string;
      name: string;
      type: string;
      projectKind: string;
      phase: string;
      style: string;
      published: boolean;
      cloudEnabled: boolean;
      accessLevel: string;
      versions: number;
      tasks: number;
      tokensUsed: number;
      totalPv: number;
      todayPv: number;
      updatedAt: string;
    }[];
  };
  tasks: {
    byType: {
      type: string;
      total: number;
      succeeded: number;
      failed: number;
      cancelled: number;
      active: number;
      successRate: number;
      avgDurationMs: number;
      maxDurationMs: number;
      avgTokens: number;
    }[];
    byStatus: DashboardCountItem[];
    errorCodes: DashboardCountItem[];
    retries: number;
    steerCount: number;
    checkpointsCount: number;
    eventsCount: number;
    queue: { pending: number; sent: number; cancelled: number; enableCloudCount: number };
    activeList: DashboardTaskItem[];
    recentFailures: DashboardTaskItem[];
    recentTasks: DashboardTaskItem[];
  };
  agentTelemetry: {
    tools: { tool: string; calls: number; failed: number; passRate: number }[];
    verifyChecks: { name: string; total: number; passed: number; failed: number; passRate: number }[];
    confirms: { kind: string; state: string; count: number }[];
    eventBreakdown: { thinking: number; todo: number; steer: number; verify: number };
  };
  llm: {
    totalCalls: number;
    inputTokens: number;
    outputTokens: number;
    totalTokens: number;
    totalCost: number;
    byModel: {
      provider: string;
      model: string;
      calls: number;
      inputTokens: number;
      outputTokens: number;
      totalTokens: number;
      avgTokensPerCall: number;
      cost: number;
    }[];
    byTaskType: { taskType: string; calls: number; inputTokens: number; outputTokens: number; totalTokens: number; cost: number }[];
    byAppModelSetting: DashboardCountItem[];
    quota: {
      enabled: boolean;
      available: boolean;
      packageName?: string | null;
      status?: string | null;
      usedTokens?: number | null;
      limitTokens?: number | null;
      remainingTokens?: number | null;
      resetAt?: string | null;
    };
  };
  versions: {
    bySource: DashboardCountItem[];
    byTaskStage: DashboardCountItem[];
    favoritedCount: number;
    notedCount: number;
    restoredCount: number;
    buildsTotal: number;
    buildsEditable: number;
    deploymentsTotal: number;
    deploymentsSucceeded: number;
    deploymentsFailed: number;
    pendingDrafts: number;
  };
  cloud: {
    enabledApps: number;
    disabledApps: number;
    tablesCount: number;
    tablePolicies: DashboardCountItem[];
    siteAccessLevels: DashboardCountItem[];
    activeSiteSessions: number;
    functions: {
      totalCalls: number;
      okCalls: number;
      errorCalls: number;
      successRate: number;
      avgDurationMs: number;
      p95DurationMs: number;
      byName: { name: string; calls: number; errors: number; avgDurationMs: number }[];
      recentErrors: { appId: string; appName: string; name: string; error: string | null; durationMs: number; at: string }[];
    };
  };
  dataSources: {
    workspaceCount: number;
    platformCount: number;
    boundAppsCount: number;
    totalBindings: number;
    byProvider: DashboardCountItem[];
    byMethod: DashboardCountItem[];
    byAuthType: DashboardCountItem[];
    calls: {
      totalCalls: number;
      okCalls: number;
      errorCalls: number;
      successRate: number;
      avgDurationMs: number;
      byOrigin: { origin: string; calls: number; errors: number }[];
      topSources: { id: string; key: string; name: string; provider: string; calls: number; errors: number; avgDurationMs: number }[];
    };
  };
  interactions: {
    conversations: {
      total: number;
      convertedToApp: number;
      conversionRate: number;
      userMessages: number;
      assistantMessages: number;
      thinkingMessages: number;
      filesTotal: number;
      fileVersionsTotal: number;
    };
    workbench: {
      totalMessages: number;
      userMessages: number;
      assistantMessages: number;
      systemMessages: number;
      elementContextMessages: number;
      skillAttachedMessages: number;
      assetAttachedMessages: number;
      steerMessages: number;
    };
    assets: {
      total: number;
      totalBytes: number;
      byCategory: { category: string; count: number; bytes: number }[];
      uploadsTotal: number;
      uploadsByFormat: DashboardCountItem[];
    };
    help: {
      conversations: number;
      userQuestions: number;
      assistantReplies: number;
      feedbackCount: number;
      recentFeedbacks: { id: string; workspaceId: string; content: string; createdAt: string }[];
    };
  };
  ecosystem: {
    skills: {
      selfBuiltCount: number;
      builtinCount: number;
      hubInstalledCount: number;
      hubInstalledBytes: number;
      hubInstalledFiles: number;
      publicationsCount: number;
      publicationsByReview: DashboardCountItem[];
      publicationsByVisibility: DashboardCountItem[];
    };
    myCommunity: {
      publishedWorks: number;
      totalViews: number;
      totalClones: number;
      totalLikes: number;
      likedByMe: number;
    };
    platformCommunity: {
      totalWorks: number;
      totalViews: number;
      totalClones: number;
      totalLikes: number;
      byAppType: { type: string; count: number; likes: number; clones: number }[];
      topWorks: { id: string; name: string; appType: string; author: string; views: number; clones: number; likes: number; createdAt: string }[];
    };
  };
  system: {
    runtime: {
      scope?: string;
      instances?: string[];
      tasks?: {
        total: number;
        succeeded: number;
        failed: number;
        cancelled: number;
        avgMs?: number;
        p95Ms?: number;
        maxMs?: number;
        byType?: Record<string, { count: number; errors: number; avgMs: number; p95Ms?: number; maxMs: number }>;
        failureCodes?: Record<string, number>;
      };
      sandbox?: { count: number; errors: number; avgMs: number; p95Ms?: number; maxMs: number };
      llm?: { count: number; errors: number; avgMs: number; p95Ms?: number; maxMs: number; retries: number; quotaErrors: number; quotaErrorRate: number };
      trend?: { hour: string; tasks: number; taskFailures: number; llmCalls: number; quotaErrors: number }[];
      alerts?: { level?: string; code: string; message: string; value: number; threshold: number }[];
    };
    rateLimits: {
      hitsLast10Min: number;
      byBucket: { bucket: string; count: number }[];
    };
    taskSignals: { pending: number; consumed: number };
    platformPulse: {
      workspaces: number;
      ssoUsers: number;
      totalApps: number;
      publishedApps: number;
      totalTasks: number;
      activeTasks: number;
      totalVersions: number;
      totalPv: number;
    };
    cluster?: {
      totalNodes?: number;
      onlineNodes?: number;
      workerSlotsTotal?: number;
      activeSandboxes?: number;
      queuedTasks?: number;
      expiredLeases?: number;
    };
  };
}

export interface ClusterNodeInfo {
  instanceId: string;
  isCurrent?: boolean;
  role: string;
  hostname: string;
  port: number;
  sandboxProvider: string;
  workerOwner: string;
  workerConcurrency: number;
  activeTasks: number;
  activeSandboxes: number;
  cpuPercent: number | null;
  heapUsedBytes: number;
  heapMaxBytes: number;
  heapPercent: number;
  sysMemUsedBytes: number;
  sysMemTotalBytes: number;
  status: 'online' | 'degraded' | 'offline' | string;
  heartbeatAgeSeconds: number;
  startedAt: string | null;
  heartbeatAt: string | null;
}

export interface ClusterQueueStats {
  queued: number;
  preparing: number;
  running: number;
  verifying: number;
  committing: number;
  cancelling: number;
  activeTotal: number;
  runningTotal: number;
  expiredLeases: number;
  oldestQueuedSeconds: number;
}

export interface SandboxItem {
  sandboxId: string;
  provider: string;
  kind?: 'session' | 'unregistered' | 'ledger-ghost' | 'destroyed' | 'failed' | string;
  state: string;
  taskId: string | null;
  taskStatus?: string | null;
  taskProgress?: number | null;
  taskPhase?: string | null;
  appId: string | null;
  appName?: string | null;
  workspaceId: string | null;
  userId?: string | null;
  ownerName?: string | null;
  ownerLoginName?: string | null;
  ownerInstance: string;
  ownerReplicaStatus?: string;
  onThisReplica?: boolean;
  taskRunning?: boolean;
  workerOwner?: string;
  externalRef: string;
  imageOrCmd: string;
  cpuLimit?: string | null;
  memoryLimit?: string | null;
  allowNet?: boolean;
  pid?: number | null;
  startupMs?: number | null;
  cpuPercent?: number | null;
  memoryUsedBytes?: number | null;
  memoryLimitBytes?: number | null;
  memoryPercent?: number | null;
  sampledAt?: string | null;
  createdAt: string | null;
  readyAt?: string | null;
  lastActiveAt?: string | null;
  destroyedAt?: string | null;
  destroyedBy?: string | null;
  destroyReason?: string | null;
  ageSeconds?: number;
  idleSeconds?: number;
  durationSeconds?: number;
  events?: OpsPlatformEvent[];
}

export interface SandboxInventorySummary {
  total: number;
  totalRows?: number;
  onThisReplica: number;
  runningTasks: number;
  byKind: Record<string, number>;
  byProvider: Record<string, number>;
  ghostCount: number;
  unregisteredCount: number;
  memoryUsedBytesTotal: number;
  memoryLimitBytesTotal: number;
  cpuPercentAvg: number | null;
  avgStartupMs: number;
  controlPlaneAvailable: boolean;
  controlPlaneMessage?: string;
}

export interface ClusterOverviewResponse {
  generatedAt: string;
  currentInstanceId: string;
  sandboxProvider: string;
  nodesSummary: {
    total: number;
    online: number;
    degraded: number;
    offline: number;
    workerSlotsTotal: number;
    workerSlotsUsed: number;
  };
  nodes: ClusterNodeInfo[];
  queue: ClusterQueueStats;
  sandboxes: {
    ok: boolean;
    replicaId: string;
    provider: string;
    summary: SandboxInventorySummary;
    items: SandboxItem[];
  };
  runtimeMetrics: DashboardData['system']['runtime'];
}

export interface OpsSandboxLogsResponse {
  range: OpsLogRange;
  summary: {
    total: number;
    active: number;
    failed: number;
    destroyed: number;
    avgStartupMs: number;
    p95StartupMs: number;
  };
  total: number;
  items: SandboxItem[];
  events: OpsPlatformEvent[];
  limit: number;
  offset: number;
}

// ---- 日志排查中心 ----
export type OpsLogRange = '1h' | '24h' | '7d' | '30d' | 'all';

export interface OpsLogActor {
  kind: 'user' | 'workspace';
  value: string;
  userId: string | null;
  workspaceId: string | null;
  loginName: string;
  realName: string;
  tasks: number;
  failed: number;
}

export interface OpsTaskSummary {
  id: string;
  workspaceId: string;
  isMine: boolean;
  appId: string;
  appName: string;
  appType: string;
  projectKind: string;
  model: string;
  mode: string;
  ownerUserId: string | null;
  ownerLoginName: string;
  ownerName: string;
  type: TaskType;
  status: TaskStatus;
  active: boolean;
  phaseLabel: string;
  progress: number;
  attempt: number;
  errorCode: string | null;
  errorMessage: string | null;
  leaseOwner: string | null;
  tokenUsed: number;
  durationMs: number;
  eventCount: number;
  logEventCount: number;
  toolErrorCount: number;
  hasLogFile: boolean;
  logFileBytes: number;
  promptPreview: string;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface OpsTaskDetail extends OpsTaskSummary {
  baseVersionId: string | null;
  resultVersionId: string | null;
  parentTaskId: string | null;
  idempotencyKey: string | null;
  leaseUntil: string | null;
  tokenBudget: number;
  input: Record<string, unknown>;
  result: Record<string, unknown> | null;
  todos: TodoItem[] | null;
  appDeleted: boolean;
}

export interface OpsTasksResponse {
  meta: {
    range: OpsLogRange;
    currentWorkspaceId: string;
    generatedAt: string;
  };
  summary: {
    total: number;
    active: number;
    failed: number;
    succeeded: number;
    cancelled: number;
  };
  errorCodes: { code: string; count: number }[];
  actors: OpsLogActor[];
  items: OpsTaskSummary[];
  total: number;
  limit: number;
  offset: number;
}

export interface OpsTaskEventRow {
  seq: number;
  type: string;
  payload: Record<string, unknown>;
  createdAt: string;
}

export interface OpsLogFileLine {
  no: number;
  level?: 'ERROR' | 'WARN' | 'INFO' | string;
  text: string;
}

export interface OpsLogFileView {
  fileName: string;
  exists: boolean;
  sizeBytes: number;
  updatedAt: string | null;
  totalLines: number;
  matchedLines: number;
  errorLines?: number;
  warnLines?: number;
  truncated: boolean;
  lines: OpsLogFileLine[];
}

export interface OpsModelUsageRow {
  id: number;
  provider: string;
  model: string | null;
  inputTokens: number;
  outputTokens: number;
  cost: number;
  createdAt: string;
}

export interface OpsTaskMessageRow {
  id: string;
  role: string;
  content: string;
  steer: boolean;
  elementContext: Record<string, unknown> | null;
  createdAt: string;
}

export interface OpsFunctionLogItem {
  id: number;
  appId: string;
  appName: string;
  workspaceId: string | null;
  ownerName: string;
  name: string;
  versionId: string | null;
  ok: boolean;
  durationMs: number;
  error: string | null;
  logs: string | null;
  caller: string | null;
  createdAt: string;
}

export interface OpsSourceCallItem {
  id: number;
  sourceId: string;
  sourceKey: string;
  sourceName: string;
  method: string;
  appId: string | null;
  appName: string;
  origin: 'preview' | 'site' | 'test' | string;
  status: number | null;
  ok: boolean;
  durationMs: number;
  error: string | null;
  createdAt: string;
}

export interface OpsPlatformEvent {
  id: number;
  category: 'cloud_schema' | 'llm_gateway' | 'api_error' | 'sandbox' | 'lifecycle' | string;
  level: 'INFO' | 'WARN' | 'ERROR' | string;
  action: string;
  workspaceId: string | null;
  userId: string | null;
  appId: string | null;
  appName: string | null;
  ownerName: string;
  taskId: string | null;
  ok: boolean;
  statusCode: number | null;
  durationMs: number | null;
  summary: string;
  detail: string | null;
  meta: Record<string, unknown>;
  createdAt: string;
}

export interface OpsPlatformEventsResponse {
  range: OpsLogRange;
  total: number;
  errorCount: number;
  byCategory: { category: string; count: number; errors: number }[];
  items: OpsPlatformEvent[];
  limit: number;
  offset: number;
}

export interface OpsCloudSchemaRow {
  appId: string;
  appName: string;
  workspaceId: string | null;
  ownerName: string;
  status: string;
  schemaName: string;
  tableCount: number;
  tableNames: string[];
  enabledAt: string | null;
  updatedAt: string | null;
}

export interface OpsSchemasResponse extends OpsPlatformEventsResponse {
  schemas: OpsCloudSchemaRow[];
}

export interface OpsTaskDetailResponse {
  task: OpsTaskDetail;
  eventCounts: Record<string, number>;
  events: OpsTaskEventRow[];
  modelUsages: OpsModelUsageRow[];
  messages: OpsTaskMessageRow[];
  checkpoint: {
    workspaceDir: string;
    agentDir: string | null;
    sessionFile: string | null;
    draftApplied: boolean;
  } | null;
  runnerLog: OpsLogFileView;
  sandboxes?: SandboxItem[];
  fnLogs: OpsFunctionLogItem[];
  sourceCalls: OpsSourceCallItem[];
  platformEvents?: OpsPlatformEvent[];
  cloudStatus?: {
    appId: string;
    status: string;
    schemaName: string;
    storagePrefix: string;
    enabledAt: string | null;
    updatedAt: string | null;
  } | null;
  cloudTables?: {
    name: string;
    columns: Record<string, string>;
    policy: Record<string, unknown> | null;
  }[];
}

export interface OpsTaskEventsDeltaResponse {
  task: {
    id: string;
    status: TaskStatus;
    phaseLabel: string;
    progress: number;
    errorCode: string | null;
    errorMessage: string | null;
    tokenUsed: number;
    startedAt: string | null;
    finishedAt: string | null;
    terminal: boolean;
  };
  events: OpsTaskEventRow[];
  lastSeq: number;
}

export interface OpsRuntimeResponse {
  target: 'api' | 'site' | 'web-build';
  sandboxProvider: string;
  runtimeDir: string;
  prometheusEndpoint: string;
  metrics: DashboardData['system']['runtime'] & {
    scope?: string;
    instances?: string[];
    alerts?: { code: string; level?: string; message: string; value: number; threshold: number }[];
  };
  clusterNodes?: ClusterNodeInfo[];
  file: OpsLogFileView;
  rateLimitHits?: { bucket: string; createdAt: string }[];
  auditLogs?: {
    id: string;
    actor: string;
    action: string;
    targetType: string;
    targetId: string;
    detail: Record<string, unknown>;
    createdAt: string;
  }[];
}

export type AdminFeedbackStatus = 'open' | 'in_progress' | 'resolved' | 'ignored';

export interface AdminCustomQuota {
  workspaceId: string;
  ownerLogin: string | null;
  ownerName: string;
  maxApps: number | null;
  dailyTaskLimit: number | null;
  maxConcurrentTasks: number | null;
  note: string | null;
  updatedBy: string | null;
  updatedAt: string;
  appCount: number;
  tasksToday: number;
  activeTasks: number;
}

export interface AdminFeedbackItem {
  id: string;
  workspaceId: string;
  ownerLogin: string | null;
  ownerName: string;
  conversationId: string | null;
  content: string;
  assetCount: number;
  status: AdminFeedbackStatus;
  adminNote: string | null;
  resolvedAt: string | null;
  createdAt: string;
}

export interface AdminDeletedAppItem {
  id: string;
  workspaceId: string;
  ownerLogin: string | null;
  ownerName: string;
  name: string;
  type: string;
  projectKind: string;
  phase: string;
  versionCount: number;
  deletedAt: string;
  updatedAt: string;
}

export interface AdminTemplateItem {
  id: string;
  workspaceId: string;
  name: string;
  description: string | null;
  type: string;
  projectKind: string;
  author: string | null;
  cloneCount: number;
  viewCount: number;
  likeCount: number;
  featured: boolean;
  pinnedOrder: number;
  createdAt: string;
}

export interface AdminRateLimitBucket {
  bucket: string;
  totalHits: number;
  hits24h: number;
  lastHitAt: string;
}

export interface AdminAuditLogItem {
  id: string;
  actor: string;
  action: string;
  targetType: string;
  targetId: string;
  detail: Record<string, unknown>;
  createdAt: string;
}

export interface AdminOverview {
  currentActor: string;
  ssoRestricted: boolean;
  defaultQuota: {
    maxApps: number;
    dailyTaskLimit: number;
    maxConcurrentTasks: number;
  };
  customQuotas: AdminCustomQuota[];
  feedbacks: AdminFeedbackItem[];
  feedbackOpenCount: number;
  deletedApps: AdminDeletedAppItem[];
  templates: AdminTemplateItem[];
  rateLimitStats: {
    totalHits: number;
    recentHits24h: number;
    buckets: AdminRateLimitBucket[];
  };
  auditLogs: AdminAuditLogItem[];
}
