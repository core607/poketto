# 需求与架构

Date: 2026-08-25

[仓库创作基础](2026-09-05-repository-authoring-foundations.md)已实现任意路径文本读取、有界公开快照与搜索、原子文本补丁、本地托管存储和精确版本图片交付。[身份 HTTP 后端](2026-09-06-workspace-identity-http.md)提供浏览器认证、邀请、成员与作用域 key。[博客与浏览器管理界面](2026-09-06-blog-browser-interface.md)通过受限 Markdown 渲染呈现这些 HTTP API，并提供隔离浏览器验收入口。MCP 工具、沙箱执行和最终 HTTPS 安装仍待完成。

[第一阶段交付提案](../proposed/2026-09-05-phase-one-daily-use.md)定义可日常使用的安装范围与验收标准，包含博客、管理端及五个仓库 MCP 工具。本次交付不包含备份、恢复演练、访客问答、C 端供应或 serverless；这些排除项不构成部署前置条件，拟议能力不代表已实现。

## 本文范围

本文保留主要的单服务器基线及为其选定的产品边界。[远程仓库权威](2026-09-01-remote-repository-authority.md)、[HTTP 入口基线](2026-09-03-http-entrance-baseline.md)和[已验证内容快照](2026-09-04-validated-content-snapshot.md)记录最初实现。新的创作基础与第一阶段记录定义替代契约；下文的历史与后续设计章节不代表已交付行为。

更广泛的[前端](../proposed/2026-08-30-nextjs-frontend.md)、[托管资产](../proposed/2026-09-01-repository-asset-blob-store.md)、[发布与图片](../proposed/2026-09-01-repository-native-publishing-and-assets.md)及[检索与沙箱执行](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md)提案，在满足完整验收标准之前仍保留为 proposed。[C 端供应](../proposed/2026-09-01-consumer-accounts-and-personal-workspaces.md)与[可选 serverless profile](../proposed/2026-09-01-optional-serverless-deployment-profile.md)仍在第一阶段范围之外。这些选择均不改变工作空间租户边界。

## 定位

Poketto 是自托管的个人知识库，公开面是博客。同一份 Markdown 内容，既支撑公开发布，也作为受控 AI 的长期记忆，通过 MCP 访问。
第一阶段公开面包含服务端渲染文章、标签、归档、有界搜索、RSS 与 sitemap。限额访客问答仍是后续产品目标。

## 设计原则

- 开源：代码与项目文档采用 Apache-2.0；美术素材与站点发布的创作内容采用 CC BY-NC-SA 4.0。
- 单实例，不开放注册。使用者是工作空间所有者、其信任的成员与这些人的 AI，通过已发放的身份或 API Key 在获授权的工作空间内行动。
- 面向资源有限的单机设计；生产容量与资源限制须在选定主机上测量后确定。
- 使用方式是 clone 自部署。代码仓与各工作空间的内容仓分离。运营者通过 secret 为默认工作空间提供预先建好的私有 HTTPS 仓库；远端 `main` 是权威，本地仓库存储只是一次性缓存。

## 核心架构决策

1. 文件为真理之源。每个工作空间拥有一个存放 Markdown 的 git 仓库。仅保留历史选型：从未实现，现已废止，由[官方 PostgreSQL](2026-09-05-stock-postgresql.md)和[仓库原生检索](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md)取代：当时计划让 PostgreSQL 只做内容的派生投影（search_documents 表），可随时全量重建。每个工作空间的投影用 checkpoint 记录已处理的 commit，崩溃后重放追赶；投影变更与 checkpoint 推进在同一个数据库事务内完成。
2. 写入模型：每个工作空间内容仓的远端 `main` 分支即真理。管理端使用有界 UTF-8 补丁服务，并为后续 MCP 入口保留共享边界，保留未修改的源码，构建带调用者归属的候选提交，并且只从预期 base 推进远端 ref。竞争 push 返回冲突；回包丢失时须向远端 `main` 对账，绝不盲目重试。可选元数据错误与不安全文件产生文件级诊断；无效发布策略关闭公开服务。仓库确认与快照安装是独立状态。
3. 仅保留历史选型：从未实现，现已废止，由[官方 PostgreSQL](2026-09-05-stock-postgresql.md)和[仓库原生检索](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md)取代：当时计划默认使用 agentic 检索，由服务端提供廉价检索原语：全文检索（zhparser + tsvector + GIN + ts_rank_cd）、标签与时间过滤、只返回摘要；调用方 AI 自行迭代查询。embedding 是可插拔实验位（独立侧表，不强制安装 pgvector），是否引入由真实查询的评测决定。
4. 信任分层。工作空间所有者可直接通过私有远程仓库创作；Poketto 观察新的远端 `main`，不会把缓存改动当作内容。计划中的 MCP 入口为成员 AI 使用作用域 API key。能力包括 READ_PRIVATE、WRITE_PRIVATE、PUBLISH、MANAGE_KEYS 与 EXECUTE_REPOSITORY；AI key 默认不含后三项。公开搜索在内部固定公开范围；成员与 key 必须通过当前工作空间授权后才能私有读写。
5. 工作空间隔离。工作空间是租户、安全与数据销毁边界。模块操作、PostgreSQL 行、内容路径、blob、缓存、预算、审计记录和后台任务都显式携带 `WorkspaceId`；入口先解析出已授权工作空间，再调用这些操作。对象不存在与未授权不得泄露其他工作空间是否存在。默认部署创建一个工作空间，不提供自助创建更多工作空间的入口。

