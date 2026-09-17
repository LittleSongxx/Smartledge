<template>
  <div class="min-h-dvh bg-background px-4 py-10 text-foreground sm:px-6">
    <article class="mx-auto w-full max-w-3xl">
      <p class="m-0 text-caption text-muted-foreground">Smartledge</p>
      <h1 class="mt-2 text-title font-semibold">如何学习本项目</h1>
      <p class="mt-3 leading-7 text-muted-foreground">
        这是仓库内的公开架构说明。会话端和管理端的「如何学习」都指向这里，不再跳到站外付费文。
      </p>

      <section class="mt-8">
        <h2 class="text-body font-semibold">系统怎么分工</h2>
        <ul class="mt-3 list-disc space-y-2 pl-5 leading-7 text-muted-foreground">
          <li>Java 拥有业务主链路：知识范围、会话、检索计划、通道调度、融合与重排、最终证据、提示词、显式引用和归档。</li>
          <li>Python 只做算法工具：解析、OCR、版面、表格、模型推理、重排、图谱和 RAPTOR。它不决定范围、路由、最终证据或回答。</li>
          <li>一个业务决定只有一个权威。投影层可以改协议形状，但不能重新解释查询、过滤、通道或引用。</li>
        </ul>
      </section>

      <section class="mt-8">
        <h2 class="text-body font-semibold">一次问答怎么走完</h2>
        <ol class="mt-3 list-decimal space-y-2 pl-5 leading-7 text-muted-foreground">
          <li>用户选择知识库或文档，形成硬范围。</li>
          <li>检索计划只解释一次范围、查询、通道、预算和排序特征。</li>
          <li>各通道召回后，融合只合并特征，重排只排序统一窗口。</li>
          <li>最终策略只挑选 Source Evidence；上下文扩写只能进 Context Only。</li>
          <li>回答里的 <code class="rounded bg-muted px-1">[n]</code> 只能绑定本轮已经渲染的来源。</li>
        </ol>
      </section>

      <section class="mt-8">
        <h2 class="text-body font-semibold">三种提问模式</h2>
        <ul class="mt-3 list-disc space-y-2 pl-5 leading-7 text-muted-foreground">
          <li>自动知识：从已选知识库里定位文档，走 RAG。</li>
          <li>当前文档：只问你明确选中的那一份文档。</li>
          <li>开放提问：不限定知识库，走 Agent；需要先修好租户上下文才能稳定使用。</li>
        </ul>
      </section>

      <section class="mt-8">
        <h2 class="text-body font-semibold">作品集试用账号</h2>
        <p class="mt-3 leading-7 text-muted-foreground">
          对外展示只用下面两个账号。它们看不到内部会话、系统参数和其他人的文档授权，也不能上传、删除或改配置。
        </p>
        <ul class="mt-3 list-disc space-y-2 pl-5 leading-7 text-muted-foreground">
          <li>会话端：账号 <code class="rounded bg-muted px-1">guest</code>，密码 <code class="rounded bg-muted px-1">Look-Guest-2026</code></li>
          <li>管理端：账号 <code class="rounded bg-muted px-1">reviewer</code>，密码 <code class="rounded bg-muted px-1">Look-Review-2026</code></li>
        </ul>
      </section>

      <section class="mt-8">
        <h2 class="text-body font-semibold">权限怎么理解</h2>
        <ul class="mt-3 list-disc space-y-2 pl-5 leading-7 text-muted-foreground">
          <li>会话端和管理端各自登录，token 不能混用。</li>
          <li>普通用户只能检索被授权的文档；没有文档级授权时，列表会是空的。</li>
          <li>文档授权即时生效，不用重建索引。可管理包含可写，可写包含可读。</li>
        </ul>
      </section>

      <p class="mt-10">
        <RouterLink class="text-primary underline-offset-4 hover:underline" to="/chat">返回会话</RouterLink>
        <span class="mx-2 text-muted-foreground">·</span>
        <RouterLink class="text-primary underline-offset-4 hover:underline" to="/admin/dashboard">返回管理后台</RouterLink>
      </p>
    </article>
  </div>
</template>
