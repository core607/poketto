# 需求与架构

Date: 2026-08-25

[仓库创作基础](2026-09-05-repository-authoring-foundations.md)已实现任意路径文本读取、有界公开快照与搜索、原子文本补丁、本地托管存储和精确版本图片交付。[身份 HTTP 后端](2026-09-06-workspace-identity-http.md)提供浏览器认证、邀请、成员与作用域 key。[博客与浏览器管理界面](2026-09-06-blog-browser-interface.md)通过受限 Markdown 渲染呈现这些 HTTP API，并提供隔离浏览器验收入口。MCP API 与[本地 worker 适配器](2026-09-05-local-execution-supervisor.md)提供仓库工具及显式启用的隔离执行。

[第一阶段交付契约](2026-09-05-phase-one-daily-use.md)定义可日常使用的交付范围与验收规则，包含博客、管理端及仓库 MCP 工具。本次交付不包含备份、恢复演练、访客问答、在托管平台上代建仓库或 serverless；这些排除项不构成部署前置条件，拟议能力不代表已实现。

## 本文范围

本文保留主要的单服务器基线及为其选定的产品边界。[远程仓库权威](2026-09-01-remote-repository-authority.md)和[HTTP 入口基线](2026-09-03-http-entrance-baseline.md)记录最初实现。新的创作基础与第一阶段记录定义替代契约；下文的历史与后续设计章节不代表已交付行为。

更广泛的[前端](2026-08-30-nextjs-frontend.md)与[检索与沙箱执行](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md)记录已实现；托管资产存储与[发布与图片](../rejected/2026-09-01-repository-native-publishing-and-assets.md)提案已被后续决定取代并拒绝。[多用户空间](2026-09-11-multiuser-workspaces-and-discovery.md)已纳入交付。[账号与站点策略](2026-09-20-consumer-identity-and-site-policy.md)取代其邀请制注册，[GitHub 授权个人空间](2026-09-21-github-authorized-personal-spaces.md)在注册后提供显式创建仓库。注册时自动创建仓库的方案仍[被拒绝](../rejected/2026-09-01-consumer-accounts-and-personal-workspaces.md)，[可选 serverless profile](../rejected/2026-09-01-optional-serverless-deployment-profile.md)已被拒绝。这些选择均不改变工作空间租户边界。

## 定位

Poketto 是面向多账号的 Git 原生内容工作空间。每个空间对应一个 Git 仓库：成员在浏览器中创作，其 AI 通过 MCP 操作同一仓库，已发布的空间把公开内容呈现为网站。同一份 Markdown 既支撑公开展示，也作为受控 AI 的长期记忆。
空间网站包含服务端渲染的文章、目录、相册与合集页面、标签、归档和有界搜索。根站点另提供跨空间搜索、已发布空间的发现批次、覆盖所有已发布空间的 sitemap，以及默认空间的 RSS。限额访客问答仍是后续产品目标。

## 设计原则

- 开源：代码与项目文档采用 Apache-2.0；美术素材与站点发布的创作内容采用 CC BY-NC-SA 4.0。
- 单实例，支持邮箱验证注册和 Google 登录。[账号与站点策略](2026-09-20-consumer-identity-and-site-policy.md)将账号策略组与空间授权分离：新账号默认为浏览者，停止公开展示仍保留已有成员及机器访问权限。使用者与其 AI 通过已发放的身份或 API Key 在获授权的工作空间内行动。
- 面向资源有限的单机设计；生产容量与资源限制须在选定主机上测量后确定。
- 运营者 clone 本仓库自部署实例，注册账号使用该实例。代码仓与各工作空间的内容仓分离。运营者通过 secret 为默认工作空间提供预先建好的私有 HTTPS 仓库，其他空间使用各自的仓库（见决策 5）。远端 `main` 是权威，本地仓库存储只是一次性缓存。

## 核心架构决策

