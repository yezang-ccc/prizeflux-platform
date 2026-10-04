export type Role = 'USER' | 'OPERATOR'
export type CampaignStatus = 'DRAFT' | 'PENDING_REVIEW' | 'PUBLISHED' | 'REJECTED' | 'CLOSED'
export type DrawOutcome = 'WON' | 'NO_PRIZE' | 'SOLD_OUT'

export interface ApiEnvelope<T> { code: string; message: string; data: T }
export interface User { userId: number; username: string; role: Role }
export interface Campaign {
  id: number
  name: string
  startsAt: string
  endsAt: string
  status: CampaignStatus
}
export interface OwnedCampaign extends Campaign { reviewComment: string | null; reviewedAt: string | null }
export interface PendingCampaignReview extends Campaign {
  ownerUserId: number
  ownerUsername: string
  submittedAt: string
}
export interface CursorPage<T> { items: T[]; nextCursor: number | null }
export interface PrizeView { id: number; name: string; probability: number }
export interface PrizeConfig extends PrizeView { totalStock: number }
export interface CampaignConfig { dailyLimit: number; prizes: PrizeConfig[] }
export interface DrawResult { requestId: number; campaignId: number; outcome: DrawOutcome; prizeId: number | null }
export type DrawQueueStatus = 'QUEUED' | 'PROCESSING' | 'RETRY_WAIT' | 'COMPLETED' | 'REJECTED' | 'MANUAL_REVIEW'
export interface DrawQueueView {
  queueRequestId: string
  requestKey: string
  status: DrawQueueStatus
  result: DrawResult | null
  errorCode: string | null
  createdAt: string
  completedAt: string | null
}
export interface DrawHistoryItem {
  drawId: number
  campaignId: number
  outcome: DrawOutcome
  prizeId: number | null
  drawnAt: string
}
export interface SessionCreated { sessionId: string; version: number; createdAt: string }
export interface SessionEvent { kind: string; content: string; toolName: string | null; toolSucceeded: boolean | null }
export interface SessionView {
  sessionId: string
  version: number
  createdAt: string
  updatedAt: string
  events: SessionEvent[]
}
export interface AiResult { answer: string; simulated: boolean; status: string; trace: { kind: string; toolName: string; toolSucceeded: boolean }[] }
export interface AiCreatorDraftResult {
  answer: string
  valid: boolean
  issues: string[]
  simulated: boolean
  status: string
  trace: { kind: string; toolName: string | null; toolSucceeded: boolean | null }[]
}
