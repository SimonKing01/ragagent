import assert from 'node:assert/strict'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

/**
 * 跨面对照守卫（源码扫描）——本仓反复栽在"写的人按一套键名、读的人按另一套"上
 * （HANDOFF §15.1.1 B19/B20 教训：静默失效，不报错）。
 *
 * 本文件钉三类形态：
 * ① 侧栏会话"乐观行"：写入侧（views 里 updataMenuChildren({...})）与读取侧
 *    （components/menu.vue 的 menuChildToSessionRow）必须用同一套 camel 键；
 *    曾写成 created_at/updated_at → 时间戳读成 undefined → 新会话落「更早」。
 *    （2026-10-03 点检实锤）
 * ② 表格死插槽：`<template #x>` 的名字必须等于同文件某个 `colKey: 'x'`，
 *    否则插槽永不生效、单元格回落原始值（曾出现 #created_at vs colKey 'createdAt'
 *    → 审计页时间列显示原始 UTC 字符串而不是格式化后的本地时间）。（同上）
 * ③ 知识面卡片视图模型的键一律 camel：Go 时代遗留的 original_file_name /
 *    display_name / error_message 属内部映射层键（接口给的是 fileName/errorMessage），
 *    写读两套命名会静默失效 —— 其中 error_message 全仓无人读，失败原因因此在
 *    卡片上不可见。（2026-10-04 收口，见 §15.1.1）
 * ④ api 面时间键 `*_at` 一律 camel：`api/auth` 曾读 user.created_at（接口给
 *    createdAt）→ 兜底把「注册时间」写成当前时刻；邀请行的 responded_at、
 *    wiki 修订的 edited_at 同源。冻结/直出载荷按面白名单登记（见用例）。
 */
const SRC = fileURLToPath(new URL('..', import.meta.url))

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) walk(full, out)
    else if (full.endsWith('.vue') || full.endsWith('.ts')) out.push(full)
  }
  return out
}

const files = walk(SRC).filter((f) => !f.endsWith('.test.ts'))

/** 花括号配平地取出从 `start`（指向 `{`）开始的对象字面量文本。 */
function sliceObject(source: string, start: number): string {
  let depth = 0
  for (let i = start; i < source.length; i++) {
    if (source[i] === '{') depth++
    else if (source[i] === '}') {
      depth--
      if (depth === 0) return source.slice(start, i + 1)
    }
  }
  return source.slice(start)
}

/**
 * 取出所有会话乐观行的对象字面量。两种写法都要覆盖（红态探针实测过：
 * 只认内联对象会漏掉 `const obj = {...}; updataMenuChildren(obj)` 这种形态，
 * 而 creatChat.vue 恰好就是它 —— 守卫会静默失去判别力）。
 */
