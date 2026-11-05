#!/usr/bin/env python3
"""本地 OCR 服务（RapidOCR，CPU）。

用法：
    python3 -m venv /tmp/ocrenv
    /tmp/ocrenv/bin/pip install rapidocr-onnxruntime
    /tmp/ocrenv/bin/python scripts/ocr-server.py 18092

接口：
    GET  /health -> {"ok": true}
    POST /ocr    -> 请求体为图片字节，返回 {"text": "...", "lines": n, "elapsedMs": n}

只做"图片 → 文字"，不依赖大模型；后端通过 knowledge.ocr.endpoint 调用。
"""

import json
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from rapidocr_onnxruntime import RapidOCR

ENGINE = RapidOCR()
# 推理串行化：onnxruntime 并发推理会成倍放大内存（曾把 700m 上限打爆导致容器重启）
INFER_LOCK = threading.Lock()


def _boxes(result) -> list[dict]:
    """把检测框整理成带坐标的文本块。"""
    boxes = []
    for item in result or []:
        if len(item) < 3 or not item[1] or not item[1].strip():
            continue
        xs = [point[0] for point in item[0]]
        ys = [point[1] for point in item[0]]
        boxes.append({
            "x1": min(xs), "x2": max(xs), "y1": min(ys), "y2": max(ys),
            "cx": (min(xs) + max(xs)) / 2,
            "cy": (min(ys) + max(ys)) / 2,
            "text": item[1].strip(),
        })
    return boxes


def _columns(boxes: list[dict], gap: float) -> list[list[dict]]:
    """把文本块聚成列：中心接近或 x 区间显著重叠都算同一列。

    同一张卡片里的英文名/中文名/价格长短差异大，只比中心会把它们拆成两列。
    """
    columns: list[dict] = []
    for box in sorted(boxes, key=lambda b: b["x1"]):
        placed = False
        for column in columns:
            center = sum(b["cx"] for b in column["items"]) / len(column["items"])
            overlap = min(box["x2"], column["x2"]) - max(box["x1"], column["x1"])
            width = min(box["x2"] - box["x1"], column["x2"] - column["x1"])
            if abs(box["cx"] - center) <= gap or (width > 0 and overlap > 0.5 * width):
                column["items"].append(box)
                column["x1"] = min(column["x1"], box["x1"])
                column["x2"] = max(column["x2"], box["x2"])
                placed = True
                break
        if not placed:
            columns.append({"x1": box["x1"], "x2": box["x2"], "items": [box]})
    return [column["items"] for column in sorted(columns, key=lambda c: c["x1"])]


def _bands(boxes: list[dict], gap: float) -> list[list[dict]]:
    """按页面级水平空白带切分上下层。

    先合并所有框的 y 覆盖区间，只在「整行都没有文字」的位置切开——多列并排时
    各列文本的 y 起点可能不同，逐框比较会误切，投影法不会。
    """
    spans = sorted((b["y1"], b["y2"]) for b in boxes)
    merged: list[list[float]] = []
    for start, end in spans:
        if merged and start <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], end)
        else:
            merged.append([start, end])
    cuts = [(merged[i][1] + merged[i + 1][0]) / 2
            for i in range(len(merged) - 1)
            if merged[i + 1][0] - merged[i][1] > gap]
    groups: dict[int, list[dict]] = {}
    for box in boxes:
        index = sum(1 for cut in cuts if box["cy"] > cut)
        groups.setdefault(index, []).append(box)
    return [groups[key] for key in sorted(groups)]


def _has_parallel_columns(boxes: list[dict]) -> bool:
    """同一水平行内存在两个不重叠的文本块，才算真正的多列。"""
    ordered = sorted(boxes, key=lambda b: b["cy"])
    row: list[dict] = []

    def row_has_columns(items: list[dict]) -> bool:
        if len(items) < 2:
            return False
        spans = sorted((b["x1"], b["x2"]) for b in items)
        return any(spans[i][1] < spans[i + 1][0] - 5 for i in range(len(spans) - 1))

    for box in ordered:
        if row and box["cy"] - row[0]["cy"] > max(b["y2"] - b["y1"] for b in row):
            if row_has_columns(row):
                return True
            row = []
        row.append(box)
    return row_has_columns(row)


