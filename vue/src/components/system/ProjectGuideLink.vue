<script setup>
import { AcademicCapIcon } from '@heroicons/vue/24/outline'
import { RouterLink } from 'vue-router'
import { Button } from '@/components/ui/button'

/*
 * 会话端和后台都要有这个入口，两处的地址、图标、提示文案必须一致，
 * 所以收在一个组件里，而不是两处各写一遍。
 */
const GUIDE_PATH = '/guide'
const GUIDE_TITLE = '如何学习本项目：架构拆解与上手路线'

defineProps({
  /*
   * 传了就是「图标+文字」，窄屏自动只留图标（和旁边的管理后台按钮一样的收法）；
   * 不传就是纯图标。用一个节点响应式收字，而不是渲染两份再各自 hidden：
   * 两份的话辅助技术会读到两个同名链接。
   */
  label: { type: String, default: '' }
})
</script>

<template>
  <!--
    min-w-11 是给窄屏的：那里文字收掉了，只按内边距算宽度会是 38px，
    高度够 44 而宽度不够，触控目标就是缺的。sm 以上交回 lg 尺寸自己算。
  -->
  <Button as-child variant="ghost" size="lg" class="h-11 min-w-11 px-2.5 sm:h-9 sm:min-w-0">
    <!--
      aria-label 两种形态都给全句：可见文字「如何学习」是它的子串，
      满足 label-in-name，同时读屏能听到这个链接到底通向什么。
    -->
    <RouterLink :to="GUIDE_PATH" :title="GUIDE_TITLE" :aria-label="GUIDE_TITLE">
      <AcademicCapIcon :data-icon="label ? 'inline-start' : undefined" aria-hidden="true" />
      <span v-if="label" class="hidden sm:inline">{{ label }}</span>
    </RouterLink>
  </Button>
</template>
