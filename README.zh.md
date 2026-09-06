# Poketto

以仓库为核心的个人知识服务，公开面是博客。已接受的目标让每个工作空间在远程 Git 中保存 Markdown、仓库自管图片与历史，把经 Poketto 上传的图片存入 ManagedBlobStore；两类图片共用安全渲染，但永不相互同步。同一份内容既支撑公开发布，也通过 MCP 作为受信 AI 的长期记忆。Poketto 首先面向单台云服务器，同时保留由配置选择外部基础设施的[可选 serverless 部署 profile](notes/proposed/2026-09-01-optional-serverless-deployment-profile.md)。

[English](README.md)

## 状态

开发中。[仓库创作基础](notes/implemented/2026-09-05-repository-authoring-foundations.md)提供任意路径 Markdown、文件级诊断、有界公开与私有搜索、发布策略、原子文本补丁、不可变本地图片存储和精确版本图片交付。远端 `main` 仍是权威；公开请求使用已验证快照，不会在请求中访问远端。[身份 HTTP 后端](notes/implemented/2026-09-06-workspace-identity-http.md)提供初始化、会话、邀请、成员与作用域 key。

[博客与浏览器管理界面](notes/implemented/2026-09-06-blog-browser-interface.md)通过服务端渲染的公开页面，以及包含图片、成员和密钥管理的中文 Markdown 编辑器呈现这些 HTTP API。`/mcp` 提供四个仓库工具；只有启用独立的 [Linux 执行服务](executor-service/README.md)后才注册 `repo_exec`。最终 HTTPS 安装与部署拓扑验收仍待完成。[第一阶段提案](notes/proposed/2026-09-05-phase-one-daily-use.md)与更广泛的提案在完整验收前保持开放。C 端供应、备份、访客问答与 serverless 不在第一阶段内。[持续交付](notes/implemented/2026-09-03-continuous-delivery.md)把通过验证的 `main` 提交发布到 GHCR，自动部署须单独启用。[需求文档](notes/implemented/2026-08-25-requirements-and-architecture.zh.md)区分已实现行为、历史选型与提案。

## 适合谁

- 希望笔记、剪藏和博客始终是带 Git 历史的 Markdown，而关系型应用状态留在内容仓之外的人。
- 希望自己的 AI 助手通过 MCP 和作用域 API Key 读写内容、而不是交出 shell 权限的人。
- 希望小型服务器部署始终是一等生产方案，同时保留将来在可选共享服务中提供隔离工作空间这条路的人。
- 对「为 agent 开发而设计的仓库」感兴趣的人——从 [AGENTS.md](AGENTS.md) 看起。

## 目录

```
AGENTS.md            本仓库的 agent 工作规则（从这里开始）
.agents/skills/      可复用工序：文风、评审、检查、笔记生命周期
build.gradle.kts     Gradle 构建与校验入口
Dockerfile           应用镜像：Gradle 构建阶段 + JRE 运行阶段
deploy/              生产 Compose、部署入口、传输脚本与脚本测试
src/                 应用模块及其测试
notes/               决策记录：proposed / implemented / rejected / archived
```

## 开发

使用 Java 26 和仓库内的 Gradle Wrapper。Linux 执行服务测试需要带 venv 与 pip 支持的 Python 3.10+；Windows 在固定的 Linux 容器中运行该必需测试入口。前端与完整校验还需要 Node.js 24.19.0 和 npm 12.0.2。数据库集成测试与完整校验需要 Docker；较快的单元测试和仓库校验不需要。`./gradlew frontendCheck` 运行前端格式、类型、测试和生产构建。通过[隔离浏览器入口](acceptance/README.md)使用合成数据操作真实应用；前端运行设置见 [frontend/README.md](frontend/README.md)。

