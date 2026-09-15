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

先启动应用完成数据库结构初始化，再在部署主机的交互式终端运行 `./deploy.sh --initialize-admin`，输入用户名并两次输入隐藏的密码。命令只创建一次站点管理员和默认空间主人，不能替换已有账号；它使用正在运行的应用容器中的数据库配置，不提供网页安装入口。自定义容器安装方式见[管理员安装命令](../notes/implemented/2026-09-11-operator-administrator-setup.md)。将 `POKETTO_SECURITY_ALLOWED_ORIGINS` 配置为浏览器使用的精确 origin；本地 HTTP 还需设置 `POKETTO_SESSION_COOKIE_SECURE=false`，HTTPS 保留安全默认值。登录前获取 `/api/auth/csrf`，后续请求携带会话 cookie 和响应指定的 CSRF header。

注册邀请码与空间邀请相互独立。`POKETTO_REGISTRATION_USER_INVITATIONS_ENABLED` 默认 `false`，只允许站点管理员签发注册邀请码；设置为 `true` 后普通账号也能签发。目前不设置固定的每用户签发数量。关闭签发后，签发者仍可列出和撤销自己的邀请码。登录后，在“账号与空间”中创建并复制注册邀请码或注册链接，也可查看状态和撤销。访客点击“注册”，填写邀请码、用户名和密码；注册并登录后还没有空间权限，需要在管理页单独填写空间邀请码加入。接口与流程见[注册邀请码](../notes/implemented/2026-09-11-registration-invitations.md)。

