<template>
  <aside class="mt-4 rounded-md border border-border bg-muted/40 px-3 py-3 text-body-sm leading-relaxed text-muted-foreground">
    <p class="m-0 font-medium text-foreground">作品集试用账号</p>
    <p class="mt-1 m-0">
      账号 <code class="rounded bg-background px-1 text-foreground">{{ account.username }}</code>
      密码 <code class="rounded bg-background px-1 text-foreground">{{ account.password }}</code>
    </p>
    <p class="mt-2 m-0">{{ restriction }}</p>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { PORTFOLIO_TRIAL } from '@/utils/demoAccounts'

const props = defineProps({
  audience: {
    type: String,
    required: true,
    validator: (value) => value === 'chat' || value === 'admin'
  }
})

const account = computed(() => PORTFOLIO_TRIAL[props.audience])
const restriction = computed(() => props.audience === 'admin'
  ? '只能浏览已授权文档、知识库和自己的对话观测。不能上传、删除、改配置、管用户，也不能看别人的会话。'
  : '只能基于已授权演示文档提问。不能上传、删改知识，也不能使用开放提问。')
</script>