1. 文件为真理之源。每个工作空间拥有一个存放 Markdown 的 git 仓库。PostgreSQL 不保存派生的内容投影（[官方 PostgreSQL](2026-09-05-stock-postgresql.md)）；本文最初选定的可重建投影与 commit checkpoint 方案从未实现。
2. 写入模型：每个工作空间内容仓的远端 `main` 分支即真理。管理端与 MCP 共用有界 UTF-8 补丁服务，保留未修改的源码，构建带调用者归属的候选提交，并且只从预期 base 推进远端 ref。竞争 push 返回冲突；回包丢失时须向远端 `main` 对账，绝不盲目重试。可选元数据错误与不安全文件产生文件级诊断；无效发布策略关闭公开服务。仓库确认与快照安装是独立状态。
3. 检索采用[仓库原生](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md)方式：agent 在仓库上组合使用普通的列目录、搜索与读取工具。本文最初选定的 PostgreSQL 全文检索方案（zhparser、tsvector）从未实现。embedding 检索只在独立的[检索实验室](2026-09-24-retrieval-lab.md)中评测，经过真实查询评测后才可进入产品。
4. 信任分层。工作空间所有者可直接通过私有远程仓库创作；Poketto 观察新的远端 `main`，不会把缓存改动当作内容。MCP 入口为成员 AI 使用作用域 API key。能力包括 READ_PRIVATE、WRITE_PRIVATE、PUBLISH、MANAGE_KEYS、EXECUTE_REPOSITORY 与 [CAPTURE](2026-09-24-capture-inbox.md)；CAPTURE 只能在 `private/inbox/` 中新建 Markdown 笔记并上传其图片，WRITE_PRIVATE 隐含该能力。AI key 默认不含 PUBLISH、MANAGE_KEYS 与 EXECUTE_REPOSITORY。公开搜索在内部固定公开范围；成员与 key 必须通过当前工作空间授权后才能私有读写。[显式成员权限](2026-09-12-member-content-permissions.md)分别控制私密读取、私密修改和公开发布；普通成员与邀请默认仅能读取当前公开范围。连接不能超出持有人的权限，也不会随其权限增加而自动扩大。
5. 工作空间隔离。工作空间是租户、安全与数据销毁边界。模块操作、PostgreSQL 行、内容路径、blob、缓存、预算、审计记录和后台任务都显式携带 `WorkspaceId`；入口先解析出已授权工作空间，再调用这些操作。对象不存在与未授权不得泄露其他工作空间是否存在。默认部署创建一个工作空间。[托管仓库连接](2026-09-11-managed-workspace-connections.md)可将已有私有仓库连接为更多空间，[GitHub 授权个人空间](2026-09-21-github-authorized-personal-spaces.md)允许具备资格的账号在自己的 GitHub 账号下创建仓库作为新空间。[浏览器与 MCP 路由](2026-09-11-workspace-browser-and-mcp-routing.md)将管理请求绑定到明确的空间路径，将机器会话绑定到凭据所属空间。跨空间公开发现由[多用户交付契约](2026-09-11-multiuser-workspaces-and-discovery.md)定义。

从仓库路径派生或由可选元数据指定的[路由](2026-09-06-logical-repository-routes.md)保留原始名称，包括空格、`%`、`?` 和 `#`，不做 URI 编码、解码或首尾裁剪。原有路径安全与长度限制继续适用；调用方在 URI 边界编码逻辑路由。

## 当前 MCP 契约

内容格式采用独立的 `public/` 和 `private/` 根目录，新内容默认私有。只有精确 `public/` 下符合条件的路径才能在已启用的 `public-root` 策略下发布；排除路径、指引和隐藏路径保持私有。默认文章路由省略根目录前缀，显式路由不赋予公开权限。[内容契约](2026-09-09-codeact-content-and-media.md)定义协调转换与格式边界。

`/mcp` 使用 Streamable HTTP 和工作空间 Bearer API key 或 OAuth 访问令牌，独立于浏览器会话。[CodeAct MCP 入口](2026-09-10-codeact-mcp-entrance.md)在隔离执行器和资产服务可用时提供 `repo_exec`、`repo_discard`、`get_artifact`、`get_asset` 和 `put_asset`。没有独立文件 CRUD 回退入口；文件访问要求经过验证的[本地 worker](../../executor-service/README.md)和 `EXECUTE_REPOSITORY` 权限。启用适配器不能替代真实进程边界验证。

Agent 使用普通目录列表、搜索、shell 和 Python 查看文件，并逐层读取内容仓库自己的 `AGENTS.md`。服务端不解释这些指引。[目录导航](2026-09-08-repository-directory-navigation.md)仍通过共享读取服务向浏览器 HTTP 提供功能，无须执行器。

文件使用仓库相对路径，无须 frontmatter ID。可选的小写标准 UUID 标识使社区互动随文章移动，空间也是身份的一部分。PostgreSQL 保存互动，不保存文章正文或内容投影。浏览器读取返回权威 UTF-8 字节、解析出的提交、服务端 revision、诊断和明确的缺失状态。由宿主介入的 CLI 通过同一原子写入服务检查 base commit 及各选定文件的 revision 或缺失条件。图片使用精确 Git 版本或不可变托管版本；上传既不写 Git，也不发布。完整读取的执行会话保留原始 Git 历史；仅公开读取的会话只获得当前公开文件，不含原始历史或私密元数据。普通编辑在保存前留在本地。CLI 对媒体操作、选定文件保存、含引用修复的原子移动、[整个工作区同步](2026-09-15-workspace-synchronization.md)和不确定写入恢复执行授权。保存保留未选中编辑和各文件独立的基线。浏览器读取权威对象，shell 读取会话副本。[worker 参考文档](../../executor-service/README.md)定义已实现的 CLI 与生命周期契约，最终部署验收仍由第一阶段记录约束。

