import { createRouter, createWebHistory } from 'vue-router'
import { isAdminAuthenticated } from '../utils/adminAuth'
import { isChatAuthenticated } from '../utils/chatAuth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/chat'
    },
    {
      path: '/login',
      name: 'ChatLogin',
      component: () => import('../views/ChatLoginView.vue'),
      meta: {
        layout: 'fullscreen',
        title: '用户登录'
      }
    },
    {
      path: '/chat',
      name: 'BusinessChat',
      component: () => import('../views/BusinessChatView.vue'),
      meta: {
        title: '业务对话',
        layout: 'fullscreen',
        // 用户端独立登录：没有用户身份不能进对话页（对话接口本身也需要用户 token）。
        requiresChatAuth: true
      }
    },
    {
      path: '/admin/login',
      name: 'AdminLogin',
      component: () => import('../views/AdminLoginView.vue'),
      meta: {
        layout: 'fullscreen',
        title: '管理后台登录'
      }
    },
    {
      path: '/admin',
      component: () => import('../views/admin/AdminLayoutView.vue'),
      meta: {
        layout: 'fullscreen',
        requiresAdminAuth: true
      },
      children: [
        {
          path: '',
          redirect: '/admin/dashboard'
        },
        {
          path: 'dashboard',
          name: 'AdminDashboard',
          component: () => import('../views/admin/AdminDashboardView.vue'),
          meta: {
            title: '运营总览'
          }
        },
        {
          path: 'quality-overview',
          name: 'AdminQualityOverview',
          component: () => import('../views/admin/AdminQualityOverviewView.vue'),
          meta: {
            title: '知识运行全景'
          }
        },
        {
          path: 'documents',
          name: 'AdminDocuments',
          component: () => import('../views/admin/AdminDocumentListView.vue'),
          meta: {
            title: '文档接入'
          }
        },
        {
          path: 'documents/:documentId',
          name: 'AdminDocumentDetail',
          component: () => import('../views/admin/AdminDocumentDetailView.vue'),
          meta: {
            title: '文档详情'
          }
        },
        {
          path: 'knowledge-bases',
          name: 'AdminKnowledgeBases',
          component: () => import('../views/admin/AdminKnowledgeBaseView.vue'),
          meta: {
            title: '知识库管理'
          }
        },
        {
          path: 'knowledge-route',
          name: 'AdminKnowledgeRoute',
          component: () => import('../views/admin/AdminKnowledgeRouteView.vue'),
          meta: {
            title: '知识路由'
          }
        },
        {
          path: 'knowledge-route/traces',
          name: 'AdminKnowledgeRouteTrace',
          component: () => import('../views/admin/AdminKnowledgeRouteTraceView.vue'),
          meta: {
            title: '路由追踪'
          }
        },
        {
          path: 'observability',
          name: 'AdminObservabilityList',
          component: () => import('../views/admin/AdminObservabilityListView.vue'),
          meta: {
            title: '对话观测'
          }
        },
        {
          path: 'observability/:conversationId',
          name: 'AdminObservabilitySession',
          component: () => import('../views/admin/AdminObservabilitySessionView.vue'),
          meta: {
            title: '会话链路'
          }
        },
        {
          path: 'observability/:conversationId/exchanges/:exchangeId',
          name: 'AdminObservabilityExchangeDetail',
          component: () => import('../views/admin/AdminObservabilityDetailView.vue'),
          meta: {
            title: '轮次详情'
          }
        },
        {
          path: 'document-acl',
          name: 'AdminDocumentAcl',
          component: () => import('../views/admin/AdminDocumentAclView.vue'),
          meta: {
            title: '文档授权'
          }
        },
        {
          path: 'members',
          name: 'AdminMembers',
          component: () => import('../views/admin/AdminMemberView.vue'),
          meta: {
            title: '用户与角色'
          }
        },
        {
          path: 'settings/configuration',
          name: 'AdminSystemConfiguration',
          component: () => import('../views/admin/AdminSystemConfigView.vue'),
          meta: {
            title: '参数配置'
          }
        }
      ]
    }
  ]
})

router.beforeEach((to) => {
  const requiresAdminAuth = to.matched.some((record) => record.meta?.requiresAdminAuth)
  const isAdminLoginRoute = to.name === 'AdminLogin'
  const requiresChatAuth = to.matched.some((record) => record.meta?.requiresChatAuth)
  const isChatLoginRoute = to.name === 'ChatLogin'

  if (requiresAdminAuth && !isAdminAuthenticated()) {
    return {
      name: 'AdminLogin',
      query: {
        redirect: to.fullPath
      }
    }
  }

  if (isAdminLoginRoute && isAdminAuthenticated()) {
    return typeof to.query.redirect === 'string' && to.query.redirect.startsWith('/admin')
      ? to.query.redirect
      : '/admin/dashboard'
  }

  // 用户端与后台各自守卫：用户端登录态与后台登录态分开存储，互不代偿。
  if (requiresChatAuth && !isChatAuthenticated()) {
    return {
      name: 'ChatLogin',
      query: {
        redirect: to.fullPath
      }
    }
  }

  if (isChatLoginRoute && isChatAuthenticated()) {
    return typeof to.query.redirect === 'string' && to.query.redirect.startsWith('/chat')
      ? to.query.redirect
      : '/chat'
  }

  return true
})

export default router
