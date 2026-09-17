<template>
  <section class="flex flex-col gap-4">
    <h1 class="m-0 text-title-sm font-semibold text-foreground">没有访问权限</h1>
    <p class="m-0 max-w-prose leading-7 text-muted-foreground">
      当前账号不能打开这个页面。菜单只显示你有权限的入口；请从侧栏选择其他页面，或联系租户管理员分配角色。
    </p>
    <div class="flex flex-wrap gap-2">
      <Button as="a" variant="outline" href="/admin/dashboard">回到运营总览</Button>
      <Button as="a" variant="ghost" href="/chat">返回会话端</Button>
    </div>
  </section>
</template>

<script setup>
import { Button } from '@/components/ui/button'
</script>
