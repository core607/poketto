# 内容仓与文档基础

Date: 2026-08-26
Status: Implemented

[English](2026-08-26-content-foundation.md)

## 问题

Poketto 必须先建立稳定的内容边界，才能实现写入、投影、检索、渲染或 MCP 工具。[需求文档](2026-08-25-requirements-and-architecture.zh.md)已经确定：独立的 git 仓库是真理之源，文档身份是全仓唯一的 UUID，revision 是内容 hash。已实现的[工作空间边界](2026-08-27-workspace-tenancy.md)进一步规定每个工作空间拥有一个仓库。本决策定义仓库初始化契约、受管路径布局、frontmatter schema、机器写入的规范形式和 revision 编码。

如果这些细节分别在后续功能中自行成形，同一份文档就会在 content、projection、web 和 MCP 模块中得到互不兼容的表示。

## 决策

### 数据目录与仓库初始化

- `poketto.data-dir` 必须显式配置为绝对路径，不得默认指向应用源码目录或容器文件系统内的路径。
- 每个工作空间的内容工作树固定在 `<data-dir>/workspaces/<workspace-id>/content`。路径只能从已经校验的 `WorkspaceId` 解析；工作空间名称、slug 和调用者提供的路径都不能选择目录。其他持久化工作空间数据以后可以使用同级目录，但除非决策明确规定，否则不得放进内容仓。
- 内容目录不存在或为空时，创建初始分支为 `main` 的非裸仓库，但不创建提交。第一次文档写入负责生成根提交，不增加虚构的初始化提交。
- 内容目录已有仓库时，只接受当前分支为 `main` 的非裸工作树，也接受尚无提交的 `main`；已有 remote 与仓库配置必须保留。
- 非空目录如果不是 git 仓库，拒绝自动初始化。错误必须指出具体路径，并提示运维者改用空目录，或先显式初始化并提交已有内容。
- 裸仓库、当前分支不是 `main` 的工作树或元数据不可读的仓库都会导致启动失败。修复仓库与切换分支仍由运维者操作。

仓库校验与错误信息同时指出工作空间和解析后的路径，但不得泄露其他工作空间的目录。测试各自使用临时的绝对数据目录。本地运行说明解释这项必需配置。

### 受管文档布局

- 内容仓中只有 `documents/` 下的 Markdown 受 Poketto 管理。仓库根目录可以保留说明或配置文件，而不会被当成用户文档。
- 路径表示位置，不表示身份。文档可以移动到 `documents/` 下的任意位置，UUID 不随之改变。
- 接受任意层级的 UTF-8 `.md` 文件。拒绝绝对路径、路径穿越、非 Markdown 扩展名，以及在 Unicode NFC 规范化和大小写折叠后发生的路径冲突，确保同一仓库在 Windows 与 Linux 上行为一致。

### Frontmatter 与正文

机器写入的每份文档都由 YAML frontmatter 和 Markdown 正文组成：

```markdown
---
id: 550e8400-e29b-41d4-a716-446655440000
title: 示例文档
visibility: private
tags:
  - 示例
created_at: 2026-08-26T09:00:00Z
updated_at: 2026-08-26T09:00:00Z
---

Markdown 正文。
```

- `id` 是规范的小写 UUID，创建后不可改变。content 层负责保证它在所属工作空间仓库内唯一；另一个工作空间可以独立使用相同文档 UUID。
- `title` 必填；去除首尾空白后不得为空，也不得包含控制字符。
- `visibility` 只能是 `private` 或 `public`。
- `tags` 必须是显式 YAML 序列。每项去除首尾空白后必须是非空字符串；经 Unicode 规范化和大小写折叠后重复的标签无效，但保留原始显示拼写。
- `created_at` 与 `updated_at` 是必填的 RFC 3339 UTC 时间。机器写入必须保留 `created_at`；序列化后的文档发生变化时必须推进 `updated_at`。这条变更规则由本层的规范序列化持有；后续加入的写入口复用它，而不是各自重述。
- `published_at` 可选。第一次 publish 操作设置它；后续编辑或把 visibility 改回 private 都不得清除它。
- 机器写入不得包含未知字段、重复 YAML key、alias、自定义 tag、多份 YAML 文档、错误的分隔符、无效 UTF-8 或字节顺序标记。
- 正文可以为空。本层只把它作为文本保存，不负责渲染 Markdown、清理 HTML、抓取链接或解释其中的指令。

