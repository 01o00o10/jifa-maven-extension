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

> [English](https://github.com/eclipse/jifa/blob/main/README.md)

## 简介

Eclipse Jifa 是一款在线分析工具，支持分析 Java 堆转储、GC 日志、线程转储以及 JFR 文件。

关于项目的更多信息请访问 [GitHub Pages](https://eclipse-jifa.github.io/jifa/zh/)。

## 快速上手

### Maven 构建

项目的 Java 模块现在由 Maven 管理。使用 JDK 17+ 执行：

```shell
mvn clean package -DskipTests
java -jar server/target/jifa.jar
```

前端仍使用自身的 npm 构建流程；发布前请先执行 `npm ci && npm run build-only`（在 `frontend` 目录），然后再打包服务端。

### AI/MCP 诊断接口

MCP 已拆分为独立模块，可单独构建和部署，不依赖 Jifa server：

```shell
mvn -pl mcp -am clean package
java -jar mcp/target/jifa-mcp.jar
```

服务启动后，AI 客户端可通过 `POST /mcp` 使用 JSON-RPC 调用 Jifa 的分析能力：

```json
{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
```

调用工具时，工具名为 `namespace.api`，参数中必须提供已上传文件的 `target` 唯一名称：

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"heap-dump.overview","arguments":{"target":"example.hprof"}}}
```

`tools/list` 会根据当前注册的分析插件动态返回工具和参数信息，`tools/call` 会复用 Jifa 原有的权限、文件定位、Worker 调度和分析执行链路。

### [在线演示 🛝](https://jifa.dragonwell-jdk.io)

### 本地运行 Jifa

#### Docker

```shell
# 默认服务地址是 http://localhost:8102
curl -fsSL https://raw.githubusercontent.com/eclipse/jifa/main/jifa.sh | bash

# 修改服务端口
curl -fsSL https://raw.githubusercontent.com/eclipse/jifa/main/jifa.sh | bash -s -- -p <port>

# 分析本地文件
curl -fsSL https://raw.githubusercontent.com/eclipse/jifa/main/jifa.sh | bash -s -- <file1 path> <file2 path> ...

# 设置 JVM 参数
curl -fsSL https://raw.githubusercontent.com/eclipse/jifa/main/jifa.sh | bash -s -- --jvm-options "<JVM options>"
```

注：本地环境需要安装 docker

#### jpackage

使用Java 14及以上版本提供的`jpackage`命令打包成二进制安装包，便于在本地使用（单机模式）。请执行以下脚本：

```shell

jifa-jpackage.{sh | bat}

```

## 相关链接

- [GitHub Pages](https://eclipse-jifa.github.io/jifa/zh/)
- 钉钉交流群二维码

  <div>
    <img src=https://user-images.githubusercontent.com/33491035/226314386-e1cf71d4-8429-4e4c-bdc0-c511a9009ee1.JPG alt="DingTalk" width=35%/>
  </div>
