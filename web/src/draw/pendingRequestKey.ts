import { ref } from 'vue'

/** 新抽奖创建新 key；网络或服务端不确定错误重试时复用当前 key。 */
export function usePendingRequestKey(createKey: () => string = () => crypto.randomUUID()) {
  const key = ref('')
  return {
    key,
    acquire() {
      if (!key.value) key.value = createKey()
      return key.value
    },
    complete() { key.value = '' },
    fail(status: number) {
      if (status > 0 && status < 500) key.value = ''
    },
  }
}