机器写入按上例字段顺序序列化 frontmatter；存在 `published_at` 时把它放在 `updated_at` 之后；使用 UTF-8 与 LF 换行；正文前保留一个空行；文件末尾保留一个换行。人工提交不必采用规范布局；架构规定投影对无效文件作 lint 标记。

### 身份与 revision 类型

- content 模块操作必须接收 `WorkspaceId`，并对外提供不可变的文档 ID、revision、visibility、metadata 和文档内容值类型。JGit 与 YAML 实现类放在 `content.internal` 下。
- revision 是所选 git tree 中原始 blob 字节的 SHA-256，编码为 `sha256:<lowercase-hex>`；content 模块之外必须把整个值视为不透明 token。
- 不得根据解析后的字段或 commit SHA 生成 revision。格式或换行变化也是编辑，必须产生新的 revision。
- 扫描 git tree 时检测重复的文档 UUID。仓库完整性错误必须列出所有冲突路径，绝不能擅自选择其中一份文档。

### 已实现范围

content 模块绑定数据目录，初始化和校验各工作空间的仓库，解析并规范序列化文档，对外提供内容值类型，并扫描已提交的 `main` tree。当前不提供 create、update、delete、publish、投影、HTTP 或 MCP 入口；这些操作建立在本边界之上。

## 备选方案

把数据目录默认为 `./data` 会让首次运行更容易，但也可能悄悄把持久内容放进源码目录或临时容器层。显式绝对路径使持久化位置成为运维者的主动决定。

自动初始化任意已有目录会简化导入，但可能悄悄接纳未经检查的文件，也会让第一次提交的所有权含糊不清。Poketto 接管已有内容前，必须由运维者显式初始化并提交。

把仓库中的所有 Markdown 都视为文档可以省掉一层目录，但仓库自身的说明和元数据就无法安全共存。`documents/` 是唯一受管子树。

让所有工作空间共用一个内容仓、按子目录区分可以减少仓库数量，但会让历史、备份、恢复和破坏性操作跨越安全边界。每个工作空间独立一仓，使仓库级操作始终局限在一个租户内。

使用路径或 slug 作为身份可以简化查找，但重命名会变成删除后重建，也会破坏稳定的 MCP 引用。frontmatter 中的 UUID 让身份独立于目录组织和公开 URL。

对解析后的内容计算 hash 可以忽略无意义的格式变化，但需要一套语义规范化契约，也可能让乐观并发控制看不见编辑。对原始 git blob 字节计算 hash 与实际提交内容一致，并且不依赖编程语言。

允许任意 frontmatter 字段便于扩展，但拼写错误也会成为持久数据，各下游模块还可能自行推断出不同 schema。项目尚无兼容义务，schema 演进应当保持显式。

## 验证

- `ContentRepositoryBootstrapTests` 在临时数据目录中覆盖目录不存在、空目录、有效已有仓库、非空非仓库、裸仓库、不可读仓库、错误分支和尚无提交的 `main`。
- `CanonicalDocumentCodecTests`、`DocumentValueTests` 与 `DocumentPathRulesTests` 覆盖字段不变量、YAML 限制、规范字节、空正文与 Unicode 正文、路径校验、标签规范化、时间变更、发布时间往返解析和精确字节 revision。
- `ContentRepositoryScanTests` 验证工作空间隔离、已提交 tree 读取、重复 UUID 检测和跨平台路径冲突报告。
- `ModularityTests` 验证 content 对外契约依赖 `WorkspaceId`，且不暴露 JGit 或 YAML 实现类型。
- `./gradlew test`、`./gradlew integrationTest`、`./gradlew repoCheck` 与 `git diff --check` 覆盖本实现。集成测试验证工作空间 catalog 初始化与内容仓库引导能在 PostgreSQL 环境中共同完成。

## 风险

严格 frontmatter 意味着未来增加字段前，必须先显式修改 schema 并补充测试。首次发布前可以接受这项约束，它能阻止 schema 意外膨胀。

基于原始字节的 revision 会把人工换行或格式调整显示为冲突。机器输出采用规范形式；把人工字节变化视为真正的 revision，比悄悄覆盖更安全。

全仓扫描的复杂度与文档数量线性相关。这是最简单且正确的基础；后续可以增加内存 catalog 或派生索引，但不得改变 git 的权威地位。

按工作空间拆仓会增加 Git 句柄和扫描数量。仓库资源必须为作用域操作打开并确定性关闭；第一轮不维护无限增长的常驻仓库缓存。