## 拟议 MCP 契约

第一阶段用 `get_file`、`get_asset`、`put_asset`、`repo_patch` 与 `repo_exec` 取代最初按 UUID 操作文档的工具选型。文件使用仓库相对路径，无须 frontmatter ID。共享读取器、revision/absence 检查、原子补丁和不可变图片服务已经实现；MCP 传输、工具注册与隔离执行适配器尚未实现。预留的 `/mcp` 安全链独立于浏览器会话，要求 Bearer API key。第一阶段提案定义工具行为与真实客户端验收。

## 后续访客问答设计

上游 LLM 的 key 只存在于服务端环境变量。日预算按整次 agent run 预留：按最坏情况（逐轮膨胀的上下文 + 输出上限 × 轮数上限）预扣，预留不足则不开始，结束后按真实用量结算。单 IP 令牌桶限流（JVM 内存实现）。价格表配置化。
clip_url 的 SSRF 防护：仅 http/https；DNS 解析后拦截私网、回环、link-local 与云 metadata 地址；每一跳 redirect 重新校验；超时、大小、content-type 限制。抓回内容视为不可信数据，其中的指令性文字不作为指令执行。
渲染管道：raw HTML 禁用、URL 消毒、输出端 HTML sanitizer、CSP 响应头。

## 后续备份设计

下文描述更广泛的备份目标，不代表已经提供备份服务。备份与恢复演练不在第一阶段实现范围内，也不构成部署前置条件。

每个工作空间的文档文本与历史靠所属内容仓的 git remote；图片 blob 与数据库非派生表（工作空间目录、key、审计、预算）各走 off-host 定时备份；不存在需要备份的内容投影。

## 图片

第一阶段资产契约取代最初仅用 hash 引用与图片索引的选型。本地托管原图在 Git 之外按工作空间存储，使用不可变的资产标识与 revision 引用。Git 图片保持只读，按需物化到可丢弃缓存。公开授权绑定页面快照和精确图片版本，最长五分钟且不超过快照有效期；私有读取重新验证当前权限。所有已确认的托管原图均保留。图片加工、pHash、图片描述与持久化图片索引不在本次交付范围内。

## 技术栈

构建要求 JDK 26，并锁定 Spring Boot 4.1.1。Spring Security 负责浏览器认证，Spring Modulith 定义应用模块边界；JGit 负责仓库访问，commonmark-java 与 Jackson YAML 解析内容，[官方 PostgreSQL 17](2026-09-05-stock-postgresql.md)存储关系型应用状态。[博客前端](2026-09-06-blog-browser-interface.md)使用 Next.js App Router、React、TypeScript 与 Tailwind，锁定 Node.js 24.19.0 和 npm 12.0.2。它取代 JTE + htmx，业务 API 与持久化仍归 Spring。
CI：GitHub Actions + Testcontainers；镜像发布到 GHCR。另提供 docker save 经 SSH 传输的部署脚本，供访问镜像仓库受限的网络环境使用。GraalVM Native Image 与 JDK 结构化并发（preview）在实验轨，不进主线。
计划中的 MCP 入口将固定 SDK 与协议版本。第一阶段使用静态 API key 是有意识的简化，不宣称实现 MCP 标准 OAuth；预留的认证链已经校验传入的 Origin header。

## 不做清单

开放注册与自助创建工作空间、OAuth、评论点赞等社交功能、微服务与 K8s 与消息队列、知识图谱、重 RAG 管道（切块 + 重排 + 多路召回）、富文本编辑器、图床 CDN、移动端、界面多语言、访客会话历史、Redis（单实例下预算计数归 PostgreSQL、限流归 JVM、缓存归 Caffeine）。
