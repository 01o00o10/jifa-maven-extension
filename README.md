<!--
    Copyright (c) 2020, 2023 Contributors to the Eclipse Foundation

    See the NOTICE file(s) distributed with this work for additional
    information regarding copyright ownership.

    This program and the accompanying materials are made available under the
    terms of the Eclipse Public License 2.0 which is available at
    http://www.eclipse.org/legal/epl-2.0

    SPDX-License-Identifier: EPL-2.0
 -->
# Eclipse Jifa

[![Eclipse License](https://img.shields.io/github/license/eclipse/jifa?label=License)](https://github.com/eclipse/jifa/blob/main/LICENSE)
![Commit Check](https://github.com/eclipse/jifa/actions/workflows/commit-check.yml/badge.svg?branch=main)

> [中文](https://github.com/eclipse/jifa/blob/main/README_zh.md)

## Introduction

Online Analyzer for Heap Dump, GC Log, Thread Dump and JFR File.

Please refer to [GitHub Pages](https://eclipse-jifa.github.io/jifa/) for more information.

## 本分支相对原生 Jifa 的改造

这个分支在保留 Jifa Heap Dump、GC Log、Thread Dump 和 JFR 原有分析能力的基础上，完成了以下扩展：

| 改造项 | 当前实现 |
| --- | --- |
| 构建系统 | Java 模块从 Gradle 一比一迁移到 Maven，根 POM 统一管理全部模块和测试 |
| Heap Dump | 保留 MAT、OSGi Bundle、HPROF Hook 注入和本地 `mat-deps` 装配行为 |
| 前端打包 | Maven 自动执行 npm/Vite 构建，并将 Vue 静态资源装入最终 Spring Boot JAR |
| MCP | 新增可独立打包和启动的 `mcp` 模块，动态暴露 Jifa Analysis API |
| AI 诊断 | 新增 DeepSeek、Qwen 等 OpenAI 兼容模型配置、工具调用、SSE 流式回复和强制收敛 |
| 前端助手 | 新增悬浮 AI 助手、文件选择、多文件独立会话、配置页面、回复编号和调用进度 |
| 会话保存 | 服务端会话保存在文件所属分析机器，浏览器保存当前展示记录，不依赖 Redis |
| 集群支持 | Master 将 AI 请求及运行时模型配置转发给文件所属 Worker 执行 |

完整设计说明见 [Jifa 底层原理系列](docs/architecture/README.md)，迁移和开发中遇到的问题见
[Maven、MCP 与 AI 改造问题复盘](docs/maven-mcp-ai-troubleshooting.md)。

### 构建与启动

要求 JDK 17。根目录执行以下命令会构建后端、分析模块、Vue 前端和独立 MCP：

```shell
mvn clean install
```

启动 Jifa 主服务：

```shell
java -jar server/target/jifa.jar
```

Jifa 使用 `jifa.port` 配置服务端口，例如：

```shell
java -jar server/target/jifa.jar --jifa.port=19090
```

独立构建并启动 MCP：

```shell
mvn -pl mcp -am clean package
java -jar mcp/target/jifa-mcp.jar \
  --server.port=18081 \
  --jifa.mcp.allowed-root=/absolute/path/to/jifa-storage
```

`jifa.mcp.allowed-root` 必须包含 Jifa 实际保存分析文件的目录。主服务与 MCP 分进程运行时，两者必须能访问同一份文件；MCP 会拒绝根目录之外的路径。

### AI 配置

推荐使用环境变量保存持久配置，避免把 API Key 提交到仓库：

```shell
export JIFA_AI_ENABLED=true
export JIFA_AI_PROVIDER=deepseek
export JIFA_AI_API_KEY=<your-api-key>
export JIFA_AI_BASE_URL=https://api.deepseek.com
export JIFA_AI_MODEL=deepseek-chat
export JIFA_AI_MCP_URL=http://127.0.0.1:18081/mcp
java -jar server/target/jifa.jar
```

也可以在前端 `/ai-settings` 页面修改当前进程的运行时配置。页面配置立即生效并覆盖启动配置，但只保存在内存中，进程重启后需要重新配置；API Key 不会回显到浏览器。

悬浮助手会按分析文件保存对话。服务端最近 8 轮上下文存放在
`${jifa.storage-path}/ai-sessions`，前端展示记录存放在当前浏览器的 `localStorage`；刷新页面或切换文件后可以恢复。

### 主要产物

- `server/target/jifa.jar`：包含 Vue 前端和全部分析能力的主服务。
- `server/target/jifa.zip`：主服务发布压缩包。
- `mcp/target/jifa-mcp.jar`：可单独部署的 MCP 服务。
- `analysis/heap-dump/provider/target/*.jar`：包含 MAT 运行时装配结果的 Heap Dump Provider。

## Quick Start

### [Playground 🛝](https://jifa.dragonwell-jdk.io)

### Run Jifa Locally

#### Docker

```shell
# Default service address is at http://localhost:8102
curl -fsSL https://raw.githubusercontent.com/eclipse/jifa/main/jifa.sh | bash

# Change the server port
curl -fsSL https://raw.githubusercontent.com/eclipse/jifa/main/jifa.sh | bash -s -- -p <port>

# Analyze local files
curl -fsSL https://raw.githubusercontent.com/eclipse/jifa/main/jifa.sh | bash -s -- <file1 path> <file2 path> ...

# Set JVM Options
curl -fsSL https://raw.githubusercontent.com/eclipse/jifa/main/jifa.sh | bash -s -- --jvm-options "<JVM options>"
```

Note: Please make sure that Docker is installed.

#### jpackage

Use the 'jpackage' command provided by Java 14 and above to package it into a binary installation package for local use (stand-alone mode). Please execute the following script:

```shell

jifa-jpackage.{sh | bat}

```

## Links
- [GitHub Pages](https://eclipse-jifa.github.io/jifa/)
