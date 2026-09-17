import { expect, test } from '@playwright/test'
import { installMockApp, setAdminAuthenticated } from './fixtures/mockApp.js'

const documentId = '2493109390115110913'
const planId = '2493109390115110945'
const parseTaskId = '2493109390115110914'

async function installProfilePlan(page, rejection = null) {
  await installMockApp(page)
  const state = { confirmed: false, profile: 'QA_PAIR', requests: [] }
  const respond = (route, data) => route.fulfill({ json: { code: '0', message: 'ok', data } })
  await page.route('**/manage/document/detail/query', (route) => respond(route, {
    documentId, documentName: '问答策略确认测试', originalFileName: 'qa-fixture.md',
    knowledgeBaseId: 'kb-fixture', knowledgeBaseName: '隔离测试库',
    currentPlanId: planId, lastParseTaskId: parseTaskId,
    parseStatus: '3', strategyStatus: state.confirmed ? '3' : '2', indexStatus: '1'
  }))
  await page.route('**/manage/document/strategy/plan/query', (route) => respond(route, {
    planReady: true, parseStatus: '3', plan: {
      planId, planVersion: '7',
      parentPipeline: { steps: [{ stepNo: '1', strategyType: '1' }] },
      childPipeline: { steps: [{ stepNo: '1', strategyType: '2' }] },
      chunkingContract: {
        schemaVersion: 'chunking-contract.v1', recommendedProfile: 'QA_PAIR', profile: state.profile,
        ruleVersion: state.profile === 'QA_PAIR' ? 'qa-pair.v1' : 'generic.v1',
        sourceParseTaskId: parseTaskId, sourceSha256: 'a'.repeat(64), confirmed: state.confirmed,
        reason: 'QA_EXPLICIT_PAIRS',
        budget: { parentMaxChars: '2200', childMaxChars: '800', overlapChars: '120', maxInputBytes: '8192' }
      }
    }
  }))
  await page.route('**/manage/document/strategy/confirm', async (route) => {
    const request = route.request().postDataJSON()
    state.requests.push(request)
    if (rejection) {
      await route.fulfill({ status: 400, json: { code: '400', message: rejection } })
      return
    }
    state.confirmed = true
    state.profile = request.chunkingProfile
    await respond(route, { documentId, planId, planVersion: '7', strategyStatus: '3' })
  })
  await page.goto('/chat')
  await setAdminAuthenticated(page, true)
  await page.goto(`/admin/documents/${documentId}`)
  await page.getByRole('button', { name: '确认并构建', exact: true }).click()
  return state
}

for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
  test(`confirms the displayed QA profile and requires reconfirmation after changing it at ${viewport.width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport)
    const state = await installProfilePlan(page)
    const profile = page.getByRole('combobox', { name: '切块画像' })
    await expect(profile).toContainText('问答对（QA_PAIR）')
    const confirm = page.getByRole('button', { name: '确认策略方案', exact: true })
    await expect(confirm).toBeEnabled()
    await confirm.click()
    await expect(page.getByRole('button', { name: '策略方案已确认', exact: true })).toBeDisabled()
    expect(state.requests).toHaveLength(1)
    expect(state.requests[0]).toMatchObject({
      documentId, basePlanId: planId, basePlanVersion: '7', sourceParseTaskId: parseTaskId,
      chunkingProfile: 'QA_PAIR',
      parentSteps: [{ stepNo: '1', strategyType: '1' }],
      childSteps: [{ stepNo: '1', strategyType: '2' }]
    })
    const build = page.locator('[data-workbench-section="execution"]').getByRole('button', { name: /构建索引执行|先确认策略方案|请先重新确认/ })
    await expect(build).toBeEnabled()

    await profile.click()
    await page.getByRole('option', { name: '通用（GENERIC）', exact: true }).click()
    await expect(build).toBeDisabled()
    await page.getByRole('button', { name: '重新确认策略方案', exact: true }).click()
    await expect(page.getByRole('button', { name: '策略方案已确认', exact: true })).toBeDisabled()
    expect(state.requests).toHaveLength(2)
    expect(state.requests[1]).toMatchObject({
      basePlanVersion: '7', sourceParseTaskId: parseTaskId, chunkingProfile: 'GENERIC'
    })
    await expect(build).toBeEnabled()
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(0)
    await page.screenshot({ path: testInfo.outputPath('profile-confirmation.png'), fullPage: true, animations: 'disabled' })
  })
}

test('preserves an actionable stale-revision rejection and keeps indexing blocked', async ({ page }) => {
  const message = '解析版本已变化，请刷新策略方案后重新确认。（PROFILE_PARSE_REVISION_STALE）'
  const state = await installProfilePlan(page, message)
  const confirm = page.getByRole('button', { name: '确认策略方案', exact: true })
  await confirm.click()
  await expect(page.getByText(message, { exact: true })).toBeVisible()
  await expect(confirm).toBeEnabled()
  await expect(page.getByRole('button', { name: '先确认策略方案', exact: true })).toBeDisabled()
  expect(state.requests).toHaveLength(1)
})
