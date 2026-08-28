export type Role = 'STUDENT' | 'TEACHER' | 'ADMIN';
export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD' | 'CHALLENGE';
export type EntityId = string;
export type UserNotificationType =
  | 'CONTEST_INVITATION'
  | 'STUDENT_POSTMORTEM_JOB_COMPLETED'
  | 'STUDENT_POSTMORTEM_JOB_FAILED';
export type SubmissionStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'ACCEPTED'
  | 'WRONG_ANSWER'
  | 'COMPILE_ERROR'
  | 'RUNTIME_ERROR'
  | 'TIME_LIMIT_EXCEEDED'
  | 'MEMORY_LIMIT_EXCEEDED'
  | 'OUTPUT_LIMIT_EXCEEDED'
  | 'SYSTEM_ERROR';

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  details?: Record<string, string> | null;
  traceId: string;
  timestamp: string;
  errorKey?: string | null;
  errorParams?: Record<string, string> | null;
}

export interface BinaryFileResponse {
  blob: Blob;
  fileName: string;
  contentType: string;
}

export type ApiErrorDetails = Record<string, string>;

export class ApiError extends Error {
  readonly code: number;
  readonly details: ApiErrorDetails | null;
  readonly traceId: string | null;
  readonly serverMessage: string;
  readonly errorKey: string | null;
  readonly errorParams: Record<string, string> | null;

  constructor(
    code: number,
    serverMessage: string,
    details: ApiErrorDetails | null = null,
    traceId: string | null = null,
    errorKey: string | null = null,
    errorParams: Record<string, string> | null = null
  ) {
    super(serverMessage);
    this.name = 'ApiError';
    this.code = code;
    this.details = details;
    this.traceId = traceId;
    this.serverMessage = serverMessage;
    this.errorKey = errorKey;
    this.errorParams = errorParams;
    Object.setPrototypeOf(this, ApiError.prototype);
  }

  get userMessage(): string {
    const resolved = messageResolver?.(this.code, this.serverMessage, {
      errorKey: this.errorKey,
      errorParams: this.errorParams,
      details: this.details,
      traceId: this.traceId
    });
    return resolved || defaultApiErrorMessage(this.code, this.traceId);
  }

  fieldError(path: string): string | undefined {
    return this.details?.[path];
  }
}

export type QueryRefetchInterval = number | false;

export interface QueryPollingProbe<TData> {
  queryHash?: string;
  state?: {
    data?: TData;
    error?: unknown;
  };
}

export interface ActiveQueryPollingOptions {
  fastMs?: number;
  slowMs?: number;
  slowAfterMs?: number;
  hiddenMs?: number;
}

const activePollingStartedAt = new Map<string, number>();

export function activeQueryRefetchInterval<TData>(
  query: QueryPollingProbe<TData>,
  isActive: (data: TData | undefined) => boolean,
  options: ActiveQueryPollingOptions = {}
): QueryRefetchInterval {
  const key = query.queryHash ?? "active-query-poll";
  if (shouldPauseQueryPolling(query.state?.error) || !isActive(query.state?.data)) {
    activePollingStartedAt.delete(key);
    return false;
  }

  const now = Date.now();
  const startedAt = activePollingStartedAt.get(key) ?? now;
  activePollingStartedAt.set(key, startedAt);

  if (!isDocumentVisible()) {
    return options.hiddenMs ?? 10000;
  }

  const elapsedMs = now - startedAt;
  return elapsedMs < (options.slowAfterMs ?? 20000)
    ? (options.fastMs ?? 1800)
    : (options.slowMs ?? 4000);
}

export function visibleQueryRefetchInterval(
  enabled: boolean,
  visibleMs: number,
  hiddenMs = 15000
): QueryRefetchInterval {
  if (!enabled) {
    return false;
  }
  return isDocumentVisible() ? visibleMs : hiddenMs;
}

export function steadyQueryRefetchInterval<TData>(
  query: QueryPollingProbe<TData>,
  enabled: boolean,
  visibleMs: number,
  hiddenMs = 15000
): QueryRefetchInterval {
  if (shouldPauseQueryPolling(query.state?.error)) {
    return false;
  }
  return visibleQueryRefetchInterval(enabled, visibleMs, hiddenMs);
}

export function operationJobPollingDelay(elapsedMs: number): number {
  const visibleDelay = elapsedMs < 20000 ? 1500 : elapsedMs < 60000 ? 3000 : 5000;
  return isDocumentVisible() ? visibleDelay : Math.max(visibleDelay, 10000);
}

export function shouldPauseQueryPolling(error: unknown): boolean {
  const code = numericErrorField(error, "code") ?? numericErrorField(error, "status");
  if (code == null) {
    return false;
  }
  return code === 401
    || code === 403
    || code === 429
    || code === 40100
    || code === 40300
    || code === 42900
    || (code >= 500 && code < 600)
    || (code >= 50000 && code < 60000);
}

function isDocumentVisible(): boolean {
  return typeof document === "undefined" || document.visibilityState === "visible";
}

function numericErrorField(error: unknown, field: "code" | "status"): number | undefined {
  if (!error || typeof error !== "object") {
    return undefined;
  }
  const value = (error as Record<string, unknown>)[field];
  return typeof value === "number" ? value : undefined;
}

export interface ApiErrorMessageContext {
  errorKey?: string | null;
  errorParams?: Record<string, string> | null;
  details?: ApiErrorDetails | null;
  traceId?: string | null;
}

let messageResolver: ((code: number, fallback: string, context?: ApiErrorMessageContext) => string | undefined) | null = null;

export function setApiErrorMessageResolver(resolver: (code: number, fallback: string, context?: ApiErrorMessageContext) => string | undefined): void {
  messageResolver = resolver;
}

function defaultApiErrorMessage(code: number, traceId?: string | null) {
  const suffix = traceId ? ` Trace ID: ${traceId}` : '';
  if (code === 40100 || Math.trunc(code / 100) === 401) return `Please sign in again.${suffix}`;
  if (code === 40300 || Math.trunc(code / 100) === 403) return `You do not have permission to perform this action.${suffix}`;
  if (code === 40400 || Math.trunc(code / 100) === 404) return `The requested resource does not exist.${suffix}`;
  if (code === 41300 || Math.trunc(code / 100) === 413) return `The uploaded content is too large.${suffix}`;
  if (code === 42900 || Math.trunc(code / 100) === 429) return `Too many requests. Please try again later.${suffix}`;
  if (code >= 50000 || Math.trunc(code / 100) >= 500) return `The service is temporarily unavailable. Please try again later.${suffix}`;
  return `The operation could not be completed. Please check your input and try again.${suffix}`;
}

export interface PageResponse<T> {
  records: T[];
  total: number;
  page: number;
  pageSize: number;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresAt: string;
  userId: EntityId;
  account: string;
  displayName: string;
  roles: Role[];
  passwordResetRequired: boolean;
}

function isRole(value: unknown): value is Role {
  return value === 'STUDENT' || value === 'TEACHER' || value === 'ADMIN';
}

function isTokenResponse(value: unknown): value is TokenResponse {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<TokenResponse>;
  return typeof candidate.accessToken === 'string' &&
    typeof candidate.refreshToken === 'string' &&
    typeof candidate.tokenType === 'string' &&
    typeof candidate.expiresAt === 'string' &&
    typeof candidate.userId === 'string' &&
    typeof candidate.account === 'string' &&
    typeof candidate.displayName === 'string' &&
    Array.isArray(candidate.roles) &&
    candidate.roles.every(isRole) &&
    typeof candidate.passwordResetRequired === 'boolean';
}

export interface UserProfileResponse {
  userId: EntityId;
  account: string;
  displayName: string;
  email?: string;
  roles: Role[];
  passwordResetRequired: boolean;
}

export interface AdminUserResponse extends UserProfileResponse {
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
  archivedAt?: string | null;
  deletedAt?: string | null;
  deletedBy?: EntityId | null;
}

export type AdminUserLifecycle = 'ACTIVE' | 'ARCHIVED';
export type AdminUserBatchAction = 'ENABLE' | 'DISABLE' | 'ARCHIVE' | 'RESTORE' | 'DELETE_ARCHIVED' | 'RESET_PASSWORD';

export interface AdminUserBatchCandidate {
  account?: string | null;
  displayName?: string | null;
  email?: string | null;
  roles?: Role[];
  enabled?: boolean;
  passwordResetRequired?: boolean;
}

export interface AdminUserBatchPreviewRequest {
  mode?: 'SEQUENCE' | 'TEXT' | 'CANDIDATES' | string;
  prefix?: string;
  start?: number;
  count?: number;
  width?: number;
  importText?: string;
  defaultRoles?: Role[];
  enabled?: boolean;
  passwordResetRequired?: boolean;
  candidates?: AdminUserBatchCandidate[];
}

export interface AdminUserBatchPreviewItem {
  rowNumber: number;
  account?: string | null;
  displayName?: string | null;
  email?: string | null;
  roles: Role[];
  enabled: boolean;
  passwordResetRequired: boolean;
  valid: boolean;
  duplicateInBatch: boolean;
  duplicateExisting: boolean;
  errors: string[];
  errorCodes?: string[] | null;
}

export interface AdminUserBatchPreviewResponse {
  total: number;
  valid: number;
  invalid: number;
  items: AdminUserBatchPreviewItem[];
}

export interface AdminUserBatchPassword {
  mode: 'FIXED' | 'ACCOUNT_SUFFIX';
  fixedPassword?: string;
  accountSuffix?: string;
}

export interface AdminUserBatchCreateResponse {
  requested: number;
  created: number;
  skipped: number;
  results: Array<{
    rowNumber: number;
    account?: string | null;
    status: string;
    message?: string | null;
    user?: AdminUserResponse | null;
  }>;
}

export interface AdminUserBatchActionResponse {
  requested: number;
  succeeded: number;
  failed: number;
  results: Array<{
    userId: EntityId;
    account?: string | null;
    status: string;
    message?: string | null;
    user?: AdminUserResponse | null;
  }>;
}

export interface DailyUserActivityResponse {
  date: string;
  activeUsers: number;
  newUsers: number;
}

export interface RoleResponse {
  role: Role;
  label: string;
}

export interface RoleCapabilityResponse {
  role: Role;
  capabilities: string[];
}

export type LearningGroupType = 'CLASS';
export type LearningGroupStatus = 'ACTIVE' | 'ARCHIVED';
export type LearningGroupMemberRole = 'OWNER' | 'TEACHER' | 'ASSISTANT' | 'STUDENT';

export interface LearningGroupResponse {
  id: EntityId;
  parentGroupId?: EntityId | null;
  type: LearningGroupType;
  name: string;
  description?: string | null;
  ownerUserId: EntityId;
  ownerDisplayName?: string | null;
  status: LearningGroupStatus;
  memberCount: number;
  studentCount: number;
  createdAt: string;
  updatedAt: string;
  archivedAt?: string | null;
  deletedAt?: string | null;
  deletedBy?: EntityId | null;
}

export interface LearningGroupMemberResponse {
  userId: EntityId;
  account: string;
  displayName: string;
  email?: string | null;
  enabled: boolean;
  role: LearningGroupMemberRole;
  joinedAt: string;
}

export interface LearningGroupPayload {
  name: string;
  description?: string;
  status?: LearningGroupStatus;
}

export interface LearningGroupMemberPayload {
  userId?: EntityId;
  account?: string;
  role?: LearningGroupMemberRole;
}

/** Durable notification metadata; resource detail is fetched through its own authorized API. */
export interface UserNotificationResponse {
  id: EntityId;
  type: UserNotificationType;
  subjectType: string;
  subjectId: string;
  scopeType: string | null;
  scopeId: string | null;
  readAt: string | null;
  createdAt: string;
}

export interface UserNotificationUnreadCountResponse {
  count: number;
}

export interface UserNotificationMarkReadResponse {
  markedCount: number;
}

/** SSE wake-up payload. It intentionally contains no business detail. */
export interface UserNotificationStreamEvent {
  id: EntityId;
  type: UserNotificationType;
  subjectType: string;
  subjectId: string;
}

export type LearningGroupMemberBatchAddStatus = 'ADDED' | 'UNCHANGED' | 'UPDATED' | 'FAILED';

export interface LearningGroupMemberBatchAddPayload {
  userIds: EntityId[];
  role?: LearningGroupMemberRole;
}

export interface LearningGroupMemberBatchAddResultItem {
  userId?: EntityId | null;
  account?: string | null;
  displayName?: string | null;
  role: LearningGroupMemberRole;
  status: LearningGroupMemberBatchAddStatus;
  message?: string | null;
  member?: LearningGroupMemberResponse | null;
}

export interface LearningGroupMemberBatchAddResponse {
  requested: number;
  succeeded: number;
  failed: number;
  results: LearningGroupMemberBatchAddResultItem[];
}

export type ContestMode = 'ACM' | 'IOI';
export type ContestStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
export type ContestVisibility = 'PRIVATE' | 'GROUP';
export type ContestProblemScoringMode = 'CASE_SUM_BEST_SUBMISSION' | 'SUBTASK_MIN_CASE_MAX_OVER_SUBMISSIONS';
export type ContestParticipantType = 'INDIVIDUAL' | 'TEAM';
export type ContestParticipantStatus = 'ACTIVE' | 'WITHDRAWN' | 'DISQUALIFIED';
export type ContestRunKind = 'FORMAL' | 'SIMULATION' | 'PRACTICE' | 'DEVELOPMENT' | 'REPLAY';
export type ContestRunStatus = 'DRAFT' | 'EXPIRED' | 'SCHEDULED' | 'RUNNING' | 'ENDED' | 'ARCHIVED';
export type ContestRunListPurpose = 'AI_OPERATIONS';
export type ContestRegistrationPolicy = 'PUBLIC_SELF_REGISTER' | 'GROUP_SELF_REGISTER' | 'APPROVAL_REQUIRED' | 'INVITE_ONLY';
export type ContestRegistrationAccess = 'PUBLIC' | 'GROUPS' | 'INVITE_ONLY';
export type ContestAiPolicyMode = 'DEFAULT' | 'STRICT' | 'DISABLED';
export type ContestRegistrationStatus = 'PENDING' | 'INVITED' | 'APPROVED' | 'REJECTED' | 'DECLINED' | 'CANCELLED';
export type ContestAnnouncementStatus = 'PUBLISHED' | 'ARCHIVED';
export type ContestClarificationStatus = 'OPEN' | 'ANSWERED' | 'CLOSED';
export type ContestClarificationVisibility = 'PRIVATE' | 'PUBLIC';
export type ContestScoreboardView = 'PUBLIC' | 'PRIVATE';
export type ContestScoreboardSnapshotKind = 'LIVE' | 'PUBLIC_FROZEN' | 'FINAL' | 'MANUAL';
export type ContestScoreboardCellStatus = 'UNSOLVED' | 'ATTEMPTED' | 'PENDING' | 'SOLVED' | 'HIDDEN';
export type ContestResolverSessionStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
export type ContestResolverStepType = 'INITIAL' | 'REVEAL' | 'FINAL';
export type ContestExportFormat = 'CSV' | 'XLSX';
export type PlagiarismJobStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELED';
export type PlagiarismDetectorType = 'JPLAG_4_3' | 'TOKEN_FINGERPRINT';
export type PlagiarismRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type PlagiarismReviewStatus = 'OPEN' | 'REVIEWED' | 'DISMISSED' | 'CONFIRMED';
export type PlagiarismAiStatus = 'SKIPPED' | 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
export type FairnessAlertType =
  | 'HIGH_RISK_UNREVIEWED'
  | 'REPEATED_HIGH_SIMILARITY'
  | 'SHARED_IP_CLUSTER'
  | 'SHARED_USER_AGENT_CLUSTER'
  | 'NEAR_TIME_HIGH_RISK_PAIR'
  | 'UNFINISHED_JUDGING';
export type FairnessAlertSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type FairnessAlertStatus = 'OPEN' | 'REVIEWED' | 'DISMISSED' | 'CONFIRMED';
export type ContestPostmortemReportStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';
export type ContestPostmortemAiStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';
export type ContestStudentPostmortemWeaknessCandidateStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED';
export type OperationJobStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type OperationJobType =
  | 'EXPORT_SCOREBOARD'
  | 'EXPORT_SUBMISSIONS'
  | 'EXPORT_PLAGIARISM_REPORT'
  | 'RUN_PLAGIARISM_CHECK'
  | 'GENERATE_SCOREBOARD_TIMELINE'
  | 'GENERATE_CONTEST_POSTMORTEM'
  | 'GENERATE_STUDENT_POSTMORTEM'
  | 'BATCH_GENERATE_STUDENT_POSTMORTEMS';

export interface ContestResponse {
  id: EntityId;
  ownerUserId: EntityId;
  scopeGroupId?: EntityId | null;
  title: string;
  description?: string | null;
  mode: ContestMode;
  status: ContestStatus;
  visibility?: ContestVisibility | null;
  startAt?: string | null;
  endAt?: string | null;
  freezeAt?: string | null;
  penaltyMinutes: number;
  cePenalty: boolean;
  aiPolicyMode: ContestAiPolicyMode;
  aiPolicyNotes?: string | null;
  problemCount: number;
  createdAt: string;
  updatedAt: string;
  publishedAt?: string | null;
  archivedAt?: string | null;
  deletedAt?: string | null;
  deletedBy?: EntityId | null;
}

export interface ContestProblemResponse {
  id: EntityId;
  contestId: EntityId;
  problemId: EntityId;
  label: string;
  displayTitle?: string | null;
  score: number;
  sortOrder: number;
  scoringMode?: ContestProblemScoringMode | null;
  createdAt: string;
  updatedAt: string;
}

