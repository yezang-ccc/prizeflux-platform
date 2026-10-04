import { defineStore } from 'pinia'
import { campaignRequest } from '@/api/client'
import type { User } from '@/types'

const TOKEN_KEY = 'prizeflux.access-token'

export const useAuthStore = defineStore('auth', {
  state: () => ({ token: sessionStorage.getItem(TOKEN_KEY) ?? '', user: null as User | null, checked: false }),
  actions: {
    async login(username: string, password: string) {
      const result = await campaignRequest<{ token: string; expiresAt: string }>('/auth/login', undefined,
        { method: 'POST', body: JSON.stringify({ username, password }) })
      this.token = result.token
      sessionStorage.setItem(TOKEN_KEY, result.token)
      try {
        this.user = await campaignRequest<User>('/auth/me', this.token)
        this.checked = true
      } catch (error) {
        this.logout()
        throw error
      }
    },
    async restore() {
      if (!this.token) { this.checked = true; return }
      try {
        this.user = await campaignRequest<User>('/auth/me', this.token)
      } catch {
        this.logout()
      } finally {
        this.checked = true
      }
    },
    logout() {
      this.token = ''
      this.user = null
      this.checked = true
      sessionStorage.removeItem(TOKEN_KEY)
    },
  },
})
