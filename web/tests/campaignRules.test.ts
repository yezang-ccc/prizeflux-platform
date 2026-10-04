import { describe, expect, it, vi } from 'vitest'
import { usePendingRequestKey } from '@/draw/pendingRequestKey'
import { validateCampaignConfig } from '@/ops/configValidation'
import type { PrizeConfig } from '@/types'

const prize = (id: number, probability: number): PrizeConfig => ({
  id, name: `奖品 ${id}`, totalStock: 10, probability,
})

describe('draw request idempotency key', () => {
  it('reuses a key after an uncertain request and rotates after success', () => {
    const createKey = vi.fn().mockReturnValueOnce('request-0001').mockReturnValueOnce('request-0002')
    const pending = usePendingRequestKey(createKey)

    expect(pending.acquire()).toBe('request-0001')
    pending.fail(0)
    expect(pending.acquire()).toBe('request-0001')
    pending.complete()
    expect(pending.acquire()).toBe('request-0002')
    expect(createKey).toHaveBeenCalledTimes(2)
  })

  it('drops a key after a definitive client response', () => {
    const createKey = vi.fn().mockReturnValueOnce('request-0001').mockReturnValueOnce('request-0002')
    const pending = usePendingRequestKey(createKey)
    pending.acquire()
    pending.fail(400)
    expect(pending.acquire()).toBe('request-0002')
    expect(createKey).toHaveBeenCalledTimes(2)
  })
})

describe('campaign prize configuration form', () => {
  it('allows probability totals below one and exact one', () => {
    expect(validateCampaignConfig(3, [prize(1, 0.2), prize(2, 0.35)])).toBeNull()
    expect(validateCampaignConfig(3, [prize(1, 0.4), prize(2, 0.6)])).toBeNull()
  })

  it('rejects invalid bounds and totals above one', () => {
    expect(validateCampaignConfig(0, [prize(1, 1)])).toContain('每日参与上限')
    expect(validateCampaignConfig(3, [prize(1, 0.7), prize(2, 0.31)])).toContain('不能超过 1')
    expect(validateCampaignConfig(3, [prize(1, Number.NaN)])).toContain('0–1 概率')
  })
})