def _reading_order_boxes(boxes: list[dict], tolerance: float) -> list[dict]:
    """按行阅读：y 差在容差内视为同一行，行内按 x 从左到右。"""
    rows: list[list[dict]] = []
    for box in sorted(boxes, key=lambda b: b["cy"]):
        if rows and abs(box["cy"] - rows[-1][0]["cy"]) <= tolerance:
            rows[-1].append(box)
        else:
            rows.append([box])
    ordered: list[dict] = []
    for row in rows:
        ordered.extend(sorted(row, key=lambda b: b["x1"]))
    return ordered


def _reading_order_entries(entries: list[tuple[float, float, str]],
                           tolerance: float) -> list[tuple[float, float, str]]:
    """块级阅读顺序：起始 y 相近的块视为同一行，行内按 x 从左到右。"""
    rows: list[list[tuple[float, float, str]]] = []
    for entry in sorted(entries, key=lambda e: e[0]):
        if rows and abs(entry[0] - rows[-1][0][0]) <= tolerance:
            rows[-1].append(entry)
        else:
            rows.append([entry])
    ordered: list[tuple[float, float, str]] = []
    for row in rows:
        ordered.extend(sorted(row, key=lambda e: e[1]))
    return ordered


def recognize(data: bytes) -> str:
    """识别图片字节，按版面坐标还原列关系。

    PPT 导出的页面多为卡片/多列排版，纯按检测顺序输出会把「车名 → 价格 → 定位」
    的对应关系拍平。这里用较大的水平空白把页面分成上下区块（如「上 4 列 + 下 3 列」
    会落在两个区块），区块内按 x 聚列；行内允许小幅 y 误差，按 x 从左到右。
    """
    with tempfile.NamedTemporaryFile(suffix=".jpg") as tmp:
        tmp.write(data)
        tmp.flush()
        with INFER_LOCK:
            result, _ = ENGINE(tmp.name)
    boxes = _boxes(result)
    if not boxes:
        return ""

    page_width = max(b["x2"] for b in boxes) - min(b["x1"] for b in boxes)
    page_height = max(b["y2"] for b in boxes) - min(b["y1"] for b in boxes)
    column_gap = max(page_width * 0.03, 30)
    wide = page_width * 0.45
    row_tolerance = max(page_height * 0.02, 16)

    sections: list[str] = []
    for band in _bands(boxes, max(page_height * 0.12, 60)):
        if not _has_parallel_columns(band):
            # 单列层：标题与正文是上下关系，按阅读顺序输出；聚列会把不相邻的标题并在一起。
            sections.append("\n".join(
                b["text"] for b in _reading_order_boxes(band, row_tolerance)))
            continue
        wide_rows = [b for b in band if (b["x2"] - b["x1"]) > wide]
        normal = [b for b in band if (b["x2"] - b["x1"]) <= wide]
        entries: list[tuple[float, float, str]] = []
        for row in wide_rows:
            entries.append((row["y1"], row["x1"], row["text"]))
        for column in _columns(normal, column_gap):
            ordered = _reading_order_boxes(column, row_tolerance)
            entries.append((ordered[0]["y1"], min(b["x1"] for b in ordered),
                            "\n".join(b["text"] for b in ordered)))
        sections.extend(text for _y, _x, text in
                        _reading_order_entries(entries, row_tolerance))
    return "\n\n".join(section for section in sections if section)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[ocr] " + (fmt % args) + "\n")

    def _send_json(self, status: int, payload: dict):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._send_json(200, {"ok": True})
        else:
            self._send_json(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/ocr":
            self._send_json(404, {"error": "not found"})
            return
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            self._send_json(400, {"error": "empty body"})
            return
        data = self.rfile.read(length)
        started = time.time()
        try:
            text = recognize(data)
        except Exception as exc:  # noqa: BLE001 - 服务边界统一转 500
            self._send_json(500, {"error": str(exc)})
            return
        self._send_json(200, {
            "text": text,
            "lines": text.count("\n") + 1 if text else 0,
            "elapsedMs": int((time.time() - started) * 1000),
        })


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18092
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"ocr server listening on :{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