Windows 下 `check` 还会在固定版本的 Linux 容器中通过临时原生磁盘卷运行 `linuxStorageTest`，包括公开标记持久化与快照恢复测试。Windows 开发模式只能在远端重新验证成功后建立内存公开快照；离线重启不会从磁盘恢复公开授权。Linux 上影响发布的写入必须先成功同步文件与目录才能推送；同步失败或不受支持时关闭公开服务。权威图片存储要求目录同步能力；不支持的宿主不能确认持久化上传。用 `$env:...` 设置同名变量，确保 `POKETTO_DATA_DIR` 是绝对路径，再使用 `.\gradlew.bat`。命令表与协作规则见 [AGENTS.md](../AGENTS.md#commands)。

## 内容与图片

管理页会列出当前账号的空间。选择空间后再编辑，URL 中的 `workspace` 参数让不同标签页保持独立。“账号与空间”支持连接已有的 GitHub/CNB 私有仓库、查询或重试中断的创建申请，以及接受空间邀请码。启用仓库连接前，将 `POKETTO_REPOSITORY_CREDENTIAL_KEY` 配置为 Base64 编码的 32 字节密钥。新空间默认关闭公开网站。仓库令牌需要读取元数据和 Git 写入权限，不会保存在浏览器草稿中。

私有 HTTP 入口统一使用 `/api/admin/workspaces/{workspaceId}`。`GET /api/auth/workspaces` 列出成员空间，`GET /api/auth/workspaces/{workspaceId}/me` 查询当前权限；没有指定空间的管理路径不会回退到默认空间。OAuth 授权时选择一个已加入的空间，`/mcp` 从已签发凭据解析该空间。详见[工作空间路由](../notes/implemented/2026-09-11-workspace-browser-and-mcp-routing.md)。

所有者在成员管理或空间邀请中分别设置私密读取、私密修改和公开内容修改/发布权限。邀请默认仅允许查看公开范围；私密修改必须同时允许私密读取。即使位于 `public/` 下，被发布策略排除的文件仍属私密内容。空间的匿名网站关闭时，成员仍可读取其当前公开范围。收回权限会撤销权限超限的连接；增加权限不会扩大已有连接的授权。详见[成员内容权限](../notes/implemented/2026-09-12-member-content-permissions.md)；安装该表结构后，已有普通成员也会失去隐含的私密访问权限。

连接 MCP 时，在客户端填写 `https://your-domain.example/mcp`，选择 OAuth，客户端 ID 和密钥留空以使用动态注册。浏览器类客户端注册 HTTPS 回调；命令行客户端注册环回回调，例如 `http://127.0.0.1:<端口>/…`，端口不限，开始监听后才拿到的端口同样可用。其余主机一律要求 HTTPS，且逐字比对。登录 Poketto 后选择已加入的空间，核对应用返回地址，再明确批准权限。只能委托自己持有的权限；私密读取、私密修改和公开发布默认均不勾选。成员可查看和断开自己的连接，所有者可管理空间内全部连接。通过隔离命令保存需要完整源码读取权限，以及受影响内容对应的写权限；私密读取加公开发布可以保存公开文件，无需私密修改。没有私密读取权限时，执行环境中的公开投影仍为只读，即使授予公开发布也不能提交；裁剪后的投影缺少作者元数据，不能安全覆盖原文。

空间主人可在“仓库连接”标签页更新托管仓库的凭据。填写 Git 用户名和新令牌，服务端验证访问权限后才替换原凭据，不能借此更改仓库地址。表单提交后会清空令牌，也不会将它保存在浏览器草稿中。确认更新成功后，再到 Git 托管平台撤销旧令牌。由部署配置管理的仓库需由站点管理员修改配置。

空内容仓可使用 [content-template](../content-template/AGENTS.md) 初始化。模板提供各自组织的 `private/` 和 `public/`，默认禁用发布。新内容放入 `private/`；要发布选定内容，先把它及所需媒体移入 `public/`，再配置 `.poketto/publishing.yaml`：

```yaml
enabled: true
mode: public-root
exclude:
  - public/drafts/**
```

只有精确根目录 `public/` 下的路径才有资格公开。排除规则使用完整仓库相对路径并优先生效；任意大小写的 `AGENTS.md` 和含隐藏路径段的文件保持私有。两个根目录内部的名称都是普通分类。策略缺失或禁用时不发布任何内容；策略无效时关闭公开服务。采用旧默认公开格式的仓库须在升级前完成[协调内容转换](../notes/implemented/2026-09-09-codeact-content-and-media.md#implementation-and-acceptance)；直接覆盖模板不能替代转换。

Markdown 元数据可选，未修改的源码字节保持原样。默认路由省略 `public/` 和 `.md`；普通文章的显式路由保持不变，但不能赋予公开权限。目录入口始终从所属目录计算路由，忽略 frontmatter 中的路由覆盖；移动后也遵循该规则，作者原文保持不变。公开详情入口为 `GET /api/public/document?route=...`；列表、搜索与标签响应包含快照元数据。`index.md` 拥有所属文件夹的路由（`public/index.md` 对应 `/`）；不存在符合读取范围且有效的 index 时，使用 `README.md`。两者均提供不递归、不重复正文图片的同目录图库；同时有效时，结构化阅读使用 index，README 原文保留。图库图片可在弹窗内用按钮或左右方向键切换，按 Escape 关闭并返回原入口焦点。公开图库先加载最长边不超过 640 像素的缩略图，打开弹窗后再读取未改动的原图。透明缩略图使用 PNG，不透明缩略图使用 JPEG；保留 JPEG 的方向信息，动画预览使用第一帧。WebP 缩略图接受最多 400 万源像素；本版不为带 EXIF 元数据的 PNG/WebP 生成缩略图。预览不可用时仍可点击查看已授权的原图。管理端预览继续读取原始图片。

公开文章、相册与合集入口、发现卡片和搜索摘要优先显示 frontmatter 中非空的 `public_author`。未填写时使用「网站发布」中的空间公开署名；空间署名也留空时使用公开空间名称。两种署名都是去除首尾空白的单行文字，最多 120 个 Unicode 字符。只有登录的空间 owner 可以修改空间署名。原有的 `author` 等任意元数据、账户资料和 Git 提交身份保持私有。

公开搜索和授权后的管理端搜索按标题及解析后的 Markdown 阅读文本作字面匹配。链接文字、图片描述、代码、表格单元格和被引用的脚注参与匹配；隐藏的目标地址、原始 HTML 和未引用的脚注定义不参与。摘要合并空白，仅在开头的一级标题文字与页面标题相同时省略它，再围绕命中位置生成有长度上限的片段。存储的 Markdown 和文章正文保持原样。

站点 `/search` 通过 `/api/public/search` 汇总所有已开启网站、当前已验证的公开快照。结果卡片标明所属空间并进入该空间的正式文章地址；`/s/{slug}/search` 仍只搜索指定空间。站点结果按创建时间从新到旧、空间 slug 和文章路由排序，总数覆盖本次搜索范围。每页读取当前内容，发布变化可能改变总数和分页边界。任一快照不可用，或查询过程中范围发生变化时，整次查询会失败，不返回部分结果。服务允许两个并发查询；单次扫描最多 256 个空间、100,000 篇文档和 64 Mi 个 UTF-16 正文字符，并在处理步骤之间检查五秒期限。超出上限时，可稍后重试或进入可用空间单独搜索。原 `/api/public/documents` 仍限定默认空间，供归档和订阅等入口使用。

公开搜索会在标题和摘要中高亮精确命中的文字。打开结果后可点击“返回搜索结果”，保留查询、页码和空间。浏览器返回会恢复选中的结果与滚动位置；浏览器仍保留该标签页的记录时，显式返回链接也会恢复位置。本地存储不可用或结果已移除时，仍会正常打开搜索页面。直接访问文章时保留空间与合集导航。

认证后的 `/api/admin/workspaces/{workspaceId}/repository` 入口提供 Markdown 索引、分页目录列表、文件读取、搜索、预览、原子补丁与移动。浏览器目标选择器可以移动文件或文件夹，并在同一次提交中修复 Markdown 引用。文本变更须在 base commit 下携带 revision 或明确的缺失条件；移动在该版本检查来源和目标。冲突或不明确结果须重新读取后再决定是否重试。`/api/admin/workspaces/{workspaceId}/assets` 图片上传要求 `Idempotency-Key`，最多接收 16 MiB，返回不可变引用，不写 Git、不发布。

编辑器在 URL 中分别保留所选目录和文档。刷新及前进／后退会恢复仓库中已保存的内容；取消放弃修改时，当前草稿和导航历史均会保留。切换空间会清除原空间的目录和文档。“新建笔记”和“新建文件夹”只需填写名称，表单会显示私有目标位置：即使选择了公开目录，也会将同一分类放在 `private/` 下。准备操作只打开本地草稿；点击“保存”才写入笔记或文件夹的普通 `index.md`。准备时拒绝已存在的精确路径，保存时检查仓库名称冲突，失败后保留草稿供修改。“高级：完整路径”仍提供从 `private/` 开始的路径输入框。

移动选择器中的私有／公开目录按钮在切换根目录时保留分类路径；选定目标后，提交移动才会写入仓库。移动目录包含其中的索引媒体，单独移动文档不会带走共享依赖。

“搜索文件名”会搜索所有获准访问的普通 Git 文件和索引媒体路径，包括尚未展开的文件夹。查询按仓库相对路径作字面匹配，每页显示最多 50 项。翻页固定使用首次返回的 commit，重新搜索会读取当前 main。仅公开读取的成员只能找到当前符合发布策略的路径，网站开关关闭时仍可使用。正文搜索保留为独立表单，两种搜索都会高亮字面命中；搜索本身保留草稿，打开其他结果前会确认是否放弃修改。二进制文件继续遵循原有的文本读取限制。

文件名接口为 `/api/admin/workspaces/{workspaceId}/repository/filenames`，参数是 `query`、可选 `commit`、`offset` 和 `limit`。查询长度为 1–200 个字符，每页允许 1–200 项，offset 为 0–100,000。后续页必须携带首页返回的 commit。单次扫描最多处理 100,000 个 Git 树与索引媒体条目，包括经过的目录；超限时明确失败，不返回部分计数。

编辑器分别显示保存状态、公开范围和公开页面是否可用。“查看公开页面”会在新标签页打开已确认的正式地址；未保存的修改继续保留在编辑器中，不会改变公开页面。网站未开启和页面暂不可用分别显示。保存成功后，编辑器会单独读取该 commit 的元数据来刷新公开状态；读取失败时保留已确认的保存结果和正文，“重新查询状态”只重试读取。新草稿和修改后的目标路径在保存后再确认公开页面状态。

托管原图保存在 `<data-dir>/managed-originals` 并持续保留；`<data-dir>/derived/repository-images` 可以删除重建。公开图片授权绑定精确页面快照，最长五分钟且不超过快照有效期。关闭网站或替换公开快照也会使已签发的图片地址失效；刷新页面可取得当前地址。私有预览重新验证当前身份。[网站交付边界](../notes/implemented/2026-09-14-workspace-public-delivery.md)记录这次授权变化，[创作基础记录](../notes/implemented/2026-09-05-repository-authoring-foundations.md)继续规定存储保证与限制。

人类 owner 可在所选空间的“网站发布”面板，或通过 `GET` / `PUT /api/auth/workspaces/{workspaceId}/publication` 读取和修改网站开关。修改须携带会话 CSRF token，并明确提交 `{ "enabled": true }` 或 `{ "enabled": false }`；省略字段会报错。面板会要求确认，回包不确定时须重新读取状态后再操作。关闭网站不影响成员读取获准访问的仓库文件。

已开启的网站以 `/s/{slug}` 为入口，各自提供 `/search`、`/tags`、`/archive` 和 `/read/...` 页面。对应的 `/api/public/spaces/{slug}` 接口将文章和媒体限定在该空间内。未知或关闭的空间返回 404。后台每轮最多轮转刷新八个已开启的空间，并刷新默认空间供仓库健康检查使用。刚开启的网站可能要等刷新成功后才能访问；网页请求不会拉取远端 Git。公开原件下载 URL 除页面路由、commit 和逻辑路径外，还必须携带 `workspace` 查询参数；不透明图片 token 本身已绑定空间。

`/sitemap.xml` 为站点首页和所有已开启的空间网站提供索引。子站点地图从各空间当前获准发布的快照列出规范的 `/s/{slug}` 和 `/s/{slug}/read/...` 地址，不抽样使用发现批次。快照不可用或枚举超限时返回 503，不交付不完整列表；未知或关闭的空间返回 404。`/robots.txt` 使用 `POKETTO_PUBLIC_URL` 声明该索引，并建议爬虫避开 `/admin` 和 `/api/`。这些抓取指令不授予或撤销内容访问权限。

站点首页从已开启的公开空间抽取内容，组成顺序稳定的浏览批次。翻页和浏览器返回保持顺序；点击“换一批”才重新抽取。撤下内容会从既有批次中隐藏对应卡片。批次最长保留 30 分钟，重启或缓存淘汰可能使其提前过期；过期链接会提供重新开始入口。每批最多从 32 个空间各抽取四页，后续批次继续遍历空间目录；发现页不是完整搜索。详见[发现批次](../notes/implemented/2026-09-14-public-discovery-batches.md)。

首页卡片区分文章、目录、相册和合集。文件夹同时包含同目录图片和编排好的阅读顺序时，会同时显示相册、合集标签。当前页的相册卡片加载一张缩略图，点击进入对应文件夹；封面不可用时保留入口，不自动下载原图。重新打开仍有效的批次页面会刷新短期封面地址，卡片顺序保持不变。

文件夹入口正文中的文章链接按作者编排顺序组成合集。从入口开始阅读时，上下篇和返回链接保留所选合集；直接打开文章则列出所属合集，不自动猜测。重复链接只计一次，私有、缺失或外站目标不进入目录，最后一篇有明确提示。文章开头与页面标题相同的一级标题会隐藏，但保留原有锚点；Markdown 原文和编辑器预览不变。详见[合集阅读](../notes/implemented/2026-09-14-collection-reading.md)。

`POST /api/admin/workspaces/{workspaceId}/media` 接收最多 128 MiB 的原始 octet-stream 字节，要求 `Idempotency-Key`，可选 `X-Media-Type`。字节去重严格限定在同一工作空间内，不同上传保留独立身份。可用 `poketto.assets.max-file-bytes` 调低上传限制；既有原件仍可读取。[逻辑媒体索引](../notes/implemented/2026-09-09-logical-media-index.md)把媒体路径合并进 Git 目录列表，并可与文本一同原子保存。[索引媒体交付](../notes/implemented/2026-09-09-indexed-media-delivery.md)支持相对图片链接，并通过认证后的 `/api/admin/workspaces/{workspaceId}/media` 和绑定公开快照的 `/api/public/media` 下载原件附件。上传不会写入索引或发布内容。

只有发布权限、没有私密读取权限的成员，可以通过“选择公开图片”插入图片。列表包含当前发布规则允许的 Git 图片和索引图片，插入相对路径，不显示私密或已撤下的内容。上传新的原始文件仍需私密写入权限。

## 导出 HTTP 接口

在编辑器中，点击文件旁或展开文件夹内的“导出”，也可以从文件侧栏顶部导出整个工作空间。
选择私人副本或公开副本，生成 ZIP 后下载。导出使用最新已保存的内容，不包含编辑器中未保存的修改。
公开副本遇到私密内容会拒绝导出，不会改变发布状态。下载交给浏览器的下载管理器；关闭对话框后，
已提供的下载包保留至到期，以便正在进行的下载完成。

在原生 Linux 上，`POST /api/admin/workspaces/{workspaceId}/exports` 接收 `paths`（明确的 Markdown、索引媒体路径或目录前缀）
和必填布尔值 `publicOnly`，返回临时句柄、ZIP 大小、SHA-256 和到期时间。
`GET /api/admin/workspaces/{workspaceId}/exports/{handle}` 下载 ZIP；追加 `/metadata` 可读取回执，
`POST /api/admin/workspaces/{workspaceId}/exports/{handle}/release` 可提前释放。创建和释放沿用会话 CSRF 保护。
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

使用 `poketto edit PATH --old TEXT --new TEXT` 替换已有本地文本文件中唯一、完全匹配的一段原文。原文不存在或匹配多处时拒绝修改。`poketto create PATH --text TEXT` 仅在目标路径不存在时新建本地文本文件。这两个命令都不改变远端 Git 或发布状态，正式保存仍需通过授权的 `poketto save`。写入前会再次核对捕获的本地内容；普通 shell 写入仍可使用，但不具备这些编辑前置检查。


长文本可使用 `poketto create PATH --stdin`，通过带引号的 heredoc 输入 UTF-8；
`--text-file FILE` 从已有 UTF-8 文件读取。`poketto edit` 可用 `--old-file FILE`
代替 `--old`，用 `--new-stdin` 或 `--new-file FILE` 代替 `--new`。末尾换行会被保留，
空替换内容表示删除原文。输入文件路径按当前 shell 目录解析，目标路径仍相对于仓库根。
这些选项保留路径不存在、原文唯一匹配及写入前比较检查，不提高 `repo_exec` 的 16,384
字符命令上限或编码后 512 KiB 的桥接帧上限；更大的内容可使用已有输入文件或拆为较小的精确编辑。

```sh
poketto create private/article.md --stdin <<'MARKDOWN'
# 文章

引号、`$variables` 和反引号都会保留为普通 Markdown。
MARKDOWN
```

`/mcp` 使用 Spring AI 2.0.1 WebMVC Streamable HTTP，以工作空间 Bearer API key 认证，独立于浏览器会话。启用执行器后，工具目录包含 `repo_exec`、`repo_discard`、`get_artifact`、`get_asset` 和 `put_asset`。图片工具传输精确版本并支持幂等上传；上传确认不意味着发布。

`put_asset` 接受 `operationKey`，以及 `url`、`file` 中恰好一种来源。
`url` 是使用 443 端口的公网 HTTPS 图片下载地址。`file` 是平台文件对象，必须包含
`download_url` 和 `file_id`；可选的 `mime_type`、`file_name` 只作提示，不能替代校验。
工具声明 `_meta["openai/fileParams"] = ["file"]`，附件是否自动转交取决于客户端支持。
下载连接使用经过检查的公网 DNS 地址，最多跟随三次重定向，总时限 30 秒，文件上限
16 MiB；不转发 Cookie 或授权头。下载后的原件仍经过现有图片校验。

客户端持有文件时，调用 `put_asset`，传 `mode: "upload"` 和 `operationKey`，不传图片来源。
返回值包含 `uploadUrl`、`method: "PUT"`、`contentType: "application/octet-stream"`、
`maxBytes` 和 `expiresAt`。在持有文件的客户端执行环境中直接上传字节：

```python
import requests
with open(image_path, "rb") as image:
    response = requests.put(upload_url, data=image,
                            headers={"Content-Type": "application/octet-stream"}, timeout=30)
response.raise_for_status()
receipt = response.json()
```

上传请求体的接收限时 30 秒，超时或断连会释放接收名额。发送端迟迟不结束请求体时，代理可能延迟转交错误；请设置客户端超时，并 GET 上传 URL 核对结果。
实例可同时为四个账号接收原始上传，每账号最多一个。每个接收过程预留 32 MiB，容纳最多 16 MiB 的请求体及其完成副本；
默认预算下仍留有至少 128 MiB 供页面图片使用。收齐后，校验与存储另行预留 128 MiB，完成后释放。
申请上传凭据不携带图片字节，无需图片内存份额。忙时返回 `TRANSFER_BUSY`，可用同一凭据重试。

这个 URL 是绑定申请者、空间和操作键的临时上传凭据，不应公开。每次使用都会重新检查
当前权限；15 分钟后或应用重启时失效，MCP 断连不使其失效。公开基址来自
`poketto.oauth.issuer`。实例最多保留 512 个凭据，每个账号最多保留 64 个凭据，其中未完成上传最多 8 个。
回执丢失时可 GET 同一 URL 查询；尚未完成返回 `UPLOAD_PENDING`。过期后使用同一操作键
重新申请，再上传相同字节，持久幂等记录会返回原资源；不同字节则冲突。两条路径统一返回 `assetId`、`revision`、`reference`、
`mediaType` 和 `size`。随后使用 `poketto media link PATH --asset ID --revision REV`，
并显式保存文章及 `.poketto/assets.json`。仅上传不会保存 Git 或发布内容。

MCP 请求体上限为 128 KiB。图片导入和图片响应在各自的处理入口申请图片内存；
普通文本调用不占图片预算。工具拒绝结果与日志包含 `code` 和 `reason`；图片预算不足
返回 `IMAGE_MEMORY_BUSY`。无论是否提供等待提示，结果不确定的写入都要先核对状态再决定是否重试。


`repo_exec` 必须携带 `expectedCopyId`：使用 `"new"` 打开账号的默认副本，仅在不存在时创建；后续调用传回结果中的 `copyId`。重连会自动接回原副本和已确认的基线，无需代次或恢复标志。关闭 MCP 连接会保留副本。每次成功且获授权的副本操作都会将闲置期限延长为七天，期限由 `retention.expiresAt` 返回。`SESSION_REPLACED` 和 `EXECUTION_REFUSED` 表示本次命令未执行；`EXECUTION_UNCONFIRMED` 表示命令可能已部分完成，包括远端写入。不要重复执行结果不确定的写入：先用同一副本 ID 执行只读检查，核对 `retention.lastInterruptedCommand` 和 `poketto status`，远端保存待确认时再使用 `poketto recover`。[副本身份契约](../executor-service/README.md#working-copy-identity)说明执行边界。

完整读取副本的保存或移动得到确认后，会先更新本地 Git HEAD 与索引，再由 CLI 报告成功。未选中的工作文件保留在本地。`repo_exec.commit` 与 `poketto status` 结果中的 `gitCommit` 表示已安装的 Git 基线；若远端结果已保留但本地安装尚未完成，状态中的 `localBaselinePending` 会标明，使用 `poketto recover` 收尾，不要重复保存。宿主的权威写入检查不依赖沙箱 Git 元数据。

完整读取副本的 `poketto status` 还会检查远端 main：`remote.state` 返回 `MATCHES_BASE`、`DIFFERS_FROM_BASE` 或 `UNAVAILABLE`，已知的远端提交由 `remote.commit` 返回。比较使用最近一次已确认的保存或同步基线，不代表所有本地文件都已更新。远端检查失败仍会返回本地状态和保存回执。状态查询不修改工作文件或基线；需要更新时显式执行 `poketto sync`，将整个工作空间与同一个远端版本对齐，包括新增和删除的路径。同步保留仅存在于本地的文件及冲突的二进制内容，重叠的文本修改会写入 LOCAL/BASE/REMOTE 冲突标记；它不会保存远端。中断后，`poketto status` 会显示 `syncPending` 和进度；`poketto recover` 继续已记录的同步，`poketto recover --skip-local` 则解除待处理状态，但不撤销已经安装的文件。后续保存仍逐项检查所选文件与权威远端的冲突。公开副本返回 `PUBLIC_PROJECTION` 和合成提交 ID，不暴露真实仓库提交；每次命令仍须通过现有的公开投影有效性检查。

要丢弃本地工作，调用 `repo_discard` 并传入准确的 `expectedCopyId`。此操作要求当前执行权限和副本归属，内容读取权限收回不妨碍清理。忙碌副本会被拒绝。删除前会记录关闭意图，进程中断后可以继续收尾；确认工作进程已停止后才移除本地文件和宿主元数据。`DISCARDED` 或 `ABSENT` 确认目标副本已不存在，随后可用 `new` 创建另一份副本。未确认的关闭操作只能用同一 ID 重试。丢弃不会撤销远端 Git 提交。

命令超时后，执行器确认完整进程树已停止，再保留当前副本。响应会报告超时；之前的修改和本条命令已完成的部分仍可通过同一 `copyId` 读取。下一条命令使用新的 `/tmp`。资源超限和生命周期取消仍会关闭副本。未开启保留时，这不保证副本能跨传输过期、应用部署或 worker 丢失而恢复。

通过 `repo_exec` 查看目录、搜索、读取和编辑文件，再使用 `poketto` CLI 持久化修改。按需逐层读取内容仓库自己的 `AGENTS.md`。独立的 `list_directory`、`get_file` 和 `repo_patch` 不再受支持，关闭 worker 时也不会恢复它们。[CodeAct 入口记录](../notes/implemented/2026-09-10-codeact-mcp-entrance.md)定义这一边界；共享目录读取服务仍用于浏览器导航。

超限的 MCP 请求体在工具执行前返回 413；传输错误只返回协议字段，不暴露异常内部信息。请求与并发上限见[集成记录](../notes/proposed/2026-09-05-local-execution-supervisor.md#mcp-and-java-integration)。

`repo_exec` 要求显式分配 `EXECUTE_REPOSITORY`，并设置 `POKETTO_EXECUTOR_ENABLED=true`。在 Linux 应用上配置 `POKETTO_EXECUTOR_SOCKET`、`POKETTO_EXECUTOR_SIGNING_KEY` 与 `POKETTO_EXECUTOR_STAGING_DIRECTORY`，再按 [worker 参考文档](../executor-service/README.md)安装并验证独立 root supervisor 和低权限 SRT 账号。应用默认接纳两个会话、最多导出 128 MiB bundle；应用接纳与导出限制须对齐 worker，并在使用前测量生产限制。

完整读取权限的执行会话保留授权范围内的当前文件和原始 Git 历史；仅公开读取的会话获得新的当前公开投影，不含原始历史或私密元数据。同一账号的授权客户端在同一空间和读取范围内共享磁盘副本；完整源码和公开投影仍然隔离。普通编辑留在本地。`poketto save` 通过共用原子写入服务提交选定文件和明确删除，并保留未选中的编辑；`poketto sync` 按单个文件自己的基线合并，`poketto recover` 核实待处理的保存或移动，不会重放后续编辑。取消、撤权和续租失败会关闭执行权限。worker 缺失、CodeAct 协议不匹配或隔离能力不受支持时，不会降级为普通子进程。

`poketto media import` 存储工作空间内的不可变原件并更新本地逻辑索引；将索引与引用它的文本一起保存，才能持久化这些引用。`poketto media link PATH --asset ID --revision REV` 将已经上传的原件接入该本地索引，不传输原件字节。完整读取会话中的 `poketto media fetch` 使用本地索引或明确选定的历史提交，仅公开读取的会话则使用服务端持有的已批准映射。CLI 路径相对仓库根目录；命令和文件生命周期见 `poketto --help`。[worker 参考文档](../executor-service/README.md)定义限制、权限、冲突处理和配套安装。

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
资源限制或取消会关闭会话，此时长输出只保留预览，并明确报告制品不可用。
[worker 参考文档](../executor-service/README.md#returned-artifacts)定义配额、按字节分页和授权规则。

## 部署

每个通过验证的 `main` 提交都会分别发布 Spring 和前端镜像，两者来自同一源码提交。把 `deploy/` 中的文件和填好的 `.env.example`（命名为 `.env`）放入主机部署目录。私有运行配置需提供域名与 DNS、一次性 owner 初始化凭证、仓库与数据库凭证、独立数据目录和四个固定镜像。运行 `deploy.sh --app-image <应用镜像> --app-revision <提交> --frontend-image <前端镜像>`；后续不带参数运行会重新部署已记录版本。两个应用镜像的 revision 标签必须匹配，PostgreSQL 与 Caddy 必须使用 registry digest。

对于使用自行维护的 Compose 配置的现有实例，[现有安装交付](../notes/implemented/2026-09-08-existing-installation-delivery.md)只更新应用与前端镜像。配置受保护的更新入口，并选择 `POKETTO_DEPLOY_LAYOUT=existing`。`POKETTO_DEPLOY_MODE` 的三种取值都可用：`pull` 由主机使用部署任务自带的包读取令牌，从规范镜像仓库拉取两个摘要；`mirror` 使用配置好的交付镜像站；`transfer` 通过 SSH 传输带校验和的归档，供两个仓库都访问不到的主机使用。Compose 文件、环境配置和依赖服务继续由运维配置维护。

Caddy 负责公开 HTTPS，把 `/api` 与 `/mcp` 转交 Spring，其余路径转交 Next.js，并阻断管理探针。只有容器健康且本地网站与 API 通过证书校验的 HTTPS 请求后才确认部署成功。HTTPS 检查在 `POKETTO_HEALTH_TIMEOUT` 的剩余时间内重试，等待证书和路由就绪；默认时限为 180 秒。主机无法访问 GHCR 时，`deploy/transfer.sh` 传输两个应用镜像；数据库与网关仍要求可访问 Docker Hub，或已缓存其精确 digest。`--pull --sync` 模式在主机拉取应用镜像的同时同步当前部署文件。自动部署仍需通过 production 环境单独启用。先独立安装并验证主机执行服务，再设置 `POKETTO_EXECUTOR_ENABLED=true`；缺少隔离前置条件时部署失败关闭。镜像身份、配置、持久化边界和待完成的真实安装验收见[部署栈记录](../notes/implemented/2026-09-05-blog-stack-delivery.md)。

把 `POKETTO_NETWORK_SUBNET` 设置为未被占用、至少含 16 个地址的 RFC1918 IPv4 CIDR，把 `POKETTO_NETWORK_DYNAMIC_RANGE` 设置为规范且严格包含于主网、至少含八个地址的动态子池。把 `POKETTO_GATEWAY_INTERNAL_IP` 设置为池外的 Caddy 固定地址，排除主网的网络地址、供网桥使用的首个可用地址和广播地址。部署会在启动容器前拒绝无效范围；Docker 只从动态池为其他服务分配地址。只有该部署启用 Tomcat 转发解析，且仅信任网关的 `/32`；Caddy 重建客户端地址、协议和主机头，并在转交 Spring 前移除 `X-Forwarded-Port`。其它入口显式默认为 `server.forward-headers-strategy=none`。`./gradlew proxyForwardingCheck` 需要 Docker 和 Python 3.10+，验证真实 Compose 地址分配、客户端独立登录限流与共享账号限流；`check` 和 CI 必须执行它。

## 诊断

每个请求和每次 MCP 工具调用都会留下一条记录。请求记录写明方法、路由、状态码、耗时、调用方类型与主体，路由指定了空间时还写明空间。工具记录写明工具名、耗时，以及调用方收到的同一个结果码，因此报上来的 `SESSION_REPLACED` 或 `EXECUTION_REFUSED` 可以直接查到，不必反推。被拒绝的请求另外记下告知调用方的状态码与标题。

记录带有一个只存在于服务端的请求标识，用于把同一次请求产生的多条记录串起来，不会返回给调用方：外部无法兑换的标识没有诊断价值，反而会让客户端自行猜测它的用途。核对故障请改用空间、调用方和时间。

不会进入记录的内容：请求体，因为其中带有仓库令牌和密码；MCP 工具参数，因为其中带有命令与正文；查询字符串与仓库文件路径；以及文档正文。管理路由会缩减为稳定形状，空间标识单独成字段，路由中不透明的路径段也会折叠：UUID 变成 `:id`，较长的 URL 安全字符串变成 `:opaque`。图片授权凭证正是走在路径上、拿到就能取该图，因此不会进入记录；公开站点的 slug 按原样保留。容器健康探测完全不记录。

决定权限归属的变更另有一套记录，统一使用 `poketto.audit` 这个日志名，每条写明动作（例如 `member.access.granted`、`key.revoked`）、做出决定的操作者、被操作的对象，以及变更后实际生效的权限。停用或降级记为 `member.access.revoked`，不会写成授予。记录在变更提交之后才写。认证结果也记在这里，因此凭证无效和授权不足可以区分开。登录名、密码、令牌和邀请码都不会出现；拒绝只写本服务自己的固定原因，不写提交上来的值。

随仓库提供的部署默认每条记录输出为一行 JSON，字段可直接寻址，异常堆栈收在记录内部而不是散成许多行。格式由 `POKETTO_LOG_FORMAT` 选择，默认 `ecs`。不经该部署的本地开发默认仍是可读格式。

各服务统一写入宿主机的 journal。容器日志随容器一同消失，而本部署在每个通过验证的提交上都会替换容器，出事前那段记录本来会一起没掉；journal 里也已经有执行服务自己的记录，应用与沙箱因此落在同一条时间线上。journald 的默认上限是文件系统的一个比例而不是选定的大小，所以要明确给出预算：

```sh
sudo mkdir -p /etc/systemd/journald.conf.d
printf '[Journal]
Storage=persistent
SystemMaxUse=1G
MaxRetentionSec=30day
RateLimitIntervalSec=0
'   | sudo tee /etc/systemd/journald.conf.d/poketto.conf
sudo systemctl restart systemd-journald
```

网关只记录到不了应用的那部分请求，访问日志和承载反向代理故障的进程日志都记。查询字符串会从记录中删除，因为仓库路径走在那里；图片地址整条跳过，因为它本身就是取图凭证；凭证类请求头连同 Referer 一并删除，后者带着管理页面自身的路径；下载响应里指明文件名的 Content-Disposition 也一并删除。关闭限流是有意为之：记录被静默丢弃会让阅读者得出"什么都没发生"的结论，比查得慢危险。查看单个服务用 `journalctl CONTAINER_NAME=<容器名> -o cat`，得到的就是记录本身；启用结构化输出后再接 `| jq`。筛安全历史用 `journalctl -o cat | jq 'select(.log.logger=="poketto.audit")'`。

运维自行维护的 Compose 实例不会通过镜像交付收到这些文件。要在那里生效，需要修改该实例自己的 Compose 配置和网关文件：把各服务的日志驱动改为 `journald`、为应用设置 `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`，并加上网关访问日志以及跳过图片地址的规则——那类地址本身就是取图凭证。在此之前服务照常运行、照常记录，只是格式仍是便于阅读的那种，且日志会在重新部署时被丢弃。

尚未覆盖的部分见[诊断记录](../notes/implemented/2026-09-14-service-diagnostics.md)。
