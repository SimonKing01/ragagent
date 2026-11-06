#!/usr/bin/env python3
"""统一响应外壳守卫（B183 上线）。

背景：B183 定了「一套外壳 + 一个解包点」——`@ApiResult` 控制器成功体统一
`{code,message,data}`（`platform/web/ApiResponse`），错误体同形（`GlobalExceptionHandler`
按请求打标分派）；前端唯一解包点 `frontend/src/utils/request.ts`。约定见
`docs/api/README.md`。

四条规则：
  R-a 每个控制器要么标注 `@ApiResult`，要么在 `scripts/api-envelope.baseline.json`
      的 `pending` 里 —— 二者皆非 ⇒ 红。**新控制器默认必须合规**（不允许悄悄绕过）。
  R-b pending **只许减不许增**：清单里的控制器若已标注 `@ApiResult` ⇒ 红
      （迁完就要删条目；清单本身就是迁移进度表）。
  R-c 清单不许有幽灵：路径已不存在 ⇒ 红。
  R-d 已标注 `@ApiResult` 的控制器里不得再手搓 `"success"` 字面量 ⇒ 红
      （既套外壳又自己拼 {success:…} 会产出双层壳）。

`--write` 只**清理已迁完/已消失**的清单条目（与 check-file-size 的 --write 同口径：
只收紧，不豁免）；新增待迁移条目必须人工决策后手改 JSON。
"""

import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import _source_roots as _sr  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASELINE = ROOT / "scripts" / "api-envelope.baseline.json"

ANNOTATION = "@ApiResult"
# 判定用正则：短名与全限定名（@com.ragagent.common.web.ApiResult）都算数——
# 子串匹配曾在自检里漏判全限定写法（B183 实翻车一次 ✗）。
ANNOTATION_RE = re.compile(r"@(?:[\w.]+\.)?ApiResult\b")
SUCCESS_LITERAL = re.compile(r'"success"\s*:')


def code_only(text):
    """剥掉注释后的源码（R-a / R-d 判定用）。

    起因（B189 实测）：javadoc 里述及 "@ApiResult" 或 "success":false 会让判定**误报**——
    DataSourceCredentialsController 就因此在守卫下假红过一次 ✓。
    """
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    keep = []
    for line in text.split('\n'):
        s = line.strip()
        if s.startswith('//') or s.startswith('*') or s.startswith('/*'):
            continue
        keep.append(line)
    return '\n'.join(keep)


def controllers():
    """{相对路径: 源文本}——全部 main 源集里的 *Controller.java。"""
    out = {}
    for _srcset, _module, directory in _sr.java_roots(("main",)):
        for p in sorted(pathlib.Path(directory).rglob("*Controller.java")):
            out[str(p.relative_to(ROOT))] = p.read_text(encoding="utf-8")
    return out


def load_pending():
    if not BASELINE.exists():
        return {}
    return json.loads(BASELINE.read_text(encoding="utf-8")).get("pending", {})


def main():
    write = "--write" in sys.argv[1:]
    files = controllers()
    pending = load_pending()

    annotated = {f for f, t in files.items() if ANNOTATION_RE.search(code_only(t))}
    problems = []

    # R-a 未标注且未登记
    for f in sorted(files):
        if f not in annotated and f not in pending:
            problems.append(
                f"R-a 未走统一外壳且未登记 {f}："
                f"标注 {ANNOTATION}（新代码默认必须合规），或人工加入 pending 清单并说明"
            )

    # R-c 幽灵条目
    for f in sorted(pending):
        if f not in files:
            problems.append(f"R-c 清单幽灵条目 {f}：控制器已不存在/已改名，请 --write 清理")

    # R-b 已迁完仍在清单
    for f in sorted(pending):
        if f in annotated:
            problems.append(f"R-b 已迁移仍在清单 {f}：请 --write 收紧（清单只许减不许增）")

    # R-d 双壳
    for f in sorted(annotated):
        if SUCCESS_LITERAL.search(code_only(files[f])):
            problems.append(f"R-d 双壳 {f}：已标注 {ANNOTATION} 却仍手搓 \"success\" 键")

    total = len(files)
    done = len(annotated)
    print(f"统一响应外壳：{done}/{total} 个控制器已迁移"
          f"（pending {len(pending)}，约定见 docs/api/README.md）")

    if write:
        kept = {f: v for f, v in pending.items() if f in files and f not in annotated}
        dropped = sorted(set(pending) - set(kept))
        BASELINE.write_text(
            json.dumps({"pending": kept}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"  已清理 {len(dropped)} 条：{', '.join(dropped) if dropped else '（无）'}")
        problems = [p for p in problems if not p.startswith("R-b") and not p.startswith("R-c")]

    if problems:
        print("\n✗ 守卫失败：")
        for p in problems:
            print("   " + p)
        return 1

    print("\n✓ 守卫通过：全部控制器已迁或已登记；清单无幽灵、无已迁残留、无双壳。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
