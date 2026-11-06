import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

/**
 * 上传类型过滤的守卫：视频不能再被硬编码丢弃，且“全部被过滤”时必须有提示。
 *
 * <p>这里读源码做静态断言，而不是 import 模块——`uploadSources.ts` 依赖 `@/utils`
 * （别名 + Vue/TDesign 运行时），`tsx --test` 起不来。</p>
 */
const sources = readFileSync(new URL('./uploadSources.ts', import.meta.url), 'utf8')
const dropdown = readFileSync(
  new URL('../components/KbUploadSourceDropdown.vue', import.meta.url), 'utf8')
const fileTypes = readFileSync(
  new URL('../../../utils/fileTypeVerification.ts', import.meta.url), 'utf8')

test('视频不再被硬编码过滤（交给引擎清单判断）', () => {
  assert.doesNotMatch(sources, /UPLOAD_VIDEO_EXTENSIONS/)
  assert.doesNotMatch(sources, /videoFilteredCount/)
  // 仍然按后端引擎清单校验
  assert.match(sources, /kbFileTypeVerification\(file, multiFile, dynamicTypes\)/)
})

test('全部文件被过滤时必须给出提示，不能静默返回', () => {
  assert.match(dropdown, /if \(validFiles\.length === 0\)[\s\S]{0,240}MessagePlugin\.warning/)
  assert.match(dropdown, /error\.unsupportedFileType/)
  assert.doesNotMatch(dropdown, /videosFilteredNoVLM/)
})

test('引擎清单未加载时，兜底白名单包含视频扩展名', () => {
  for (const ext of ['mp4', 'mov', 'm4v', 'webm', 'mkv', 'avi']) {
    assert.match(fileTypes, new RegExp(`"${ext}"`), `兜底白名单缺少 ${ext}`)
  }
})
