# 开发与使用

Poketto 的运行依赖、内容配置、MCP 接入与部署参考。

[项目介绍](../README.zh.md) · [English](usage.md)

## 开发

使用 Java 26 和仓库内的 Gradle Wrapper。Linux 执行服务测试需要带 venv 与 pip 支持的 Python 3.10+；Windows 在固定的 Linux 容器中运行该必需测试入口。前端与完整校验还需要 Node.js 24.19.0 和 npm 12.0.2。数据库集成测试与完整校验需要 Docker；较快的单元测试和仓库校验不需要。`./gradlew frontendCheck` 运行前端格式、类型、测试和生产构建。通过[隔离浏览器入口](../acceptance/README.md)使用合成数据操作真实应用；前端运行设置见 [frontend/README.md](../frontend/README.md)。

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

初始化首个 owner 前，私下设置 `POKETTO_AUTH_INITIALIZATION_TOKEN`，并把 `POKETTO_SECURITY_ALLOWED_ORIGINS` 配置为浏览器使用的精确 origin。本地 HTTP 还需设置 `POKETTO_SESSION_COOKIE_SECURE=false`；HTTPS 保留安全默认值。初始化或登录前先获取 `/api/auth/csrf`，后续请求同时携带会话 cookie 和响应指定的 CSRF header。初始化与登录顺序见[身份 HTTP 契约](../notes/implemented/2026-09-06-workspace-identity-http.md#operation)。部署 profile 从私有运行配置中传入这些身份设置。

注册邀请码与空间邀请相互独立。`POKETTO_REGISTRATION_USER_INVITATIONS_ENABLED` 默认 `false`，只允许站点管理员签发注册邀请码；设置为 `true` 后普通账号也能签发。目前不设置固定的每用户签发数量。关闭签发后，签发者仍可列出和撤销自己的邀请码。账号与注册 HTTP 接口见[注册邀请码](../notes/implemented/2026-09-11-registration-invitations.md#http-contract)，对应网页流程属于[多用户交付提案](../notes/proposed/2026-09-11-multiuser-workspaces-and-discovery.md)。

Windows 下 `check` 还会在固定版本的 Linux 容器中通过临时原生磁盘卷运行 `linuxStorageTest`，包括公开标记持久化与快照恢复测试。Windows 开发模式只能在远端重新验证成功后建立内存公开快照；离线重启不会从磁盘恢复公开授权。Linux 上影响发布的写入必须先成功同步文件与目录才能推送；同步失败或不受支持时关闭公开服务。权威图片存储要求目录同步能力；不支持的宿主不能确认持久化上传。用 `$env:...` 设置同名变量，确保 `POKETTO_DATA_DIR` 是绝对路径，再使用 `.\gradlew.bat`。命令表与协作规则见 [AGENTS.md](../AGENTS.md#commands)。

## 内容与图片

空内容仓可使用 [content-template](../content-template/AGENTS.md) 初始化。模板提供各自组织的 `private/` 和 `public/`，默认禁用发布。新内容放入 `private/`；要发布选定内容，先把它及所需媒体移入 `public/`，再配置 `.poketto/publishing.yaml`：

```yaml
enabled: true
mode: public-root
exclude:
  - public/drafts/**
```

只有精确根目录 `public/` 下的路径才有资格公开。排除规则使用完整仓库相对路径并优先生效；任意大小写的 `AGENTS.md` 和含隐藏路径段的文件保持私有。两个根目录内部的名称都是普通分类。策略缺失或禁用时不发布任何内容；策略无效时关闭公开服务。采用旧默认公开格式的仓库须在升级前完成[协调内容转换](../notes/implemented/2026-09-09-codeact-content-and-media.md#implementation-and-acceptance)；直接覆盖模板不能替代转换。

Markdown 元数据可选，未修改的源码字节保持原样。默认路由省略 `public/` 和 `.md`；显式路由保持不变，但不能赋予公开权限。公开详情入口为 `GET /api/public/document?route=...`；列表、搜索与标签响应包含快照元数据。`index.md` 拥有所属文件夹的路由（`public/index.md` 对应 `/`），并提供不递归、不重复正文图片的同目录图库。

认证后的 `/api/admin/repository` 入口提供 Markdown 索引、分页目录列表、文件读取、搜索、预览、原子补丁与移动。浏览器目标选择器可以移动文件或文件夹，并在同一次提交中修复 Markdown 引用。文本变更须在 base commit 下携带 revision 或明确的缺失条件；移动在该版本检查来源和目标。冲突或不明确结果须重新读取后再决定是否重试。`/api/admin/assets` 图片上传要求 `Idempotency-Key`，最多接收 16 MiB，返回不可变引用，不写 Git、不发布。

新建路径输入框默认从 `private/` 开始。移动选择器中的私有／公开目录按钮在切换根目录时保留分类路径；选定目标后，提交移动才会写入仓库。移动目录包含其中的索引媒体，单独移动文档不会带走共享依赖。

托管原图保存在 `<data-dir>/managed-originals` 并持续保留；`<data-dir>/derived/repository-images` 可以删除重建。公开图片授权绑定精确页面快照，最长五分钟且不超过快照有效期。撤回内容后停止签发新授权，私有预览则重新验证当前身份。限制、存储保证与失败行为见[创作基础记录](../notes/implemented/2026-09-05-repository-authoring-foundations.md)。

`POST /api/admin/media` 接收最多 128 MiB 的原始 octet-stream 字节，要求 `Idempotency-Key`，可选 `X-Media-Type`。字节去重严格限定在同一工作空间内，不同上传保留独立身份。可用 `poketto.assets.max-file-bytes` 调低上传限制；既有原件仍可读取。[逻辑媒体索引](../notes/implemented/2026-09-09-logical-media-index.md)把媒体路径合并进 Git 目录列表，并可与文本一同原子保存。[索引媒体交付](../notes/implemented/2026-09-09-indexed-media-delivery.md)支持相对图片链接，并通过认证后的 `/api/admin/media` 和绑定公开快照的 `/api/public/media` 下载原件附件。上传不会写入索引或发布内容。

## 导出 HTTP 接口

在编辑器中，点击文件旁或展开文件夹内的“导出”，也可以从文件侧栏顶部导出整个工作空间。
选择私人副本或公开副本，生成 ZIP 后下载。导出使用最新已保存的内容，不包含编辑器中未保存的修改。
公开副本遇到私密内容会拒绝导出，不会改变发布状态。下载交给浏览器的下载管理器；关闭对话框后，
已提供的下载包保留至到期，以便正在进行的下载完成。

在原生 Linux 上，`POST /api/admin/exports` 接收 `paths`（明确的 Markdown、索引媒体路径或目录前缀）
和必填布尔值 `publicOnly`，返回临时句柄、ZIP 大小、SHA-256 和到期时间。
`GET /api/admin/exports/{handle}` 下载 ZIP；追加 `/metadata` 可读取回执，
`POST /api/admin/exports/{handle}/release` 可提前释放。创建和释放沿用会话 CSRF 保护。
每次操作都会重查所属身份和工作空间，句柄不能作为匿名下载链接分享。

私密包要求私密读取权限并保留原文 frontmatter。公开包只包含已批准的文章字段和有权限访问的原件；
选中私密内容会失败，不会顺带发布。相对链接指向 ZIP 内的实际媒体，不包含原始 Git 历史、运行指导
或内部媒体索引。下载时再次检查发布状态、到期时间和身份，在输出前校验 ZIP，以附件方式返回，
并设置 `no-store`、`nosniff`。

服务在 `<data-dir>/portable-exports/<workspace-id>` 暂存，清理到期或遗留的包。
默认同时构建一个包、下载两个包（同一工作空间最多一个），最多包含 512 MiB 原件、256 MiB 文本和
10,000 个条目。`poketto.exports` 下可配置 `max-zip-bytes`（800 MiB）、
`max-retained-bytes`（2 GiB）、`max-workspace-bytes`（1600 MiB）、`max-packages`（8）、
`lifetime-seconds`（600）与 `build-seconds`（120）。构建前预留完整 ZIP 额度，成功后仅计入实际大小。
容量不足返回 429；缺失、过期或属于其他身份的句柄返回 404。不支持 POSIX 权限的文件系统会在读取
导出内容前返回 503。包内容与生命周期见
[导出决策](../notes/implemented/2026-09-10-portable-content-exports.md)。

在 CodeAct 会话中使用 `poketto export PATH... --output FILE [--public]`。
选择仓库相对路径的文件或文件夹，`.` 表示可见工作空间。仅公开读取的会话始终导出宿主批准的公开投影。
ZIP 包含最新已保存的内容与原件，不包含本地编辑；输出位置已有不同内容时保留原文件。
结果符合 artifact 限额时，可执行 `poketto artifact create FILE --type application/zip`，再通过
`get_artifact` 取回。[worker 文档](../executor-service/README.md)定义时限、大小边界、安装顺序和错误码；
[真实 HTTP MCP 验收](../acceptance/clients/evidence/2026-09-10-cli-exports.json)验证了身份认证后的导出与 artifact 回传。
`MATERIALIZE_CAPACITY` 会保留会话和已有文件：清理本地空间、缩小选择范围，或改用浏览器导出超出 worker 容量的 ZIP。

## MCP 与隔离执行

`/mcp` 使用 Spring AI 2.0.1 WebMVC Streamable HTTP，以工作空间 Bearer API key 认证，独立于浏览器会话。启用执行器后，工具目录包含 `repo_exec`、`get_artifact`、`get_asset` 和 `put_asset`。图片工具传输精确版本并支持幂等上传；上传确认不意味着发布。

通过 `repo_exec` 查看目录、搜索、读取和编辑文件，再使用 `poketto` CLI 持久化修改。按需逐层读取内容仓库自己的 `AGENTS.md`。独立的 `list_directory`、`get_file` 和 `repo_patch` 不再受支持，关闭 worker 时也不会恢复它们。[CodeAct 入口记录](../notes/implemented/2026-09-10-codeact-mcp-entrance.md)定义这一边界；共享目录读取服务仍用于浏览器导航。

超限的 MCP 请求体在工具执行前返回 413；传输错误只返回协议字段，不暴露异常内部信息。请求与并发上限见[集成记录](../notes/proposed/2026-09-05-local-execution-supervisor.md#mcp-and-java-integration)。

`repo_exec` 要求显式分配 `EXECUTE_REPOSITORY`，并设置 `POKETTO_EXECUTOR_ENABLED=true`。在 Linux 应用上配置 `POKETTO_EXECUTOR_SOCKET`、`POKETTO_EXECUTOR_SIGNING_KEY` 与 `POKETTO_EXECUTOR_STAGING_DIRECTORY`，再按 [worker 参考文档](../executor-service/README.md)安装并验证独立 root supervisor 和低权限 SRT 账号。应用默认接纳两个会话、最多导出 128 MiB bundle；应用接纳与导出限制须对齐 worker，并在使用前测量生产限制。

完整读取权限的执行会话保留授权范围内的当前文件和原始 Git 历史；仅公开读取的会话获得新的当前公开投影，不含原始历史或私密元数据。即使共用 key，每个客户端也有独立目录。普通编辑留在本地。`poketto save` 通过共用原子写入服务提交选定文件和明确删除，并保留未选中的编辑；`poketto sync` 按单个文件自己的基线合并，`poketto recover` 核实待处理的保存或移动，不会重放后续编辑。取消、撤权和续租失败会关闭执行权限。worker 缺失、CodeAct 协议不匹配或隔离能力不受支持时，不会降级为普通子进程。

`poketto media import` 存储工作空间内的不可变原件并更新本地逻辑索引；将索引与引用它的文本一起保存，才能持久化这些引用。完整读取会话中的 `poketto media fetch` 使用本地索引或明确选定的历史提交，仅公开读取的会话则使用服务端持有的已批准映射。CLI 路径相对仓库根目录；命令和文件生命周期见 `poketto --help`。[worker 参考文档](../executor-service/README.md)定义限制、权限、冲突处理和配套安装。

`poketto move SOURCE DESTINATION` 移动已保存的文件、目录和索引媒体，并在同一次远端提交中修复
Markdown 引用。未选中的本地编辑和未保存索引条目仍留在本地。选定文件存在未保存修改或目标已占用时，
移动会被拒绝。提交成功但本地安装尚未完成时，先使用 `poketto recover`，再继续保存、移动或同步；
恢复会保留原始操作，也会保留本地安装完成后产生的新编辑。
如果本地修改导致安装持续被拒绝，可用 `poketto recover --skip-local` 确认远端移动并保留全部本地文件。
它解除待处理移动，但不推进文件基线；再次保存相关路径前，用 `poketto sync` 合并并处理冲突。

`poketto media list` 列出索引中的媒体，不会下载原件。完整读取会话也能看到尚未保存的导入，
公开会话只使用宿主持有的已批准映射。可用 `--prefix` 筛选路径，并使用返回的 `nextOffset`
和 `indexVersion` 继续翻页。完整读取者可用 `--commit` 选择历史索引。获取原件时才会核对存储中的实际对象。
分页时保持 `--prefix` 和 `--commit` 不变；更换范围时从偏移零重新开始。
历史列表共用原件读取的并发限制，名额占满时可能返回 `MEDIA_UNAVAILABLE`。

`poketto artifact create FILE --type MIME` 为当前 MCP 会话保留不可变的临时结果。
`get_artifact` 可展示通过校验的位图，或分页返回文本、二进制；长命令输出也会附带制品句柄。
句柄在五分钟后或会话关闭时失效，不会上传、保存或发布文件。
超时、资源限制或取消会关闭会话，此时长输出只保留预览，并明确报告制品不可用。
[worker 参考文档](../executor-service/README.md#returned-artifacts)定义配额、按字节分页和授权规则。

## 部署

每个通过验证的 `main` 提交都会分别发布 Spring 和前端镜像，两者来自同一源码提交。把 `deploy/` 中的文件和填好的 `.env.example`（命名为 `.env`）放入主机部署目录。私有运行配置需提供域名与 DNS、一次性 owner 初始化凭证、仓库与数据库凭证、独立数据目录和四个固定镜像。运行 `deploy.sh --app-image <应用镜像> --app-revision <提交> --frontend-image <前端镜像>`；后续不带参数运行会重新部署已记录版本。两个应用镜像的 revision 标签必须匹配，PostgreSQL 与 Caddy 必须使用 registry digest。

对于使用自行维护的 Compose 配置的现有实例，[现有安装交付](../notes/implemented/2026-09-08-existing-installation-delivery.md)只更新应用与前端镜像。配置受保护的更新入口，并选择 `POKETTO_DEPLOY_LAYOUT=existing` 与 transfer 模式；Compose 文件、环境配置和依赖服务继续由运维配置维护。

Caddy 负责公开 HTTPS，把 `/api` 与 `/mcp` 转交 Spring，其余路径转交 Next.js，并阻断管理探针。只有容器健康且本地网站与 API 通过证书校验的 HTTPS 请求后才确认部署成功。HTTPS 检查在 `POKETTO_HEALTH_TIMEOUT` 的剩余时间内重试，等待证书和路由就绪；默认时限为 180 秒。主机无法访问 GHCR 时，`deploy/transfer.sh` 传输两个应用镜像；数据库与网关仍要求可访问 Docker Hub，或已缓存其精确 digest。`--pull --sync` 模式在主机拉取应用镜像的同时同步当前部署文件。自动部署仍需通过 production 环境单独启用。先独立安装并验证主机执行服务，再设置 `POKETTO_EXECUTOR_ENABLED=true`；缺少隔离前置条件时部署失败关闭。镜像身份、配置、持久化边界和待完成的真实安装验收见[部署栈记录](../notes/implemented/2026-09-05-blog-stack-delivery.md)。

把 `POKETTO_NETWORK_SUBNET` 设置为未被占用、至少含 16 个地址的 RFC1918 IPv4 CIDR，把 `POKETTO_NETWORK_DYNAMIC_RANGE` 设置为规范且严格包含于主网、至少含八个地址的动态子池。把 `POKETTO_GATEWAY_INTERNAL_IP` 设置为池外的 Caddy 固定地址，排除主网的网络地址、供网桥使用的首个可用地址和广播地址。部署会在启动容器前拒绝无效范围；Docker 只从动态池为其他服务分配地址。只有该部署启用 Tomcat 转发解析，且仅信任网关的 `/32`；Caddy 重建客户端地址、协议和主机头，并在转交 Spring 前移除 `X-Forwarded-Port`。其它入口显式默认为 `server.forward-headers-strategy=none`。`./gradlew proxyForwardingCheck` 需要 Docker 和 Python 3.10+，验证真实 Compose 地址分配、客户端独立登录限流与共享账号限流；`check` 和 CI 必须执行它。