export interface ContestParticipantResponse {
  id: EntityId;
  contestId: EntityId;
  contestRunId?: EntityId | null;
  userId: EntityId;
  participantType: ContestParticipantType;
  status: ContestParticipantStatus;
  accountSnapshot: string;
  displayNameSnapshot: string;
  emailSnapshot?: string | null;
  scopeGroupId?: EntityId | null;
  groupNameSnapshot?: string | null;
  registeredAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface AdminContestAiUsageSummary {
  userId: EntityId;
  account: string;
  displayName: string;
  callCount: number;
  promptTokens: number;
  completionTokens: number;
  conversationCount: number;
  blockedCount: number;
  evaluatedCount: number;
  constrainCount: number;
  refuseCount: number;
  degradedCount: number;
  lastUsedAt: string | null;
}

/** Canonical V3 ledger view; historical values are explicitly estimated snapshots. */
export interface AdminContestAiAssistanceSummary {
  userId: EntityId;
  account: string;
  displayName: string;
  turnCount: number;
  promptTokens: number;
  completionTokens: number;
  conversationCount: number;
  interceptedCount: number;
  dataSource: "LIVE" | "HISTORICAL_SNAPSHOT" | "MIXED" | string;
  tokenAccountingStatus: "COMPLETE" | "PARTIAL" | "ESTIMATED" | string;
  lastUsedAt: string | null;
}

export interface AdminContestAiConversationSummary {
  conversationId: string;
  title: string | null;
  mode: string | null;
  contestRunId: EntityId | null;
  contestProblemId: EntityId | null;
  problemId: EntityId | null;
  problemTitle: string | null;
  messageCount: number;
  lastMessageAt: string | null;
}

export interface AdminContestAiMessageResponse {
  id: EntityId;
  role: string;
  content: string;
  status: string | null;
  model: string | null;
  createdAt: string | null;
}

export interface ContestRunResponse {
  id: EntityId;
  contestId: EntityId;
  runKind: ContestRunKind;
  title: string;
  status: ContestRunStatus;
  startAt: string;
  endAt: string;
  freezeAt?: string | null;
  sourceRunId?: EntityId | null;
  registrationPolicy: ContestRegistrationPolicy;
  registrationAccess: ContestRegistrationAccess;
  approvalRequired: boolean;
  allowedGroupIds: EntityId[];
  registrationStartAt?: string | null;
  registrationEndAt?: string | null;
  maxParticipants?: number | null;
  contestTitleSnapshot?: string | null;
  contestDescriptionSnapshot?: string | null;
  modeSnapshot?: ContestMode | null;
  penaltyMinutesSnapshot?: number | null;
  cePenaltySnapshot?: boolean | null;
  aiPolicyModeSnapshot?: ContestAiPolicyMode | null;
  aiPolicyNotesSnapshot?: string | null;
  archivedAt?: string | null;
  archiveReason?: string | null;
  statusBeforeArchive?: ContestRunStatus | null;
  deletedAt?: string | null;
  deletedBy?: EntityId | null;
  publicScoreboardUnfrozenAt?: string | null;
  publicScoreboardUnfrozenBy?: EntityId | null;
  createdBy: EntityId;
  createdAt: string;
  updatedAt: string;
}

export interface ContestAnnouncementResponse {
  id: EntityId;
  contestId: EntityId;
  contestRunId: EntityId;
  authorUserId: EntityId;
  title: string;
  content: string;
  pinned: boolean;
  status: ContestAnnouncementStatus;
  publishedAt: string;
  archivedAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ContestAnnouncementPayload {
  title: string;
  content: string;
  pinned?: boolean;
}

export interface ContestClarificationResponse {
  id: EntityId;
  contestId: EntityId;
  contestRunId: EntityId;
  contestProblemId?: EntityId | null;
  participantId?: EntityId | null;
  userId?: EntityId | null;
  question: string;
  status: ContestClarificationStatus;
  answer?: string | null;
  answerVisibility?: ContestClarificationVisibility | null;
  answeredBy?: EntityId | null;
  answeredAt?: string | null;
  closedAt?: string | null;
  mine: boolean;
  publicAnswer: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ContestClarificationPayload {
  contestProblemId?: EntityId | null;
  question: string;
}

export interface ContestClarificationReplyPayload {
  answer: string;
  visibility: ContestClarificationVisibility;
}

export interface ContestRunPayload {
  runKind?: ContestRunKind;
  title: string;
  startAt: string;
  endAt: string;
  freezeAt?: string | null;
  sourceRunId?: EntityId | null;
  registrationPolicy?: ContestRegistrationPolicy;
  registrationAccess?: ContestRegistrationAccess;
  approvalRequired?: boolean;
  allowedGroupIds?: EntityId[];
  registrationStartAt?: string | null;
  registrationEndAt?: string | null;
  maxParticipants?: number | null;
}

export interface ContestRunUpdatePayload {
  title?: string;
  startAt?: string;
  endAt?: string;
  freezeAt?: string | null;
  registrationPolicy?: ContestRegistrationPolicy;
  registrationAccess?: ContestRegistrationAccess;
  approvalRequired?: boolean;
  allowedGroupIds?: EntityId[];
  registrationStartAt?: string | null;
  registrationEndAt?: string | null;
  maxParticipants?: number | null;
}

export interface ContestRegistrationResponse {
  id: EntityId;
  contestId: EntityId;
  contestRunId: EntityId;
  userId: EntityId;
  status: ContestRegistrationStatus;
  requestedAt: string;
  reviewedBy?: EntityId | null;
  approvedAt?: string | null;
  rejectedAt?: string | null;
  cancelledAt?: string | null;
  rejectReason?: string | null;
  account?: string | null;
  displayName?: string | null;
  email?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ContestOpenRunResponse {
  contest: ContestResponse;
  run: ContestRunResponse;
  canRegister: boolean;
  canSubmit: boolean;
  canViewProblems: boolean;
  canViewScoreboard: boolean;
  full: boolean;
  registration?: ContestRegistrationResponse | null;
  participant?: ContestParticipantResponse | null;
}

export interface ContestOpenRunListParams {
  page?: number;
  pageSize?: number;
  status?: ContestRunStatus | '';
  keyword?: string;
  mode?: ContestMode | '';
  registrationStatus?: ContestRegistrationStatus | '';
}

export interface ContestRunListParams {
  page?: number;
  pageSize?: number;
  status?: ContestRunStatus | '';
  keyword?: string;
  from?: string;
  to?: string;
  purpose?: ContestRunListPurpose;
}

export interface ContestRegistrationListParams {
  page?: number;
  pageSize?: number;
  status?: ContestRegistrationStatus | '';
  keyword?: string;
}

export interface ContestRunProblemSnapshotResponse {
  id: EntityId;
  contestId: EntityId;
  contestRunId: EntityId;
  contestProblemId: EntityId;
  problemId: EntityId;
  label: string;
  displayTitle?: string | null;
  statement?: string | null;
  notes?: string | null;
  tags?: string[] | string | null;
  difficulty?: Difficulty | null;
  timeLimitMillis?: number | null;
  memoryLimitKb?: number | null;
  score?: number | null;
  scoringMode?: ContestProblemScoringMode | null;
  sortOrder: number;
  visibility?: ProblemVisibility | null;
  createdAt: string;
}

export interface ContestPayload {
  title: string;
  description?: string;
  mode: ContestMode;
  penaltyMinutes?: number;
  cePenalty?: boolean;
  aiPolicyMode?: ContestAiPolicyMode;
  aiPolicyNotes?: string;
}

export interface ContestUpdatePayload {
  title?: string;
  description?: string | null;
  mode?: ContestMode;
  penaltyMinutes?: number;
  cePenalty?: boolean;
  aiPolicyMode?: ContestAiPolicyMode;
  aiPolicyNotes?: string | null;
}

export interface ContestProblemPayload {
  problemId: EntityId;
  label: string;
  displayTitle?: string | null;
  score: number;
  sortOrder: number;
  scoringMode?: ContestProblemScoringMode;
}

export interface ContestListParams {
  page?: number;
  pageSize?: number;
  status?: ContestStatus | '';
  mine?: boolean;
  scopeGroupId?: EntityId | '';
  keyword?: string;
  acm?: boolean | '';
}

export interface ContestScoreboardProblemResponse {
  contestProblemId: EntityId;
  problemId: EntityId;
  label: string;
  displayTitle?: string | null;
  score: number;
  sortOrder: number;
}

export interface ContestScoreboardCellResponse {
  contestProblemId?: EntityId | null;
  status: ContestScoreboardCellStatus;
  attempts: number;
  wrongAttempts: number;
  pendingAttempts: number;
  acceptedAtMillis?: number | null;
  penaltyMinutes: number;
  score?: number | null;
  maxScore?: number | null;
  bestSubmissionId?: EntityId | null;
  lastScoreImprovedAtMillis?: number | null;
}

export interface ContestScoreboardRowResponse {
  rank: number;
  participantId: EntityId;
  userId: EntityId;
  accountSnapshot: string;
  displayNameSnapshot: string;
  solvedCount: number;
  penaltyMinutes: number;
  lastAcceptedAtMillis?: number | null;
  totalScore?: number | null;
  lastScoreImprovedAtMillis?: number | null;
  cells: ContestScoreboardCellResponse[];
}

export interface ContestScoreboardResponse {
  contestId: EntityId;
  contestRunId?: EntityId | null;
  mode: ContestMode;
  view: ContestScoreboardView;
  snapshotId?: EntityId | null;
  snapshotKind: ContestScoreboardSnapshotKind;
  atContestMillis: number;
  generatedAt: string;
  frozen: boolean;
  freezeAtContestMillis?: number | null;
  penaltyMinutes: number;
  cePenalty: boolean;
  problems: ContestScoreboardProblemResponse[];
  rows: ContestScoreboardRowResponse[];
}

export interface ContestScoreboardSnapshotResponse {
  id: EntityId;
  contestId: EntityId;
  contestRunId?: EntityId | null;
  snapshotKind: ContestScoreboardSnapshotKind;
  view: ContestScoreboardView;
  snapshotAt: string;
  contestTimeMillis: number;
  scoringVersion: number;
  frozen: boolean;
  checksum: string;
  createdBy?: EntityId | null;
  createdAt: string;
}

export interface ContestScoreboardTimelineTickResponse {
  id: EntityId;
  contestId: EntityId;
  contestRunId: EntityId;
  view: ContestScoreboardView;
  bucketMillis: number;
  snapshotId: EntityId;
  checksum?: string | null;
  createdAt: string;
}

export type ContestScoreboardTimelineStatus = 'GENERATING' | 'PARTIAL' | 'READY' | 'FAILED';

export interface ContestScoreboardTimelineResponse {
  status: ContestScoreboardTimelineStatus;
  ticks: ContestScoreboardTimelineTickResponse[];
  jobId?: EntityId | null;
  progressCurrent?: number | null;
  progressTotal?: number | null;
  message?: string | null;
}

export interface ContestResolverSessionResponse {
  id: EntityId;
  contestId: EntityId;
  contestRunId: EntityId;
  status: ContestResolverSessionStatus;
  title: string;
  view: ContestScoreboardView;
  freezeSnapshotId?: EntityId | null;
  finalSnapshotId?: EntityId | null;
  stepCount: number;
  checksum: string;
  createdBy: EntityId;
  publishedAt?: string | null;
  archivedAt?: string | null;
  statusBeforeArchive?: ContestResolverSessionStatus | null;
  deletedAt?: string | null;
  deletedBy?: EntityId | null;
  createdAt: string;
  updatedAt: string;
}

export interface ContestResolverStepResponse {
  id: EntityId;
  resolverSessionId: EntityId;
  contestId: EntityId;
  contestRunId: EntityId;
  stepOrder: number;
  stepType: ContestResolverStepType;
  participantId?: EntityId | null;
  contestProblemId?: EntityId | null;
  submissionId?: EntityId | null;
  payloadJson: string;
  scoreboard: ContestScoreboardResponse;
  createdAt: string;
}

export interface ContestResolverSessionDetailResponse {
  session: ContestResolverSessionResponse;
  steps: ContestResolverStepResponse[];
}

export interface ContestPostmortemReportResponse {
  id: EntityId;
  contestId: EntityId;
  contestRunId: EntityId;
  status: ContestPostmortemReportStatus;
  aiStatus: ContestPostmortemAiStatus;
  generatedBy: EntityId;
  statisticsJson: string;
  aiMarkdown?: string | null;
  aiProvider?: string | null;
  aiModel?: string | null;
  promptTokens: number;
  completionTokens: number;
  errorMessage?: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt?: string | null;
}

export interface ContestStudentPostmortemWeaknessCandidateResponse {
  id: EntityId;
  reportId: EntityId;
  contestId: EntityId;
  contestRunId: EntityId;
  contestParticipantId: EntityId;
  userId: EntityId;
  status: ContestStudentPostmortemWeaknessCandidateStatus;
  knowledgeNode: string;
  symptom: string;
  tags: string[];
  evidence: string[];
  confidence: number;
  memoryId?: EntityId | null;
  weaknessId?: EntityId | null;
  createdAt: string;
  updatedAt: string;
  decidedAt?: string | null;
}

export interface ContestStudentPostmortemReportResponse {
  id: EntityId;
  contestId: EntityId;
  contestRunId: EntityId;
  contestParticipantId: EntityId;
  userId: EntityId;
  status: ContestPostmortemReportStatus;
  aiStatus: ContestPostmortemAiStatus;
  generatedBy: EntityId;
  statisticsJson: string;
  aiMarkdown?: string | null;
  practiceSuggestionsJson?: string | null;
  aiProvider?: string | null;
  aiModel?: string | null;
  promptTokens: number;
  completionTokens: number;
  errorMessage?: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt?: string | null;
  weaknessCandidates: ContestStudentPostmortemWeaknessCandidateResponse[];
}

export interface ContestStudentPostmortemOperationJobResponse {
  id: EntityId;
  status: OperationJobStatus;
  createdAt: string;
  startedAt?: string | null;
  updatedAt: string;
}

export interface ContestStudentPostmortemSummaryResponse {
  contestParticipantId: EntityId;
  userId: EntityId;
  accountSnapshot: string;
  displayNameSnapshot: string;
  emailSnapshot?: string | null;
  reportId?: EntityId | null;
  status?: ContestPostmortemReportStatus | null;
  aiStatus?: ContestPostmortemAiStatus | null;
  submissionCount: number;
  acceptedCount: number;
  totalScore?: number | null;
  maxScore?: number | null;
  weaknessCandidateCount: number;
  pendingWeaknessCandidateCount: number;
  lastGeneratedAt?: string | null;
}

export interface ContestScoreboardParams {
  runId?: EntityId | '';
  view?: ContestScoreboardView;
  atMillis?: number | '';
  snapshotId?: EntityId | '';
}

export interface ContestScoreboardSnapshotPayload {
  snapshotKind?: ContestScoreboardSnapshotKind;
  view?: ContestScoreboardView;
  atMillis?: number | null;
}

export interface ContestExportResponse {
  fileName: string;
  contentType: string;
  base64Content: string;
  byteSize: number;
}

export interface OperationJobArtifactResponse {
  id: EntityId;
  jobId: EntityId;
  fileName: string;
  contentType: string;
  byteSize: number;
  sha256?: string | null;
  expiresAt?: string | null;
  createdAt: string;
}

export interface OperationJobResponse {
  id: EntityId;
  jobType: OperationJobType;
  status: OperationJobStatus;
  resourceType?: string | null;
  resourceId?: EntityId | null;
  contestId?: EntityId | null;
  contestRunId?: EntityId | null;
  requestedBy: EntityId;
  errorMessage?: string | null;
  attemptCount: number;
  maxAttempts: number;
  progressCurrent?: number | null;
  progressTotal?: number | null;
  progressMessage?: string | null;
  resultJson?: string | null;
  artifact?: OperationJobArtifactResponse | null;
  startedAt?: string | null;
  completedAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface OperationAuditEventResponse {
  id: EntityId;
  actorUserId: EntityId;
  action: string;
  actionDisplayName?: string | null;
  resourceType?: string | null;
  resourceId?: EntityId | null;
  contestId?: EntityId | null;
  contestRunId?: EntityId | null;
  targetUserId?: EntityId | null;
  status?: string | null;
  traceId?: string | null;
  summaryJson?: string | null;
  createdAt: string;
}

export interface ContestSubmissionResponse {
  id: EntityId;
  contestId: EntityId;
  contestRunId?: EntityId | null;
  contestProblemId: EntityId;
  problemId: EntityId;
  problemLabel?: string | null;
  problemTitle?: string | null;
  contestParticipantId: EntityId;
  userId: EntityId;
  accountSnapshot: string;
  displayNameSnapshot: string;
  emailSnapshot?: string | null;
  language: string;
  status: SubmissionStatus;
  judgeMessage: string;
  timeMillis?: number | null;
  memoryKb?: number | null;
  score?: number | null;
  maxScore?: number | null;
  caseResults?: SubmissionCaseResultResponse[] | null;
  submittedAtContestMillis?: number | null;
  createdAt: string;
  judgedAt?: string | null;
  codeIncluded: boolean;
  code?: string | null;
  stdoutExcerpt?: string | null;
  stderrExcerpt?: string | null;
  exitStatus?: number | null;
  runTimeMillis?: number | null;
}

export interface ContestSubmissionCodeResponse {
  auditLogId: EntityId;
  submission: ContestSubmissionResponse;
}

export interface SubmissionCodeAccessLogResponse {
  id: EntityId;
  contestId: EntityId;
  contestRunId?: EntityId | null;
  submissionId: EntityId;
  viewerUserId: EntityId;
  targetUserId: EntityId;
  contestParticipantId?: EntityId | null;
  viewerAccount?: string | null;
  viewerDisplayName?: string | null;
  targetAccountSnapshot?: string | null;
  targetDisplayNameSnapshot?: string | null;
  problemLabel?: string | null;
  problemTitle?: string | null;
  reason?: string | null;
  traceId?: string | null;
  createdAt: string;
}

export interface ContestSubmissionListParams {
  page?: number;
  pageSize?: number;
  runId?: EntityId | '';
  contestProblemId?: EntityId | '';
  participantId?: EntityId | '';
  userId?: EntityId | '';
  status?: SubmissionStatus | '';
  language?: string;
}

export interface SubmissionCodeAccessLogListParams {
  page?: number;
  pageSize?: number;
  runId?: EntityId | '';
  submissionId?: EntityId | '';
  viewerUserId?: EntityId | '';
  targetUserId?: EntityId | '';
}

export interface ContestScoreboardExportParams extends ContestScoreboardParams {
  format?: ContestExportFormat;
}

export interface ContestSubmissionExportParams extends ContestSubmissionListParams {
  format?: ContestExportFormat;
}

export interface PlagiarismJobCreatePayload {
  contestProblemIds?: EntityId[];
  languages?: string[];
  minimumSimilarity?: number;
  includeAiAnalysis?: boolean;
}

export interface PlagiarismJobResponse {
  id: EntityId;
  contestId: EntityId;
  contestRunId?: EntityId | null;
  status: PlagiarismJobStatus;
  detector: PlagiarismDetectorType;
  minimumSimilarity: number;
  includeAiAnalysis: boolean;
  totalSubmissions: number;
  totalPairs: number;
  highRiskPairs: number;
  errorMessage?: string | null;
  createdBy: EntityId;
  startedAt?: string | null;
  completedAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PlagiarismPairResponse {
  id: EntityId;
  jobId: EntityId;
  contestId: EntityId;
  contestProblemId: EntityId;
  problemId: EntityId;
  problemLabel: string;
  problemTitle: string;
  language: string;
  leftSubmissionId: EntityId;
  rightSubmissionId: EntityId;
  leftParticipantId: EntityId;
  rightParticipantId: EntityId;
  leftUserId: EntityId;
  rightUserId: EntityId;
  leftAccountSnapshot: string;
  leftDisplayNameSnapshot: string;
  rightAccountSnapshot: string;
  rightDisplayNameSnapshot: string;
  similarity: number;
  maximalSimilarity: number;
  minimalSimilarity: number;
  matchedTokens: number;
  riskLevel: PlagiarismRiskLevel;
  reviewStatus: PlagiarismReviewStatus;
  teacherNote?: string | null;
  aiStatus: PlagiarismAiStatus;
  aiSummary?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PlagiarismFragmentResponse {
  id: EntityId;
  pairId: EntityId;
  sequenceNo: number;
  leftStartToken: number;
  rightStartToken: number;
  tokenLength: number;
  leftExcerpt?: string | null;
  rightExcerpt?: string | null;
}

export interface PlagiarismPairDetailResponse {
  pair: PlagiarismPairResponse;
  fragments: PlagiarismFragmentResponse[];
  aiAnalysis?: string | null;
  aiErrorMessage?: string | null;
}

export interface PlagiarismPairListParams {
  page?: number;
  pageSize?: number;
  contestProblemId?: EntityId | '';
  language?: string;
  riskLevel?: PlagiarismRiskLevel | '';
  reviewStatus?: PlagiarismReviewStatus | '';
}

export interface PlagiarismPairUpdatePayload {
  reviewStatus?: PlagiarismReviewStatus;
  teacherNote?: string | null;
}

export interface ContestPlagiarismGraphSummary {
  nodeCount: number;
  edgeCount: number;
  highRiskEdgeCount: number;
  criticalRiskEdgeCount: number;
  repeatedPairCount: number;
}

export interface ContestPlagiarismGraphNode {
  participantId: EntityId;
  userId: EntityId;
  accountSnapshot: string;
  displayNameSnapshot: string;
  pairCount: number;
  highRiskPairCount: number;
  criticalRiskPairCount: number;
  connectedParticipantCount: number;
}

export interface ContestPlagiarismGraphEdge {
  pairId: EntityId;
  jobId: EntityId;
  contestProblemId: EntityId;
  problemLabel: string;
  problemTitle: string;
  language: string;
  leftParticipantId: EntityId;
  rightParticipantId: EntityId;
  leftDisplayNameSnapshot: string;
  rightDisplayNameSnapshot: string;
  similarity: number;
  matchedTokens: number;
  riskLevel: PlagiarismRiskLevel;
  reviewStatus: PlagiarismReviewStatus;
  aiSummary?: string | null;
}

export interface ContestPlagiarismGraphCluster {
  leftParticipantId: EntityId;
  rightParticipantId: EntityId;
  leftDisplayNameSnapshot: string;
  rightDisplayNameSnapshot: string;
  pairCount: number;
  highRiskPairCount: number;
  maxSimilarity: number;
  pairIds: EntityId[];
}

export interface ContestPlagiarismGraphResponse {
  contestId: EntityId;
  contestRunId: EntityId;
  summary: ContestPlagiarismGraphSummary;
  nodes: ContestPlagiarismGraphNode[];
  edges: ContestPlagiarismGraphEdge[];
  clusters: ContestPlagiarismGraphCluster[];
}

export interface ContestPlagiarismGraphParams {
  jobId?: EntityId | '';
  contestProblemId?: EntityId | '';
  problemId?: EntityId | '';
  language?: string;
  riskLevel?: PlagiarismRiskLevel | '';
  reviewStatus?: PlagiarismReviewStatus | '';
}

export interface FairnessAlertResponse {
  id: EntityId;
  contestId: EntityId;
  contestRunId: EntityId;
  type: FairnessAlertType;
  severity: FairnessAlertSeverity;
  status: FairnessAlertStatus;
  primaryParticipantId?: EntityId | null;
  secondaryParticipantId?: EntityId | null;
  plagiarismPairId?: EntityId | null;
  primaryDisplayName: string;
  secondaryDisplayName: string;
  title: string;
  summary: string;
  evidence: Record<string, unknown>;
  teacherNote?: string | null;
  reviewedBy?: EntityId | null;
  reviewedAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface FairnessAlertListParams {
  page?: number;
  pageSize?: number;
  severity?: FairnessAlertSeverity | '';
  type?: FairnessAlertType | '';
  status?: FairnessAlertStatus | '';
  participantId?: EntityId | '';
}

export interface FairnessAlertUpdatePayload {
  status?: FairnessAlertStatus;
  teacherNote?: string | null;
}

export interface TestCaseDto {
  input: string;
  expectedOutput: string;
  sample: boolean;
}

export type TestcasePackageStatus = 'UPLOADING' | 'PROCESSING' | 'READY' | 'FAILED';
export type TestcaseCheckerType = 'STANDARD' | 'CUSTOM';
export type TestcaseCheckerProtocol = 'AIOJ_JSON';

export interface TestcasePackageChecker {
  type: TestcaseCheckerType;
  language?: string | null;
  source?: string | null;
  protocol?: TestcaseCheckerProtocol | null;
}

export interface TestcasePackageSubtask {
  id: EntityId;
  key: string;
  title?: string | null;
  score: number;
  sortOrder: number;
}

export interface TestcasePackageCase {
  id: EntityId;
  name: string;
  inputPath: string;
  outputPath: string;
  sample: boolean;
  subtaskKey?: string | null;
  score?: number | null;
  inputSizeBytes: number;
  outputSizeBytes: number;
  sortOrder: number;
}

export interface TestcasePackageResponse {
  id: EntityId;
  problemId: EntityId;
  version: string;
  fileName: string;
  fileSizeBytes: number;
  sha256: string;
  status: TestcasePackageStatus;
  active: boolean;
  caseCount: number;
  sampleCount: number;
  storageProvider: string;
  createdAt: string;
  activatedAt?: string | null;
  archivedAt?: string | null;
  deletedAt?: string | null;
  deletedBy?: EntityId | null;
  errorMessage?: string | null;
  checker?: TestcasePackageChecker | null;
  subtasks?: TestcasePackageSubtask[];
  cases?: TestcasePackageCase[];
}

export interface AppendTestcasePackageCasePayload {
  caseName: string;
  score?: number | null;
  subtaskKey?: string | null;
  inputFile: File;
  outputFile: File;
}

export interface AppendTestcasePackageCasesPayload {
  cases: AppendTestcasePackageCasePayload[];
}

export interface TestcaseUploadInitResponse {
  uploadId: string;
  status: TestcasePackageStatus;
  packageId?: EntityId | null;
  uploadedChunks: number[];
  chunkSizeBytes: number;
  totalChunks: number;
  expiresAt: string;
  message?: string | null;
}

export interface TestcaseUploadStatusResponse {
  uploadId: string;
  status: TestcasePackageStatus;
  uploadedChunks: number[];
  totalChunks: number;
  progress: number;
  packageId?: EntityId | null;
  errorMessage?: string | null;
}

export interface TestcaseUploadFailRequest {
  message?: string;
}

export type ProblemVisibility = 'PUBLIC' | 'PRIVATE';

export interface ProblemPayload {
  title: string;
  difficulty: Difficulty;
  statement: string;
  notes?: string;
  tags: string[];
  testCases: TestCaseDto[];
  timeLimitMillis: number;
  languageTimeLimitMultipliers?: ProblemLanguageTimeLimitMultipliers;
  memoryLimitKb: number;
  standardSolutionLanguage?: string;
  standardSolutionCode?: string;
  standardSolutions?: ProblemStandardSolutionPayload[];
  testcaseGeneratorPython?: string;
  visibility?: ProblemVisibility;
}

export interface ProblemLanguageTimeLimitMultipliers {
  cpp?: number | null;
  python?: number | null;
  java?: number | null;
}

export interface ProblemStandardSolutionPayload {
  language: string;
  code?: string | null;
}

export interface ProblemResponse {
  id: EntityId;
  title: string;
  difficulty: Difficulty;
  statement: string;
  notes?: string | null;
  tags: string[];
  samples: TestCaseDto[];
  timeLimitMillis: number;
  languageTimeLimitMultipliers?: ProblemLanguageTimeLimitMultipliers | null;
  memoryLimitKb: number;
  aiGenerated: boolean;
  visibility?: ProblemVisibility | null;
  createdAt: string;
  archivedAt?: string | null;
  deletedAt?: string | null;
  deletedBy?: EntityId | null;
}

export interface ProblemSolutionResponse {
  id: EntityId;
  problemId: EntityId;
  language: string;
  content: string;
  createdAt: string;
}

export interface ProblemTestcaseGeneratorResponse {
  id: EntityId;
  problemId: EntityId;
  content: string;
  createdAt: string;
  updatedAt: string;
}

export interface SubmissionResponse {
  id: EntityId;
  problemId: EntityId;
  userId: EntityId;
  contestId?: EntityId | null;
  contestRunId?: EntityId | null;
  contestProblemId?: EntityId | null;
  contestParticipantId?: EntityId | null;
  submittedAtContestMillis?: number | null;
  visibleToParticipant: boolean;
  language: string;
  code?: string | null;
  status: SubmissionStatus;
  judgeMessage: string;
  timeMillis?: number;
  memoryKb?: number;
  stdoutExcerpt?: string | null;
  stderrExcerpt?: string | null;
  exitStatus?: number | null;
  runTimeMillis?: number | null;
  score?: number | null;
  maxScore?: number | null;
  caseResults?: SubmissionCaseResultResponse[] | null;
  createdAt: string;
  judgedAt?: string;
}

export interface SubmissionCaseResultResponse {
  id: EntityId;
  submissionId: EntityId;
  contestId?: EntityId | null;
  contestProblemId?: EntityId | null;
  contestParticipantId?: EntityId | null;
  testcasePackageId?: EntityId | null;
  caseId?: EntityId | null;
  caseIndex: number;
  caseName?: string | null;
  subtaskKey?: string | null;
  status: SubmissionStatus;
  score: number;
  maxScore: number;
  timeMillis?: number | null;
  memoryKb?: number | null;
  message?: string | null;
  createdAt: string;
}

export interface AiChatMessageResponse {
  id: EntityId;
  conversationId: string;
  problemId?: EntityId | null;
  clientMessageId?: string | null;
  role: 'user' | 'assistant' | 'system';
  content: string;
  model?: string;
  status?: 'RUNNING' | 'COMPLETED' | 'FAILED' | string;
  errorMessage?: string | null;
  createdAt: string;
  completedAt?: string | null;
}

export interface AiConversationResponse {
  conversationId: string;
  problemId?: EntityId;
  title?: string;
  source?: string | null;
  sourceRefType?: string | null;
  sourceRefId?: string | null;
  mode?: string | null;
  summary?: string | null;
  recentProblemId?: EntityId | null;
  messageCount: number;
  latestMessagePreview?: string | null;
  deletedAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AiMemoryResponse {
  id: EntityId;
  category: string;
  title?: string | null;
  memoryType: string;
  content: string;
  confidence: number;
  source?: string | null;
  status: string;
  createdAt: string;
  updatedAt: string;
  lastUsedAt?: string | null;
}

export type AiMemoryCategory = 'memory' | 'habit' | 'rule' | 'preference' | 'weakness' | 'teaching_style';

export interface AiMemoryListParams {
  category?: AiMemoryCategory | '';
  status?: 'ACTIVE' | 'DISABLED' | 'RESOLVED' | 'SUPERSEDED' | '';
  keyword?: string;
}

export interface AiMemoryUpsertPayload {
  category?: AiMemoryCategory | string;
  title?: string;
  memoryType: string;
  content: string;
  confidence?: number;
  status?: string;
}

export interface AiMemoryExportResponse {
  fileName: string;
  markdown: string;
}

export interface AiMemoryImportResponse {
  created: number;
  updated: number;
  records: AiMemoryResponse[];
}

export interface AiMemoryCandidateResponse {
  id: EntityId;
  category: string;
  memoryKey: string;
  canonicalText: string;
  scopeType: string;
  scopeId?: string | null;
  evidenceType: string;
  extractionConfidence: number;
  writeScore: number;
  longTerm: boolean;
  problemSpecific: boolean;
  hypothetical: boolean;
  quoted: boolean;
  needsConfirmation: boolean;
  qualityFlags: string[];
  ambiguityFlags: string[];
  status: string;
  rejectedReason?: string | null;
  sourceConversationId?: string | null;
  sourceMessageId?: EntityId | null;
  createdAt: string;
  updatedAt: string;
  candidateKind?: string | null;
  plannerAction?: string | null;
  targetMemoryId?: EntityId | null;
  targetClaimId?: EntityId | null;
}

export interface AiMemoryCandidateActionPayload {
  category?: string;
  title?: string;
  memoryType?: string;
  canonicalText?: string;
  reason?: string;
}

export interface AiMemoryReviewListItemResponse {
  id: EntityId;
  userId: EntityId;
  category: string;
  memoryKey: string;
  canonicalText: string;
  scopeType: string;
  scopeId?: string | null;
  evidenceType: string;
  extractionConfidence: number;
  writeScore: number;
  needsConfirmation: boolean;
  qualityFlags: string[];
  ambiguityFlags: string[];
  status: string;
  rejectedReason?: string | null;
  sourceConversationId?: string | null;
  sourceMessageId?: EntityId | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface AiMemoryReviewEvidenceResponse {
  id: EntityId;
  claimId?: EntityId | null;
  candidateId?: EntityId | null;
  evidenceType: string;
  evidenceText: string;
  confidence?: number | null;
  reason?: string | null;
  createdAt?: string | null;
}

export interface AiMemoryReviewRelatedMemoryResponse {
  id: EntityId;
  category: string;
  title?: string | null;
  memoryType: string;
  content: string;
  confidence?: number | null;
  source?: string | null;
  status: string;
  updatedAt?: string | null;
}

export interface AiMemoryReviewRelatedProfileResponse {
  id: EntityId;
  category: string;
  profileKey: string;
  label: string;
  state: string;
  confidence?: number | null;
  evidenceCount?: number | null;
  updatedAt?: string | null;
}

export interface AiMemoryReviewDetailResponse {
  candidate: AiMemoryReviewListItemResponse;
  evidence: AiMemoryReviewEvidenceResponse[];
  relatedMemories: AiMemoryReviewRelatedMemoryResponse[];
  relatedProfiles: AiMemoryReviewRelatedProfileResponse[];
  suggestedActions: string[];
}

export interface AiMemoryReviewActionPayload extends AiMemoryCandidateActionPayload {
  action: 'APPROVE' | 'REJECT' | 'EDIT_ACCEPT' | 'MERGE' | 'REQUEST_USER_CONFIRMATION' | string;
  targetMemoryId?: EntityId | null;
  targetClaimId?: EntityId | null;
}

export interface AiMemoryMergeMaintenancePayload {
  targetUserId?: EntityId | null;
  category?: string;
  limit?: number;
}

export interface AiMemoryMergeMaintenanceResponse {
  targetUserId: EntityId;
  scannedMemories: number;
  relatedGroups: number;
  queuedJobs: number;
  candidateIds: EntityId[];
  jobIds: EntityId[];
}

export interface AiMemoryObservabilityMetricResponse {
  key: string;
  count: number;
}

export interface AiMemoryObservabilityRecentJobResponse {
  jobId: EntityId;
  jobType: string;
  status: string;
  attemptCount?: number | null;
  maxAttempts?: number | null;
  nextRunAt?: string | null;
  updatedAt?: string | null;
  lastErrorSummary: string;
}

export interface AiMemoryObservabilityResponse {
  generatedAt: string;
  jobsByStatus: AiMemoryObservabilityMetricResponse[];
  jobsByType: AiMemoryObservabilityMetricResponse[];
  eventsByType: AiMemoryObservabilityMetricResponse[];
  dueJobCount: number;
  expiredLeaseCount: number;
  recentFinalFailures: AiMemoryObservabilityRecentJobResponse[];
  totalJobCount: number;
  jobFailureRate: number;
  memoryExtractionFailureCount: number;
  embeddingFailureCount: number;
  embeddingCapacityRejectedCount: number;
}

export interface AiMemoryDebugResponse {
  queryContext: {
    query: string;
    intent: string;
    mode?: string | null;
    problemId?: EntityId | null;
    problemTags: string[];
  };
  selected: AiMemoryDebugItem[];
  rejected: AiMemoryDebugItem[];
}

export interface AiMemoryDebugItem {
  id: EntityId;
  claimId?: EntityId | null;
  category: string;
  memoryType: string;
  title?: string | null;
  content: string;
  score: number;
  selected: boolean;
  reasons: string[];
}

export interface AiUsageResponse {
  usedRecent: number;
  rollingLimit: number;
  recentWindowHours: number;
  usedThisMonth: number;
  monthlyLimit: number;
}

export interface DailyAiUsageStatsResponse {
  date: string;
  calls: number;
  successfulCalls: number;
  promptTokens: number;
  completionTokens: number;
}

export interface AccountImportParsedUser {
  account: string;
  displayName: string;
  email?: string | null;
  confidence: number;
  sourceLine?: string | null;
}

export interface AccountImportParseResponse {
  users: AccountImportParsedUser[];
  note: string;
}

export type AiModelConfigScope = 'TEXT_GENERATION' | 'MEMORY_EXTRACTION' | 'REPORT_ANALYSIS' | 'PROBLEM_DRAFT' | 'ACCOUNT_IMPORT_PARSE' | 'INTENT' | 'AGENT_CURATOR' | 'EMBEDDING';
export type AiModelConfigKeyAction = 'KEEP' | 'REPLACE' | 'CLEAR';

export interface AiModelConfigResponse {
  scope: AiModelConfigScope;
  enabled: boolean;
  inherited: boolean;
  source: 'DATABASE' | 'ENVIRONMENT' | 'TEXT_GENERATION' | 'INLINE' | string;
  provider: string;
  baseUrl: string;
  model: string;
  jsonOutputEnabled: boolean;
  thinkingEnabled: boolean;
  reasoningEffort: 'high' | 'max' | string;
  temperature?: number | null;
  maxTokens?: number | null;
  embeddingDimension?: number | null;
  apiKeyConfigured: boolean;
  apiKeyPreview?: string | null;
  apiKeySource?: 'DATABASE' | 'ENVIRONMENT' | 'TEMPORARY' | 'NONE' | string;
  apiKeyEnvName?: string | null;
  updatedAt?: string | null;
  updatedBy?: EntityId | number | null;
}

export interface AiModelConfigPayload {
  enabled?: boolean;
  provider?: string;
  baseUrl?: string;
  model?: string;
  apiKeyAction?: AiModelConfigKeyAction;
  apiKey?: string;
  jsonOutputEnabled?: boolean;
  thinkingEnabled?: boolean;
  reasoningEffort?: 'high' | 'max' | string;
  temperature?: number | null;
  maxTokens?: number | null;
  embeddingDimension?: number | null;
}

export interface AiModelConfigTestPayload extends AiModelConfigPayload {
  prompt?: string;
}

export interface AiModelConfigTestResponse {
  success: boolean;
  provider: string;
  model: string;
  latencyMillis: number;
  promptTokens: number;
  completionTokens: number;
  contentPreview?: string | null;
  errorMessage?: string | null;
}

export interface AiModelOption {
  id: string;
  ownedBy?: string | null;
  supportsJsonOutput: boolean;
  supportsThinking: boolean;
  thinkingEffortModes: string[];
  fixedTemperature?: number | null;
  recommendedTemperature?: number | null;
  contextLength?: number | null;
  deprecated: boolean;
}

export interface AiModelListResponse {
  scope: AiModelConfigScope;
  provider: string;
  baseUrl: string;
  apiKeyConfigured: boolean;
  apiKeyEnvName?: string | null;
  manualAllowed: boolean;
  fetchStatus: 'SUCCESS' | 'MISSING_KEY' | 'UNSUPPORTED' | 'FAILED' | string;
  errorMessage?: string | null;
  models: AiModelOption[];
}

export interface DailySubmissionStatsResponse {
  date: string;
  totalSubmissions: number;
  acceptedSubmissions: number;
}

export type AiAssistMode = 'assist' | 'hint' | 'debug' | 'edge' | 'optimize' | 'boundary' | 'code_explain' | 'concept' | 'clarify' | 'qa';

export interface AiProblemContextPayload {
  id?: EntityId;
  title?: string;
  difficulty?: Difficulty | string;
  statement?: string;
  notes?: string | null;
  tags?: string[];
  samples?: TestCaseDto[];
  timeLimitMillis?: number;
  memoryLimitKb?: number;
}

export interface AiCodeContextPayload {
  language?: string;
  code: string;
}

export type AiClarificationOptionType = 'choice' | 'text' | 'textarea' | 'free_text' | 'code' | 'confirm';
export type AiClarificationInputKind = 'single_choice' | 'multi_choice' | 'free_text' | 'code' | 'number' | 'file' | 'confirm' | 'mixed';

export interface AiClarificationOption {
  id?: string;
  type?: AiClarificationOptionType;
  label: string;
  message: string;
  placeholder?: string;
  messageTemplate?: string;
}

export interface AiClarificationInput {
  kind: AiClarificationInputKind;
  required?: boolean;
  options?: AiClarificationOption[];
  allowCustom?: boolean;
  customKind?: 'free_text' | 'code' | 'number' | 'file' | string | null;
  placeholder?: string;
}

export interface AiClarification {
  id?: string;
  priority?: 'blocking' | 'helpful' | 'confirm' | string;
  title?: string;
  prompt?: string;
  input?: AiClarificationInput;
  options: AiClarificationOption[];
  defaultAction?: 'ask_user' | 'use_assumption' | 'continue' | string;
  assumption?: string | null;
}

export interface AiClarificationAnswerPayload {
  requestId?: string;
  question?: string;
  answerText: string;
  selectedOptionIds?: string[];
  customText?: string;
}

export interface AiSelectionRangePayload {
  startOffset?: number;
  endOffset?: number;
  startLine?: number;
  endLine?: number;
}

export interface AiSelectionSurroundingPayload {
  before?: string;
  after?: string;
  sectionTitle?: string;
  messagePreview?: string;
}

export interface AiSelectedCodeContextPayload {
  language?: string;
  functionName?: string;
  enclosingSymbol?: string;
  latestCodeMessageId?: string;
  codeHash?: string;
  hasCompileRisk?: boolean;
}

export interface AiSelectedProblemContextPayload {
  problemId?: EntityId | string;
  title?: string;
  tags?: string[];
  constraints?: string[];
}

export interface AiSelectionContextPayload {
  selectionId: string;
  conversationId?: string;
  sourceType: 'assistant_message' | 'user_message' | 'code_block' | 'problem_context' | 'clarification' | 'context_debug' | string;
  sourceMessageId?: string;
  sourceRole?: 'assistant' | 'user' | 'system' | string;
  selectedText: string;
  selectedMarkdown?: string;
  selectionRange?: AiSelectionRangePayload;
  surroundingContext?: AiSelectionSurroundingPayload;
  codeContext?: AiSelectedCodeContextPayload;
  problemContext?: AiSelectedProblemContextPayload;
  uiIntent?: 'ask_about_selection' | 'explain_selection' | 'debug_selection' | 'optimize_selection' | 'continue_from_selection' | string;
}

export interface AiContestContextPayload {
  contestId?: EntityId | string;
  contestRunId?: EntityId | string;
  contestProblemId?: EntityId | string;
}

export interface AiSubmissionContextPayload {
  submissionId: EntityId | string;
  intent?: 'DEBUG' | 'EXPLAIN_ERROR' | 'OPTIMIZE' | 'COMPARE_WITH_CURRENT_CODE' | string;
  userSelected?: boolean;
  note?: string;
}

export interface AiChatPayload {
  conversationId?: string;
  problemId?: EntityId;
  clientMessageId?: string;
  message: string;
  mode?: AiAssistMode;
  problemContext?: AiProblemContextPayload;
  codeContext?: AiCodeContextPayload;
  clarificationAnswer?: AiClarificationAnswerPayload;
  selectionContext?: AiSelectionContextPayload;
  contestContext?: AiContestContextPayload;
  submissionContext?: AiSubmissionContextPayload;
}

export interface AiProblemContextSummary {
  problemId?: EntityId | string;
  title?: string;
  difficulty?: Difficulty | string;
  tags?: string[];
  constraints?: string[];
  statementSummary?: string;
  notesSummary?: string;
  timeLimitMillis?: number;
  memoryLimitKb?: number;
  source?: string;
}

export interface AiSubmissionCaseContextSummary {
  caseIndex?: number;
  caseName?: string;
  status?: string;
  score?: number | null;
  maxScore?: number | null;
  timeMillis?: number | null;
  memoryKb?: number | null;
  message?: string | null;
}

export interface AiSubmissionContextSummary {
  submissionId?: EntityId | string;
  problemId?: EntityId | string;
  intent?: string;
  userSelected?: boolean;
  note?: string;
  scope?: string;
  contestActive?: boolean;
  language?: string;
  status?: string;
  judgeMessage?: string;
  exitStatus?: number | null;
  runTimeMillis?: number | null;
  memoryKb?: number | null;
  score?: number | null;
  maxScore?: number | null;
  codeAllowedToModel?: boolean;
  codeHash?: string;
  policyMessage?: string;
  caseResults?: AiSubmissionCaseContextSummary[];
  submittedAt?: string;
  judgedAt?: string;
  source?: string;
}

export interface AiRenderHints {
  showProblemContext?: 'compact' | 'none' | string;
  problemRefs?: string[];
  [key: string]: unknown;
}

export interface AiContextSection {
  id: string;
  type: string;
  title: string;
  priority: number;
  source: string;
  sensitivity: string;
  estimatedTokens: number;
  required: boolean;
  contentPreview?: string;
  metadata?: Record<string, unknown>;
}

export interface AiContextBudgetReport {
  model: string;
  modelWindowTokens: number;
  compressionThresholdTokens: number;
  maxPromptBudgetTokens: number;
  estimatedPromptTokensBefore: number;
  estimatedPromptTokensAfter: number;
  compressionApplied: boolean;
  trimmedSections: string[];
  droppedSections: string[];
  estimatedBySection: Record<string, number>;
  warnings: string[];
}

export interface AiContextBuildReport {
  sections: AiContextSection[];
  sourceSummary: Record<string, number>;
  totalEstimatedTokens: number;
  requiredEstimatedTokens: number;
  optionalEstimatedTokens: number;
  requiredSectionCount: number;
  optionalSectionCount: number;
  budget?: AiContextBudgetReport;
}

export interface AiAssistantMessageEvent {
  messageId?: EntityId | string;
  assistantMessageId?: EntityId | string;
  userMessageId?: EntityId | string;
  conversationId?: string;
  clientMessageId?: string;
  requestClientMessageId?: string;
  contentMarkdown: string;
  parseWarnings?: string[];
  renderHints?: AiRenderHints;
  problemContext?: AiProblemContextSummary;
  submissionContext?: AiSubmissionContextSummary;
}

export interface AiStreamDoneEvent {
  conversationId?: string;
  conversationMode?: string | null;
  clientMessageId?: string;
  assistantClientMessageId?: string;
  userMessageId?: EntityId | string;
  assistantMessageId?: EntityId | string;
  turnId?: string;
}

export interface AiStreamContextEvent {
  userMemory?: string;
  conversationSummary?: string;
  currentProblems?: string;
  retrievedHistory?: string;
  conversationContextPack?: string;
  renderHints?: AiRenderHints;
  problemContext?: AiProblemContextSummary;
  submissionContext?: AiSubmissionContextSummary;
  contextBuildReport?: AiContextBuildReport;
}

export interface AiLearningProfileEvidenceResponse {
  id: EntityId;
  profileId: EntityId;
  evidenceType: string;
  sourceType: string;
  sourceId?: EntityId | string | null;
  summary: string;
  confidence?: number | null;
  createdAt?: string | null;
}

export interface AiLearningProfileResponse {
  id: EntityId;
  category: string;
  key: string;
  label: string;
  state: string;
  confidence?: number | null;
  evidenceCount?: number | null;
  lastEvidenceAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  evidence?: AiLearningProfileEvidenceResponse[];
}

export interface AiLearningProfileUpdatePayload {
  state?: string;
  label?: string;
  note?: string;
}

export interface AiConversationContextDebugResponse {
  conversationId: string;
  userId: string;
  state: Record<string, unknown>;
  recentMessages: Array<{ id: string; role: string; contentPreview: string; createdAt?: string }>;
  pendingClarifications: Array<Record<string, unknown>>;
  answeredClarifications: Array<Record<string, unknown>>;
  summarySegments: Array<Record<string, unknown>>;
  contextPackPreview: string;
  sections: AiContextSection[];
  sourceSummary: Record<string, number>;
  contextBuildReport: AiContextBuildReport;
  tokenEstimate: {
    recentMessages: number;
    state: number;
    summaries: number;
    longTermMemories: number;
    total: number;
  };
  warnings: Array<{ level: string; code: string; message: string }>;
}

export interface ProblemDraftResponse {
  id: EntityId;
  status: "PENDING_REVIEW" | "APPROVED" | "REJECTED" | string;
  title: string;
  difficulty: Difficulty | string;
  statement: string;
  notes?: string | null;
  standardSolutionLanguage?: string | null;
  standardSolutionCode?: string | null;
  referenceSolutionLanguage?: string | null;
  referenceSolutionCode?: string | null;
  testcaseGeneratorPython?: string | null;
  stressTestcaseGeneratorPython?: string | null;
  generationPlan?: string | null;
  tags: string[];
  validationStatus: string;
  validationErrors: string[];
  testCases: TestCaseDto[];
  timeLimitMillis: number;
  memoryLimitKb: number;
  importedProblemId?: EntityId | null;
  model: string;
  promptTokens: number;
  completionTokens: number;
  createdAt: string;
  archivedAt?: string | null;
  deletedAt?: string | null;
  deletedBy?: EntityId | null;
  refinedFromDraftId?: EntityId | null;
  refineNote?: string | null;
  verificationStatus?: string | null;
  verificationReportJson?: string | null;
  repairAttemptCount?: number | null;
  lastRepairReason?: string | null;
}

export interface ProblemDraftRefinePayload {
  title?: string;
  difficulty?: string;
  statement?: string;
  notes?: string;
  standardSolutionLanguage?: string;
  standardSolutionCode?: string;
  referenceSolutionLanguage?: string;
  referenceSolutionCode?: string;
  testcaseGeneratorPython?: string;
  stressTestcaseGeneratorPython?: string;
  generationPlan?: string;
  tags?: string[];
  testCases?: TestCaseDto[];
  timeLimitMillis?: number;
  memoryLimitKb?: number;
  refineNote?: string;
}

export interface ProblemDraftGeneratePayload {
  topic: string;
  difficulty?: string;
  cfRating?: number;
  teachingGoal?: string;
  algorithm?: string;
  tags?: string[];
  scenario?: string;
  inputOutputSpec?: string;
  dataConstraints?: string;
  qualityRequirements?: string;
  standardSolutionLanguage?: string;
  problemInfoRequirement?: string;
  statementRequirement?: string;
  testcaseRequirement?: string;
  targetHiddenCaseCount?: number;
  solutionRequirement?: string;
  explanationRequirement?: string;
  enableAutoRepair?: boolean;
  enableReferenceCheck?: boolean;
}

export type ProblemDraftGenerationJobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

export interface ProblemDraftGenerationJobResponse {
  id: EntityId;
  creatorUserId: EntityId;
  jobType?: "GENERATE" | "REGENERATE" | string | null;
  sourceDraftId?: EntityId | null;
  status: ProblemDraftGenerationJobStatus | string;
  stage: string;
  topicSnapshot?: string | null;
  progressCurrent: number;
  progressTotal: number;
  progressMessage?: string | null;
  draftId?: EntityId | null;
  errorCode?: number | null;
  errorKey?: string | null;
  errorMessage?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export type ContestInvitationBatchItemStatus =
  | 'SAVED_FOR_PUBLISH'
  | 'QUEUED_FOR_NOTIFICATION'
  | 'UNCHANGED'
  | 'FAILED';

export interface ContestInvitationBatchPayload {
  userIds: EntityId[];
}

export interface ContestInvitationBatchResultItem {
  userId: EntityId;
  account?: string | null;
  displayName?: string | null;
  status: ContestInvitationBatchItemStatus;
  message?: string | null;
}

export interface ContestInvitationBatchResponse {
  requested: number;
  succeeded: number;
  failed: number;
  results: ContestInvitationBatchResultItem[];
}

export type ProblemDraftGenerateStreamEvent = 'meta' | 'heartbeat' | 'draft' | 'error' | 'done' | string;

export interface ProblemDraftGenerateStreamOptions {
  signal?: AbortSignal;
  onEvent?: (event: ProblemDraftGenerateStreamEvent, data: string) => void;
}

export interface ProblemDraftGenerateStreamErrorPayload {
  code?: number;
  message?: string;
  errorKey?: string | null;
  elapsedMillis?: number;
}

export class ProblemDraftGenerateStreamError extends Error {
  readonly code: number | null;
  readonly errorKey: string | null;
  readonly elapsedMillis: number | null;

  constructor(payload: ProblemDraftGenerateStreamErrorPayload) {
    super(payload.message?.trim() || 'Problem draft generation failed');
    this.name = 'ProblemDraftGenerateStreamError';
    this.code = typeof payload.code === 'number' && Number.isFinite(payload.code) ? payload.code : null;
    this.errorKey = payload.errorKey ?? null;
    this.elapsedMillis = typeof payload.elapsedMillis === 'number' && Number.isFinite(payload.elapsedMillis) ? payload.elapsedMillis : null;
    Object.setPrototypeOf(this, ProblemDraftGenerateStreamError.prototype);
  }
}

export type ProblemListSort = 'NEWEST' | 'OLDEST' | 'DIFFICULTY_ASC' | 'DIFFICULTY_DESC';

export interface ProblemListParams {
  page?: number;
  pageSize?: number;
  keyword?: string;
  difficulty?: Difficulty | '';
  tag?: string;
  status?: '' | 'ACTIVE' | 'ARCHIVED' | 'ALL';
  visibility?: '' | 'PUBLIC' | 'PRIVATE' | 'ALL';
  sort?: ProblemListSort | '';
}

export type SubmissionScope = 'PRACTICE' | 'CONTEST';

export interface SubmissionListParams {
  page?: number;
  pageSize?: number;
  problemId?: EntityId;
  userId?: EntityId;
  contestId?: EntityId;
  contestRunId?: EntityId;
  contestProblemId?: EntityId;
  status?: SubmissionStatus | '';
  language?: string;
  mine?: boolean;
  scope?: SubmissionScope;
}

export interface SubmissionPayload {
  problemId: EntityId;
  language: string;
  code: string;
  contestId?: EntityId;
  contestRunId?: EntityId;
  contestProblemId?: EntityId;
}

export type AuthExpiredReason = 'unauthorized' | 'refresh_failed';
export type AuthChangeReason = 'save' | 'refresh' | 'clear';
export type AuthChangedDetail = { reason: AuthChangeReason; sessionId: string | null; revision: number };
export type AuthExpiredDetail = { reason: AuthExpiredReason };

const AUTH_EXPIRED_CODE = 40100;
const AUTH_FORBIDDEN_CODE = 40300;
const TOKEN_KEY = 'aioj.accessToken';
const REFRESH_KEY = 'aioj.refreshToken';
const USER_KEY = 'aioj.user';
const SESSION_KEY = 'aioj.sessionId';
const REVISION_KEY = 'aioj.authRevision';
const REFRESH_LOCK_KEY = 'aioj.refreshLock';
const REFRESH_LOCK_TTL_MS = 8_000;
export const AUTH_STORAGE_KEYS = {
  accessToken: TOKEN_KEY,
  refreshToken: REFRESH_KEY,
  user: USER_KEY,
  sessionId: SESSION_KEY,
  revision: REVISION_KEY
} as const;

type AuthSnapshot = {
  accessToken: string | null;
  refreshToken: string | null;
  sessionId: string | null;
  revision: number;
};

type RefreshLock = {
  owner: string;
  sessionId: string | null;
  startedAt: number;
};

const TAB_ID = createSessionId();

export class SessionChangedError extends Error {
  constructor() {
    super('Authentication session changed while the request was running');
    this.name = 'SessionChangedError';
    Object.setPrototypeOf(this, SessionChangedError.prototype);
  }
}

export function isSessionChangedError(error: unknown) {
  return error instanceof SessionChangedError;
}

export const apiBaseUrl = () => import.meta.env.VITE_API_BASE_URL || 'http://localhost:8101';

export function apiUrl(path: string) {
  if (/^https?:\/\//i.test(path)) {
    return path;
  }
  const base = apiBaseUrl().trim();
  if (!base || base === '/') {
    return path.startsWith('/') ? path : `/${path}`;
  }
  const normalizedBase = base.replace(/\/+$/, '');
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  if (normalizedBase.endsWith('/api') && normalizedPath.startsWith('/api/')) {
    return `${normalizedBase.slice(0, -4)}${normalizedPath}`;
  }
  return `${normalizedBase}${normalizedPath}`;
}

export const authStore = {
  get accessToken() {
    return localStorage.getItem(TOKEN_KEY);
  },
  get refreshToken() {
    return localStorage.getItem(REFRESH_KEY);
  },
  get user(): TokenResponse | null {
    const raw = localStorage.getItem(USER_KEY);
    if (!raw) return null;
    try {
      const parsed = JSON.parse(raw) as unknown;
      if (isTokenResponse(parsed)) return parsed;
      localStorage.removeItem(USER_KEY);
      return null;
    } catch {
      localStorage.removeItem(USER_KEY);
      return null;
    }
  },
  save(tokens: TokenResponse, reason: Extract<AuthChangeReason, 'save' | 'refresh'> = 'save') {
    const sessionId = reason === 'refresh'
      ? localStorage.getItem(SESSION_KEY) || createSessionId()
      : createSessionId();
    const revision = nextAuthRevision();
    localStorage.setItem(TOKEN_KEY, tokens.accessToken);
    localStorage.setItem(REFRESH_KEY, tokens.refreshToken);
    localStorage.setItem(USER_KEY, JSON.stringify(tokens));
    localStorage.setItem(SESSION_KEY, sessionId);
    localStorage.setItem(REVISION_KEY, String(revision));
    notifyAuthChanged(reason);
  },
  clear() {
    const hadSession = this.hasSession();
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_KEY);
    localStorage.removeItem(USER_KEY);
    localStorage.removeItem(SESSION_KEY);
    localStorage.setItem(REVISION_KEY, String(nextAuthRevision()));
    if (hadSession) notifyAuthChanged('clear');
  },
  get sessionId() {
    return localStorage.getItem(SESSION_KEY);
  },
  get revision() {
    return currentAuthRevision();
  },
  snapshot(): AuthSnapshot {
    return {
      accessToken: localStorage.getItem(TOKEN_KEY),
      refreshToken: localStorage.getItem(REFRESH_KEY),
      sessionId: localStorage.getItem(SESSION_KEY),
      revision: currentAuthRevision()
    };
  },
  hasSession() {
    return Boolean(localStorage.getItem(TOKEN_KEY) || localStorage.getItem(REFRESH_KEY) || localStorage.getItem(USER_KEY));
  }
};

export function isAuthStorageKey(key: string | null) {
  return !key || key === TOKEN_KEY || key === REFRESH_KEY || key === USER_KEY || key === SESSION_KEY || key === REVISION_KEY;
}

function notifyAuthChanged(reason: AuthChangeReason) {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<AuthChangedDetail>('aioj:auth-changed', {
    detail: { reason, sessionId: authStore.sessionId, revision: authStore.revision }
  }));
}

function notifyAuthExpired(reason: AuthExpiredReason) {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<AuthExpiredDetail>('aioj:auth-expired', { detail: { reason } }));
}

function expireAuth(reason: AuthExpiredReason, expected?: AuthSnapshot) {
  if (expected && !isAuthSnapshotStillCurrent(expected)) return false;
  if (!authStore.hasSession()) return false;
  authStore.clear();
  notifyAuthExpired(reason);
  return true;
}

function createSessionId() {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

function currentAuthRevision() {
  const parsed = Number(localStorage.getItem(REVISION_KEY) || '0');
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : 0;
}

function nextAuthRevision() {
  return currentAuthRevision() + 1;
}

function isAuthSnapshotStillCurrent(snapshot: AuthSnapshot) {
  return authStore.accessToken === snapshot.accessToken &&
    authStore.refreshToken === snapshot.refreshToken &&
    authStore.sessionId === snapshot.sessionId;
}

function isSameSession(snapshot: AuthSnapshot) {
  return authStore.sessionId === snapshot.sessionId;
}

function assertRequestSessionCurrent(snapshot: AuthSnapshot, sessionBound: boolean) {
  if (!sessionBound) return;
  if (!isSameSession(snapshot)) {
    throw new SessionChangedError();
  }
}

function queryString(params: Record<string, unknown> = {}) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      if (Array.isArray(value)) {
        value.filter((item) => item !== undefined && item !== null && item !== '').forEach((item) => {
          search.append(key, String(item));
        });
      } else {
        search.set(key, String(value));
      }
    }
  });
  const text = search.toString();
  return text ? `?${text}` : '';
}

function preserveLargeIntegerIds(text: string) {
  return text.replace(/("(?:(?:[A-Za-z0-9_]*Id)|id)"\s*:\s*)(-?\d{16,})/g, '$1"$2"');
}

async function parseResponse<T>(response: Response): Promise<T> {
  const text = await response.text();
  let payload: ApiResponse<T> | null = null;
  if (text) {
    try {
      payload = JSON.parse(preserveLargeIntegerIds(text)) as ApiResponse<T>;
    } catch {
      payload = null;
    }
  }
  if (!response.ok || !payload || payload.code !== 0) {
    const code = payload?.code ?? response.status * 100;
    const message = payload?.message || `Request failed: ${response.status}`;
    const details = payload?.details && typeof payload.details === 'object' ? payload.details as ApiErrorDetails : null;
    const traceId = payload?.traceId ?? null;
    const errorKey = payload?.errorKey ?? null;
    const errorParams = payload?.errorParams && typeof payload.errorParams === 'object' ? payload.errorParams : null;
    throw new ApiError(code, message, details, traceId, errorKey, errorParams);
  }
  return payload.data;
}

async function parseBinaryResponse(response: Response, fallbackFileName = 'download.bin'): Promise<BinaryFileResponse> {
  const contentType = response.headers.get('Content-Type') || 'application/octet-stream';
  const fileName = fileNameFromDisposition(response.headers.get('Content-Disposition')) || fallbackFileName;
  return {
    blob: await response.blob(),
    fileName,
    contentType
  };
}

function fileNameFromDisposition(disposition: string | null): string | null {
  if (!disposition) return null;
  const encodedMatch = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (encodedMatch?.[1]) {
    try {
      return decodeURIComponent(encodedMatch[1].trim().replace(/^"|"$/g, ''));
    } catch {
      return encodedMatch[1].trim().replace(/^"|"$/g, '');
    }
  }
  const plainMatch = /filename="?([^";]+)"?/i.exec(disposition);
  return plainMatch?.[1]?.trim() || null;
}

export function isAuthenticationError(error: unknown) {
  if (!(error instanceof ApiError)) return false;
  if (error.errorKey === 'auth.required' ||
    error.errorKey === 'auth.tokenRequired' ||
    error.errorKey === 'auth.accessTokenRequired' ||
    error.errorKey === 'auth.invalidToken' ||
    error.errorKey === 'auth.refreshTokenInvalid' ||
    error.errorKey === 'auth.accountDisabled') {
    return true;
  }
  if (error.errorKey) return false;
  return error.code === AUTH_EXPIRED_CODE || Math.trunc(error.code / 100) === 401;
}

export function isPermissionError(error: unknown) {
  if (!(error instanceof ApiError)) return false;
  if (error.errorKey === 'auth.forbidden') return true;
  if (error.errorKey?.endsWith('.forbidden') || error.errorKey?.endsWith('.manageForbidden')) return true;
  return error.code === AUTH_FORBIDDEN_CODE || Math.trunc(error.code / 100) === 403;
}

function shouldHandleAuthenticationForPath(path: string) {
  return !path.startsWith('/api/v1/auth/login') &&
    !path.startsWith('/api/v1/auth/register') &&
    !path.startsWith('/api/v1/auth/refresh') &&
    !path.startsWith('/api/v1/auth/logout');
}

function canRefreshForPath(path: string) {
  return shouldHandleAuthenticationForPath(path);
}

function shouldAttachAuthorization(path: string) {
  return !path.startsWith('/api/v1/auth/login') &&
    !path.startsWith('/api/v1/auth/register') &&
    !path.startsWith('/api/v1/auth/refresh') &&
    !path.startsWith('/api/v1/auth/logout');
}

export function isAuthBoundaryError(error: unknown) {
  if (isSessionChangedError(error)) return true;
  return isAuthenticationError(error) || isPermissionError(error);
}

function parseRefreshLock(): RefreshLock | null {
  const raw = localStorage.getItem(REFRESH_LOCK_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<RefreshLock>;
    if (typeof parsed.owner === 'string' && typeof parsed.startedAt === 'number') {
      return { owner: parsed.owner, startedAt: parsed.startedAt, sessionId: parsed.sessionId ?? null };
    }
  } catch {
    // Ignore corrupt coordination state and let the current tab repair it.
  }
  localStorage.removeItem(REFRESH_LOCK_KEY);
  return null;
}

function tryAcquireRefreshLock(owner: string, snapshot: AuthSnapshot) {
  const now = Date.now();
  const existing = parseRefreshLock();
  if (existing && now - existing.startedAt < REFRESH_LOCK_TTL_MS) return false;
  localStorage.setItem(REFRESH_LOCK_KEY, JSON.stringify({ owner, startedAt: now, sessionId: snapshot.sessionId }));
  return parseRefreshLock()?.owner === owner;
}

function releaseRefreshLock(owner: string) {
  if (parseRefreshLock()?.owner === owner) {
    localStorage.removeItem(REFRESH_LOCK_KEY);
  }
}

function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function hasUsableTokenFromAnotherRefresh(snapshot: AuthSnapshot) {
  return Boolean(snapshot.sessionId &&
    authStore.sessionId === snapshot.sessionId &&
    authStore.accessToken &&
    authStore.accessToken !== snapshot.accessToken &&
    authStore.refreshToken &&
    authStore.refreshToken !== snapshot.refreshToken);
}

async function waitForRefreshFromAnotherTab(snapshot: AuthSnapshot): Promise<TokenResponse | null> {
  const deadline = Date.now() + REFRESH_LOCK_TTL_MS + 1_000;
  while (Date.now() < deadline) {
    if (!isSameSession(snapshot)) throw new SessionChangedError();
    if (hasUsableTokenFromAnotherRefresh(snapshot)) return authStore.user;
    const lock = parseRefreshLock();
    if (!lock || Date.now() - lock.startedAt >= REFRESH_LOCK_TTL_MS) break;
    await delay(80);
  }
  return hasUsableTokenFromAnotherRefresh(snapshot) ? authStore.user : null;
}

async function performRefresh(snapshot: AuthSnapshot): Promise<TokenResponse> {
  if (!snapshot.refreshToken) {
    throw new ApiError(AUTH_EXPIRED_CODE, 'Login expired', null, null, 'auth.refreshTokenInvalid');
  }
  if (!isAuthSnapshotStillCurrent(snapshot)) {
    if (hasUsableTokenFromAnotherRefresh(snapshot)) {
      const tokens = authStore.user;
      if (tokens) return tokens;
    }
    throw new SessionChangedError();
  }
  const response = await fetch(apiUrl('/api/v1/auth/refresh'), {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${snapshot.refreshToken}`,
      'Content-Type': 'application/json'
    }
  });
  const tokens = await parseResponse<TokenResponse>(response);
  authStore.save(tokens, 'refresh');
  return tokens;
}

async function coordinatedRefresh(snapshot: AuthSnapshot): Promise<TokenResponse> {
  if (hasUsableTokenFromAnotherRefresh(snapshot)) {
    const tokens = authStore.user;
    if (tokens) return tokens;
  }
  const owner = `${TAB_ID}:${Date.now()}`;
  if (!tryAcquireRefreshLock(owner, snapshot)) {
    const updated = await waitForRefreshFromAnotherTab(snapshot);
    if (updated) return updated;
    if (!tryAcquireRefreshLock(owner, snapshot)) {
      const retryUpdated = await waitForRefreshFromAnotherTab(snapshot);
      if (retryUpdated) return retryUpdated;
    }
  }
  try {
    return await performRefresh(snapshot);
  } finally {
    releaseRefreshLock(owner);
  }
}

let refreshPromise: Promise<TokenResponse> | null = null;

async function refreshAccessToken() {
  const snapshot = authStore.snapshot();
  if (!snapshot.refreshToken) {
    throw new ApiError(AUTH_EXPIRED_CODE, 'Login expired', null, null, 'auth.refreshTokenInvalid');
  }
  if (!refreshPromise) {
    refreshPromise = coordinatedRefresh(snapshot).finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

async function refreshAccessTokenIfStillCurrent(snapshot: AuthSnapshot) {
  if (!isSameSession(snapshot)) throw new SessionChangedError();
  if (hasUsableTokenFromAnotherRefresh(snapshot)) return;
  try {
    await refreshAccessToken();
  } catch (error) {
    const updated = await waitForRefreshFromAnotherTab(snapshot);
    if (updated) return;
    if (!isAuthSnapshotStillCurrent(snapshot)) {
      if (isSameSession(snapshot) && authStore.accessToken) return;
      throw new SessionChangedError();
    }
    throw error;
  }
}

async function retryAfterAuthenticationError<T>(
  path: string,
  retry: boolean,
  snapshot: AuthSnapshot,
  error: unknown,
  retryRequest: () => Promise<T>
): Promise<T | null> {
  if (!(error instanceof ApiError) || !isAuthenticationError(error) || !shouldHandleAuthenticationForPath(path)) {
    return null;
  }
  if (retry && authStore.refreshToken && canRefreshForPath(path)) {
    try {
      await refreshAccessTokenIfStillCurrent(snapshot);
      return retryRequest();
    } catch (refreshError) {
      if (!isAuthSnapshotStillCurrent(snapshot)) {
        assertRequestSessionCurrent(snapshot, true);
        return retryRequest();
      }
      expireAuth('refresh_failed', snapshot);
      throw refreshError;
    }
  }
  expireAuth('unauthorized', snapshot);
  return null;
}


async function request<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const headers = new Headers(init.headers);
  const snapshot = authStore.snapshot();
  const accessToken = snapshot.accessToken;
  const attachAuthorization = shouldAttachAuthorization(path);
  const sentAuthorization = Boolean(accessToken && attachAuthorization);
  if (!headers.has('Content-Type') && init.body !== undefined) {
    headers.set('Content-Type', 'application/json');
  }
  if (accessToken && attachAuthorization) {
    headers.set('Authorization', `Bearer ${accessToken}`);
  }
  const response = await fetch(apiUrl(path), { ...init, headers });
  try {
    const data = await parseResponse<T>(response);
    assertRequestSessionCurrent(snapshot, sentAuthorization);
    return data;
  } catch (error) {
    const retried = await retryAfterAuthenticationError(path, retry, snapshot, error, () => request<T>(path, init, false));
    if (retried !== null) {
      return retried;
    }
    throw error;
  }
}

async function requestForm<T>(path: string, formData: FormData, retry = true): Promise<T> {
  const headers = new Headers();
  const snapshot = authStore.snapshot();
  const accessToken = snapshot.accessToken;
  const attachAuthorization = shouldAttachAuthorization(path);
  const sentAuthorization = Boolean(accessToken && attachAuthorization);
  if (accessToken && attachAuthorization) {
    headers.set('Authorization', `Bearer ${accessToken}`);
  }
  const response = await fetch(apiUrl(path), { method: 'POST', headers, body: formData });
  try {
    const data = await parseResponse<T>(response);
    assertRequestSessionCurrent(snapshot, sentAuthorization);
    return data;
  } catch (error) {
    const retried = await retryAfterAuthenticationError(path, retry, snapshot, error, () => requestForm<T>(path, formData, false));
    if (retried !== null) {
      return retried;
    }
    throw error;
  }
}

async function requestBinary(path: string, init: RequestInit = {}, fallbackFileName = 'download.bin', retry = true): Promise<BinaryFileResponse> {
  const headers = new Headers(init.headers);
  const snapshot = authStore.snapshot();
  const accessToken = snapshot.accessToken;
  const attachAuthorization = shouldAttachAuthorization(path);
  const sentAuthorization = Boolean(accessToken && attachAuthorization);
  if (accessToken && attachAuthorization) {
    headers.set('Authorization', `Bearer ${accessToken}`);
  }
  const response = await fetch(apiUrl(path), { ...init, headers });
  if (response.ok) {
    const data = await parseBinaryResponse(response, fallbackFileName);
    assertRequestSessionCurrent(snapshot, sentAuthorization);
    return data;
  }
  try {
    await parseResponse<never>(response);
  } catch (error) {
    const retried = await retryAfterAuthenticationError(path, retry, snapshot, error, () => requestBinary(path, init, fallbackFileName, false));
    if (retried !== null) {
      return retried;
    }
    throw error;
  }
  throw new ApiError(response.status * 100, `Request failed: ${response.status}`);
}

async function logoutCurrentSession() {
  const refreshToken = authStore.refreshToken;
  try {
    if (!refreshToken) return true;
    const response = await fetch(apiUrl('/api/v1/auth/logout'), {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${refreshToken}`,
        'Content-Type': 'application/json'
      },
      body: '{}'
    });
    return await parseResponse<boolean>(response);
  } finally {
    authStore.clear();
  }
}

export const api = {
  login: (account: string, password: string) =>
    request<TokenResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ account, password })
    }),
  register: (payload: { account: string; password: string; displayName: string; email?: string; role?: 'STUDENT' }) =>
    request<TokenResponse>('/api/v1/auth/register', { method: 'POST', body: JSON.stringify(payload) }),
  refresh: () => refreshAccessToken(),
  logout: () => logoutCurrentSession(),
  me: () => request<UserProfileResponse>('/api/v1/users/me'),
  updateMe: (payload: { displayName: string; email?: string }) =>
    request<UserProfileResponse>('/api/v1/users/me', { method: 'PUT', body: JSON.stringify(payload) }),
  changePassword: (payload: { currentPassword: string; newPassword: string }) =>
    request<TokenResponse>('/api/v1/users/me/password', { method: 'PUT', body: JSON.stringify(payload) }),

  roles: () => request<RoleResponse[]>('/api/v1/admin/roles'),
  roleCapabilities: () => request<RoleCapabilityResponse[]>('/api/v1/admin/roles/capabilities'),
  users: (params: { page?: number; pageSize?: number; keyword?: string; search?: string; role?: Role | ''; enabled?: boolean | ''; lifecycle?: AdminUserLifecycle | '' } = {}) =>
    request<PageResponse<AdminUserResponse>>(`/api/v1/admin/users${queryString({
      page: 1,
      pageSize: 20,
      ...params,
      search: params.search || params.keyword,
      keyword: undefined
    })}`),
  createUser: (payload: { account: string; password: string; displayName: string; email?: string; roles: Role[]; enabled?: boolean; passwordResetRequired?: boolean }) =>
    request<AdminUserResponse>('/api/v1/admin/users', { method: 'POST', body: JSON.stringify(payload) }),
  updateUser: (id: EntityId, payload: { displayName: string; email?: string; roles: Role[]; enabled?: boolean; passwordResetRequired?: boolean }) =>
    request<AdminUserResponse>(`/api/v1/admin/users/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deleteUser: (id: EntityId) => request<boolean>(`/api/v1/admin/users/${id}`, { method: 'DELETE' }),
  previewBatchUsers: (payload: AdminUserBatchPreviewRequest) =>
    request<AdminUserBatchPreviewResponse>('/api/v1/admin/users/batch/preview', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  createBatchUsers: (payload: { users: AdminUserBatchCandidate[]; password: AdminUserBatchPassword }) =>
    request<AdminUserBatchCreateResponse>('/api/v1/admin/users/batch', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  batchUserAction: (payload: { userIds: EntityId[]; action: AdminUserBatchAction; password?: AdminUserBatchPassword; passwordResetRequired?: boolean }) =>
    request<AdminUserBatchActionResponse>('/api/v1/admin/users/batch/actions', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  userActivityAnalytics: (days = 14) =>
    request<DailyUserActivityResponse[]>(`/api/v1/admin/users/analytics/activity${queryString({ days })}`),

  operationJobs: (params: { page?: number; pageSize?: number; status?: OperationJobStatus | ''; type?: OperationJobType | '' } = {}) =>
    request<PageResponse<OperationJobResponse>>(`/api/v1/admin/operation-jobs${queryString({ page: 1, pageSize: 20, ...params })}`),
  operationJob: (jobId: EntityId) =>
    request<OperationJobResponse>(`/api/v1/admin/operation-jobs/${jobId}`),
  contestOperationJob: (id: EntityId, jobId: EntityId) =>
    request<OperationJobResponse>(`/api/v1/contests/${id}/operation-jobs/${jobId}`),
  retryOperationJob: (jobId: EntityId) =>
    request<OperationJobResponse>(`/api/v1/admin/operation-jobs/${jobId}/retry`, {
      method: 'POST',
      body: '{}'
    }),
  operationJobArtifact: (jobId: EntityId) =>
    request<ContestExportResponse>(`/api/v1/admin/operation-jobs/${jobId}/artifact`),
  operationAuditEvents: (params: {
    page?: number;
    pageSize?: number;
    action?: string;
    resourceType?: string;
    contestId?: EntityId | '';
    contestRunId?: EntityId | '';
    actorUserId?: EntityId | '';
  } = {}) =>
    request<PageResponse<OperationAuditEventResponse>>(`/api/v1/admin/audit-events${queryString({ page: 1, pageSize: 20, ...params })}`),

  classes: (params: { keyword?: string; status?: LearningGroupStatus | '' } = {}) =>
    request<LearningGroupResponse[]>(`/api/v1/classes${queryString(params)}`),
  classDetail: (id: EntityId) => request<LearningGroupResponse>(`/api/v1/classes/${id}`),
  createClass: (payload: LearningGroupPayload) =>
    request<LearningGroupResponse>('/api/v1/classes', { method: 'POST', body: JSON.stringify(payload) }),
  updateClass: (id: EntityId, payload: Partial<LearningGroupPayload>) =>
    request<LearningGroupResponse>(`/api/v1/classes/${id}`, { method: 'PATCH', body: JSON.stringify(payload) }),
  archiveClass: (id: EntityId) =>
    request<LearningGroupResponse>(`/api/v1/classes/${id}/archive`, { method: 'POST', body: '{}' }),
  restoreClass: (id: EntityId) =>
    request<LearningGroupResponse>(`/api/v1/classes/${id}/restore`, { method: 'POST', body: '{}' }),
  deleteClass: (id: EntityId) =>
    request<void>(`/api/v1/classes/${id}`, { method: 'DELETE' }),
  classMembers: (id: EntityId) => request<LearningGroupMemberResponse[]>(`/api/v1/classes/${id}/members`),
  addClassMember: (id: EntityId, payload: LearningGroupMemberPayload) =>
    request<LearningGroupMemberResponse>(`/api/v1/classes/${id}/members`, { method: 'POST', body: JSON.stringify(payload) }),
  addClassMembers: (id: EntityId, payload: LearningGroupMemberBatchAddPayload) =>
    request<LearningGroupMemberBatchAddResponse>(`/api/v1/classes/${id}/members/batch`, { method: 'POST', body: JSON.stringify(payload) }),
  removeClassMember: (id: EntityId, userId: EntityId) =>
    request<boolean>(`/api/v1/classes/${id}/members/${userId}`, { method: 'DELETE' }),

  contests: (params: ContestListParams = {}) =>
    request<PageResponse<ContestResponse>>(`/api/v1/contests${queryString({ page: 1, pageSize: 20, ...params })}`),
  openContestRuns: (params: ContestOpenRunListParams = {}) =>
    request<PageResponse<ContestOpenRunResponse>>(`/api/v1/contests/open${queryString({ page: 1, pageSize: 20, ...params })}`),
  contest: (id: EntityId) => request<ContestResponse>(`/api/v1/contests/${id}`),
  createContest: (payload: ContestPayload) =>
    request<ContestResponse>('/api/v1/contests', { method: 'POST', body: JSON.stringify(payload) }),
  updateContest: (id: EntityId, payload: ContestUpdatePayload) =>
    request<ContestResponse>(`/api/v1/contests/${id}`, { method: 'PATCH', body: JSON.stringify(payload) }),
  confirmContest: (id: EntityId) =>
    request<ContestResponse>(`/api/v1/contests/${id}/confirm`, { method: 'POST', body: '{}' }),
  publishContest: (id: EntityId) =>
    request<ContestResponse>(`/api/v1/contests/${id}/publish`, { method: 'POST', body: '{}' }),
  archiveContest: (id: EntityId) =>
    request<ContestResponse>(`/api/v1/contests/${id}/archive`, { method: 'POST', body: '{}' }),
  restoreContest: (id: EntityId) =>
    request<ContestResponse>(`/api/v1/contests/${id}/restore`, { method: 'POST', body: '{}' }),
  deleteContest: (id: EntityId) =>
    request<ContestResponse>(`/api/v1/contests/${id}`, { method: 'DELETE' }),
  contestProblems: (id: EntityId) => request<ContestProblemResponse[]>(`/api/v1/contests/${id}/problems`),
  replaceContestProblems: (id: EntityId, problems: ContestProblemPayload[]) =>
    request<ContestProblemResponse[]>(`/api/v1/contests/${id}/problems`, {
      method: 'PUT',
      body: JSON.stringify({ problems })
    }),
  contestParticipants: (id: EntityId) => request<ContestParticipantResponse[]>(`/api/v1/contests/${id}/participants`),
  contestAiUsage: (contestId: EntityId, contestRunId?: EntityId | null) =>
    request<AdminContestAiUsageSummary[]>(
      `/api/v1/admin/ai/contests/${contestId}/usage${contestRunId ? `?contestRunId=${contestRunId}` : ''}`
    ),
  contestAiUsageConversations: (contestId: EntityId, userId: EntityId, contestRunId?: EntityId | null) =>
    request<AdminContestAiConversationSummary[]>(
      `/api/v1/admin/ai/contests/${contestId}/usage/${userId}/conversations${contestRunId ? `?contestRunId=${contestRunId}` : ''}`
    ),
  contestAiUsageMessages: (contestId: EntityId, userId: EntityId, conversationId: string) =>
    request<AdminContestAiMessageResponse[]>(
      `/api/v1/admin/ai/contests/${contestId}/usage/${userId}/conversations/${conversationId}/messages`
    ),
  contestAiAssistanceStatistics: (contestId: EntityId, contestRunId?: EntityId | null) =>
    request<AdminContestAiAssistanceSummary[]>(
      `/api/v1/admin/ai/contests/${contestId}/assistance-statistics${contestRunId ? `?contestRunId=${contestRunId}` : ''}`
    ),
  contestAiAssistanceConversations: (contestId: EntityId, userId: EntityId, contestRunId?: EntityId | null) =>
    request<AdminContestAiConversationSummary[]>(
      `/api/v1/admin/ai/contests/${contestId}/assistance-statistics/${userId}/conversations${contestRunId ? `?contestRunId=${contestRunId}` : ''}`
    ),
  contestAiAssistanceMessages: (
    contestId: EntityId,
    userId: EntityId,
    conversationId: string,
    contestRunId?: EntityId | null
  ) =>
    request<AdminContestAiMessageResponse[]>(
      `/api/v1/admin/ai/contests/${contestId}/assistance-statistics/${userId}/conversations/${conversationId}/messages${contestRunId ? `?contestRunId=${contestRunId}` : ''}`
    ),
  importContestParticipants: (id: EntityId) =>
    request<ContestParticipantResponse[]>(`/api/v1/contests/${id}/participants/import-from-group`, {
      method: 'POST',
      body: '{}'
    }),
  addContestParticipant: (id: EntityId, payload: { userId?: EntityId; account?: string }) =>
    request<ContestParticipantResponse>(`/api/v1/contests/${id}/participants`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  contestRuns: (id: EntityId, params: ContestRunListParams = {}) =>
    request<PageResponse<ContestRunResponse>>(`/api/v1/contests/${id}/runs${queryString({ page: 1, pageSize: 20, ...params })}`),
  contestRun: (id: EntityId, runId: EntityId) =>
    request<ContestRunResponse>(`/api/v1/contests/${id}/runs/${runId}`),
  openContestRun: (id: EntityId, runId: EntityId) =>
    request<ContestOpenRunResponse>(`/api/v1/contests/${id}/runs/${runId}/open`),
  createContestRun: (id: EntityId, payload: ContestRunPayload) =>
    request<ContestRunResponse>(`/api/v1/contests/${id}/runs`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  updateContestRun: (id: EntityId, runId: EntityId, payload: ContestRunUpdatePayload) =>
    request<ContestRunResponse>(`/api/v1/contests/${id}/runs/${runId}`, {
      method: 'PATCH',
      body: JSON.stringify(payload)
    }),
  publishContestRun: (id: EntityId, runId: EntityId) =>
    request<ContestRunResponse>(`/api/v1/contests/${id}/runs/${runId}/publish`, { method: 'POST', body: '{}' }),
  archiveContestRun: (id: EntityId, runId: EntityId, reason?: string) =>
    request<ContestRunResponse>(`/api/v1/contests/${id}/runs/${runId}/archive${queryString({ reason })}`, {
      method: 'POST',
      body: '{}'
    }),
  restoreContestRun: (id: EntityId, runId: EntityId) =>
    request<ContestRunResponse>(`/api/v1/contests/${id}/runs/${runId}/restore`, { method: 'POST', body: '{}' }),
  deleteContestRun: (id: EntityId, runId: EntityId) =>
    request<ContestRunResponse>(`/api/v1/contests/${id}/runs/${runId}`, { method: 'DELETE' }),
  contestRunProblems: (id: EntityId, runId: EntityId) =>
    request<ContestRunProblemSnapshotResponse[]>(`/api/v1/contests/${id}/runs/${runId}/problems`),
  contestAnnouncements: (id: EntityId, runId: EntityId, params: { includeArchived?: boolean } = {}) =>
    request<ContestAnnouncementResponse[]>(`/api/v1/contests/${id}/runs/${runId}/announcements${queryString(params)}`),
  createContestAnnouncement: (id: EntityId, runId: EntityId, payload: ContestAnnouncementPayload) =>
    request<ContestAnnouncementResponse>(`/api/v1/contests/${id}/runs/${runId}/announcements`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  updateContestAnnouncement: (id: EntityId, runId: EntityId, announcementId: EntityId, payload: ContestAnnouncementPayload) =>
    request<ContestAnnouncementResponse>(`/api/v1/contests/${id}/runs/${runId}/announcements/${announcementId}`, {
      method: 'PATCH',
      body: JSON.stringify(payload)
    }),
  archiveContestAnnouncement: (id: EntityId, runId: EntityId, announcementId: EntityId) =>
    request<ContestAnnouncementResponse>(`/api/v1/contests/${id}/runs/${runId}/announcements/${announcementId}/archive`, {
      method: 'POST',
      body: '{}'
    }),
  restoreContestAnnouncement: (id: EntityId, runId: EntityId, announcementId: EntityId) =>
    request<ContestAnnouncementResponse>(`/api/v1/contests/${id}/runs/${runId}/announcements/${announcementId}/restore`, {
      method: 'POST',
      body: '{}'
    }),
  contestClarifications: (id: EntityId, runId: EntityId, params: {
    page?: number;
    pageSize?: number;
    status?: ContestClarificationStatus | '';
    visibility?: ContestClarificationVisibility | '';
    contestProblemId?: EntityId | '';
    staffView?: boolean;
  } = {}) =>
    request<PageResponse<ContestClarificationResponse>>(`/api/v1/contests/${id}/runs/${runId}/clarifications${queryString({ page: 1, pageSize: 20, ...params })}`),
  createContestClarification: (id: EntityId, runId: EntityId, payload: ContestClarificationPayload) =>
    request<ContestClarificationResponse>(`/api/v1/contests/${id}/runs/${runId}/clarifications`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  replyContestClarification: (id: EntityId, runId: EntityId, clarificationId: EntityId, payload: ContestClarificationReplyPayload) =>
    request<ContestClarificationResponse>(`/api/v1/contests/${id}/runs/${runId}/clarifications/${clarificationId}/reply`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  closeContestClarification: (id: EntityId, runId: EntityId, clarificationId: EntityId) =>
    request<ContestClarificationResponse>(`/api/v1/contests/${id}/runs/${runId}/clarifications/${clarificationId}/close`, {
      method: 'POST',
      body: '{}'
    }),
  contestRunRegistrations: (id: EntityId, runId: EntityId, params: ContestRegistrationListParams = {}) =>
    request<PageResponse<ContestRegistrationResponse>>(`/api/v1/contests/${id}/runs/${runId}/registrations${queryString({ page: 1, pageSize: 20, ...params })}`),
  inviteContestRunRegistration: (id: EntityId, runId: EntityId, payload: { userId?: EntityId; account?: string }) =>
    request<ContestRegistrationResponse>(`/api/v1/contests/${id}/runs/${runId}/registrations/invite`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  inviteContestRunRegistrationsBatch: (id: EntityId, runId: EntityId, payload: ContestInvitationBatchPayload) =>
    request<ContestInvitationBatchResponse>(`/api/v1/contests/${id}/runs/${runId}/registrations/invite/batch`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  registerContestRun: (id: EntityId, runId: EntityId) =>
    request<ContestRegistrationResponse>(`/api/v1/contests/${id}/runs/${runId}/registrations`, { method: 'POST', body: '{}' }),
  cancelContestRunRegistration: (id: EntityId, runId: EntityId) =>
    request<ContestRegistrationResponse>(`/api/v1/contests/${id}/runs/${runId}/registrations/me`, { method: 'DELETE' }),
  myContestInvitations: (params: { page?: number; pageSize?: number } = {}) =>
    request<PageResponse<ContestRegistrationResponse>>(`/api/v1/contests/invitations/me${queryString({ page: 1, pageSize: 20, ...params })}`),
  userNotifications: (params: {
    type?: UserNotificationType;
    subjectType?: string;
    subjectId?: string;
    scopeType?: string;
    scopeId?: string;
    unreadOnly?: boolean;
    page?: number;
    pageSize?: number;
  } = {}) => request<PageResponse<UserNotificationResponse>>(
    `/api/v1/notifications${queryString({ page: 1, pageSize: 20, ...params })}`
  ),
  userNotificationUnreadCount: (type?: UserNotificationType) =>
    request<UserNotificationUnreadCountResponse>(`/api/v1/notifications/unread-count${queryString({ type })}`),
  markUserNotificationsRead: (payload: {
    type: UserNotificationType;
    subjectType?: string;
    subjectId?: string;
  }) => request<UserNotificationMarkReadResponse>('/api/v1/notifications/mark-read', {
    method: 'POST',
    body: JSON.stringify(payload)
  }),
  acceptContestInvitation: (id: EntityId, runId: EntityId) =>
    request<ContestRegistrationResponse>(`/api/v1/contests/${id}/runs/${runId}/registrations/invitation/accept`, { method: 'POST', body: '{}' }),
  declineContestInvitation: (id: EntityId, runId: EntityId) =>
    request<ContestRegistrationResponse>(`/api/v1/contests/${id}/runs/${runId}/registrations/invitation/decline`, { method: 'POST', body: '{}' }),
  approveContestRunRegistration: (id: EntityId, runId: EntityId, registrationId: EntityId) =>
    request<ContestRegistrationResponse>(`/api/v1/contests/${id}/runs/${runId}/registrations/${registrationId}/approve`, {
      method: 'POST',
      body: '{}'
    }),
  rejectContestRunRegistration: (id: EntityId, runId: EntityId, registrationId: EntityId, reason?: string) =>
    request<ContestRegistrationResponse>(`/api/v1/contests/${id}/runs/${runId}/registrations/${registrationId}/reject${queryString({ reason })}`, {
      method: 'POST',
      body: '{}'
    }),
  contestScoreboard: (id: EntityId, params: ContestScoreboardParams = {}) =>
    request<ContestScoreboardResponse>(`/api/v1/contests/${id}/scoreboard${queryString({ ...params })}`),
  exportContestScoreboard: (id: EntityId, params: ContestScoreboardExportParams = {}) =>
    request<ContestExportResponse>(`/api/v1/contests/${id}/scoreboard/export${queryString({ format: 'CSV', ...params })}`),
  createContestScoreboardExportJob: (id: EntityId, params: ContestScoreboardExportParams = {}) =>
    request<OperationJobResponse>(`/api/v1/contests/${id}/scoreboard/export-jobs${queryString({ format: 'CSV', ...params })}`, {
      method: 'POST',
      body: '{}'
    }),
  contestScoreboardSnapshots: (id: EntityId, params: { runId?: EntityId | '' } = {}) =>
    request<ContestScoreboardSnapshotResponse[]>(`/api/v1/contests/${id}/scoreboard/snapshots${queryString(params)}`),
  contestScoreboardSnapshot: (id: EntityId, snapshotId: EntityId) =>
    request<ContestScoreboardResponse>(`/api/v1/contests/${id}/scoreboard/snapshots/${snapshotId}`),
  createContestScoreboardSnapshot: (id: EntityId, payload: ContestScoreboardSnapshotPayload = {}, params: { runId?: EntityId | '' } = {}) =>
    request<ContestScoreboardResponse>(`/api/v1/contests/${id}/scoreboard/snapshots${queryString(params)}`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  contestScoreboardTimeline: (id: EntityId, runId: EntityId, params: { view?: ContestScoreboardView } = {}) =>
    request<ContestScoreboardTimelineResponse>(`/api/v1/contests/${id}/runs/${runId}/scoreboard/timeline${queryString(params)}`),
  unfreezePublicScoreboard: (id: EntityId, runId: EntityId) =>
    request<ContestRunResponse>(`/api/v1/contests/${id}/runs/${runId}/scoreboard/unfreeze-public`, { method: 'POST', body: '{}' }),
  refreezePublicScoreboard: (id: EntityId, runId: EntityId) =>
    request<ContestRunResponse>(`/api/v1/contests/${id}/runs/${runId}/scoreboard/refreeze-public`, { method: 'POST', body: '{}' }),
  createContestResolverSession: (id: EntityId, runId: EntityId, payload: { title?: string | null } = {}) =>
    request<ContestResolverSessionDetailResponse>(`/api/v1/contests/${id}/runs/${runId}/resolver-sessions`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  contestResolverSessions: (id: EntityId, runId: EntityId) =>
    request<ContestResolverSessionResponse[]>(`/api/v1/contests/${id}/runs/${runId}/resolver-sessions`),
  contestResolverSession: (id: EntityId, runId: EntityId, sessionId: EntityId) =>
    request<ContestResolverSessionDetailResponse>(`/api/v1/contests/${id}/runs/${runId}/resolver-sessions/${sessionId}`),
  publishContestResolverSession: (id: EntityId, runId: EntityId, sessionId: EntityId) =>
    request<ContestResolverSessionResponse>(`/api/v1/contests/${id}/runs/${runId}/resolver-sessions/${sessionId}/publish`, { method: 'POST', body: '{}' }),
  archiveContestResolverSession: (id: EntityId, runId: EntityId, sessionId: EntityId) =>
    request<ContestResolverSessionResponse>(`/api/v1/contests/${id}/runs/${runId}/resolver-sessions/${sessionId}/archive`, { method: 'POST', body: '{}' }),
  restoreContestResolverSession: (id: EntityId, runId: EntityId, sessionId: EntityId) =>
    request<ContestResolverSessionResponse>(`/api/v1/contests/${id}/runs/${runId}/resolver-sessions/${sessionId}/restore`, { method: 'POST', body: '{}' }),
  deleteContestResolverSession: (id: EntityId, runId: EntityId, sessionId: EntityId) =>
    request<ContestResolverSessionResponse>(`/api/v1/contests/${id}/runs/${runId}/resolver-sessions/${sessionId}`, { method: 'DELETE' }),
  createContestPostmortemReport: (id: EntityId, runId: EntityId) =>
    request<ContestPostmortemReportResponse>(`/api/v1/contests/${id}/runs/${runId}/postmortem-reports`, { method: 'POST', body: '{}' }),
  createContestPostmortemOperationJob: (id: EntityId, runId: EntityId) =>
    request<OperationJobResponse>(`/api/v1/contests/${id}/runs/${runId}/postmortem-reports/operation-jobs`, { method: 'POST', body: '{}' }),
  contestPostmortemReports: (id: EntityId, runId: EntityId, params: { page?: number; pageSize?: number } = {}) =>
    request<PageResponse<ContestPostmortemReportResponse>>(`/api/v1/contests/${id}/runs/${runId}/postmortem-reports${queryString({ page: 1, pageSize: 20, ...params })}`),
  contestPostmortemReport: (id: EntityId, runId: EntityId, reportId: EntityId) =>
    request<ContestPostmortemReportResponse>(`/api/v1/contests/${id}/runs/${runId}/postmortem-reports/${reportId}`),
  retryContestPostmortemAi: (id: EntityId, runId: EntityId, reportId: EntityId) =>
    request<ContestPostmortemReportResponse>(`/api/v1/contests/${id}/runs/${runId}/postmortem-reports/${reportId}/retry-ai`, { method: 'POST', body: '{}' }),
  myContestStudentPostmortemReports: (id: EntityId, runId: EntityId, params: { page?: number; pageSize?: number } = {}) =>
    request<PageResponse<ContestStudentPostmortemReportResponse>>(`/api/v1/contests/${id}/runs/${runId}/student-postmortem-reports${queryString({ page: 1, pageSize: 20, ...params })}`),
  createMyContestStudentPostmortemReport: (id: EntityId, runId: EntityId) =>
    request<ContestStudentPostmortemReportResponse>(`/api/v1/contests/${id}/runs/${runId}/student-postmortem-reports`, { method: 'POST', body: '{}' }),
  createMyContestStudentPostmortemOperationJob: (id: EntityId, runId: EntityId) =>
    request<OperationJobResponse>(`/api/v1/contests/${id}/runs/${runId}/student-postmortem-reports/operation-jobs`, { method: 'POST', body: '{}' }),
  myActiveContestStudentPostmortemOperationJob: (id: EntityId, runId: EntityId) =>
    request<ContestStudentPostmortemOperationJobResponse | null>(`/api/v1/contests/${id}/runs/${runId}/student-postmortem-reports/operation-jobs/active`),
  contestStudentPostmortemReport: (id: EntityId, runId: EntityId, reportId: EntityId) =>
    request<ContestStudentPostmortemReportResponse>(`/api/v1/contests/${id}/runs/${runId}/student-postmortem-reports/${reportId}`),
  retryContestStudentPostmortemAi: (id: EntityId, runId: EntityId, reportId: EntityId) =>
    request<ContestStudentPostmortemReportResponse>(`/api/v1/contests/${id}/runs/${runId}/student-postmortem-reports/${reportId}/retry-ai`, { method: 'POST', body: '{}' }),
  acceptContestStudentPostmortemWeakness: (id: EntityId, runId: EntityId, reportId: EntityId, candidateId: EntityId) =>
    request<ContestStudentPostmortemWeaknessCandidateResponse>(`/api/v1/contests/${id}/runs/${runId}/student-postmortem-reports/${reportId}/weakness-candidates/${candidateId}/accept`, { method: 'POST', body: '{}' }),
  rejectContestStudentPostmortemWeakness: (id: EntityId, runId: EntityId, reportId: EntityId, candidateId: EntityId) =>
    request<ContestStudentPostmortemWeaknessCandidateResponse>(`/api/v1/contests/${id}/runs/${runId}/student-postmortem-reports/${reportId}/weakness-candidates/${candidateId}/reject`, { method: 'POST', body: '{}' }),
  contestStudentPostmortemSummaries: (id: EntityId, runId: EntityId, params: { page?: number; pageSize?: number } = {}) =>
    request<PageResponse<ContestStudentPostmortemSummaryResponse>>(`/api/v1/contests/${id}/runs/${runId}/student-postmortem-summaries${queryString({ page: 1, pageSize: 20, ...params })}`),
  createContestStudentPostmortemReportForParticipant: (id: EntityId, runId: EntityId, participantId: EntityId) =>
    request<ContestStudentPostmortemReportResponse>(`/api/v1/contests/${id}/runs/${runId}/participants/${participantId}/student-postmortem-reports`, { method: 'POST', body: '{}' }),
  createContestStudentPostmortemOperationJobForParticipant: (id: EntityId, runId: EntityId, participantId: EntityId) =>
    request<OperationJobResponse>(`/api/v1/contests/${id}/runs/${runId}/participants/${participantId}/student-postmortem-reports/operation-jobs`, { method: 'POST', body: '{}' }),
  createBatchContestStudentPostmortemOperationJob: (id: EntityId, runId: EntityId, payload: { participantIds?: EntityId[] | null } = {}) =>
    request<OperationJobResponse>(`/api/v1/contests/${id}/runs/${runId}/student-postmortem-reports/batch-operation-jobs`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  finalizeContest: (id: EntityId, params: { runId?: EntityId | '' } = {}) =>
    request<ContestScoreboardResponse>(`/api/v1/contests/${id}/finalize${queryString(params)}`, { method: 'POST', body: '{}' }),
  contestSubmissions: (id: EntityId, params: ContestSubmissionListParams = {}) =>
    request<PageResponse<ContestSubmissionResponse>>(`/api/v1/contests/${id}/submissions${queryString({ page: 1, pageSize: 20, ...params })}`),
  exportContestSubmissions: (id: EntityId, params: ContestSubmissionExportParams = {}) =>
    request<ContestExportResponse>(`/api/v1/contests/${id}/submissions/export${queryString({ format: 'CSV', ...params })}`),
  createContestSubmissionsExportJob: (id: EntityId, params: ContestSubmissionExportParams = {}) =>
    request<OperationJobResponse>(`/api/v1/contests/${id}/submissions/export-jobs${queryString({ format: 'CSV', ...params })}`, {
      method: 'POST',
      body: '{}'
    }),
  contestSubmission: (id: EntityId, submissionId: EntityId) =>
    request<ContestSubmissionResponse>(`/api/v1/contests/${id}/submissions/${submissionId}`),
  accessContestSubmissionCode: (id: EntityId, submissionId: EntityId, payload: { reason?: string | null } = {}) =>
    request<ContestSubmissionCodeResponse>(`/api/v1/contests/${id}/submissions/${submissionId}/code-accesses`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  contestSubmissionCodeAccessLogs: (id: EntityId, params: SubmissionCodeAccessLogListParams = {}) =>
    request<PageResponse<SubmissionCodeAccessLogResponse>>(`/api/v1/contests/${id}/submission-code-access-logs${queryString({ page: 1, pageSize: 20, ...params })}`),
  createContestPlagiarismJob: (id: EntityId, payload: PlagiarismJobCreatePayload = {}, params: { runId?: EntityId | '' } = {}) =>
    request<PlagiarismJobResponse>(`/api/v1/contests/${id}/plagiarism-jobs${queryString(params)}`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  createContestPlagiarismOperationJob: (id: EntityId, payload: PlagiarismJobCreatePayload = {}, params: { runId?: EntityId | '' } = {}) =>
    request<OperationJobResponse>(`/api/v1/contests/${id}/plagiarism-jobs/operation-jobs${queryString(params)}`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  contestPlagiarismJobs: (id: EntityId, params: { page?: number; pageSize?: number; runId?: EntityId | '' } = {}) =>
    request<PageResponse<PlagiarismJobResponse>>(`/api/v1/contests/${id}/plagiarism-jobs${queryString({ page: 1, pageSize: 20, ...params })}`),
  contestPlagiarismJob: (id: EntityId, jobId: EntityId) =>
    request<PlagiarismJobResponse>(`/api/v1/contests/${id}/plagiarism-jobs/${jobId}`),
  contestPlagiarismPairs: (id: EntityId, jobId: EntityId, params: PlagiarismPairListParams = {}) =>
    request<PageResponse<PlagiarismPairResponse>>(`/api/v1/contests/${id}/plagiarism-jobs/${jobId}/pairs${queryString({ page: 1, pageSize: 20, ...params })}`),
  contestPlagiarismPair: (id: EntityId, jobId: EntityId, pairId: EntityId) =>
    request<PlagiarismPairDetailResponse>(`/api/v1/contests/${id}/plagiarism-jobs/${jobId}/pairs/${pairId}`),
  updateContestPlagiarismPair: (id: EntityId, jobId: EntityId, pairId: EntityId, payload: PlagiarismPairUpdatePayload) =>
    request<PlagiarismPairResponse>(`/api/v1/contests/${id}/plagiarism-jobs/${jobId}/pairs/${pairId}`, {
      method: 'PATCH',
      body: JSON.stringify(payload)
    }),
  retryContestPlagiarismAiAnalysis: (id: EntityId, jobId: EntityId, pairId: EntityId) =>
    request<PlagiarismPairDetailResponse>(`/api/v1/contests/${id}/plagiarism-jobs/${jobId}/pairs/${pairId}/ai-analysis/retry`, {
      method: 'POST',
      body: '{}'
    }),
  exportContestPlagiarismJob: (id: EntityId, jobId: EntityId, params: { format?: ContestExportFormat } = {}) =>
    request<ContestExportResponse>(`/api/v1/contests/${id}/plagiarism-jobs/${jobId}/export${queryString({ format: 'CSV', ...params })}`),
  createContestPlagiarismExportJob: (id: EntityId, jobId: EntityId, params: { format?: ContestExportFormat } = {}) =>
    request<OperationJobResponse>(`/api/v1/contests/${id}/plagiarism-jobs/${jobId}/export-jobs${queryString({ format: 'CSV', ...params })}`, {
      method: 'POST',
      body: '{}'
    }),
  contestPlagiarismGraph: (id: EntityId, runId: EntityId, params: ContestPlagiarismGraphParams = {}) =>
    request<ContestPlagiarismGraphResponse>(`/api/v1/contests/${id}/runs/${runId}/plagiarism-graph${queryString({ ...params })}`),
  rebuildContestFairnessAlerts: (id: EntityId, runId: EntityId) =>
    request<PageResponse<FairnessAlertResponse>>(`/api/v1/contests/${id}/runs/${runId}/fairness-alerts/rebuild`, {
      method: 'POST',
      body: '{}'
    }),
  contestFairnessAlerts: (id: EntityId, runId: EntityId, params: FairnessAlertListParams = {}) =>
    request<PageResponse<FairnessAlertResponse>>(`/api/v1/contests/${id}/runs/${runId}/fairness-alerts${queryString({ page: 1, pageSize: 20, ...params })}`),
  updateContestFairnessAlert: (id: EntityId, runId: EntityId, alertId: EntityId, payload: FairnessAlertUpdatePayload) =>
    request<FairnessAlertResponse>(`/api/v1/contests/${id}/runs/${runId}/fairness-alerts/${alertId}`, {
      method: 'PATCH',
      body: JSON.stringify(payload)
    }),

  problems: (params: ProblemListParams = {}) =>
    request<PageResponse<ProblemResponse>>(`/api/v1/problems${queryString({ page: 1, pageSize: 20, ...params })}`),
  problem: (id: EntityId, context: { contestRunId?: EntityId | null; contestProblemId?: EntityId | null; staffView?: boolean } = {}) =>
    request<ProblemResponse>(`/api/v1/problems/${id}${queryString({
      contestRunId: context.contestRunId ?? undefined,
      contestProblemId: context.contestProblemId ?? undefined,
      staffView: context.staffView || undefined
    })}`),
  problemStandardSolution: (id: EntityId) =>
    request<ProblemSolutionResponse | null>(`/api/v1/problems/${id}/standard-solution`),
  problemStandardSolutions: (id: EntityId) =>
    request<ProblemSolutionResponse[]>(`/api/v1/problems/${id}/standard-solutions`),
  problemTestcaseGenerator: (id: EntityId) =>
    request<ProblemTestcaseGeneratorResponse | null>(`/api/v1/problems/${id}/testcase-generator`),
  createProblem: (payload: ProblemPayload) =>
    request<ProblemResponse>('/api/v1/problems', { method: 'POST', body: JSON.stringify(payload) }),
  updateProblem: (id: EntityId, payload: ProblemPayload) =>
    request<ProblemResponse>(`/api/v1/problems/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
  archiveProblem: (id: EntityId) =>
    request<ProblemResponse>(`/api/v1/problems/${id}/archive`, { method: 'POST', body: '{}' }),
  restoreProblem: (id: EntityId) =>
    request<ProblemResponse>(`/api/v1/problems/${id}/restore`, { method: 'POST', body: '{}' }),
  deleteProblem: (id: EntityId) => request<void>(`/api/v1/problems/${id}`, { method: 'DELETE' }),
  initTestcasePackage: (
    problemId: EntityId,
    payload: { fileName: string; fileSizeBytes: number; sha256: string; chunkSizeBytes: number; totalChunks: number }
  ) =>
    request<TestcaseUploadInitResponse>(`/api/v1/problems/${problemId}/testcase-packages/init`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  uploadTestcaseChunk: (problemId: EntityId, uploadId: string, index: number, chunk: Blob, chunkSha256?: string) =>
    request<TestcaseUploadStatusResponse>(
      `/api/v1/problems/${problemId}/testcase-packages/uploads/${encodeURIComponent(uploadId)}/chunks/${index}`,
      {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/octet-stream',
          ...(chunkSha256 ? { 'X-Chunk-Sha256': chunkSha256 } : {})
        },
        body: chunk
      }
    ),
  completeTestcaseUpload: (problemId: EntityId, uploadId: string) =>
    request<TestcasePackageResponse>(
      `/api/v1/problems/${problemId}/testcase-packages/uploads/${encodeURIComponent(uploadId)}/complete`,
      { method: 'POST', body: '{}' }
    ),
  failTestcaseUpload: (problemId: EntityId, uploadId: string, payload: TestcaseUploadFailRequest = {}) =>
    request<TestcaseUploadStatusResponse>(
      `/api/v1/problems/${problemId}/testcase-packages/uploads/${encodeURIComponent(uploadId)}/fail`,
      { method: 'POST', body: JSON.stringify(payload) }
    ),
  testcaseUploadStatus: (problemId: EntityId, uploadId: string) =>
    request<TestcaseUploadStatusResponse>(
      `/api/v1/problems/${problemId}/testcase-packages/uploads/${encodeURIComponent(uploadId)}/status`
    ),
  testcasePackages: (problemId: EntityId) =>
    request<TestcasePackageResponse[]>(`/api/v1/problems/${problemId}/testcase-packages`),
  activateTestcasePackage: (problemId: EntityId, packageId: EntityId) =>
    request<TestcasePackageResponse>(`/api/v1/problems/${problemId}/testcase-packages/${packageId}/activate`, {
      method: 'POST',
      body: '{}'
    }),
  archiveTestcasePackage: (problemId: EntityId, packageId: EntityId) =>
    request<TestcasePackageResponse>(`/api/v1/problems/${problemId}/testcase-packages/${packageId}/archive`, {
      method: 'POST',
      body: '{}'
    }),
  restoreTestcasePackage: (problemId: EntityId, packageId: EntityId) =>
    request<TestcasePackageResponse>(`/api/v1/problems/${problemId}/testcase-packages/${packageId}/restore`, {
      method: 'POST',
      body: '{}'
    }),
  deleteTestcasePackage: (problemId: EntityId, packageId: EntityId) =>
    request<void>(`/api/v1/problems/${problemId}/testcase-packages/${packageId}`, { method: 'DELETE' }),
  downloadTestcasePackage: (problemId: EntityId, packageId: EntityId) =>
    requestBinary(`/api/v1/problems/${problemId}/testcase-packages/${packageId}/download`, {}, 'testcase-package.zip'),
  appendTestcasePackageCases: (problemId: EntityId, packageId: EntityId, payload: AppendTestcasePackageCasesPayload) => {
    const formData = new FormData();
    formData.set('metadata', JSON.stringify({
      cases: payload.cases.map((item) => ({
        caseName: item.caseName,
        score: item.score ?? null,
        subtaskKey: item.subtaskKey ?? null
      }))
    }));
    payload.cases.forEach((item) => {
      formData.append('inputFiles', item.inputFile);
      formData.append('outputFiles', item.outputFile);
    });
    return requestForm<TestcasePackageResponse>(
      `/api/v1/problems/${problemId}/testcase-packages/${packageId}/cases/batch`,
      formData
    );
  },
  appendTestcasePackageCase: (problemId: EntityId, packageId: EntityId, payload: AppendTestcasePackageCasePayload) => {
    const formData = new FormData();
    formData.set('caseName', payload.caseName);
    if (payload.score != null) {
      formData.set('score', String(payload.score));
    }
    if (payload.subtaskKey) {
      formData.set('subtaskKey', payload.subtaskKey);
    }
    formData.set('inputFile', payload.inputFile);
    formData.set('outputFile', payload.outputFile);
    return requestForm<TestcasePackageResponse>(
      `/api/v1/problems/${problemId}/testcase-packages/${packageId}/cases`,
      formData
    );
  },

  submit: (payload: SubmissionPayload) =>
    request<SubmissionResponse>('/api/v1/submissions', { method: 'POST', body: JSON.stringify(payload) }),
  submission: (id: EntityId) => request<SubmissionResponse>(`/api/v1/submissions/${id}`),
  submissions: (params: SubmissionListParams = {}) =>
    request<PageResponse<SubmissionResponse>>(`/api/v1/submissions${queryString({ page: 1, pageSize: 20, ...params })}`),
  mySubmissions: (params: SubmissionListParams = {}) =>
    request<PageResponse<SubmissionResponse>>(`/api/v1/submissions${queryString({ page: 1, pageSize: 20, mine: true, ...params })}`),

  aiSend: (payload: AiChatPayload) =>
    request<AiChatMessageResponse>('/api/v1/ai/chat/send', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  aiConversations: (params: { page?: number; pageSize?: number; problemId?: EntityId; source?: string; sourceRefType?: string; sourceRefId?: string; keyword?: string; includeDeleted?: boolean } = {}) =>
    request<PageResponse<AiConversationResponse>>(`/api/v1/ai/conversations${queryString({ page: 1, pageSize: 20, ...params })}`),
  createAiConversation: (payload: { problemId?: EntityId; title?: string; source?: string; sourceRefType?: string; sourceRefId?: string; mode?: string }) =>
    request<AiConversationResponse>('/api/v1/ai/conversations', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  updateAiConversation: (
    conversationId: string,
    payload: { title?: string; mode?: string; recentProblemId?: EntityId }
  ) =>
    request<AiConversationResponse>(`/api/v1/ai/conversations/${encodeURIComponent(conversationId)}`, {
      method: 'PATCH',
      body: JSON.stringify(payload)
    }),
  aiHistory: (conversationId: string) =>
    request<AiChatMessageResponse[]>(`/api/v1/ai/conversations/${encodeURIComponent(conversationId)}/messages`),
  aiContextDebug: (conversationId: string) =>
    request<AiConversationContextDebugResponse>(`/api/v1/ai/conversations/${encodeURIComponent(conversationId)}/context-debug`),
  deleteAiConversation: (conversationId: string) =>
    request<void>(`/api/v1/ai/conversations/${encodeURIComponent(conversationId)}`, { method: 'DELETE' }),
  batchDeleteAiConversations: (conversationIds: string[]) =>
    request<void>('/api/v1/ai/conversations/batch-delete', {
      method: 'POST',
      body: JSON.stringify({ conversationIds })
    }),
  aiMemories: (params: AiMemoryListParams = {}) =>
    request<AiMemoryResponse[]>(`/api/v1/ai/memories${queryString({ ...params })}`),
  createAiMemory: (payload: AiMemoryUpsertPayload) =>
    request<AiMemoryResponse>('/api/v1/ai/memories', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  updateAiMemory: (id: EntityId, payload: AiMemoryUpsertPayload) =>
    request<AiMemoryResponse>(`/api/v1/ai/memories/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(payload)
    }),
  deleteAiMemory: (id: EntityId) =>
    request<void>(`/api/v1/ai/memories/${id}`, { method: 'DELETE' }),
  disableAiMemory: (id: EntityId) =>
    request<AiMemoryResponse>(`/api/v1/ai/memories/${id}/disable`, { method: 'POST', body: '{}' }),
  enableAiMemory: (id: EntityId) =>
    request<AiMemoryResponse>(`/api/v1/ai/memories/${id}/enable`, { method: 'POST', body: '{}' }),
  exportAiMemories: () =>
    request<AiMemoryExportResponse>('/api/v1/ai/memories/export'),
  downloadAiMemoryArchiveMarkdown: () =>
    requestBinary('/api/v1/ai/memories/export/markdown', {}, 'aioj-learning-archive.md'),
  importAiMemories: (payload: { markdown: string; mode?: 'MERGE' }) =>
    request<AiMemoryImportResponse>('/api/v1/ai/memories/import', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  aiMemoryCandidates: (params: { status?: string } = {}) =>
    request<AiMemoryCandidateResponse[]>(`/api/v1/ai/memory-candidates${queryString(params)}`),
  aiMemoryCandidateDetail: (id: EntityId) =>
    request<AiMemoryReviewDetailResponse>(`/api/v1/ai/memory-candidates/${id}`),
  acceptAiMemoryCandidate: (id: EntityId, payload: AiMemoryCandidateActionPayload = {}) =>
    request<AiMemoryResponse>(`/api/v1/ai/memory-candidates/${id}/accept`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  acceptAiMemoryCandidateWithEdit: (id: EntityId, payload: AiMemoryCandidateActionPayload) =>
    request<AiMemoryResponse>(`/api/v1/ai/memory-candidates/${id}/accept-with-edit`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  rejectAiMemoryCandidate: (id: EntityId, reason?: string) =>
    request<AiMemoryCandidateResponse>(`/api/v1/ai/memory-candidates/${id}/reject`, {
      method: 'POST',
      body: JSON.stringify({ reason })
    }),
  adminAiMemoryMergeMaintenance: (payload: AiMemoryMergeMaintenancePayload = {}) =>
    request<AiMemoryMergeMaintenanceResponse>('/api/v1/ai/admin/memory-merge-maintenance', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  adminAiMemoryObservability: () =>
    request<AiMemoryObservabilityResponse>('/api/v1/ai/admin/memory-observability'),
  aiMemoryDebug: (params: { query: string; problemId?: EntityId; problemTags?: string[]; mode?: string }) =>
    request<AiMemoryDebugResponse>(`/api/v1/ai/memory-debug${queryString(params)}`),
  aiLearningProfiles: (params: { category?: string; state?: string } = {}) =>
    request<AiLearningProfileResponse[]>(`/api/v1/ai/learning-profile${queryString(params)}`),
  aiLearningProfileEvidence: (id: EntityId) =>
    request<AiLearningProfileEvidenceResponse[]>(`/api/v1/ai/learning-profile/${id}/evidence`),
  updateAiLearningProfile: (id: EntityId, payload: AiLearningProfileUpdatePayload) =>
    request<AiLearningProfileResponse>(`/api/v1/ai/learning-profile/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(payload)
    }),
  markAiLearningProfileMastered: (id: EntityId) =>
    request<AiLearningProfileResponse>(`/api/v1/ai/learning-profile/${id}/mark-mastered`, {
      method: 'POST',
      body: '{}'
    }),
  disableAiLearningProfile: (id: EntityId) =>
    request<AiLearningProfileResponse>(`/api/v1/ai/learning-profile/${id}/disable`, {
      method: 'POST',
      body: '{}'
    }),
  deleteAiLearningProfile: (id: EntityId) =>
    request<void>(`/api/v1/ai/learning-profile/${id}`, { method: 'DELETE' }),
  generateDraft: (payload: ProblemDraftGeneratePayload, opts?: { signal?: AbortSignal }) =>
    request<ProblemDraftResponse>('/api/v1/ai/problem-drafts/generate', {
      method: 'POST',
      body: JSON.stringify(payload),
      signal: opts?.signal
    }),
  generateDraftStream: (payload: ProblemDraftGeneratePayload, opts?: ProblemDraftGenerateStreamOptions) =>
    streamProblemDraftGenerateRequest(payload, opts, true),
  createProblemDraftGenerationJob: (payload: ProblemDraftGeneratePayload, opts?: { signal?: AbortSignal }) =>
    request<ProblemDraftGenerationJobResponse>('/api/v1/ai/problem-drafts/generation-jobs', {
      method: 'POST',
      body: JSON.stringify(payload),
      signal: opts?.signal
    }),
  problemDraftGenerationJobs: (params: {
    page?: number;
    pageSize?: number;
    status?: ProblemDraftGenerationJobStatus | string;
    creatorUserId?: EntityId;
  } = {}) =>
    request<PageResponse<ProblemDraftGenerationJobResponse>>(`/api/v1/admin/problem-draft-generation-jobs${queryString({ page: 1, pageSize: 20, ...params })}`),
  problemDraftGenerationJob: (id: EntityId) =>
    request<ProblemDraftGenerationJobResponse>(`/api/v1/admin/problem-draft-generation-jobs/${id}`),
  problemDrafts: (params: {
    page?: number;
    pageSize?: number;
    status?: string;
    validationStatus?: 'VALID' | 'INVALID';
    creatorUserId?: EntityId;
    sort?: 'newest' | 'oldest';
    lifecycleStatus?: '' | 'ACTIVE' | 'ARCHIVED' | 'ALL';
  } = {}) =>
    request<PageResponse<ProblemDraftResponse>>(`/api/v1/admin/problem-drafts${queryString({ page: 1, pageSize: 20, ...params })}`),
  problemDraft: (id: EntityId) =>
    request<ProblemDraftResponse>(`/api/v1/admin/problem-drafts/${id}`),
  refineDraft: (id: EntityId, payload: ProblemDraftRefinePayload) =>
    request<ProblemDraftResponse>(`/api/v1/admin/problem-drafts/${id}/refine`, {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  createProblemDraftRegenerationJob: (id: EntityId, feedback: string) =>
    request<ProblemDraftGenerationJobResponse>(`/api/v1/admin/problem-drafts/${id}/regeneration-job`, {
      method: 'POST',
      body: JSON.stringify({ feedback })
    }),
  regenerateDraft: (id: EntityId, feedback: string) =>
    request<ProblemDraftResponse>(`/api/v1/admin/problem-drafts/${id}/regenerate`, {
      method: 'POST',
      body: JSON.stringify({ feedback })
    }),
  approveDraft: (id: EntityId, importProblem = false, visibility?: ProblemVisibility) =>
    request<ProblemDraftResponse>(`/api/v1/admin/problem-drafts/${id}/approve`, {
      method: 'POST',
      body: JSON.stringify({ importProblem, visibility: visibility ?? null })
    }),
  manualReviewDraft: (id: EntityId) =>
    request<ProblemDraftResponse>(`/api/v1/admin/problem-drafts/${id}/manual-review`, {
      method: 'POST',
      body: '{}'
    }),
  rejectDraft: (id: EntityId, reasonNote?: string) =>
    request<ProblemDraftResponse>(`/api/v1/admin/problem-drafts/${id}/reject`, {
      method: 'POST',
      body: JSON.stringify({ reasonNote })
    }),
  archiveDraft: (id: EntityId) =>
    request<ProblemDraftResponse>(`/api/v1/admin/problem-drafts/${id}/archive`, { method: 'POST', body: '{}' }),
  restoreDraft: (id: EntityId) =>
    request<ProblemDraftResponse>(`/api/v1/admin/problem-drafts/${id}/restore`, { method: 'POST', body: '{}' }),
  deleteDraft: (id: EntityId) =>
    request<void>(`/api/v1/admin/problem-drafts/${id}`, { method: 'DELETE' }),
  usage: () => request<AiUsageResponse>('/api/v1/ai/usage/me'),
  parseAccountImport: (text: string) =>
    request<AccountImportParseResponse>('/api/v1/admin/ai/account-import/parse', {
      method: 'POST',
      body: JSON.stringify({ text })
    }),
  aiUsageAnalytics: (days = 14) =>
    request<DailyAiUsageStatsResponse[]>(`/api/v1/admin/ai/analytics/usage${queryString({ days })}`),
  submissionDailyAnalytics: (days = 7) =>
    request<DailySubmissionStatsResponse[]>(`/api/v1/admin/submissions/analytics/daily${queryString({ days })}`),
  aiModelConfigs: () =>
    request<AiModelConfigResponse[]>('/api/v1/admin/ai-model-configs'),
  aiModelConfigModels: (scope: AiModelConfigScope, provider?: string, baseUrl?: string) => {
    const search = new URLSearchParams();
    if (provider) search.set('provider', provider);
    if (baseUrl) search.set('baseUrl', baseUrl);
    const suffix = search.toString() ? `?${search.toString()}` : '';
    return request<AiModelListResponse>(`/api/v1/admin/ai-model-configs/${scope}/models${suffix}`);
  },
  updateAiModelConfig: (scope: AiModelConfigScope, payload: AiModelConfigPayload) =>
    request<AiModelConfigResponse>(`/api/v1/admin/ai-model-configs/${scope}`, {
      method: 'PUT',
      body: JSON.stringify(payload)
    }),
  testAiModelConfig: (scope: AiModelConfigScope, payload: AiModelConfigTestPayload = {}) =>
    request<AiModelConfigTestResponse>(`/api/v1/admin/ai-model-configs/${scope}/test`, {
      method: 'POST',
      body: JSON.stringify(payload)
    })
};

export async function streamAi(
  payload: AiChatPayload,
  onEvent: (event: 'meta' | 'message' | 'clarification' | 'error' | 'done' | string, data: string) => void,
  options?: { signal?: AbortSignal; resumeTurnId?: string }
) {
  return streamAiRequest(payload, onEvent, options, true);
}

/**
 * Opens the authenticated notification wake-up channel. Notifications are
 * durable REST facts, so a reconnect merely reduces polling latency and never
 * carries a token in the URL.
 */
export async function subscribeUserNotifications(
  onEvent: (event: UserNotificationStreamEvent) => void,
  options?: { signal?: AbortSignal }
): Promise<void> {
  let reconnectAttempt = 0;
  while (!options?.signal?.aborted) {
    try {
      await readUserNotificationStream(onEvent, options, true);
      reconnectAttempt = 0;
    } catch (error) {
      if (isAbortError(error) || options?.signal?.aborted) {
        throw error;
      }
      if (error instanceof ApiError && (isAuthenticationError(error) || isPermissionError(error))) {
        throw error;
      }
      reconnectAttempt += 1;
      await delay(Math.min(5_000, 500 * 2 ** Math.min(reconnectAttempt - 1, 4)));
    }
  }
}

async function readUserNotificationStream(
  onEvent: (event: UserNotificationStreamEvent) => void,
  options: { signal?: AbortSignal } | undefined,
  retry: boolean
): Promise<void> {
  const path = '/api/v1/notifications/stream';
  const snapshot = authStore.snapshot();
  const accessToken = snapshot.accessToken;
  const sentAuthorization = Boolean(accessToken);
  const response = await fetch(apiUrl(path), {
    method: 'GET',
    headers: {
      Accept: 'text/event-stream',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {})
    },
    signal: options?.signal
  });
  if (!response.ok) {
    try {
      await parseResponse<never>(response);
    } catch (error) {
      const retried = await retryAfterAuthenticationError(path, retry, snapshot, error,
        () => readUserNotificationStream(onEvent, options, false));
      if (retried !== null) return retried;
      throw error;
    }
    throw new ApiError(response.status * 100, `Notification stream failed: ${response.status}`);
  }
  assertRequestSessionCurrent(snapshot, sentAuthorization);
  if (!response.body) {
    throw new ApiError(40000, 'Streaming is not supported by this browser', null, null, 'client.streamingUnsupported');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  const dispatchBlock = (block: string) => {
    if (!block.trim()) return;
    assertRequestSessionCurrent(snapshot, sentAuthorization);
    let eventName = 'message';
    const dataLines: string[] = [];
    for (const rawLine of block.split('\n')) {
      if (!rawLine || rawLine.startsWith(':')) continue;
      const colon = rawLine.indexOf(':');
      const field = colon >= 0 ? rawLine.slice(0, colon) : rawLine;
      let value = colon >= 0 ? rawLine.slice(colon + 1) : '';
      if (value.startsWith(' ')) value = value.slice(1);
      if (field === 'event') eventName = value || 'message';
      if (field === 'data') dataLines.push(value);
    }
    if (eventName !== 'notification' || dataLines.length === 0) return;
    let parsed: Partial<UserNotificationStreamEvent>;
    try {
      parsed = JSON.parse(preserveLargeIntegerIds(dataLines.join('\n'))) as Partial<UserNotificationStreamEvent>;
    } catch {
      return;
    }
    if (!parsed.type || parsed.id == null || !parsed.subjectType || parsed.subjectId == null) return;
    onEvent({
      id: String(parsed.id),
      type: parsed.type as UserNotificationType,
      subjectType: String(parsed.subjectType),
      subjectId: String(parsed.subjectId)
    });
  };
  const feed = (text: string) => {
    buffer += text.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
    let boundary = buffer.indexOf('\n\n');
    while (boundary >= 0) {
      dispatchBlock(buffer.slice(0, boundary));
      buffer = buffer.slice(boundary + 2);
      boundary = buffer.indexOf('\n\n');
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    feed(decoder.decode(value, { stream: true }));
  }
  feed(decoder.decode());
  if (buffer.trim()) {
    dispatchBlock(buffer);
  }
}

async function streamAiRequest(
  payload: AiChatPayload,
  onEvent: (event: 'meta' | 'message' | 'clarification' | 'error' | 'done' | string, data: string) => void,
  options: { signal?: AbortSignal; resumeTurnId?: string } | undefined,
  retry: boolean
): Promise<void> {
  const snapshot = authStore.snapshot();
  const accessToken = snapshot.accessToken;
  const sentAuthorization = Boolean(accessToken);
  // resumeTurnId reattaches to an existing server-side turn after a dropped connection;
  // the server never regenerates for a resume request.
  const path = options?.resumeTurnId
    ? `/api/v1/ai/chat/stream?resumeTurnId=${encodeURIComponent(options.resumeTurnId)}`
    : '/api/v1/ai/chat/stream';
  const response = await fetch(apiUrl(path), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {})
    },
    body: JSON.stringify(payload),
    signal: options?.signal
  });
  if (!response.ok) {
    try {
      await parseResponse<never>(response);
    } catch (error) {
      const retried = await retryAfterAuthenticationError('/api/v1/ai/chat/stream', retry, snapshot, error, () => streamAiRequest(payload, onEvent, options, false));
      if (retried !== null) return retried;
      throw error;
    }
    throw new ApiError(response.status * 100, `AI stream failed: ${response.status}`);
  }
  assertRequestSessionCurrent(snapshot, sentAuthorization);
  if (!response.body) {
    throw new ApiError(40000, 'Streaming is not supported by this browser', null, null, 'client.streamingUnsupported');
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let sawEvent = false;
  let sawDone = false;

  const dispatchBlock = (block: string) => {
    if (!block.trim()) return;
    assertRequestSessionCurrent(snapshot, sentAuthorization);
    let eventName = 'message';
    const dataLines: string[] = [];
    for (const rawLine of block.split('\n')) {
      if (!rawLine || rawLine.startsWith(':')) continue;
      const colon = rawLine.indexOf(':');
      const field = colon >= 0 ? rawLine.slice(0, colon) : rawLine;
      let value = colon >= 0 ? rawLine.slice(colon + 1) : '';
      if (value.startsWith(' ')) value = value.slice(1);
      if (field === 'event') eventName = value || 'message';
      if (field === 'data') dataLines.push(value);
    }
    const data = dataLines.join('\n');
    sawEvent = true;
    if (eventName === 'done') sawDone = true;
    onEvent(eventName, data);
  };

  const feed = (text: string) => {
    buffer += text.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
    let boundary = buffer.indexOf('\n\n');
    while (boundary >= 0) {
      dispatchBlock(buffer.slice(0, boundary));
      buffer = buffer.slice(boundary + 2);
      boundary = buffer.indexOf('\n\n');
    }
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      feed(decoder.decode(value, { stream: true }));
    }
    feed(decoder.decode());
    if (buffer.trim()) {
      dispatchBlock(buffer);
      buffer = '';
    }
  } catch (error) {
    if (isAbortError(error)) {
      throw error;
    }
    if (sawDone || sawEvent) {
      if (!sawDone) onEvent('done', '[DONE]');
      return;
    }
    throw error;
  }
}

async function streamProblemDraftGenerateRequest(
  payload: ProblemDraftGeneratePayload,
  options: ProblemDraftGenerateStreamOptions | undefined,
  retry: boolean
): Promise<ProblemDraftResponse> {
  const path = '/api/v1/ai/problem-drafts/generate/stream';
  const snapshot = authStore.snapshot();
  const accessToken = snapshot.accessToken;
  const sentAuthorization = Boolean(accessToken);
  const response = await fetch(apiUrl(path), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {})
    },
    body: JSON.stringify(payload),
    signal: options?.signal
  });
  if (!response.ok) {
    try {
      await parseResponse<never>(response);
    } catch (error) {
      const retried = await retryAfterAuthenticationError(path, retry, snapshot, error, () => streamProblemDraftGenerateRequest(payload, options, false));
      if (retried !== null) return retried;
      throw error;
    }
    throw new ApiError(response.status * 100, `Problem draft stream failed: ${response.status}`);
  }
  assertRequestSessionCurrent(snapshot, sentAuthorization);
  if (!response.body) {
    throw new ApiError(40000, 'Streaming is not supported by this browser', null, null, 'client.streamingUnsupported');
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let finalDraft: ProblemDraftResponse | null = null;

  const dispatchBlock = (block: string) => {
    if (!block.trim()) return;
    assertRequestSessionCurrent(snapshot, sentAuthorization);
    let eventName: ProblemDraftGenerateStreamEvent = 'message';
    const dataLines: string[] = [];
    for (const rawLine of block.split('\n')) {
      if (!rawLine || rawLine.startsWith(':')) continue;
      const colon = rawLine.indexOf(':');
      const field = colon >= 0 ? rawLine.slice(0, colon) : rawLine;
      let value = colon >= 0 ? rawLine.slice(colon + 1) : '';
      if (value.startsWith(' ')) value = value.slice(1);
      if (field === 'event') eventName = value || 'message';
      if (field === 'data') dataLines.push(value);
    }
    const data = dataLines.join('\n');
    if (eventName === 'error') {
      const error = new ProblemDraftGenerateStreamError(parseProblemDraftStreamError(data));
      options?.onEvent?.(eventName, data);
      throw error;
    }
    if (eventName === 'draft') {
      finalDraft = JSON.parse(data) as ProblemDraftResponse;
    }
    options?.onEvent?.(eventName, data);
  };

  const feed = (text: string) => {
    buffer += text.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
    let boundary = buffer.indexOf('\n\n');
    while (boundary >= 0) {
      dispatchBlock(buffer.slice(0, boundary));
      buffer = buffer.slice(boundary + 2);
      boundary = buffer.indexOf('\n\n');
    }
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      feed(decoder.decode(value, { stream: true }));
    }
    feed(decoder.decode());
    if (buffer.trim()) {
      dispatchBlock(buffer);
      buffer = '';
    }
  } catch (error) {
    if (isAbortError(error)) {
      throw error;
    }
    if (finalDraft) {
      return finalDraft;
    }
    throw error;
  }

  if (finalDraft) {
    return finalDraft;
  }
  throw new ProblemDraftGenerateStreamError({ message: 'Problem draft stream ended without a draft' });
}

function parseProblemDraftStreamError(data: string): ProblemDraftGenerateStreamErrorPayload {
  const fallback = data.trim() || 'Problem draft generation failed';
  try {
    const parsed = JSON.parse(data) as Record<string, unknown>;
    return {
      code: typeof parsed.code === 'number' ? parsed.code : undefined,
      message: typeof parsed.message === 'string' && parsed.message.trim() ? parsed.message : fallback,
      errorKey: typeof parsed.errorKey === 'string' ? parsed.errorKey : null,
      elapsedMillis: typeof parsed.elapsedMillis === 'number' ? parsed.elapsedMillis : undefined
    };
  } catch {
    return { message: fallback };
  }
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError';
}

export { installErrorReporter, reportApiError } from './reporting';