应用启动需要 PostgreSQL 数据源、绝对路径形式的 `POKETTO_DATA_DIR`，以及一个预先建好的私有 HTTPS Git 仓库。运行 `bootRun` 前设置 `SPRING_DATASOURCE_URL`、数据库认证信息、`POKETTO_REPOSITORY_REMOTE_URI`、`POKETTO_REPOSITORY_USERNAME` 与 `POKETTO_REPOSITORY_PASSWORD`。Flyway 会创建默认工作空间；应用将它绑定到远端 `main`，只在 `<data-dir>/workspaces/<workspace-id>/content` 物化一次性缓存。`POKETTO_REPOSITORY_CACHE_MAX_WORKSPACES` 与 `POKETTO_REPOSITORY_TIMEOUT_SECONDS` 可以调整默认值为 32 个工作空间和 30 秒的限制；`POKETTO_REPOSITORY_REFRESH_SECONDS` 决定所服务内容多久对照远端 `main` 重新校验一次（默认 30 秒），`POKETTO_REPOSITORY_STALE_AFTER_SECONDS` 决定所服务内容最多多久没有成功重新校验，健康检查就会把它报告为停止服务（默认 3600 秒）。内容不可用时进程与刷新循环继续运行，但 readiness 报告停止服务，公开读取失败关闭。快照过期同样停止公开读取，最长允许沿用一小时。运行中的实例通过 `GET /actuator/health` 回应部署检查，并在 `GET /api/public/documents` 提供默认工作空间的公开文档；经 Poketto 的写入立即可见，合法的直接推送在下一次刷新后可见。

```sh
./gradlew test repoCheck
./gradlew check
POKETTO_DATA_DIR=/srv/poketto \
POKETTO_REPOSITORY_REMOTE_URI=https://git.example.com/owner/private-content.git \
POKETTO_REPOSITORY_USERNAME=operator \
POKETTO_REPOSITORY_PASSWORD=... \
./gradlew bootRun
```

