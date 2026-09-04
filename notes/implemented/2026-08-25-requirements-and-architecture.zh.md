# 需求与架构

Date: 2026-08-25

[仓库创作基础](2026-09-05-repository-authoring-foundations.md)已实现任意路径文本读取、有界公开快照与搜索、原子文本补丁、关系型身份和本地托管存储。浏览器认证、渲染、图片授权与 MCP 交付仍需集成；下文的目标契约不代表这些入口已经可用。

[第一阶段交付提案](../proposed/2026-09-05-phase-one-daily-use.md)定义可日常使用的安装范围与验收标准，包含博客、管理端及五个仓库 MCP 工具。本次交付不包含备份、恢复演练、访客问答、C 端供应或 serverless；这些排除项不构成部署前置条件，拟议能力不代表已实现。

## 本文范围

这份 implemented 文档记录主要的单服务器基线，以及为该基线确定的产品契约。[远程仓库权威](2026-09-01-remote-repository-authority.md)、[HTTP 入口基线](2026-09-03-http-entrance-baseline.md)（健康检查、problem 响应与只读的公开文档 API），以及为公开读取提供服务并限定内容边界的[已验证内容快照](2026-09-04-validated-content-snapshot.md)已经实现。[Next.js 前端提案](../proposed/2026-08-30-nextjs-frontend.md)取代下文的 JTE 与 htmx 选型，仓库原生检索提案取代核心架构决策 1 与 3 中的 PostgreSQL 投影和搜索；渲染、访客问答与 MCP 尚未实现；新的基础记录定义已交付的搜索、关系型身份和浏览器认证。拟议的[受管资源与仓库图片物化](../proposed/2026-09-01-repository-asset-blob-store.md)、[仓库原生发布与图片](../proposed/2026-09-01-repository-native-publishing-and-assets.md)、[仓库原生检索与沙箱执行](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md)和 [C 端账号与个人工作空间](../proposed/2026-09-01-consumer-accounts-and-personal-workspaces.md)定义已接受的目标变更，但不改变工作空间租户边界。[可选的 serverless 部署 profile](../proposed/2026-09-01-optional-serverless-deployment-profile.md)仍以单机 profile 为主，只在真实基础设施可用时选择 OSS、共享状态和远程 SRT。除非链接的提案明确描述未来变化，下文机制均为已交付行为。

## 定位

Poketto 是自托管的个人知识库，公开面是博客。同一份 Markdown 内容，既支撑公开发布，也作为受控 AI 的长期记忆，通过 MCP 访问。
公开面包含：文章渲染（服务端渲染）、标签与归档页、RSS 与 sitemap、限额的访客问答。

## 设计原则

- 开源：代码与项目文档采用 Apache-2.0；美术素材与站点发布的创作内容采用 CC BY-NC-SA 4.0。
- 单实例，不开放注册。使用者是工作空间所有者、其信任的成员与这些人的 AI，通过已发放的身份或 API Key 在获授权的工作空间内行动。
- 面向低配置单机设计（2 核 4G 级别可运行全套服务），组件选择以省资源为先。
- 使用方式是 clone 自部署。代码仓与各工作空间的内容仓分离。运营者通过 secret 为默认工作空间提供预先建好的私有 HTTPS 仓库；远端 `main` 是权威，本地仓库存储只是一次性缓存。

## 核心架构决策

