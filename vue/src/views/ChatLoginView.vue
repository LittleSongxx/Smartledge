<template>
  <div class="relative grid min-h-dvh place-items-center bg-admin-bg px-4 py-16 sm:px-6">
    <main class="w-full max-w-[440px]">
      <div class="mb-5 flex items-center gap-3 px-1">
        <div class="grid size-9 place-items-center rounded-md bg-primary text-caption font-bold text-primary-foreground">NA</div>
        <div>
          <p class="m-0 text-caption text-muted-foreground">Smartledge</p>
          <h1 class="m-0 text-title-sm font-semibold text-foreground">知识问答</h1>
        </div>
      </div>

      <form class="rounded-lg border border-border bg-card p-5 shadow-control sm:p-6" novalidate @submit.prevent="submitLogin">
        <div class="border-b border-border pb-4">
          <h2 class="m-0 text-title font-semibold text-foreground">用户登录</h2>
          <p class="mt-2 text-body-sm leading-relaxed text-muted-foreground">
            使用你的账号登录后即可提问，回答只会引用你有权限查看的文档。
          </p>
          <p v-if="redirectHint" class="mt-2 text-caption text-muted-foreground">登录后将返回：{{ redirectHint }}</p>
        </div>

        <div class="mt-5 flex flex-col gap-4">
          <div class="flex flex-col gap-2">
            <Label for="chat-login-username">账号</Label>
            <Input
              id="chat-login-username"
              v-model="form.username"
              type="text"
              placeholder="请输入账号"
              autocomplete="username"
              :aria-invalid="Boolean(errorMessage) || undefined"
              :aria-describedby="errorMessage ? 'chat-login-error' : undefined"
            />
          </div>

          <div class="flex flex-col gap-2">
            <div class="flex items-center justify-between gap-3">
              <Label for="chat-login-password">密码</Label>
              <Button
                variant="ghost"
                size="icon-sm"
                type="button"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                :title="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                <EyeSlashIcon v-if="showPassword" aria-hidden="true" />
                <EyeIcon v-else aria-hidden="true" />
              </Button>
            </div>
            <Input
              id="chat-login-password"
              v-model="form.password"
              :type="showPassword ? 'text' : 'password'"
              placeholder="请输入密码"
              autocomplete="current-password"
              :aria-invalid="Boolean(errorMessage) || undefined"
              :aria-describedby="errorMessage ? 'chat-login-error' : undefined"
            />
          </div>

          <div class="flex flex-col gap-2">
            <Label for="chat-login-tenant">租户编码（可选）</Label>
            <Input
              id="chat-login-tenant"
              v-model="form.tenantCode"
              type="text"
              placeholder="留空使用默认租户"
              autocomplete="organization"
            />
            <p class="m-0 text-caption text-muted-foreground">账号在租户内唯一；跨租户同名账号需要填写租户编码。</p>
          </div>
        </div>

        <p v-if="errorMessage" id="chat-login-error" role="alert" class="mt-4 rounded-md border border-destructive/20 bg-destructive/10 px-3 py-2 text-body-sm text-destructive">
          {{ errorMessage }}
        </p>

        <div class="mt-6">
          <Button class="w-full rounded-md" size="lg" type="submit" :loading="submitting" loading-text="登录中">登录并开始问答</Button>
        </div>
      </form>
    </main>

    <IcpFooter class="absolute inset-x-0 bottom-[max(1rem,env(safe-area-inset-bottom))] px-4 sm:px-6" />
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { EyeIcon, EyeSlashIcon } from '@heroicons/vue/24/outline'
import { useRoute, useRouter } from 'vue-router'
import IcpFooter from '../components/IcpFooter.vue'
import { chatAuthApi, APIError } from '../api/api'
import { saveChatAuth } from '../utils/chatAuth'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const router = useRouter()
const route = useRoute()

const form = reactive({ username: '', password: '', tenantCode: '' })
const errorMessage = ref('')
const submitting = ref(false)
const showPassword = ref(false)

// 只接受站内用户端跳转目标，避免被构造成外站地址。
const safeRedirect = computed(() => {
  const target = route.query.redirect
  if (typeof target !== 'string') {
    return '/chat'
  }
  return target.startsWith('/chat') || target.startsWith('/api/') ? target : '/chat'
})
const redirectHint = computed(() => safeRedirect.value === '/chat' ? '' : safeRedirect.value)

async function submitLogin() {
  errorMessage.value = ''
  if (!form.username.trim() || !form.password) {
    errorMessage.value = '请输入账号和密码。'
    return
  }

  submitting.value = true
  try {
    const username = form.username.trim()
    const result = await chatAuthApi.login({
      username,
      password: form.password,
      tenantCode: form.tenantCode.trim() || undefined
    })
    saveChatAuth({ username: result?.username || username, token: result?.token || '' })
    router.replace(safeRedirect.value)
  } catch (error) {
    errorMessage.value = error instanceof APIError || error instanceof Error
      ? error.message
      : '登录失败，请稍后重试。'
  } finally {
    submitting.value = false
  }
}
</script>