[副本身份契约](2026-09-14-account-working-copies.md)要求每次执行请求明确新建副本，或携带此前返回的副本 ID。ID 不匹配时在执行前拒绝，即使重连后的 commit 相同也如此。账号工作副本的文件在传输关闭、重连和应用重启后仍然保留：只有显式丢弃或连续七天没有授权使用才会删除它们，运行租约结束只丢失其运行时状态：shell 状态、私有 `/tmp` 与保留的产物。未保存的工作没有异地备份。

## 后续访客问答设计

上游 LLM 的 key 只存在于服务端环境变量。日预算按整次 agent run 预留：按最坏情况（逐轮膨胀的上下文 + 输出上限 × 轮数上限）预扣，预留不足则不开始，结束后按真实用量结算。单 IP 令牌桶限流（JVM 内存实现）。价格表配置化。
clip_url 的 SSRF 防护：仅 http/https；DNS 解析后拦截私网、回环、link-local 与云 metadata 地址；每一跳 redirect 重新校验；超时、大小、content-type 限制。抓回内容视为不可信数据，其中的指令性文字不作为指令执行。
渲染管道：raw HTML 禁用、URL 消毒、输出端 HTML sanitizer、CSP 响应头。

## 后续备份设计

下文描述更广泛的备份目标，不代表已经提供备份服务。备份与恢复演练不在第一阶段实现范围内，也不构成部署前置条件。

每个工作空间的文档文本与历史靠所属内容仓的 git remote；图片 blob 与数据库非派生表（工作空间目录、key、审计、预算）各走 off-host 定时备份；不存在需要备份的内容投影。

## 图片

第一阶段资产契约取代最初仅用 hash 引用与图片索引的选型。本地托管原图在 Git 之外按工作空间存储，使用不可变的资产标识与 revision 引用。Git 图片保持只读，按需物化到可丢弃缓存。公开授权绑定页面快照和精确图片版本，最长五分钟且不超过快照有效期；私有读取重新验证当前权限。所有已确认的托管原图均保留。除可丢弃的[公开相册缩略图](2026-09-14-album-thumbnails.md)外，图片加工、pHash、图片描述与持久化图片索引不在本次交付范围内。

[存储端口](2026-09-05-repository-authoring-foundations.md#managed-originals-and-image-delivery)支持其他原始文件的有界流式读写，物理字节去重严格限定在单个工作空间内，并保留独立上传身份。[逻辑媒体索引](2026-09-09-logical-media-index.md)定义路径发现与索引、文本的原子保存。[索引媒体交付](2026-09-09-indexed-media-delivery.md)增加原始字节 HTTP 上传、相对图片渲染与经过授权的原件附件下载，并限制并发、持续重查权限。[可移植导出](2026-09-10-portable-content-exports.md)按授权范围打包已保存文档和实际原件。

## 技术栈

构建要求 JDK 26，并锁定 Spring Boot 4.1.1 与 Spring AI 2.0.1。Spring Security 负责浏览器认证，Spring Modulith 定义应用模块边界；JGit 负责仓库访问，commonmark-java 与 Jackson YAML 解析内容，[官方 PostgreSQL 17](2026-09-05-stock-postgresql.md)存储关系型应用状态。[浏览器前端](2026-09-06-blog-browser-interface.md)使用 Next.js App Router、React、TypeScript 与原生 CSS，锁定 Node.js 24.19.0 和 npm 12.0.2。它取代 JTE + htmx，业务 API 与持久化仍归 Spring。
CI：GitHub Actions + Testcontainers；镜像发布到 GHCR，服务器按 digest 拉取。仍提供 docker save 经 SSH 传输的部署脚本，供访问镜像仓库受限的网络环境使用。GraalVM Native Image 与 JDK 结构化并发（preview）在实验轨，不进主线。
MCP 协议版本随固定 SDK 确定。[MCP OAuth](2026-09-11-mcp-oauth.md)在静态 API Key 之外提供持有人明确批准、可独立撤销的客户端连接；Streamable HTTP 校验传入的 Origin header。

## 不做清单

注册时自动创建仓库、私信和群聊、微服务与 K8s 与消息队列、知识图谱、重 RAG 管道（切块 + 重排 + 多路召回）、富文本编辑器、图床 CDN、移动端、界面多语言、访客会话历史、Redis（单实例下预算计数归 PostgreSQL、限流归 PostgreSQL 或 JVM、缓存归 Caffeine）。
