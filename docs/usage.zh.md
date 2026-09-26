# 开发与使用

Poketto 的运行依赖、内容配置、MCP 接入与部署参考。

[项目介绍](../README.zh.md) · [English](usage.md)

## 社区互动

`/community` 的「社区」页提供关注空间的动态、私密收藏、自己的点赞、通知、关注的空间和屏蔽名单。
社区成员、创作者和管理员可通过浏览器会话点赞、收藏、关注、评论及回复。
浏览者可阅读公开讨论、移除自己的记录、标记通知已读、举报评论和管理屏蔽。
空间成员身份和 API key 不赋予这些互动权限。

文章的 frontmatter `id` 是唯一的小写标准 UUID 时，才能接受互动。浏览器新建的草稿会自动带上；
已有文章在编辑器点击「启用文章互动」，再保存并公开。格式错误的 `id` 必须在源码中修正。
同一篇文章改名或移动时保留 ID，复制为另一篇文章时生成新 ID；换用 ID 会开始另一份互动历史，改回原 ID 即可接回原有记录。
没有有效 ID 的文章仍能阅读，其空间仍可被关注。

文章页显示「阅读 N」，即该地址的匿名读者数，至少为 1 时才显示。页面可见五秒后计为一位读者，
同一客户端每篇文章每个 UTC 日只计一次；不统计爬虫和未公开的地址，不保存客户端地址，文章换了地址会重新计数。
详见[公开阅读次数](../notes/implemented/2026-09-24-public-view-counts.md)。

已登录的社区成员可以在公开文章下方点「建议修改」：修改正文，可附上最多 500 字的说明，并选择采纳后是否署名。
以下情况会拒绝提交：页面载入后文章已经改过、建议没有任何改动、提议人与空间所有者之间有屏蔽，
或提议人对这篇文章已有另一条待处理的建议；频率上限为每分钟 5 条、每天 30 条。
持有 `PUBLISH` 权限的成员在工作台的「读者勘误」里以逐行对比审阅建议。
采纳只替换正文，且只在远端文件的正文仍与建议的基础一致时进行；否则建议标记为过期。采纳进行中时，拒绝或撤回会返回冲突；十分钟仍未完成的采纳重新视为待处理。
同意署名的提议人会在文章底部获得致谢，并以账号编号记录在提交的 `Poketto-Suggested-By` 标注中。
空间所有者和提议人都会收到通知，提议人可以撤回待处理的建议，已处理建议的正文在 90 天后清除。
详见[读者勘误](../notes/implemented/2026-09-24-reader-corrections.md)。

评论为纯文本，最多 4,000 个 Unicode 字符，只支持一级回复。删除自己的评论后无法恢复；
有回复的根评论保留为占位，不再接受新回复。空间 owner 和站点管理员可移除评论，移除根评论会一并隐藏其回复。
管理员在工作台的“站务”分区处理举报，此权限不包含私有 Git 内容。举报原因最多 1,000 个字符。

屏蔽后，你将看不到该账号的评论和通知，双方不能相互回复；公开文章不受影响。
新根评论最多通知 100 位空间 owner，回复通知根评论作者。每个收件箱保留最新 1,000 条通知。
收藏和关注是私密的，也不会通知空间 owner。不提供私信或邮件提醒。
文章被撤回、限制展示或移除时，相关公开讨论和卡片会隐藏，记录仍保留。账号降级后不能新增互动，已有评论保留。

每个账号最多保留 1,000 个点赞、1,000 个收藏、100 个关注空间和 500 个屏蔽账号。
每分钟和每个 UTC 日，每个账号最多发 10 条和 300 条评论，点赞、收藏和关注合计 60 次和 3,000 次，
举报 5 次和 30 次，屏蔽 30 次和 500 次。关注动态的每次扫描最多 100,000 篇文章、五秒，同时最多两个；
被拒绝时可稍后重试或减少关注空间。

公开社区读取使用 `/api/public/community/spaces/{slug}` 及其 `/articles/{articleId}` 子路径；
浏览器变更和私密列表使用 `/api/auth/community`，沿用会话、Origin 和 CSRF 校验，请求体最多 64 KiB。
评论请求携带新生成的 `requestId`；结果不确定时用同一请求 ID 重试，不会重复发布。
部分记录被隐藏时，空页仍可能带有下一页游标。
[社区决策](../notes/implemented/2026-09-23-community-interactions.md)定义身份、可见性与事务边界。

## 开发