初始化首个 owner 前，私下设置 `POKETTO_AUTH_INITIALIZATION_TOKEN`，并把 `POKETTO_SECURITY_ALLOWED_ORIGINS` 配置为浏览器使用的精确 origin。本地 HTTP 还需设置 `POKETTO_SESSION_COOKIE_SECURE=false`；HTTPS 保留安全默认值。初始化或登录前先获取 `/api/auth/csrf`，后续请求同时携带会话 cookie 和响应指定的 CSRF header。初始化与登录顺序见[身份 HTTP 契约](notes/implemented/2026-09-06-workspace-identity-http.md#operation)。部署 profile 从私有运行配置中传入这些身份设置。

Windows 下 `check` 还会在固定版本的 Linux 容器中通过临时原生磁盘卷运行 `linuxStorageTest`，包括公开标记持久化与快照恢复测试。Windows 开发模式只能在远端重新验证成功后建立内存公开快照；离线重启不会从磁盘恢复公开授权。Linux 上影响发布的写入必须先成功同步文件与目录才能推送；同步失败或不受支持时关闭公开服务。权威图片存储要求目录同步能力；不支持的宿主不能确认持久化上传。用 `$env:...` 设置同名变量，确保 `POKETTO_DATA_DIR` 是绝对路径，再使用 `.\gradlew.bat`。命令表与协作规则见 [AGENTS.md](AGENTS.md#commands)。

## 内容与图片

要发布内容，在内容仓创建 `.poketto/publishing.yaml`：

```yaml
enabled: true
mode: public-by-default
exclude:
  - drafts/**
```

策略缺失或禁用时不发布任何内容；策略无效时关闭公开服务。根目录 `private/` 与配置的排除路径保持私有。Markdown 元数据可选，未修改的源码字节保持原样。公开详情入口为 `GET /api/public/document?route=...`；列表、搜索与标签响应包含快照元数据。`index.md` 拥有所属文件夹的路由，并提供不递归、不重复正文图片的同目录图库。

认证后的 `/api/admin/repository` 入口提供文件树、文件读取、搜索、预览与原子补丁。每个变更路径须在 base commit 下携带 revision 或明确的缺失条件；冲突或不明确结果须重新读取后再决定是否重试。`/api/admin/assets` 图片上传要求 `Idempotency-Key`，最多接收 16 MiB，返回不可变引用，不写 Git、不发布。

托管原图保存在 `<data-dir>/managed-originals` 并持续保留；`<data-dir>/derived/repository-images` 可以删除重建。公开图片授权绑定精确页面快照，最长五分钟且不超过快照有效期。撤回内容后停止签发新授权，私有预览则重新验证当前身份。限制、存储保证与失败行为见[创作基础记录](notes/implemented/2026-09-05-repository-authoring-foundations.md)。

## MCP 与隔离执行

`/mcp` 使用 Spring AI 2.0.1 WebMVC Streamable HTTP，以工作空间 Bearer API key 认证，独立于浏览器会话。工具为 `get_file`、`get_asset`、`put_asset` 和 `repo_patch`，与 HTTP 入口共用权威 UTF-8 读取、精确图片版本、幂等上传和原子 revision/absence 检查。上传确认不意味着发布。

超限的 MCP 请求体在工具执行前返回 413；传输错误只返回协议字段，不暴露异常内部信息。请求与并发上限见[集成记录](notes/proposed/2026-09-05-local-execution-supervisor.md#mcp-and-java-integration)。

`repo_exec` 要求显式分配 `EXECUTE_REPOSITORY`，并设置 `POKETTO_EXECUTOR_ENABLED=true`。在 Linux 应用上配置 `POKETTO_EXECUTOR_SOCKET`、`POKETTO_EXECUTOR_SIGNING_KEY` 与 `POKETTO_EXECUTOR_STAGING_DIRECTORY`，再按 [worker 参考文档](executor-service/README.md)安装并验证独立 root supervisor 和低权限 SRT 账号。应用默认接纳两个会话、最多导出 128 MiB bundle；应用接纳与导出限制须对齐 worker，并在使用前测量生产限制。

每个客户端执行会话固定于选定的 commit，即使共用 key 也有独立目录。补丁返回新 commit，不会切换旧执行目录；`get_file` 始终读取权威 Git 对象。取消、撤权和续租失败会关闭执行权限。worker 缺失或隔离能力不受支持时，不会降级为普通子进程。[集成记录](notes/proposed/2026-09-05-local-execution-supervisor.md)区分可执行检查、已有合成证据和最终客户端与部署验收。

## 部署

每个通过验证的 `main` 提交都会分别发布 Spring 和前端镜像，两者来自同一源码提交。把 `deploy/` 中的文件和填好的 `.env.example`（命名为 `.env`）放入主机部署目录。私有运行配置需提供域名与 DNS、一次性 owner 初始化凭证、仓库与数据库凭证、独立数据目录和四个固定镜像。运行 `deploy.sh --app-image <应用镜像> --app-revision <提交> --frontend-image <前端镜像>`；后续不带参数运行会重新部署已记录版本。两个应用镜像的 revision 标签必须匹配，PostgreSQL 与 Caddy 必须使用 registry digest。

Caddy 负责公开 HTTPS，把 `/api` 与 `/mcp` 转交 Spring，其余路径转交 Next.js，并阻断管理探针。只有容器健康且本地网站与 API 通过证书校验的 HTTPS 请求后才确认部署成功。主机无法访问 GHCR 时，`deploy/transfer.sh` 传输两个应用镜像；数据库与网关仍要求可访问 Docker Hub，或已缓存其精确 digest。`--pull --sync` 模式在主机拉取应用镜像的同时同步当前部署文件。自动部署仍需通过 production 环境单独启用。先独立安装并验证主机执行服务，再设置 `POKETTO_EXECUTOR_ENABLED=true`；缺少隔离前置条件时部署失败关闭。镜像身份、配置、持久化边界和待完成的真实安装验收见[部署栈记录](notes/implemented/2026-09-05-blog-stack-delivery.md)。

把 `POKETTO_NETWORK_SUBNET` 设置为未被占用、至少含 16 个地址的 RFC1918 IPv4 CIDR，把 `POKETTO_NETWORK_DYNAMIC_RANGE` 设置为规范且严格包含于主网、至少含八个地址的动态子池。把 `POKETTO_GATEWAY_INTERNAL_IP` 设置为池外的 Caddy 固定地址，排除主网的网络地址、供网桥使用的首个可用地址和广播地址。部署会在启动容器前拒绝无效范围；Docker 只从动态池为其他服务分配地址。只有该部署启用 Tomcat 转发解析，且仅信任网关的 `/32`；Caddy 重建客户端地址、协议和主机头，并在转交 Spring 前移除 `X-Forwarded-Port`。其它入口显式默认为 `server.forward-headers-strategy=none`。`./gradlew proxyForwardingCheck` 需要 Docker 和 Python 3.10+，验证真实 Compose 地址分配、客户端独立登录限流与共享账号限流；`check` 和 CI 必须执行它。

## 授权

代码与项目文档：[Apache-2.0](LICENSE)。
美术素材与站点发布的创作内容：[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans)。