function extractMenuChildObjects(source: string): string[] {
  const out: string[] = []
  for (const m of source.matchAll(/updataMenuChildren\(\s*([A-Za-z_$][\w$]*|\{)/g)) {
    const arg = m[1]
    if (arg === '{') {
      out.push(sliceObject(source, (m.index ?? 0) + m[0].length - 1))
      continue
    }
    // 变量形态：取调用点之前、最近的一次同名对象字面量赋值
    const assignRe = new RegExp(`(?:const|let|var)\\s+${arg}\\s*=\\s*\\{`, 'g')
    let last: RegExpExecArray | null = null
    for (let ex = assignRe.exec(source); ex; ex = assignRe.exec(source)) {
      if (ex.index > (m.index ?? 0)) break
      last = ex
    }
    if (last) {
      out.push(sliceObject(source, last.index + last[0].length - 1))
    } else {
      out.push('') // 解析不到 → 由断言报红，避免静默漏检
    }
  }
  return out
}

test('侧栏乐观会话行：写入侧必须提供读取侧要读的时间键（camel）', () => {
  const menu = readFileSync(join(SRC, 'components/menu.vue'), 'utf8')
  // 读取侧契约（改动这里必须同步改所有写入侧，本用例即为同步闸门）
  assert.match(menu, /createdAt: typeof item\.createdAt === 'string'/)
  assert.match(menu, /updatedAt: typeof item\.updatedAt === 'string'/)

  const writers: Array<{ file: string; block: string }> = []
  for (const file of files) {
    for (const block of extractMenuChildObjects(readFileSync(file, 'utf8'))) {
      writers.push({ file, block })
    }
  }
  assert.ok(writers.length >= 1, '未找到任何 updataMenuChildren 写入点（守卫需同步更新）')

  for (const { file, block } of writers) {
    assert.match(block, /createdAt\s*:/, `${file}: 乐观行缺 createdAt（读取侧读不到 → 会话落「更早」）`)
    assert.match(block, /updatedAt\s*:/, `${file}: 乐观行缺 updatedAt`)
    assert.doesNotMatch(block, /\bcreated_at\s*:/, `${file}: 乐观行不得用 snake created_at`)
    assert.doesNotMatch(block, /\bupdated_at\s*:/, `${file}: 乐观行不得用 snake updated_at`)
  }
})

test('知识库文件面：时间读侧必须用接口的 camel 键', () => {
  // 2026-10-03 点检实锤：这几处读 created_at/updated_at（接口给的是 createdAt/updatedAt，
  // 见 api/knowledge 的文件列表与 KB 详情接口）→ 时间恒为空/NaN、信息卡"创建时间"行永不显示。
  const targets = [
    'hooks/useKnowledgeBase.ts',
    'views/knowledge/components/DocumentCardView.vue',
    'views/knowledge/components/DocumentListView.vue',
    'components/KBInfoPopover.vue',
  ]
  for (const rel of targets) {
    const source = readFileSync(join(SRC, rel), 'utf8')
    assert.doesNotMatch(source, /\.(created_at|updated_at)\b/,
      `${rel}: 读接口时间请用 createdAt/updatedAt（camel）`)
  }
})

test('表格插槽名必须等于同文件某个 colKey（防死插槽）', () => {
  for (const file of files.filter((f) => f.endsWith('.vue'))) {
    const source = readFileSync(file, 'utf8')
    const colKeys = new Set([...source.matchAll(/colKey:\s*'([^']+)'/g)].map((m) => m[1]))
    for (const m of source.matchAll(/<template\s+#([a-z0-9]+_[a-z0-9_]+)=/g)) {
      const slot = m[1]
      assert.ok(
        colKeys.has(slot),
        `${file}: 插槽 #${slot} 在同文件里没有对应 colKey（插槽永不生效；colKeys=${[...colKeys].join(', ')}）`,
      )
    }
  }
})

test('知识面卡片视图模型：键一律 camel（防 snake 遗留回流）', () => {
  // 2026-10-04 收口。这三个键只活在前端映射层（useKnowledgeBase 把接口项映射成卡片
  // 视图模型），接口下发的是 camel（fileName / errorMessage）：
  //   original_file_name → originalFileName（全名，供下载）
  //   display_name       → displayName（去扩展名，供卡片展示）
  //   error_message      → errorMessage（失败原因；旧名全仓无人读写=死字段，
  //                        紧凑模式时间线又不显示原因，于是卡片上永远看不到失败原因）
  // 旧名一旦回流，读侧拿到的就是 undefined 且不报错，正是本仓反复踩的坑。
  const legacy: Array<[string, string]> = [
    ['original_file_name', 'originalFileName'],
    ['display_name', 'displayName'],
    ['error_message', 'errorMessage'],
  ]
  const knowledgeFace = [
    ...walk(join(SRC, 'views/knowledge')).filter((f) => !f.endsWith('.test.ts')),
    join(SRC, 'hooks/useKnowledgeBase.ts'),
  ]
  for (const file of knowledgeFace) {
    const source = readFileSync(file, 'utf8')
    for (const [oldKey, camelKey] of legacy) {
      assert.doesNotMatch(
        source,
        new RegExp(`\\b${oldKey}\\b`),
        `${file}: 内部视图模型键请用 camel ${camelKey}（旧名 ${oldKey} 读到的恒为 undefined）`,
      )
    }
  }

  // 写入侧（映射层）必须真的提供这三个 camel 键 —— 只删旧名不补新名同样是断链。
  const mapping = readFileSync(join(SRC, 'hooks/useKnowledgeBase.ts'), 'utf8')
  assert.match(mapping, /\boriginalFileName\s*:/, 'useKnowledgeBase: 卡片映射缺 originalFileName')
  assert.match(mapping, /\bdisplayName\s*[,:]/, 'useKnowledgeBase: 卡片映射缺 displayName')
  // 读取侧：下载名解析（原名称 → camel 键）
  const download = readFileSync(join(SRC, 'views/knowledge/knowledgeDownloadFileName.ts'), 'utf8')
  assert.match(download, /\boriginalFileName\b/, 'knowledgeDownloadFileName: 需读 camel originalFileName')

  // 失败原因必须被消费：接口给 errorMessage，卡片浮层是唯一出口
  // （紧凑模式时间线只渲染阶段点+耗时，不含 lastError）。
  const card = readFileSync(join(SRC, 'views/knowledge/components/DocumentCardView.vue'), 'utf8')
  assert.match(card, /\berrorMessage\b/, 'DocumentCardView: 需消费接口 errorMessage（否则失败原因不可见）')
})

test('api 面时间键：`*_at` 一律 camel（防 snake 读到 undefined）', () => {
  // 2026-10-04 续：api 层是线格式面，写 snake 时间键 = 读恒 undefined。
  // 实锤过的四处：api/auth 的 user.created_at（兜底把「注册时间」写成当前时刻）、
  // 邀请行的 created_at/responded_at、wiki 修订的 edited_at、InviteLookup.expires_at。
  // 现有 python 守卫（check-fe-contract-keys.py）抓不到这类：created_at 在 SQL/DB
  // 列名里到处都是，被判成「后端仍认 snake」而跳过 —— 本用例是它的补位。
  //
  // 白名单是「按面」而不是漏检，每条都要写明理由：
  const whitelist: Record<string, string> = {
    'api/chat/index.ts': '本地游标形参名（线上参数是 beforeTime，@RequestParam camel；注释已声明）',
    'api/chat/streame.ts': 'TTFB 调试日志字段（非契约键）',
    'api/system/index.ts': '系统设置键 + 沙箱/任务引擎直出载荷（§15.2 纪律：不换）',
  }
  const SNAKE_AT = /(?<![A-Za-z0-9_])([a-z][a-z0-9]*(?:_[a-z0-9]+)*_at)(?![A-Za-z0-9_])/g
  const targets = walk(join(SRC, 'api')).filter(
    (f) => !f.endsWith('.test.ts') && !Object.keys(whitelist).some((w) => f.endsWith(w)),
  )
  assert.ok(targets.length > 0, '未扫到 api 面文件（守卫需同步更新）')
  for (const file of targets) {
    readFileSync(file, 'utf8').split('\n').forEach((line, idx) => {
      const code = line.split('//')[0]
      for (const m of code.matchAll(SNAKE_AT)) {
        assert.fail(
          `${file}:${idx + 1}: 时间键 ${m[1]} 应改 camel（接口下发 camel，snake 读到的恒为 undefined）。`
            + '若确属冻结/直出载荷，请加入本用例白名单并写明理由。',
        )
      }
    })
  }

  // 钉住用户可见的那处：个人资料页「注册时间」读的就是这个字段。
  const auth = readFileSync(join(SRC, 'api/auth/index.ts'), 'utf8')
  assert.match(auth, /createdAt:\s*u\?\.createdAt/,
    'api/auth: userInfoFromApi 必须读 user.createdAt（camel），否则注册时间被兜底写成当前时刻')
})

test('信封读法：Java 后端是裸载荷，不得再按 Go 的 {success,data} 取数', () => {
  // 2026-10-04 点检实锤：集成设置页读 `userResp.data.tenant`，而 /auth/me 的 tenant
  // 在顶层（{user, tenant, memberships, …}）→ 恒 undefined → 整页抛
  // 「加载 API 集成设置失败」；同页 agents 读 `resp.data`，而 /agents 返回
  // {agents, disabledOwnAgentIds} → 选择器静默为空。
  // 这两处读法随初始建仓从 Go 仓整份复制而来（Go 的响应是 {success,data} 包裹），
  // 后端换 Java 后信封没了，读侧却没同步——与 ③④ 同族：**写侧换了形状，读侧没跟上**。
  const page = readFileSync(join(SRC, 'views/integrations/ApiIntegrationSettings.vue'), 'utf8')
  assert.doesNotMatch(
    page,
    /\?\.data\?\.tenant\b/,
    '集成设置页：/auth/me 的 tenant 在顶层，读 .data.tenant 恒 undefined（整页报加载失败）',
  )
  assert.doesNotMatch(
    page,
    /Array\.isArray\(resp\?\.data\)/,
    '集成设置页：/agents 返回 {agents,…}，读 resp.data 得到 undefined（智能体列表静默为空）',
  )
  // 钉住正确读法（与 IMChannelPanel / AgentEmbedChannelPanel 一致）
  assert.match(page, /agents\.value = Array\.isArray\(resp\?\.agents\)/,
    '集成设置页：agents 列表须读 resp.agents（裸信封）')
  assert.match(page, /\?\.tenant\b/, '集成设置页：tenant 须从 /auth/me 顶层读取')

  // 同族清剿（同日逐条实测端点形状）：这些消费点此前都按 Go 的 {success,data} 取数，
  // Java 后端已改裸载荷 → 读到的恒 undefined，症状是「静默为空 / 误报失败 / 入口消失」。
  // 每条都写明端点真实形状，防止有人照旧写法改回去。
  const barePayloadReads: Array<{ file: string; forbid: RegExp; shape: string }> = [
    {
      file: 'views/chat/index.vue',
      forbid: /res\?\.data\?\.questions/,
      shape: '/agents/{id}/suggested-questions 直出数组（creatChat.vue 即规范读法），否则开场建议永远为空',
    },
    {
      file: 'views/knowledge/components/FAQEntryManager.vue',
      forbid: /res\?\.data\?\.taskId/,
      shape: 'POST .../faq/entries → FaqTaskStartResponse{taskId}，否则导入进度条永不出现',
    },
    {
      file: 'views/knowledge/KnowledgeBase.vue',
      forbid: /res\?\.data\?\.moved_count/,
      shape: 'PUT .../knowledge/folders → FolderMoveResponse{folderPath, movedCount}，否则成功也弹「重命名失败」',
    },
    {
      file: 'views/knowledge/KnowledgeBase.vue',
      forbid: /res\.data\?\.task_id/,
      shape: 'POST /knowledge/move → MoveKnowledgeResponse{taskId,…}（camel 裸载荷）',
    },
    {
      file: 'views/knowledge/KnowledgeBase.vue',
      forbid: /folderTree\.value = \(res\?\.data/,
      shape: 'GET .../knowledge/folders → {rootDocumentCount,totalDocumentCount,folders}，否则文件夹树为空',
    },
    {
      file: 'views/knowledge/KnowledgeBase.vue',
      forbid: /moveTargetKbs\.value = res\.data/,
      shape: 'GET .../move-targets 直出数组，否则移动对话框没有目标库',
    },
    {
      file: 'views/knowledge/KnowledgeBase.vue',
      forbid: /const data = res\.data;/,
      shape: 'GET /knowledge/move/progress/{taskId} 直出 KnowledgeMoveProgress，否则轮询永不推进',
    },
    {
      file: 'views/knowledge/KnowledgeBase.vue',
      forbid: /res\?\.success && knowledgeSpansPayloadHasTrace/,
      shape: 'GET /knowledge/{id}/spans 直出载荷，否则「查看处理轨迹」入口被隐藏',
    },
  ]
  for (const { file, forbid, shape } of barePayloadReads) {
    assert.doesNotMatch(
      readFileSync(join(SRC, file), 'utf8'),
      forbid,
      `${file}: 不得按旧 {success,data} 信封取数——${shape}`,
    )
  }
})

test('统一响应外壳在 request.ts 一处解包（api 层直返载荷、store 读裸载荷）', () => {
  // 后端 @ApiResult 路由的成功体统一 {code:0,message:"ok",data:…}
  // （约定见 docs/api-response-convention.md）；解包点**唯一** = utils/request.ts 的响应拦截器
  // ⇒ api 层不再需要「裸载荷适配成 { data }」的补丁，消费端直接读载荷。
  // 旧约定：「裸载荷 + 消费端要 { data } → 在 api 层 return { data: resp }」随外壳退役。
  const request = readFileSync(join(SRC, 'utils/request.ts'), 'utf8')
  assert.match(request, /function isApiEnvelope\(/, '外壳判定必须在 request.ts（唯一解包点）')
  assert.match(request, /return withHttpStatus\(data\.data, status\)/, '外壳要就地解包成 data')

  const agentApi = readFileSync(join(SRC, 'api/agent/index.ts'), 'utf8')
  assert.doesNotMatch(agentApi, /\.then\(\(resp\) => \(\{ data: resp \}\)\)/,
    'getPlaceholders：解包后不得再手工包 { data }')
  assert.doesNotMatch(agentApi, /data: Array\.isArray\(resp\) \? resp : \[\]/,
    'getAgentTypePresets：同上')
  assert.match(agentApi, /return get<PlaceholdersResponse>\('\/api\/v1\/agents\/placeholders'\)/,
    'getPlaceholders 直返载荷')
  assert.match(agentApi, /return get<AgentTypePreset\[\]>\('\/api\/v1\/agents\/type-presets'\)/,
    'getAgentTypePresets 直返载荷')

  const store = readFileSync(join(SRC, 'stores/editorResources.ts'), 'utf8')
  assert.match(store, /placeholders\.value = placeholdersRes \?\? null/,
    'store 直接读载荷（解包在 request.ts 做，别两边都改）')
  assert.match(store, /agentTypePresets\.value = Array\.isArray\(presetsRes\) \? presetsRes : \[\]/,
    'store 直接读载荷')
})

test('解析引擎规则两面：KB 配置面 camel / 覆盖与智能体面 snake（不得混读）', () => {
  // 实锤：KB 配置 jsonb 键名已统一成 camel（V2 迁移 + 后端 ParserEngineRuleView），
  // 但解析设置页仍按 snake 读 → 对 camel 数据 `undefined.some` 整页打崩（真机复现：
  // 解析分区 select 数 0 + Unhandled Vue error）；写入侧也因键名不符被后端丢弃
  // （库里出现 fileTypes: []）。
  const parserSettings = readFileSync(join(SRC, 'views/knowledge/settings/KBParserSettings.vue'), 'utf8')
  assert.doesNotMatch(
    parserSettings,
    /file_types|xlsx_first_row_as_header/,
    'KBParserSettings：KB 配置面是 camel（fileTypes / xlsxFirstRowAsHeader），读 snake 会 undefined.some 崩页',
  )
  assert.match(
    parserSettings,
    /normalizeKbParserRules\(props\.parserEngineRules\)/,
    'KBParserSettings：初始读取必须过归一化（容忍 legacy snake，且保证 fileTypes 一定是数组）',
  )
  assert.match(
    parserSettings,
    /localEngineRules\.value = normalizeKbParserRules\(v\)/,
    'KBParserSettings：props 变化时的同步也必须归一化',
  )

  const chunking = readFileSync(join(SRC, 'views/knowledge/settings/KBChunkingSettings.vue'), 'utf8')
  assert.doesNotMatch(
    chunking,
    /file_types|xlsx_first_row_as_header/,
    'KBChunkingSettings：同样只认 KB 配置面（camel）',
  )

  const upload = readFileSync(join(SRC, 'views/knowledge/components/UploadConfirmDialog.vue'), 'utf8')
  assert.doesNotMatch(
    upload,
    /parserEngineRules\?: Array<\{\s*file_types/,
    'UploadConfirmDialog：chunkingConfig 的规则类型是 KB 配置面（camel）',
  )
  assert.match(
    upload,
    /parserEngineRules: toOverrideParserRules\(chunking\.parserEngineRules \?\? \[\]\)/,
    '搬进上传覆盖时仍走显式转换（覆盖载荷已统一 camel）',
  )
  assert.match(
    upload,
    /s\.chunkingConfig\.parserEngineRules = fromOverrideParserRules\(/,
    '从覆盖搬回 KB 配置时必须显式转回 camel',
  )

  // 上传覆盖面已统一为 camel（自有 JSON 面，后端只落 metadata、无运行时读者）；
  // 智能体面**必须**保持 snake —— 后端 ParserEngineRules.resolve 读 rule.get("file_types")。
  const overridesType = readFileSync(join(SRC, 'types/knowledgeProcess.ts'), 'utf8')
  assert.match(overridesType, /fileTypes: string\[\]/,
    'types/knowledgeProcess：上传覆盖面是 camel（自有契约，与 KB 配置面一致）')
  assert.doesNotMatch(overridesType, /file_types:\s*string\[\]/,
    'types/knowledgeProcess：上传覆盖面不得回流 snake（守卫 check-json-key-case 亦禁止）')
  const agentApi = readFileSync(join(SRC, 'api/agent/index.ts'), 'utf8')
  assert.match(agentApi, /chatParserEngineRules\?: \{ file_types: string\[\]; engine: string \}\[\]/,
    'api/agent：智能体 chatParserEngineRules 的内层键仍是 snake（运行时契约）')
})

test('api 面 snake 记号棘轮：只许减不许增', () => {
  // 2026-10-04 扩面冻结 → 同日逐条核实。
  // 首轮扫描 api 面 40 处存量记号（32 个 file:token 键），先冻结成棘轮防新增；
  // 本轮对后端逐个核实（DTO 字段名 / 显式 put 的键 / JsonNode 读取 / 实测接口）后：
  //   · 13 键改 camel：偏好 lastActiveTenantId、oidcOnlyLogin，模型 isDefault，
  //     MCP requireApproval，KB 摘要 creatorId/creatorName/chunkCount/knowledgeCount，
  //     wiki 六键（pageCount/hasChildren/familiarCount/issueType/suspectedKnowledgeIds/reportedBy）
  //     —— 其中 6 处有真实消费点（wiki 页数/问题标签/举报人、MCP 审核开关、偏好落库…）。
  //   · 6 键删除（死字段）：agent reflection_enabled/sandbox_config_id/welcome_message/
  //     suggested-questions 的 knowledge_base_ids、auth browser_search_instructions、UserInfo.knowledge_bases。
  //   · 13 键保留为「已核实合法」（下方 BASELINE，每条写明判定依据）。
  // 口径：新增一处 snake 即红；清理一处后从 BASELINE 删除（只许减）；基线条目消失会报过期。
  const FACE_WHITELIST: Record<string, string> = {
    'api/chat/': 'SSE/事件载荷与本地游标（事件协议面）；B135b2 起事件面已全 camel，'
      + '本条目仅覆盖面内残留，规则见 HANDOFF §15.3',
    'api/system/index.ts': '系统设置 KV + 沙箱/任务引擎直出载荷（§15.2 纪律：冻结面不换；expires_at_unix 已于 B72 修 camel）',
  }
  // —— 原「整文件白名单」api/model/modelUsage.ts 已删除（其理由「前后端一致」对任何线格式
  //    键都成立 ⇒ 等于没有理由），改为逐键登记下方 10 条。该文件自此与其它 api 面同受
  //    「新增 snake 即红」约束。
  const BINDING_CODE_INTERNAL_MAP =
    '已核实：binding 码（值）在前端局部映射表里当键用——非线格式键（后端产出的是值，键面全 camel）'
  const BASELINE: Record<string, string> = {
    'api/agent/index.ts:file_types': '已核实：后端 ParserEngineRules 按 file_types 读规则 jsonb，两侧一致',
    'api/model/modelUsage.ts:asr_model': BINDING_CODE_INTERNAL_MAP,
    'api/model/modelUsage.ts:chat_model': BINDING_CODE_INTERNAL_MAP,
    'api/model/modelUsage.ts:embedding_model': BINDING_CODE_INTERNAL_MAP,
    'api/model/modelUsage.ts:follow_up_model': BINDING_CODE_INTERNAL_MAP,
    'api/model/modelUsage.ts:image_processing_model': BINDING_CODE_INTERNAL_MAP,
    'api/model/modelUsage.ts:query_understand_model': BINDING_CODE_INTERNAL_MAP,
    'api/model/modelUsage.ts:rerank_model': BINDING_CODE_INTERNAL_MAP,
    'api/model/modelUsage.ts:summary_model': BINDING_CODE_INTERNAL_MAP,
    'api/model/modelUsage.ts:vlm_model': BINDING_CODE_INTERNAL_MAP,
    'api/model/modelUsage.ts:wiki_synthesis_model': BINDING_CODE_INTERNAL_MAP,
    'api/embed/index.ts:channel_id': '已核实：embed 宿主↔iframe 消息协议（widget.js 与 Vue 侧同一套键）',
    'api/embed/index.ts:session_id': '已核实：同上',
    'api/knowledge-base/index.ts:tag_ids': '已核实：列表筛选查询参数（后端按 snake 接收）',
    'api/knowledge-base/index.ts:start_time': '已核实：同上',
    'api/knowledge-base/index.ts:end_time': '已核实：同上',
    // —— 后续收口：api/initialization 移出整面白名单，余量逐条登记（均为 testMultimodalFunction
    //    的本地入参键，发送前逐一映射为 camel form 字段；InitializationController @RequestParam 全 camel）
    'api/initialization/index.ts:vlm_model': '已核实：多模态联调本地入参键（映射 vlmModel form 字段）',
    'api/initialization/index.ts:storage_type': '已核实：同上（映射 storageType）',
    'api/initialization/index.ts:cos_secret_id': '已核实：同上（映射 cosSecretId）',
    'api/initialization/index.ts:cos_secret_key': '已核实：同上（映射 cosSecretKey）',
    'api/initialization/index.ts:cos_region': '已核实：同上（映射 cosRegion）',
    'api/initialization/index.ts:cos_bucket_name': '已核实：同上（映射 cosBucketName）',
    'api/initialization/index.ts:cos_app_id': '已核实：同上（映射 cosAppId）',
    'api/initialization/index.ts:cos_path_prefix': '已核实：同上（映射 cosPathPrefix）',
    'api/initialization/index.ts:minio_path_prefix': '已核实：同上（映射 minioPathPrefix）',
    'api/initialization/index.ts:chunk_size': '已核实：同上（映射 chunkSize）',
    'api/initialization/index.ts:chunk_overlap': '已核实：同上（映射 chunkOverlap）',
  }
  const stripStrings = (line: string): string => line.replace(/'[^']*'|"[^"]*"|`[^`]*`/g, '""')
  // 不锚定行首：行内对象字面量（`{ some_key: 1 }`）也要抓（初版锚定行首，红态探针漏检）
  const DECL = /(?<![\w$.])([a-z][a-z0-9]*_[a-z0-9_]+)\??\s*:/
  const READ = /\.([a-z][a-z0-9]*_[a-z0-9_]+)\b/

  const found = new Map<string, string>() // key → 首个位置
  const root = SRC.endsWith('/') ? SRC : `${SRC}/`
  const targets = walk(join(SRC, 'api')).filter(
    (f) => f.endsWith('.ts') && !f.endsWith('.test.ts')
      && !Object.keys(FACE_WHITELIST).some((w) => f.includes(w)),
  )
  assert.ok(targets.length > 0, '未扫到 api 面文件（守卫需同步更新）')
  for (const file of targets) {
    const rel = file.slice(root.length)
    readFileSync(file, 'utf8').split('\n').forEach((line, idx) => {
      const code = stripStrings(line.split('//')[0])
      const tokens = [...code.matchAll(new RegExp(DECL, 'g')), ...code.matchAll(new RegExp(READ, 'g'))]
      for (const m of tokens) {
        const token = m[1]
        if (token.includes('__')) continue
        const key = `${rel}:${token}`
        if (!found.has(key)) found.set(key, `${rel}:${idx + 1}`)
      }
    })
  }

  const unexpected = [...found.entries()].filter(([k]) => !(k in BASELINE))
  if (unexpected.length) {
    assert.fail(
      'api 面出现未登记的 snake 记号（接口下发 camel 时读到的恒为 undefined）：\n'
        + unexpected.map(([k, loc]) => `    ${k}（${loc}）`).join('\n')
        + '\n  改 camel；若确属冻结/直出/查询参数面，请在 BASELINE 登记理由（或加入 FACE_WHITELIST）。',
    )
  }
  const stale = Object.keys(BASELINE).filter((k) => !found.has(k))
  if (stale.length) {
    assert.fail(
      'BASELINE 有过期条目（已不存在），请删除以保持「只许减」：\n'
        + stale.map((k) => `    ${k}（原理由：${BASELINE[k]}）`).join('\n'),
    )
  }
})

test('B72 回归钉：DRIFT 修复面不得回流 snake（chunk 编辑/FAQ 标签/上传回显/图谱提取/Ollama 列表）', () => {
  // 2026-10-05 全量排查修掉的 9 条「前端读/写 snake、后端发/收 camel」失配。
  // 后端 Jackson FAIL_ON_UNKNOWN=off，键名失配不报错、字段静默丢 —— 本用例钉住修复面防回流。
  const forbid = (rel: string, re: RegExp, why: string) => {
    assert.doesNotMatch(readFileSync(join(SRC, rel), 'utf8'), re, `${rel}: ${why}`)
  }
  const require = (rel: string, re: RegExp, why: string) => {
    assert.match(readFileSync(join(SRC, rel), 'utf8'), re, `${rel}: ${why}`)
  }

  // ① chunk 编辑面：UpdateChunkRequest = record(content, enabled, expectedRevision)，camel 绑定零注解；
  //    ChunkResponse 下发 startAt/endAt/contentRevision。is_enabled/expected_revision 曾把启用开关
  //    和乐观锁静默打失效；start_at/end_at 曾让合并预览的间隙检测恒不触发。
  forbid('components/doc-content.vue', /\b(expected_revision|is_enabled|content_revision|start_at|end_at)\b/,
    'chunk 面一律 camel：expectedRevision/enabled/contentRevision/startAt/endAt')
  // ② FAQ 标签：KnowledgeTagResponse.seqId（camel record；对照 KbTagManageDrawer.vue 的读法）
  forbid('views/knowledge/components/FAQEntryManager.vue', /\bseq_id\b/,
    '标签序号读 seqId（snake 曾让标签恒显「未分类」、选择器 value 变 undefined）')
  // ③ 上传回显：KB 配置面内层键是 camel（ChunkExtractService 读 customInstructions）；
  //    覆盖面写侧的 custom_instructions（buildProcessOverrides）是冻结面，不在本钉范围。
  forbid('views/knowledge/components/UploadConfirmDialog.vue', /extractConfig\?\.\s*custom_instructions/,
    'kb.extractConfig 是 KB 配置面（camel），只有覆盖面写侧才用 snake')
  // ④ 图谱提取/Ollama 列表/联调计时：后端读 "modelId"（空即 400）、出站 modifiedAt/processingTime
  forbid('api/initialization/index.ts', /\b(model_id|modified_at|processing_time)\b/,
    'initialization 面契约键 camel：modelId/modifiedAt/processingTime')
  forbid('views/knowledge/settings/GraphSettings.vue', /\bmodel_id\b/,
    'fabriText/extractTextRelations 请求体用 modelId（snake 曾让两个按钮必 400）')
  forbid('views/settings/OllamaSettings.vue', /\bmodified_at\b/,
    'OllamaManageService 出站键已是 modifiedAt（snake 只存在于 Ollama→后端入站段）')
  // ⑤ 平台 API Key 有效期：PlatformAPIKeyCreateRequest.expiresAtUnix（潜伏断链）
  forbid('api/system/index.ts', /\bexpires_at_unix\b/, '平台 API Key 创建载荷用 expiresAtUnix')
  // ⑥ SSE 死读：后端无任何事件发平名 created_at —— agentQuery 的时间键是
  //    userCreatedAt/assistantCreatedAt（QaSseOrchestrator；事件面已翻转为 camel，
  //    这 3 键是漏扫尾巴），
  //    agentQuery 的绑定走 bindServerTurnTimestamps；userMessageInjected 无时间键。
  forbid('composables/useChatStreamHandler.ts', /data\.created_at/,
    'SSE 载荷无平名 created_at（读到的恒 undefined）')
  require('utils/messageTimestamp.ts', /payload\.assistantCreatedAt/,
    'assistant 时间读 assistantCreatedAt（SSE 面已全 camel，别再读 snake——B135b2）')
  // ⑦ 集成页请求预览串必须与实发一致（后端 QaRequests 全 camel）
  forbid('views/integrations/ApiIntegrationSettings.vue', /agent_enabled:/,
    '预览串与实发一致：agentEnabled/agentId（照抄旧预览会写出 agent 模式失效的请求）')
})


test('B149 回归钉：2300 details 的键面（knowledgeBases）不得回流 snake', () => {
  // ModelService 亲手构造的 2300 details 键面全 camel（唯一漏网的 knowledge_bases 已换锚）；
  // snake 读写会让「模型被占用」对话框静默空掉。三处 = api 类型 + 视图读侧 + 视图源码扫描断言。
  //
  // 为什么只有这一项：预设 kbFilter 的 allOf/noneOf 不在此钉——scripts/check-fe-contract-keys.py
  // 已天然覆盖它（后端已无对应 snake 字面量 ⇒ 前端任何文件再写即「新增即红」，覆盖面比钉子更全）；
  // 而 knowledge_bases 在 backend 仍作为 DB 表名/模板令牌存在 ⇒ 按守卫设计被跳过，故须在此显式钉住。
  const forbidSnake = (rel: string, why: string) => assert.doesNotMatch(
    readFileSync(join(SRC, rel), 'utf8'), /\bknowledge_bases\b/, `${rel}: ${why}`)
  forbidSnake('api/model/modelUsage.ts', '键是 knowledgeBases')
  forbidSnake('views/settings/ModelSettings.vue', '读 usageConflict.knowledgeBases')
  forbidSnake('views/settings/modelUsageDetails.test.ts', '源码扫描断言同上')
})

test('embed 用户气泡底色跟随渠道主题色（后台「外观展示」的 primaryColor）', () => {
  // 需求（2026-10-10 点检）：embed 挂件里用户消息（.user_msg）的底色要用后台配置的主题色。
  // 链路：渠道 config.primaryColor → EmbedPage 落成 --embed-primary（内联 style 挂在 .embed-page
  // 根，自定义属性随子树继承，含 iframe/shadow 边界）→ 气泡侧必须 var(--embed-primary, 回落值)，
  // 回落值 = 原写死的灰底，保证「未配置主题色」时行为与从前完全一致。
  const bubble = readFileSync(join(SRC, 'views/embed/EmbedUserMessage.vue'), 'utf8')
  assert.match(
    bubble,
    /background:\s*var\(--embed-primary,\s*var\(--td-gray-color-14\)\)/,
    '亮色：用户气泡底色应为 var(--embed-primary, 原灰底)',
  )
  assert.match(
    bubble,
    /background:\s*var\(--embed-primary,\s*var\(--td-bg-color-secondarycontainer\)\)/,
    '暗色：同上',
  )
  const page = readFileSync(join(SRC, 'views/embed/EmbedPage.vue'), 'utf8')
  assert.match(page, /'--embed-primary':\s*color/, 'EmbedPage 必须把 primaryColor 落成 --embed-primary')
})
