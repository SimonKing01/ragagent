#!/usr/bin/env python3
"""版本一致性守卫：根 pom / README / Dockerfile / 子模块 pom 不得漂移。

判据（硬零）：
  V1 根 pom 的 Spring Boot 与 README 的 badge/技术栈大版本一致；
  V2 Boot 3.3 对应 springdoc 2.8.x；
  V3 mybatis-plus starter 与 jsqlparser 共用同一个版本引用；
  V4 子模块 pom 不得自行 import spring-boot-dependencies（BOM 只在根 pom）；
  V5 Dockerfile 的 JDK 大版本与 pom 的 java.version 一致。

红态探针：把 README badge 的 3.3 改成 3.2，本脚本必须非零退出；跑完恢复。
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
POM = ROOT / "pom.xml"
README = ROOT / "README.md"
DOCKERFILE = ROOT / "Dockerfile"


def props(text: str) -> dict[str, str]:
    """粗解析 <properties>：本项目只用字面值，够用且不引第三方 XML 依赖。"""
    m = re.search(r"<properties>(.*?)</properties>", text, re.S)
    seg = m.group(1) if m else ""
    return dict(re.findall(r"<([\w.\-]+)>([^<]+)</\1>", seg))


def managed_versions(pom_text: str) -> dict[str, str]:
    """dependencyManagement 里 artifactId → 版本引用（字面量或 ${property}）。"""
    m = re.search(r"<dependencyManagement>(.*?)</dependencyManagement>", pom_text, re.S)
    seg = m.group(1) if m else ""
    out = {}
    for blk in re.findall(r"<dependency>.*?</dependency>", seg, re.S):
        aid = re.search(r"<artifactId>([^<]+)</artifactId>", blk)
        ver = re.search(r"<version>([^<]+)</version>", blk)
        if aid and ver:
            out[aid.group(1)] = ver.group(1)
    return out


def main() -> int:
    pom_text = POM.read_text(encoding="utf-8")
    p = props(pom_text)
    boot = p.get("spring-boot.version", "")
    springdoc = p.get("springdoc.version", "")
    java_version = p.get("java.version", "")
    if not boot or not springdoc or not java_version:
        print("✗ 版本一致性守卫失败：")
        print(f"   V0 根 pom 缺少必要属性（spring-boot.version / springdoc.version / java.version）：{sorted(p)}")
        return 1

    boot_mm = ".".join(boot.split(".")[:2])
    readme = README.read_text(encoding="utf-8")
    problems = []

    # V1 README 的大版本与 pom 一致（badge 用 %20，正文用空格；两处都检查）
    badges = re.findall(r"Spring%20Boot-(\d+\.\d+)", readme)
    bodies = re.findall(r"Spring Boot (\d+\.\d+)", readme)
    if not badges and not bodies:
        problems.append(f"V1 README 未体现 Spring Boot {boot_mm}（pom 为 {boot}）")
    for found in badges + bodies:
        if found != boot_mm:
            problems.append(f"V1 README 写的是 Spring Boot {found}，pom 为 {boot}")

    # V2 Boot 与 springdoc 对应线
    if boot_mm == "3.3" and not springdoc.startswith("2.8."):
        problems.append(f"V2 Boot {boot} 应配 springdoc 2.8.x，当前 {springdoc}")

    # V3 jsqlparser artifact 与 starter 共用同一版本引用
    refs = managed_versions(pom_text)
    starter_ref = refs.get("mybatis-plus-spring-boot3-starter")
    parser_ref = refs.get("mybatis-plus-jsqlparser")
    if not starter_ref or not parser_ref or starter_ref != parser_ref:
        problems.append(
            f"V3 mybatis-plus-jsqlparser 必须与 starter 共用版本引用"
            f"（{starter_ref} / {parser_ref}）"
        )

    # V4 子模块 pom 不得自行声明 Boot BOM
    for module_pom in ROOT.glob("*/pom.xml"):
        if "spring-boot-dependencies" in module_pom.read_text(encoding="utf-8"):
            problems.append(f"V4 {module_pom.relative_to(ROOT)} 自行声明了 Boot BOM")

    # V5 Dockerfile 的 JDK 版本与 pom 的 java.version 一致
    docker = DOCKERFILE.read_text(encoding="utf-8")
    docker_jdks = re.findall(r"eclipse-temurin[:\-](\d+)", docker)
    if not docker_jdks:
        problems.append("V5 Dockerfile 未找到 eclipse-temurin 基础镜像")
    for jdk in docker_jdks:
        if jdk != java_version:
            problems.append(f"V5 Dockerfile JDK {jdk} ≠ pom java.version {java_version}")

    if problems:
        print("✗ 版本一致性守卫失败：")
        for problem in problems:
            print("   " + problem)
        return 1
    print(
        f"✓ 版本一致性：Boot {boot} / springdoc {springdoc} / JDK {java_version} "
        "与 README、Dockerfile 一致"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
