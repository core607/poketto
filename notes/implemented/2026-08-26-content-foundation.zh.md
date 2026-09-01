# 内容仓与文档基础

Date: 2026-08-26
Status: Implemented

[English](2026-08-26-content-foundation.md)

## 问题

Poketto 必须先建立稳定的内容边界，才能实现写入、投影、检索、渲染或 MCP 工具。[需求文档](2026-08-25-requirements-and-architecture.zh.md)已经确定：独立的 git 仓库是真理之源，文档身份是全仓唯一的 UUID，revision 是内容 hash。已实现的[工作空间边界](2026-08-27-workspace-tenancy.md)进一步规定每个工作空间拥有一个仓库。本决策定义仓库初始化契约、受管路径布局、frontmatter schema、机器写入的规范形式和 revision 编码。

如果这些细节分别在后续功能中自行成形，同一份文档就会在 content、projection、web 和 MCP 模块中得到互不兼容的表示。

[远程仓库权威](2026-09-01-remote-repository-authority.md)取代了本文最初的本地初始化边界，并持有当前的物化与确认语义。下文的文档格式与 revision 决策仍然有效。

## 决策

### 数据目录与仓库缓存

- `poketto.data-dir` 必须显式配置为绝对路径，不得默认指向应用源码目录或容器文件系统内的路径。
- 每个工作空间的一次性内容缓存固定在 `<data-dir>/workspaces/<workspace-id>/content`。路径只能从已经校验的 `WorkspaceId` 解析；工作空间名称、slug、仓库坐标和调用者提供的路径都不能选择目录。其他工作空间数据以后可以使用同级目录，但除非决策明确规定，否则不得放进仓库缓存。
- 必须提供 secret-backed 远程绑定。绑定缺失或无效时直接失败，绝不能把本地缓存误当权威。
- 缓存不存在或为空时，创建非裸 `main` 工作树，抓取远端 `main` 并物化该精确提交。预先建好的空远端保持 unborn；第一次精确 ref 文档写入创建根提交。
- 所有缓存文件都由机器拥有且可随时丢弃。每次读写把 tracked 状态重置到解析出的提交，并移除 untracked 与 ignored 文件。直接创作必须通过私有远端，不能修改缓存。
- 路径不是目录、非空路径不是预期工作树或仓库元数据不可读时拒绝使用。按工作空间计数的配置上限约束常驻缓存，而且只淘汰空闲条目。

仓库与传输错误会指出工作空间，但不暴露仓库坐标或凭据。测试各自使用临时绝对数据目录和一次性裸远端。本地运行说明解释各项必需配置。

### 受管文档布局

- 内容仓中只有 `documents/` 下的 Markdown 受 Poketto 管理。仓库根目录可以保留说明或配置文件，而不会被当成用户文档。
- 路径表示位置，不表示身份。文档可以移动到 `documents/` 下的任意位置，UUID 不随之改变。
- 接受任意层级的 UTF-8 `.md` 文件。拒绝绝对路径、路径穿越、非 Markdown 扩展名、Windows 无法存储的名称（保留设备名、`<>:"|?*` 与控制字符、以点或空格结尾的路径段、`.md` 前为空的文件名），以及在 Unicode NFC 规范化和大小写折叠后发生的路径冲突，确保同一仓库在 Windows 与 Linux 上行为一致。

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
- `created_at` 与 `updated_at` 是必填的 RFC 3339 UTC 时间。机器写入必须保留 `created_at`；序列化后的文档发生变化时必须推进 `updated_at`。这条变更规则由本层的规范序列化持有；[文档写操作](2026-08-29-document-write-operations.md)复用它，而不是各自重述。
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

content 模块绑定数据目录，把各工作空间的远程权威解析为一次性缓存，解析并规范序列化文档，对外提供内容值类型，并扫描 commit-pinned `main` tree。文档写入已经建立在本边界之上；投影、HTTP 与 MCP 入口仍在边界之外。