构建依赖和命令见 [AGENTS.md](../AGENTS.md#commands)。Windows 上使用 `.\gradlew.bat`，用 `$env:...` 设置变量；`check` 会在固定版本的 Linux 容器中运行 Linux 执行服务测试和 `linuxStorageTest`。通过[隔离浏览器入口](../acceptance/README.md)使用合成数据操作真实应用；前端设置见 [frontend/README.md](../frontend/README.md)。

应用需要 PostgreSQL、绝对路径形式的 `POKETTO_DATA_DIR`，以及一个预先建好的私有 HTTPS Git 仓库。运行 `bootRun` 前设置 `SPRING_DATASOURCE_URL`、数据库认证信息、`POKETTO_REPOSITORY_REMOTE_URI`、`POKETTO_REPOSITORY_USERNAME` 与 `POKETTO_REPOSITORY_PASSWORD`。默认工作空间跟随该仓库的 `main`；`<data-dir>/workspaces/<workspace-id>/content` 下的 Git 对象只是可丢弃的缓存。可选设置：

- `POKETTO_REPOSITORY_CACHE_MAX_WORKSPACES`（默认 32）与 `POKETTO_REPOSITORY_TIMEOUT_SECONDS`（默认 30）。
- `POKETTO_REPOSITORY_REFRESH_SECONDS`（默认 30）：所服务内容多久对照远端 `main` 重新校验一次。合法的直接推送在下一次刷新后可见；经 Poketto 的写入立即可见。
- `POKETTO_REPOSITORY_STALE_AFTER_SECONDS`（默认 3600；不超过 3600，也不小于刷新间隔）：所服务内容最多多久没有成功重新校验，readiness 就报告停止服务，公开读取失败关闭。进程继续运行并重试。

`GET /actuator/health` 回应部署检查，`GET /api/public/documents` 列出默认工作空间的公开文档。

```sh
./gradlew test repoCheck
./gradlew check
POKETTO_DATA_DIR=/srv/poketto \
POKETTO_REPOSITORY_REMOTE_URI=https://git.example.com/owner/private-content.git \
POKETTO_REPOSITORY_USERNAME=operator \
POKETTO_REPOSITORY_PASSWORD=... \
./gradlew bootRun
```

先启动一次应用完成数据库结构初始化，再在部署主机的交互式终端运行 `./deploy.sh --initialize-admin`，输入用户名和隐藏的密码。命令只创建一次站点管理员和默认空间主人，不能替换已有账号；不提供网页安装入口。自定义容器安装方式见[管理员安装命令](../notes/implemented/2026-09-11-operator-administrator-setup.md)。将 `POKETTO_SECURITY_ALLOWED_ORIGINS` 配置为浏览器使用的精确 origin；本地 HTTP 还需设置 `POKETTO_SESSION_COOKIE_SECURE=false`。客户端登录前获取 `/api/auth/csrf`，后续请求携带会话 cookie 和响应指定的 CSRF header。

访客可以用验证后的邮箱、密码和昵称注册，也可以在启用后使用 Google 登录。已有账号保留用户名密码登录，可绑定经过验证的邮箱。空间邀请授予其注明的空间权限，不提升策略组。浏览器登录后，重启和部署都不会退出，直到连续 90 天未使用（`poketto.security.account-session-idle-days`）、主动退出、修改密码或账号被停用；从未登录的会话闲置 30 分钟后过期。

设置 `POKETTO_RESEND_API_KEY` 和 `POKETTO_EMAIL_FROM`（发件地址须使用已验证的发信域名），启用邮箱验证和密码找回。六位验证码十分钟有效，最多允许五次错误尝试，仅可使用一次；重发需间隔六十秒。`POKETTO_EMAIL_DAILY_LIMIT`（默认 100）限制每个 UTC 日的发送量，另有邮箱和来源地址限制。密码找回会使该账号的浏览器会话和机器凭证失效，但保留空间成员权限。更换 Resend 密钥会使尚未使用的验证码失效。

设置 `POKETTO_GOOGLE_CLIENT_ID` 和 `POKETTO_GOOGLE_CLIENT_SECRET`，启用 Google 登录。在 Google 创建 Web 应用，将重定向 URI 设为 `<POKETTO_OAUTH_ISSUER>/api/auth/identity/google/callback`，其中 `POKETTO_OAUTH_ISSUER` 须为不带路径的精确 HTTPS origin（本地开发可用环回 HTTP）；仅请求 `openid`、`email` 和 `profile`。同邮箱不自动合并账号：先登录，再显式绑定 Google。不能解除最后一种登录方式。未配置的登录方式不显示入口。

新账号均为**浏览者**。管理员分配一个策略组：浏览者、社区成员、创作者或站点管理员。创作者和管理员可以连接仓库、公开网站。站点管理员可以搜索账号、填写原因调整分组、查看变更记录和拥有的空间，并审阅仓库公开范围内的文章和媒体，但不获得私密访问权。最后一个管理员不能降级。空间的全部所有者都是创作者或管理员时，其网站才公开：任一所有者降级后，该空间的公开直链、发现、搜索、订阅和媒体下线，直到恢复资格；编辑以及成员和 MCP 权限不受影响。

Linux 上，数据目录必须支持文件与目录同步；否则影响发布的写入和图片上传会被拒绝，公开服务关闭。Windows 上公开快照只保存在内存中，离线重启后要等远端重新验证成功，公开读取才会恢复。

## 内容与图片

管理页列出当前账号的空间，选择空间后再编辑。工作台的“账号”分区可以连接已有的 GitHub/CNB 私有仓库、重试中断的创建申请，以及接受空间邀请。仓库令牌需要读取元数据和 Git 写入权限。启用仓库连接前，将 `POKETTO_REPOSITORY_CREDENTIAL_KEY` 配置为 Base64 编码的 32 字节密钥。新空间默认关闭网站。

站点配置 GitHub App（见[配置指南](github-app.md)）后，创作者和管理员在账号设置中授权自己的 GitHub 个人账号，再通过“管理 GitHub 仓库授权”安装 App；“仅选部分仓库”安装需要至少一个已有仓库，例如一个空白私有仓库。确认账号和未占用的仓库名后，为空间创建私有仓库。若 App 缺少该仓库的权限，在安装设置中选中它后继续原申请。建仓结果不确定时，先在申请记录中核对，再发起其他申请；浏览器不会自动重发结果不确定的远程写入。

恢复 GitHub App 连接时，由最初提供授权的空间主人先在账号设置中恢复授权，通过“管理 GitHub 仓库授权”选中仓库，再在空间的“存储位置”分区点击“核对并恢复连接”；仓库改名后填写当前名称。策略组降级后同样可用。转移给其他账号的仓库或重新创建的同名仓库不能接替原仓库。操作等待期间短期核验过期时，重试即可，无须重新授权 GitHub。

将 App 的 Webhook URL 设置为公开 HTTPS 域名下的 `/api/hooks/github`，Webhook 密钥使用 `POKETTO_GITHUB_WEBHOOK_SECRET`，并订阅 Repository 事件。撤销会停止受影响的仓库访问，不删除内容或成员关系；只有主动重连才能恢复。服务恢复后，可在 GitHub 中重新投递失败的通知；已处理过的投递返回 409。

令牌连接的空间主人在“存储位置”中更换 Git 用户名和令牌；服务端先验证，不能借此更改仓库地址。确认更新成功后，到托管平台撤销旧令牌。由部署配置管理的仓库需由运维修改配置。

私有 HTTP 入口统一使用 `/api/admin/workspaces/{workspaceId}`，不会回退到默认空间；`GET /api/auth/workspaces` 列出成员空间，`/mcp` 从凭据解析空间。详见[工作空间路由](../notes/implemented/2026-09-11-workspace-browser-and-mcp-routing.md)。

所有者在成员管理或空间邀请中分别设置私密读取、私密修改和公开内容修改/发布权限。邀请默认仅允许读取公开范围；私密修改必须同时允许私密读取。网站关闭时，成员仍可读取公开范围。收回权限会撤销超出权限的连接；增加权限不会扩大已有连接。详见[成员内容权限](../notes/implemented/2026-09-12-member-content-permissions.md)。

创建空间时，会把 [content-template](../content-template/AGENTS.md) 提交到空仓库：根指引、各带指引的 `private/` 与 `public/`，以及默认禁用的发布策略。仓库已有内容或首次提交未完成时，空间的“存储位置”分区只补齐缺少的模板文件。同一分区还可添加周记与日记、读书笔记、相册和新闻摘编模板，每套在 `private/` 和 `public/` 下各添加一个文件夹，附带告诉 AI 助手这类内容如何命名、组织和发布的指引；模板只新建缺少的文件。详见[空间模板](../notes/implemented/2026-09-24-space-templates.md)。

新内容放入 `private/`。要发布，先把它及所需媒体移入 `public/`，再启用 `.poketto/publishing.yaml`：

```yaml
enabled: true
mode: public-root
exclude:
  - public/drafts/**
```

只有精确根目录 `public/` 下的路径才有资格公开。排除规则使用完整仓库相对路径并优先生效；任意大小写的 `AGENTS.md` 和含隐藏路径段的文件保持私有。策略缺失或禁用时不发布任何内容；策略无效时关闭公开服务。编辑器中的“发布”和“撤回为草稿”在对应的私有和公开路径之间移动已保存的笔记；遇到私有依赖或路径冲突会拒绝，也不会开启已关闭的网站，或绕过排除规则和账号限制。需要一同移动媒体时，请移动所在文件夹。

Markdown 元数据可选，未修改的源码字节保持原样。默认路由省略 `public/` 和 `.md`；frontmatter 中的显式路由不能赋予公开权限。文件夹的 `index.md`（没有有效 index 时为 `README.md`）是目录入口，使用所属文件夹的路由（`public/index.md` 对应 `/`）。入口以缩略图图库展示文件夹自身的图片；没有缩略图的图片（例如超过 400 万像素的 WebP）仍可打开原图。详见[相册缩略图](../notes/implemented/2026-09-14-album-thumbnails.md)。

要定时发布一篇可公开的文章，在 frontmatter 中把 `publish_at` 设为 `YYYY-MM-DD`（按 UTC 零点）或带时区的时间，如 `2026-10-01T09:00:00+08:00`。到点之前，文章不会出现在任何匿名可见的地方，包括搜索、订阅、封面和社区；到点后自动出现，不需要新的提交。未填写 `created_at` 或 `date` 时，文章日期取 `publish_at`。无法解析的值会报告 `INVALID_MARKDOWN`，文章保持不公开。空间成员会提前看到这个文件，编辑器显示「定时发布」。详见[定时发布](../notes/implemented/2026-09-24-scheduled-publishing.md)。

空间所有者可以在网站设置里开启「公开修订历史」。开启后，文章底部链接「修订历史」，即 `/s/{slug}/history/{route}`，按从早到晚列出这篇文章公开过的正文，可任选两个版本比较。历史只回溯到文章在同一路径、同一地址下持续可公开的范围；提交号、提交说明、身份信息和 frontmatter 都不会显示。一次读取最多扫描 256 个提交、50 个版本和 2 MiB 正文，限时两秒；超出时页面会说明更早的版本没有列出。详见[公开修订历史](../notes/implemented/2026-09-24-public-revision-history.md)。

公开页面、卡片和搜索结果优先显示 frontmatter 中的 `public_author`，其次是空间公开署名，再次是空间名称。`public_author` 和空间署名都是单行文字，最多 120 个 Unicode 字符。在工作台「网站」中，登录的空间 owner 可以设置署名、修改空间名称（1–120 个字符，`PUT …/publication/name`），以及填写最多 280 个字符、显示在空间网站上的简介（`PUT …/publication/description`）；两者都提交 `{ "text": … }`。原有的 `author` 等其他元数据、账户资料和 Git 提交身份保持私有。

文章页按每分钟约 400 个中日韩字符或 200 个其他单词估算阅读时长；最浅两级标题（h1 到 h3）至少有三个时，列为页内链接。注明语言的代码块（如 ```` ```ts ````）有语法高亮，不会猜测语言。数学公式只认双美元符号：行内写 `$$x^2$$`，独占一行的 `$$` 或 ```` ```math ```` 代码块为独立公式；单个 `$` 保持为普通文字。```` ```mermaid ```` 代码块绘制为图表。编辑器预览使用相同规则。详见[阅读辅助](../notes/implemented/2026-09-24-reading-aids.md)。

搜索按标题及解析后的阅读文本作字面匹配，包括链接文字、图片描述、代码、表格单元格和被引用的脚注，不包括链接目标地址和原始 HTML。站点 `/search` 搜索所有已开启的网站，`/s/{slug}/search` 只搜索一个空间。任一快照不可用或超出上限（256 个空间、100,000 篇文档、五秒）时，站点搜索整体失败，不返回部分结果；可稍后重试或只搜索一个空间。详见[站点搜索](../notes/implemented/2026-09-14-public-site-search.md)。

“新建笔记”和“新建文件夹”在 `private/` 下准备草稿，保留所选分类；“保存”后才写入，文件夹写为其 `index.md`。具有私有读取权限的成员可以打开“历史版本”，查看该路径的变化，不追踪改名。“恢复到编辑框”需要写入权限，只替换编辑框中的正文；随后“保存”生成新提交，并仍会拒绝覆盖并发修改。已删除、二进制、超出大小限制和托管媒体版本不能恢复。“搜索文件名”按仓库相对路径作字面匹配，仅公开读取的成员只能找到符合发布策略的路径。一次扫描若超过 100,000 个 Git 树条目与已索引媒体条目之和，会直接拒绝，而不是返回部分结果。详见[内容导航](../notes/implemented/2026-09-14-admin-content-navigation.md)、[历史恢复记录](../notes/implemented/2026-09-23-browser-history-and-restoration.md)和[文件名搜索](../notes/implemented/2026-09-14-administration-filename-search.md)。

未保存正文会作为明文恢复数据保留在当前浏览器，按账号、空间、路径和标签页隔离：不跨设备同步，清除浏览器数据会丢失，能访问该浏览器配置的人可能读取。“本机草稿”列出可恢复的记录；恢复不会自动保存，保存时仍检查原版本。请以已保留提示为准再依赖恢复。此浏览器中的账号和空间共用 20 份草稿、总计 2 MiB 的上限，每份最多 1 MiB，不会静默删除草稿；容量已满时，可切换到对应账号或空间清理，或清除本站数据（会删除全部本机草稿）。保存成功、明确放弃和退出登录会清理对应草稿。

在正文编辑框中粘贴或拖入一张最多 16 MiB 的 PNG、JPEG、WebP 或 GIF 图片即可上传并插入引用；这需要私密写入权限，本身不会保存或发布。没有私密读取权限的成员通过“选择公开图片”插入已有的公开图片。移动目录会带上其中的索引媒体，并在同一次提交中修复 Markdown 引用；单独移动文档不会带走共享依赖。

托管原图保存在 `<data-dir>/managed-originals` 并持续保留；`<data-dir>/derived/repository-images` 可以删除重建。公开图片地址最长五分钟有效，网站关闭或快照变化后失效；刷新页面可取得当前地址。详见[网站交付边界](../notes/implemented/2026-09-14-workspace-public-delivery.md)和[创作基础记录](../notes/implemented/2026-09-05-repository-authoring-foundations.md)。

`POST /api/admin/workspaces/{workspaceId}/media` 接收最多 128 MiB 的原始字节，要求 `Idempotency-Key`，可选 `X-Media-Type`；`poketto.assets.max-file-bytes` 可调低上限，不影响既有原件。`/api/admin/workspaces/{workspaceId}/assets` 图片上传同样要求 `Idempotency-Key`，最多 16 MiB。上传不会写入媒体索引或发布内容。[逻辑媒体索引](../notes/implemented/2026-09-09-logical-media-index.md)和[索引媒体交付](../notes/implemented/2026-09-09-indexed-media-delivery.md)说明索引媒体如何列出、随文本保存和交付。

指向索引中 MP3、WAV、MP4 或 WebM 原件的相对链接会显示原生播放器，不自动播放、不预加载，旁边保留下载链接。导入时使用 `audio/mpeg`、`audio/wav`（或 `audio/wave`、`audio/x-wav`）、`audio/mp4`、`video/mp4`、`audio/webm` 或 `video/webm`。具体编码取决于浏览器支持；访问权限结束后，浏览器已经缓冲的字节无法召回。详见[播放限制与决策](../notes/implemented/2026-09-23-controlled-media-playback.md)。

登录的空间 owner 可在“网站”分区开关网站，或携带会话 CSRF token 调用 `PUT /api/auth/workspaces/{workspaceId}/publication`，提交 `{ "enabled": true }` 或 `{ "enabled": false }`；回包不确定时，先用 `GET` 重新读取状态再重试。关闭网站不影响成员访问仓库。已开启的网站以 `/s/{slug}` 为入口，提供各自的搜索、标签、归档和 `/read/...` 页面，并在 `/s/{slug}/rss.xml` 提供最近 30 篇记录的 RSS；`/api/public/spaces/{slug}` 接口限定在该空间，未知或关闭的空间返回 404。刚开启的网站可能要等后台刷新轮到它后才能访问。

`/sitemap.xml` 为站点首页和所有已开启的空间提供索引；快照不可用时返回 503，不交付不完整列表。`/robots.txt` 使用 `POKETTO_PUBLIC_URL`，并建议爬虫避开 `/admin`、`/api/` 和首页批次地址。每篇公开文章有固定的封面地址 `/s/{slug}/cover/{route}`，供分享卡片使用：返回正文第一张公开图片的缩略图；没有封面时跳转到网站的 `/share.png`；文章不再公开后返回 404。

站点首页从已开启的空间抽取内容组成浏览批次，最长 30 分钟内保持顺序；点击“换一批”重新抽取，过期链接会提供新批次。每批最多从 32 个空间各取四张卡片，并非完整目录。每个空间的卡片优先考虑作者标注 `featured: true` 的文章、较新文章和不同标签，其余随机；不使用私密阅读历史或关注名单。“按标签发现”按完整标签精确筛选，区分大小写，最多 64 字符。文件夹入口中的文章链接按作者编排顺序组成合集，可用上下篇链接阅读。详见[发现批次](../notes/implemented/2026-09-14-public-discovery-batches.md)、[创作与发现](../notes/implemented/2026-09-23-authoring-and-discovery-experience.md)和[合集阅读](../notes/implemented/2026-09-14-collection-reading.md)。

## 收集入口

每次收集会在 `private/inbox/` 下直接新建一篇私有笔记，内容来自一个链接、一段选中的文字、一句备注和一张可选的图片，按 UTC 命名为 `YYYY-MM-DD-HHmm-<标题>.md`，重名时依次加 `-2`、`-3`……frontmatter 包含新的 `id`、`title`、`source` 和 `saved`；选中的文字写成引用块，图片作为托管原件保存并在笔记中引用。收集不会读回、修改或公开任何已有内容。每个账号每分钟最多收集 30 次，每个 UTC 日最多 500 次，所有密钥和浏览器共用这个额度。

- **手机**：只带 `CAPTURE` 能力的密钥以 `Authorization: Bearer <密钥>` 调用 `POST /api/capture`，请求体可以是 JSON（`title`、`url`、`text`、`note`），也可以是表单，表单还可以附加最大 16 MiB 的文件字段 `image`。笔记写入密钥所属的空间，成功返回 `201` 和 `{ "path", "commit" }`。空间的「AI 助手」分区为 owner 生成这种密钥，并列出 iOS 快捷指令的设置步骤。OAuth 连接令牌不能用于这个接口，它们只用于 `/mcp`。
- **浏览器**：同一分区提供书签代码，点击后以弹出窗口打开 `/capture`，带上当前页面的标题、网址和选中的文字。弹出窗口使用已登录的会话，通过 `POST /api/admin/workspaces/{id}/capture` 保存；只打开窗口不会写入。

只有拥有私有写入权限的持有人才能获得 `CAPTURE`，私有写入本身也包含收集。`CAPTURE` 不能读取、覆盖、移动、删除或公开任何内容，也不能在收件箱以外新建文件。详见[收集入口](../notes/implemented/2026-09-24-capture-inbox.md)。

## 导出 HTTP 接口

在编辑器中，点击文件或文件夹旁的“导出”，或从文件侧栏导出整个工作空间，选择私人副本或公开副本后下载 ZIP。导出使用最新已保存的内容，不包含未保存的修改。公开副本遇到私密内容会拒绝导出，不会顺带发布。私人副本要求私密读取权限，并保留原文 frontmatter。导出包不含 Git 历史、运行指导或内部媒体索引，相对链接指向 ZIP 内的媒体。

在原生 Linux 上，`POST /api/admin/workspaces/{workspaceId}/exports` 接收 `paths` 和必填布尔值 `publicOnly`，返回临时句柄、ZIP 大小、SHA-256 和到期时间；`GET …/exports/{handle}` 下载，追加 `/metadata` 读取回执，`POST …/exports/{handle}/release` 提前释放。句柄只对所属身份和工作空间有效，到期即失效。

服务在 `<data-dir>/portable-exports/<workspace-id>` 暂存导出包。同时最多构建一个包、下载两个包（同一工作空间最多一个），每个包最多 512 MiB 原件、256 MiB 文本和 10,000 个条目。`poketto.exports` 下可配置 `max-zip-bytes`（800 MiB）、`max-retained-bytes`（2 GiB）、`max-workspace-bytes`（1600 MiB）、`max-packages`（8）、`lifetime-seconds`（600）与 `build-seconds`（120）。容量不足返回 429；缺失、过期或属于其他身份的句柄返回 404；不支持 POSIX 权限的文件系统返回 503。详见[导出决策](../notes/implemented/2026-09-10-portable-content-exports.md)。

在 CodeAct 会话中使用 `poketto export PATH... --output FILE [--public]`；`.` 表示可见工作空间，仅公开读取的会话始终导出公开投影。ZIP 包含已保存的内容，不包含本地编辑，也不会覆盖已有的不同文件。结果符合 artifact 限额时，可执行 `poketto artifact create FILE --type application/zip`，再通过 `get_artifact` 取回。`MATERIALIZE_CAPACITY` 会保留会话和已有文件：清理本地空间、缩小选择范围，或改用浏览器导出。

## MCP 与隔离执行

`/mcp` 通过 Streamable HTTP 提供 MCP，以工作空间 Bearer 凭据（API key 或 OAuth 访问令牌）认证，独立于浏览器会话。工具目录始终包含 `get_asset` 和 `put_asset`；启用执行器后还包含 `repo_exec`、`repo_discard` 和 `get_artifact`。没有独立的文件工具：通过 `repo_exec` 查看目录、搜索、读取和编辑文件，用 `poketto` CLI 持久化修改，并按需逐层读取仓库的 `AGENTS.md`。详见 [CodeAct 入口记录](../notes/implemented/2026-09-10-codeact-mcp-entrance.md)。

`repo_exec` 要求显式分配 `EXECUTE_REPOSITORY`，并设置 `POKETTO_EXECUTOR_ENABLED=true`。在 Linux 应用上配置 `POKETTO_EXECUTOR_SOCKET`、`POKETTO_EXECUTOR_SIGNING_KEY` 与 `POKETTO_EXECUTOR_STAGING_DIRECTORY`，再按 [worker 参考文档](../executor-service/README.md)安装并验证 root supervisor 和低权限 SRT 账号。应用默认接纳四个会话（`POKETTO_EXECUTOR_MAX_SESSIONS`）、最多 128 MiB bundle，这些值不能超过 worker 的限制。worker 缺失或隔离能力不受支持时，不会降级为普通子进程。

请求体上限为 128 KiB（超出时在工具执行前返回 413），命令上限 16,384 字符，编码后的桥接帧上限 512 KiB。拒绝结果包含 `code` 和 `reason`。详见 [MCP 请求准入移除](../notes/implemented/2026-09-15-mcp-request-admission-removal.md)。

`repo_exec` 必须携带 `expectedCopyId`：使用 `"new"` 打开账号的默认副本，仅在不存在时创建；后续调用传回结果中的 `copyId`。重连会自动接回副本，关闭连接会保留副本。每次成功的操作都会把闲置期限延长为七天（`retention.expiresAt`）；到期或丢弃会删除本地工作。同一账号的客户端在同一空间和读取范围内共享副本。`SESSION_REPLACED` 和 `EXECUTION_REFUSED` 表示命令未执行；`EXECUTION_UNCONFIRMED` 表示命令可能已部分完成，包括远端写入：不要重复执行，先用只读命令、`retention.lastInterruptedCommand` 和 `poketto status` 检查同一副本，远端保存待确认时再使用 `poketto recover`。详见[副本身份契约](../executor-service/README.md#working-copy-identity)。

同一运行租约中的命令共享工作目录、环境变量、shell 函数、别名、私有 `/tmp` 和后台进程。超时、输出超限、shell 退出、空闲清理或租约替换会清除这些运行状态，但保留副本文件和已完成的部分工作；下一条命令会报告 `freshSandbox: true`。空闲清理由 worker 的 `idleUnitSeconds` 决定（1 到 86400，示例配置为 1800）。后台进程随沙箱停止，命令结束后不能再执行 `poketto` 操作。

完整读取的会话包含授权范围内的当前文件和 Git 历史。仅公开读取的会话获得当前公开投影，不含历史或私密元数据，返回 `PUBLIC_PROJECTION` 和合成提交 ID，且不能保存。

`poketto edit PATH --old TEXT --new TEXT` 替换已有本地文本文件中唯一、完全匹配的一段原文，原文不存在或匹配多处时拒绝。`poketto create PATH --text TEXT` 仅在路径不存在时新建文件。长文本可用 `create` 的 `--stdin` 或 `--text-file FILE`，以及 `edit` 的 `--old-file FILE`（代替 `--old`）和 `--new-stdin` 或 `--new-file FILE`（代替 `--new`）。输入为 UTF-8，保留末尾换行；输入文件路径按当前 shell 目录解析，目标路径仍相对于仓库根。这些选项不提高命令或桥接帧上限。两个命令写入前都会再次核对本地内容，普通 shell 写入没有这项检查；在 `poketto save` 之前都不改变远端。

```sh
poketto create private/article.md --stdin <<'MARKDOWN'
# 文章

引号、`$variables` 和反引号都会保留为普通 Markdown。
MARKDOWN
```

`poketto save` 提交选定文件和明确删除，其他编辑留在本地，并逐项检查所选文件与远端的冲突。完整读取副本的保存或移动得到确认后，还会更新本地 Git HEAD；`repo_exec.commit` 与 `poketto status.gitCommit` 报告这个基线。状态显示 `localBaselinePending` 时，使用 `poketto recover`，不要重复保存。

完整读取副本的 `poketto status` 会把远端 main 与最近一次确认的基线比较：`remote.state` 为 `MATCHES_BASE`、`DIFFERS_FROM_BASE` 或 `UNAVAILABLE`。状态查询不修改文件。`poketto sync` 将整个工作空间与同一个远端版本合并，保留仅存在于本地的文件及冲突的二进制内容，重叠的文本修改写入 LOCAL/BASE/REMOTE 冲突标记；它不会保存远端。中断后，状态显示 `syncPending`；`poketto recover` 继续执行，`poketto recover --skip-local` 解除待处理状态，但不撤销已安装的文件。详见[工作空间同步](../notes/implemented/2026-09-15-workspace-synchronization.md)。

`poketto move SOURCE DESTINATION` 移动已保存的文件、目录和索引媒体，并在同一次远端提交中修复 Markdown 引用；选定文件有未保存修改或目标已占用时拒绝。随后本地安装待完成时，先使用 `poketto recover`，再继续保存、移动或同步。本地修改导致无法安装时，`poketto recover --skip-local` 确认远端移动且不改动本地文件；保存相关路径前先运行 `poketto sync`。

`repo_discard` 传入准确的 `expectedCopyId`，删除副本及其未保存的工作。此操作要求执行权限和副本归属；忙碌副本会被拒绝。`DISCARDED` 或 `ABSENT` 确认副本已不存在，随后可用 `new` 创建新副本。未确认的丢弃只能用同一 ID 重试。丢弃不会撤销远端提交。

`poketto media import` 存储不可变原件并更新本地媒体索引；将索引与引用它的文本一起保存，引用才会持久化。`poketto media link PATH --asset ID --revision REV` 接入已上传的原件。`poketto media fetch` 和 `poketto media list` 读取本地索引，完整读取会话也可用 `--commit` 指定历史提交；公开会话只能看到已批准的媒体。`media list` 使用返回的 `nextOffset` 和 `indexVersion` 翻页，期间保持 `--prefix` 与 `--commit` 不变。原件读取繁忙时，历史列表可能返回 `MEDIA_UNAVAILABLE`。命令见 `poketto --help`，限制与冲突处理见 [worker 参考文档](../executor-service/README.md)。

`poketto artifact create FILE --type MIME` 保留不可变的临时结果，同一账号、同一空间和读取范围内的任何 MCP 会话都可通过 `get_artifact` 读取，以位图或文本、二进制分页返回；长命令输出也会附带制品句柄。句柄在五分钟后或租约关闭时失效，例如该账号以另一凭证接管副本时。详见[返回制品](../executor-service/README.md#returned-artifacts)。

`put_asset` 导入图片并返回 `assetId`、`revision`、`reference`、`mediaType` 和 `size`；用 `poketto media link` 接入后，保存文章和 `.poketto/assets.json`。仅上传不会保存或发布。每次调用携带 `operationKey`（相同重试沿用），并提供一种来源：

- `url`：使用 443 端口的公网 HTTPS 图片地址，最多三次重定向、30 秒、16 MiB，不转发 Cookie 或授权头。
- `file`：包含 `download_url` 和 `file_id` 的平台文件对象。工具声明 `_meta["openai/fileParams"] = ["file"]`，是否转交取决于客户端。
- `mode: "upload"`，不提供来源，适用于客户端持有文件时。返回值包含 `uploadUrl`、`method: "PUT"`、`contentType: "application/octet-stream"`、`maxBytes` 和 `expiresAt`；在客户端自己的执行环境中上传原始字节：

```python
import requests
with open(image_path, "rb") as image:
    response = requests.put(upload_url, data=image,
                            headers={"Content-Type": "application/octet-stream"}, timeout=30)
response.raise_for_status()
receipt = response.json()
```

上传 URL 是绑定调用者、空间和操作键的秘密凭据，基址来自 `poketto.oauth.issuer`。它在 15 分钟后或应用重启时失效；每个账号最多持有 64 个凭据，其中未完成的最多 8 个。服务器接收请求体最多 30 秒，请设置客户端超时。回执丢失时可 GET 同一 URL 查询（尚未完成返回 `UPLOAD_PENDING`）。过期后使用同一操作键重新申请，再上传相同字节；不同字节会冲突。`TRANSFER_BUSY` 可用同一凭据重试；`IMAGE_MEMORY_BUSY` 表示图片内存预算已占满。详见 [MCP 图片传输](../notes/implemented/2026-09-15-mcp-image-transfers.md)。

## 部署

每个通过验证的 `main` 提交都会分别发布 Spring 和前端镜像，两者来自同一源码提交。把 `deploy/` 中的文件和填好的 `.env.example`（命名为 `.env`）放入主机部署目录，提供域名、仓库与数据库凭证、独立数据目录和四个固定镜像。运行 `deploy.sh --app-image <应用镜像> --app-revision <提交> --frontend-image <前端镜像>`；后续不带参数运行会重新部署已记录版本。两个应用镜像的 revision 标签必须匹配，PostgreSQL 与 Caddy 必须使用 registry digest。随后用 `./deploy.sh --initialize-admin` 创建首个管理员。

对于自行维护 Compose 配置的现有实例，[现有安装交付](../notes/implemented/2026-09-08-existing-installation-delivery.md)只更新应用与前端镜像，以及显式提供的身份配置。安装当前版本的受保护更新入口，并设置 `POKETTO_DEPLOY_LAYOUT=existing`。`POKETTO_DEPLOY_MODE` 可选 `pull`（主机使用部署任务的包读取令牌，从规范镜像仓库拉取两个摘要）、`mirror`（使用配置好的交付镜像站）或 `transfer`（通过 SSH 传输带校验和的归档，供两个仓库都访问不到的主机使用）。

`transfer.sh --existing --set-stdin` 接受按行分隔的 `KEY=value`，仅限 `POKETTO_RESEND_API_KEY`、`POKETTO_EMAIL_FROM`、`POKETTO_EMAIL_DAILY_LIMIT`、`POKETTO_GOOGLE_CLIENT_ID`、`POKETTO_GOOGLE_CLIENT_SECRET`、`POKETTO_SUPPORT_EMAIL` 和 [GitHub App 配置指南](github-app.md)中的五项设置，只通过标准输入传给受保护更新器。值按字面传递，包括 `$` 和引号。权限为 0600 的 `.deployment/images.json` 覆盖文件保留未提供的设置，显式空值清除设置；手动运行 Compose 时，将该覆盖文件放在最后。手动部署使用主机上的身份配置。启用 CI 部署前，先将 Resend 密钥、Google 凭证和 GitHub App 设置配置为 GitHub secrets，将发件地址、每日限额和联系邮箱配置为 GitHub variables。此后两种布局的这些配置均以 GitHub 为准：未设置或已删除的配置会清除主机上的值，未设置每日限额时恢复为 100。Google 两个字段须一起清空。中断后使用相同镜像和配置重试。

将 `POKETTO_SUPPORT_EMAIL` 设置为 `/privacy` 和 `/terms` 页面展示的公开联系方式，并按实际部署的数据处理方式核对页面说明。Google 品牌配置可使用站点首页、`/privacy` 和 `/terms` 地址。

Caddy 负责公开 HTTPS，把 `/api`、`/mcp` 以及 `/.well-known/` 下的 OAuth 发现路径转交 Spring，其余路径转交 Next.js，并阻断 `/actuator`。容器健康，且本地网站与 API 在 `POKETTO_HEALTH_TIMEOUT`（默认 180 秒）内通过证书校验的 HTTPS 请求后，部署才算成功。主机无法访问 GHCR 时，`deploy/transfer.sh` 传输两个应用镜像；数据库与网关仍要求可访问 Docker Hub 或已缓存其精确 digest，`--pull --sync` 在主机拉取镜像的同时同步部署文件。自动部署需通过 production 环境单独启用。先安装并测试主机执行服务，再设置 `POKETTO_EXECUTOR_ENABLED=true`。详见[部署栈记录](../notes/implemented/2026-09-05-blog-stack-delivery.md)。

把 `POKETTO_NETWORK_SUBNET` 设置为未被占用、至少含 16 个地址的 RFC1918 IPv4 CIDR，把 `POKETTO_NETWORK_DYNAMIC_RANGE` 设置为规范且严格包含于主网、至少含八个地址的动态子池，把 `POKETTO_GATEWAY_INTERNAL_IP` 设置为池外的 Caddy 固定地址，排除主网的网络地址、首个可用地址和广播地址。只有该部署信任转发头，且只信任这个网关；其他入口使用 `server.forward-headers-strategy=none`。

## 诊断

每个请求和每次 MCP 工具调用都会留下一条记录：请求记录写明方法、路由、状态码、耗时、调用方类型与主体以及空间；工具记录写明工具名、耗时和调用方收到的结果码。记录中的请求标识不会返回给调用方，核对故障请按空间、调用方和时间查找。记录中不会出现请求体、工具参数、查询字符串、仓库文件路径、文档正文或图片授权凭证；容器健康探测不记录。

权限变更和认证结果记录在 `poketto.audit` 日志名下，写明动作（例如 `member.access.granted`、`key.revoked`）、操作者、对象和变更后生效的权限。登录名、密码、令牌和邀请码都不会出现。

随仓库提供的部署每条记录输出为一行 JSON；格式由 `POKETTO_LOG_FORMAT` 选择，默认 `ecs`。各服务统一写入宿主机的 journal，容器替换后记录仍在。请为 journald 明确设定预算：

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

网关记录到不了应用的请求，不含查询字符串、图片地址、凭证类请求头、Referer 和下载文件名。查看单个服务用 `journalctl CONTAINER_NAME=<容器名> -o cat`（结构化输出可接 `| jq`），筛安全历史用 `journalctl -o cat | jq 'select(.log.logger=="poketto.audit")'`。

运维自行维护的 Compose 实例不会通过镜像交付收到这些设置：需把各服务的日志驱动改为 `journald`、为应用设置 `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`，并加上网关访问日志及跳过图片地址的规则。在此之前，日志仍是可读文本，且会在重新部署时被丢弃。详见[诊断记录](../notes/implemented/2026-09-14-service-diagnostics.md)。

## OAuth 连接

设置 `POKETTO_OAUTH_ISSUER=https://your-domain.example`（末尾不带斜杠）启用 OAuth，并在 `POKETTO_SECURITY_ALLOWED_ORIGINS` 中使用同一 origin。未设置 issuer 时 OAuth 保持关闭，静态 API key 仍可使用。网关必须把 OAuth 发现地址转交 Spring；现有布局的部署需自行更新网关和环境配置。

连接 MCP 时，在客户端填写 `https://your-domain.example/mcp`，选择 OAuth，客户端 ID 和密钥留空。浏览器类客户端注册 HTTPS 回调；命令行客户端注册环回回调，例如 `http://127.0.0.1:<端口>/…`，端口不限；其余主机一律要求 HTTPS，且逐字比对。在授权页登录，选择已加入的空间，核对返回地址，选择权限后批准。只能委托自己持有的权限；私密读取、私密修改和公开发布默认均不勾选。

通过隔离命令保存需要完整源码读取权限，以及受影响内容对应的写权限：私密读取加公开发布可以保存公开文件，无需私密修改；没有私密读取权限时，执行环境中的投影为只读。成员可断开自己的连接，所有者可断开空间内任何连接。“保持连接”允许轮换 refresh token；“已连接应用”列出每个连接的权限和到期时间，断开时撤销其令牌和执行会话。授权最长九十天。不支持 OAuth 的客户端可在 `Authorization: Bearer <key>` 中发送静态密钥。

支持公共客户端动态注册与 S256 PKCE；不声明机密客户端密钥和 CIMD。协议上限与互通性见 [OAuth 决策](../notes/implemented/2026-09-11-mcp-oauth.md)。
