import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LoginView from '@/views/LoginView.vue'
import RegisterView from '@/views/RegisterView.vue'
import CampaignListView from '@/views/CampaignListView.vue'
import CampaignDetailView from '@/views/CampaignDetailView.vue'
import HistoryView from '@/views/HistoryView.vue'
import OperatorCampaignsView from '@/views/OperatorCampaignsView.vue'
import OperatorCampaignEditView from '@/views/OperatorCampaignEditView.vue'
import OperatorAiView from '@/views/OperatorAiView.vue'
import CreatorCampaignsView from '@/views/CreatorCampaignsView.vue'
import CreatorCampaignEditView from '@/views/CreatorCampaignEditView.vue'
import OperatorReviewView from '@/views/OperatorReviewView.vue'
import type { Role } from '@/types'

declare module 'vue-router' {
  interface RouteMeta { guest?: boolean; role?: Role }
}

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { guest: true } },
    { path: '/register', name: 'register', component: RegisterView, meta: { guest: true } },
    { path: '/', redirect: '/campaigns' },
    { path: '/campaigns', name: 'campaigns', component: CampaignListView, meta: { role: 'USER' } },
    { path: '/campaigns/:id', name: 'campaign', component: CampaignDetailView, meta: { role: 'USER' } },
    { path: '/me/history', name: 'history', component: HistoryView, meta: { role: 'USER' } },
    { path: '/me/campaigns', name: 'my-campaigns', component: CreatorCampaignsView, meta: { role: 'USER' } },
    { path: '/me/campaigns/:id', name: 'my-campaign', component: CreatorCampaignEditView, meta: { role: 'USER' } },
    { path: '/ops/reviews', name: 'ops-reviews', component: OperatorReviewView, meta: { role: 'OPERATOR' } },
    { path: '/ops/campaigns', name: 'ops-campaigns', component: OperatorCampaignsView, meta: { role: 'OPERATOR' } },
    { path: '/ops/campaigns/:id', name: 'ops-campaign', component: OperatorCampaignEditView, meta: { role: 'OPERATOR' } },
    { path: '/ops/assistant', name: 'ops-assistant', component: OperatorAiView, meta: { role: 'OPERATOR' } },
    { path: '/:pathMatch(.*)*', redirect: '/campaigns' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.checked) await auth.restore()
  if (to.meta.guest) return auth.user ? (auth.user.role === 'OPERATOR' ? '/ops/reviews' : '/campaigns') : true
  if (!auth.user) return { name: 'login', query: { redirect: to.fullPath } }
  const role = to.meta.role
  if (role && role !== auth.user.role) return auth.user.role === 'OPERATOR' ? '/ops/reviews' : '/campaigns'
  return true
})
