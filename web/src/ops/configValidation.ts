import type { PrizeConfig } from '@/types'

export function validateCampaignConfig(dailyLimit: number, prizes: PrizeConfig[]): string | null {
  if (!Number.isInteger(dailyLimit) || dailyLimit < 1) return '每日参与上限必须是正整数。'
  if (!prizes.length) return '至少需要配置一个奖品。'
  if (prizes.some((prize) => !Number.isSafeInteger(prize.id) || prize.id < 1 || !prize.name.trim()
    || !Number.isInteger(prize.totalStock) || prize.totalStock < 0 || !Number.isFinite(prize.probability)
    || prize.probability < 0 || prize.probability > 1)) {
    return '请检查奖品编号、名称、库存和 0–1 概率。'
  }
  if (prizes.reduce((sum, prize) => sum + prize.probability, 0) > 1) {
    return '奖品概率总和不能超过 1；小于 1 的余量代表未中奖。'
  }
  return null
}
