#!/usr/bin/env python3
"""R1/R2：契约形状预算（端口 ≤7 方法 / `*RowView`·`*PageView` 禁入 / 类字段 ≤20）。

判据：
  - `*Port` 接口方法数 ≤7；
  - `*RowView` / `*PageView` 不得进 contract（它们是域内投影，不是跨域词汇）；
  - 契约类的实例字段数 ≤20（record 按组件数，class 按非 static 字段数）；
    H6 治理后收紧到 12。

存量违例登记在 `scripts/contract-shape.baseline.json`（逐条附理由，只许减不许增）：
  - 端口：方法数只能下降，不能新增条目；
  - 行视图：条目只能删除；
  - 字段：字段数只能下降。

红态探针：给任一未登记 Port 加第 8 个方法（或在 knowledge 包加一个 `*RowView`），
本脚本必须非零退出；恢复即绿。
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "contract/src/main/java"
BASELINE = ROOT / "scripts/contract-shape.baseline.json"

MAX_PORT_METHODS = 7
# 先按 20 卡住"明显膨胀"的形状；H6 治理后再收紧到 12。
MAX_FIELDS = 20

PORT_METHOD_RE = re.compile(
    r"^(?:default\s+)?(?:static\s+)?[\w<>,\.\[\]? ]+\s+\w+\s*\([^;{]*\)\s*(?:throws[^;{]*)?[;{]"
)
RECORD_RE = re.compile(r"record\s+\w+\s*\(([^)]*)\)", re.S)
FIELD_RE = re.compile(
    r"^\s*(?:private|public|protected)\s+(?!static\b)(?:final\s+)?[\w<>,\.\[\] ?]+\s+\w+\s*(?:=|;)"
)
TYPE_DECL_RE = re.compile(r"^(record|class|interface|enum)\b")


def count_port_methods(path: Path) -> int:
    """只数端口自身的成员；嵌套类型（异常/值类型）里的成员不计入预算。

    行扫描要跟踪花括号深度：接口体为 1 层，嵌套类型内部为 2 层以上。
    不跟踪深度会把嵌套异常类的构造器算成端口方法，重载一个构造器就报预算超限。
    """
    n = 0
    depth = 0
    for line in path.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if depth == 1 and s and not s.startswith(("*", "/", "@", "import", "package", "//")):
            if not TYPE_DECL_RE.match(s) and PORT_METHOD_RE.match(s):
                n += 1
        depth += s.count("{") - s.count("}")
        if depth < 0:
            depth = 0
    return n


def count_fields(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    record = RECORD_RE.search(text)
    if record:
        return len([p for p in record.group(1).split(",") if p.strip()])
    return sum(1 for line in text.splitlines() if FIELD_RE.match(line))


def main() -> int:
    baseline = json.loads(BASELINE.read_text(encoding="utf-8")) if BASELINE.exists() else {}
    allowed_ports = baseline.get("ports", {})
    allowed_rows = baseline.get("rows", {})
    allowed_fields = baseline.get("fields", {})

    problems = []
    seen_ports = set()
    seen_rows = set()
    seen_fields = set()

    for path in sorted(SRC.rglob("*.java")):
        if path.name == "package-info.java":
            continue
        rel = str(path.relative_to(SRC))
        stem = path.stem
        if stem.endswith("Port"):
            methods = count_port_methods(path)
            seen_ports.add(stem)
            limit = allowed_ports.get(stem, {}).get("methods", MAX_PORT_METHODS)
            if methods > limit:
                problems.append(
                    f"端口 {stem} 有 {methods} 个方法（上限 {limit}）——拆窄或登记基线"
                )
        if stem.endswith("RowView") or stem.endswith("PageView"):
            seen_rows.add(rel)
            if rel not in allowed_rows:
                problems.append(
                    f"{rel} 是域内行/页投影，不应进 contract——回域或登记基线"
                )
        fields = count_fields(path)
        if fields > MAX_FIELDS:
            seen_fields.add(rel)
            limit = allowed_fields.get(rel, {}).get("fields", MAX_FIELDS)
            if fields > limit:
                problems.append(
                    f"{rel} 有 {fields} 个字段（上限 {limit}）——收窄形状或登记基线"
                )

    print("▶ 契约形状预算（端口 ≤7 方法 / 行视图禁入 / 字段 ≤20）")
    if problems:
        print("✗ 守卫失败：")
        for p in problems:
            print(f"    {p}")
        print("    处置：拆窄 / 回域 / 收窄形状；存量债务登记 scripts/contract-shape.baseline.json")
        return 1

    stale = []
    stale += [f"ports:{k}" for k in allowed_ports if k not in seen_ports]
    stale += [f"rows:{k}" for k in allowed_rows if k not in seen_rows]
    stale += [f"fields:{k}" for k in allowed_fields if k not in seen_fields]
    print(
        f"✓ 守卫通过：端口 {len(seen_ports)} / 行视图 {len(seen_rows)} / "
        f"字段预算命中 {len(seen_fields)}（基线 {len(allowed_ports) + len(allowed_rows) + len(allowed_fields)} 条）"
    )
    if stale:
        print(f"    注意：基线条目已失效，可清理收紧：{stale}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