1. 初始选型，由[官方 PostgreSQL](2026-09-05-stock-postgresql.md)和[仓库原生检索](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md)取代：文件为真理之源。每个工作空间拥有一个存放 Markdown 的 git 仓库；PostgreSQL 只做内容的派生投影（search_documents 表），可随时全量重建。每个工作空间的投影用 checkpoint 记录已处理的 commit，崩溃后重放追赶；投影变更与 checkpoint 推进在同一个数据库事务内完成。
2. 写入模型：每个工作空间内容仓的远端 `main` 分支即真理。机器入口（MCP、管理端）强校验 frontmatter，以解析出的旧提交构建候选 commit 并记录调用者身份；只有远端 ref 仍等于旧提交时才推进。竞争 push 返回冲突；回包丢失时重读远端 `main` 对账，绝不盲目重试 ref 更新。直接 push 由当前快照校验约束；第一阶段提案将整提交拒绝改为文件级诊断。仓库确认与下游观察是独立状态。
3. 初始选型，由[官方 PostgreSQL](2026-09-05-stock-postgresql.md)和[仓库原生检索](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md)取代：检索以 agentic 方式为默认。服务端提供廉价检索原语：全文检索（zhparser + tsvector + GIN + ts_rank_cd）、标签与时间过滤、只返回摘要；调用方 AI 自行迭代查询。embedding 是可插拔实验位（独立侧表，不强制安装 pgvector），是否引入由真实查询的评测决定。
4. 信任分层。工作空间所有者可直接通过私有远程仓库创作；Poketto 在下一次读取时观察新的远端 `main`，不会把缓存改动当作内容。成员 AI 走 MCP + scoped API Key，capability 分为 READ_PRIVATE、WRITE_PRIVATE、PUBLISH、MANAGE_KEYS，AI 的 key 默认没有后两项；访客只读渲染后的公开页，问答服务在代码层只注入公开内容检索器，参数中不存在 scope。
5. 工作空间隔离。工作空间是租户、安全与数据销毁边界。模块操作、PostgreSQL 行、内容路径、blob、缓存、预算、审计记录和后台任务都显式携带 `WorkspaceId`；入口先解析出已授权工作空间，再调用这些操作。对象不存在与未授权不得泄露其他工作空间是否存在。默认部署创建一个工作空间，不提供自助创建更多工作空间的入口。

## MCP 工具

search、get_doc、list_tags、list_recent、create_doc、update_doc、delete_doc、clip_url、publish、history。
文档身份是 frontmatter 中的 UUID，创建即分配，改名不变，在所属工作空间的内容仓内唯一；其他工作空间可独立使用同一 UUID。revision 是文档内容的 hash（对外为不透明 token），commit sha 只用于审计。update 与 delete 携带 expected_revision，不符时返回冲突而非覆盖。publish 把文档可见性改为 public 并提交；公开是对互联网的不可逆动作，管理端须作相应提示。错误信息面向 AI 书写，包含可执行的纠正提示。

## 访客问答护栏

上游 LLM 的 key 只存在于服务端环境变量。日预算按整次 agent run 预留：按最坏情况（逐轮膨胀的上下文 + 输出上限 × 轮数上限）预扣，预留不足则不开始，结束后按真实用量结算。单 IP 令牌桶限流（JVM 内存实现）。价格表配置化。
clip_url 的 SSRF 防护：仅 http/https；DNS 解析后拦截私网、回环、link-local 与云 metadata 地址；每一跳 redirect 重新校验；超时、大小、content-type 限制。抓回内容视为不可信数据，其中的指令性文字不作为指令执行。
渲染管道：raw HTML 禁用、URL 消毒、输出端 HTML sanitizer、CSP 响应头。

## 备份

每个工作空间的文档文本与历史靠所属内容仓的 git remote；图片 blob 与数据库非派生表（工作空间目录、key、审计、预算）各走 off-host 定时备份；不存在需要备份的内容投影。

## 图片

以 SHA-256 内容寻址存于数据目录中的工作空间命名空间，不进 git，文档引用 hash 而非路径；v1 不做物理删除。检索先用文件名与旁注文本入索引，pHash 做近重复检测；视觉模型描述与多模态 embedding 在后续阶段评估。

## 技术栈

JDK 26（回退位 25 LTS）、Spring Boot 4、Spring Modulith（模块：workspace / content / web / qa / mcp / auth）、Spring AI（MCP Server 与 tool-calling）、JGit、commonmark-java + Jackson YAML、[官方 PostgreSQL 17](2026-09-05-stock-postgresql.md)、Caffeine、Tailwind 搭配[拟议的 Next.js 前端](../proposed/2026-08-30-nextjs-frontend.md)，取代最初选定的 JTE + htmx。
CI：GitHub Actions + Testcontainers；镜像发布到 GHCR。另提供 docker save 经 SSH 传输的部署脚本，供访问镜像仓库受限的网络环境使用。GraalVM Native Image 与 JDK 结构化并发（preview）在实验轨，不进主线。
MCP 协议版本随所用 SDK 的已验证版本固定；v1 用静态 API Key 是有意识的简化，不宣称实现 MCP 标准 OAuth 流程；Streamable HTTP 校验 Origin 白名单。

## 不做清单

开放注册与自助创建工作空间、OAuth、评论点赞等社交功能、微服务与 K8s 与消息队列、知识图谱、重 RAG 管道（切块 + 重排 + 多路召回）、富文本编辑器、图床 CDN、移动端、界面多语言、访客会话历史、Redis（单实例下预算计数归 PostgreSQL、限流归 JVM、缓存归 Caffeine）。