[仓库原生发布与图片](../proposed/2026-09-01-repository-native-publishing-and-assets.md)提议把目标中的 `documents/`、UUID、逐文件可见性和仅按 hash 引用图片的要求，改为任意层级 Markdown、仓库发布策略、不可变受管引用与只读同目录图片图库。在该提案实现之前，本文仍描述可执行基线；这项反转不会被倒写成当前解析器或仓库布局已经具备的行为。

## 备选方案

把数据目录默认为 `./data` 会让首次运行更容易，但也可能悄悄把持久内容放进源码目录或临时容器层。显式绝对路径使持久化位置成为运维者的主动决定。

接纳已有本地目录会简化导入，却会悄悄恢复本地权威并制造含糊的确认点。Poketto 接管已有内容前，必须先把它提交并 push 到已配置的私有远端。

把仓库中的所有 Markdown 都视为文档可以省掉一层目录，但仓库自身的说明和元数据就无法安全共存。`documents/` 是唯一受管子树。

让所有工作空间共用一个内容仓、按子目录区分可以减少仓库数量，但会让历史、备份、恢复和破坏性操作跨越安全边界。每个工作空间独立一仓，使仓库级操作始终局限在一个租户内。

使用路径或 slug 作为身份可以简化查找，但重命名会变成删除后重建，也会破坏稳定的 MCP 引用。frontmatter 中的 UUID 让身份独立于目录组织和公开 URL。

对解析后的内容计算 hash 可以忽略无意义的格式变化，但需要一套语义规范化契约，也可能让乐观并发控制看不见编辑。对原始 git blob 字节计算 hash 与实际提交内容一致，并且不依赖编程语言。

允许任意 frontmatter 字段便于扩展，但拼写错误也会成为持久数据，各下游模块还可能自行推断出不同 schema。项目尚无兼容义务，schema 演进应当保持显式。

## 验证

- `ContentRepositoryBootstrapTests` 在临时数据目录中覆盖绑定缺失、空远端物化、一次性缓存替换、直接 push、本地改动移除、缓存上限与 secret 不泄露。
- `CanonicalDocumentCodecTests`、`DocumentValueTests` 与 `DocumentPathRulesTests` 覆盖字段不变量、YAML 限制、规范字节、空正文与 Unicode 正文、路径校验、标签规范化、时间变更、发布时间往返解析和精确字节 revision。
- `ContentRepositoryScanTests` 验证工作空间隔离、已提交 tree 读取、重复 UUID 检测和跨平台路径冲突报告。
- `ModularityTests` 验证 content 对外契约依赖 `WorkspaceId`，且不暴露 JGit 或 YAML 实现类型。
- `./gradlew test`、`./gradlew integrationTest`、`./gradlew repoCheck` 与 `git diff --check` 覆盖本实现。集成测试验证工作空间 catalog 初始化与内容仓库引导能在 PostgreSQL 环境中共同完成。

## 风险

严格 frontmatter 意味着未来增加字段前，必须先显式修改 schema 并补充测试。首次发布前可以接受这项约束，它能阻止 schema 意外膨胀。

基于原始字节的 revision 会把人工换行或格式调整显示为冲突。机器输出采用规范形式；把人工字节变化视为真正的 revision，比悄悄覆盖更安全。

全仓扫描的复杂度与文档数量线性相关。这是最简单且正确的基础；后续可以增加内存 catalog 或派生索引，但不得改变 git 的权威地位。

按工作空间拆仓会增加 Git 句柄、缓存和扫描数量。仓库资源只为作用域操作打开并确定性关闭；配置的缓存上限阻止已物化工作空间无限增长。

任一受管文件无效都会让 `scan` 对整个仓库失败，一次带坏文档的 break-glass 提交就会阻塞读取所有已提交文档。架构要求投影对人工提交做 lint 标记而非拒收，因此投影需要按文档报告错误或自建 tree 读取；该契约归投影提案定义，届时可能重塑本扫描接口。
