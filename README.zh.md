# Poketto

以仓库为核心的个人知识服务，公开面是博客。已接受的目标让每个工作空间在远程 Git 中保存 Markdown、仓库自管图片与历史，把经 Poketto 上传的图片存入 ManagedBlobStore；两类图片共用安全渲染，但永不相互同步。同一份内容既支撑公开发布，也通过 MCP 作为受信 AI 的长期记忆。Poketto 首先面向单台云服务器，同时保留由配置选择外部基础设施的[可选 serverless 部署 profile](notes/proposed/2026-09-01-optional-serverless-deployment-profile.md)。

[English](README.md)

## 状态

开发中。[仓库创作基础](notes/implemented/2026-09-05-repository-authoring-foundations.md)已经实现任意目录 Markdown 发现、文件级诊断、有界公开与私有搜索、发布策略、原子文本补丁、浏览器登录、邀请、作用域密钥及可靠的本地图片存储端口。远端 `main` 仍是权威，本地 Git 缓存可丢弃。公开请求读取经过验证的快照，不访问远端。

源码包含 Next.js 博客与后台、精确版本图片交付及四个 MCP 工具；`repo_exec` 仅通过独立的 [Linux 执行服务](executor-service/README.md)启用。[隔离验收栈](acceptance/README.md)与[真实 MCP 客户端流程](acceptance/clients/README.md)提供可复现入口；最终浏览器、数据库集成、两个客户端及 HTTPS 安装验收仍未完成。[第一阶段提案](notes/proposed/2026-09-05-phase-one-daily-use.md)与相关完整提案，在满足全部标准之前仍保留 proposed 状态。[持续交付](notes/implemented/2026-09-03-continuous-delivery.md)将通过验证的 `main` 提交发布到 GHCR，自动部署另行启用。C 端供应、备份、访客问答与 serverless 不属于本次交付。[需求文档](notes/implemented/2026-08-25-requirements-and-architecture.zh.md)区分当前行为、历史选型和提案。

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

使用 Java 26 和仓库内的 Gradle Wrapper。数据库集成测试与完整校验需要 Docker；较快的单元测试和仓库校验不需要。

应用启动需要 PostgreSQL 数据源、绝对路径形式的 `POKETTO_DATA_DIR`，以及一个预先建好的私有 HTTPS Git 仓库。运行 `bootRun` 前设置 `SPRING_DATASOURCE_URL`、数据库认证信息、`POKETTO_REPOSITORY_REMOTE_URI`、`POKETTO_REPOSITORY_USERNAME` 与 `POKETTO_REPOSITORY_PASSWORD`。Flyway 会创建默认工作空间；应用将它绑定到远端 `main`，只在 `<data-dir>/workspaces/<workspace-id>/content` 物化一次性缓存。`POKETTO_REPOSITORY_CACHE_MAX_WORKSPACES` 与 `POKETTO_REPOSITORY_TIMEOUT_SECONDS` 可以调整默认值为 32 个工作空间和 30 秒的限制；`POKETTO_REPOSITORY_REFRESH_SECONDS` 决定所服务内容多久对照远端 `main` 重新校验一次（默认 30 秒），`POKETTO_REPOSITORY_STALE_AFTER_SECONDS` 决定所服务内容最多多久没有成功重新校验，健康检查就会把它报告为停止服务（默认 3600 秒）。内容不可用时，进程和刷新循环继续运行，但就绪检查报告停止服务，公开读取失败关闭。快照过期也会停止公开读取；最长有效期为一小时。运行中的实例通过 `GET /actuator/health` 回应部署检查，并在 `GET /api/public/documents` 提供默认工作空间的公开文档；经 Poketto 的写入立即可见，合法的直接推送在下一次刷新后可见。

```sh
./gradlew test repoCheck
./gradlew check
POKETTO_DATA_DIR=/srv/poketto \
POKETTO_REPOSITORY_REMOTE_URI=https://git.example.com/owner/private-content.git \
POKETTO_REPOSITORY_USERNAME=operator \
POKETTO_REPOSITORY_PASSWORD=... \
./gradlew bootRun
```

Windows 下，`check` 还会通过 `linuxStorageTest` 在固定版本的 Linux 容器及临时原生磁盘卷中验证存储；权威存储要求支持目录同步。用 `$env:...` 设置同名变量，确保 `POKETTO_DATA_DIR` 是绝对路径，再使用 `.\gradlew.bat`。命令表与协作规则见 [AGENTS.md](AGENTS.md#commands)。

要发布内容，在内容仓创建 `.poketto/publishing.yaml`：

```yaml
enabled: true
mode: public-by-default
exclude:
  - drafts/**
```

策略缺失或禁用时不公开任何内容；无效策略会关闭公开内容服务。根目录 `private/` 和配置的排除路径始终私有。Markdown 元数据可选，未修改的原文字节保持不变。公开详情入口为 `GET /api/public/document?route=...`；列表、搜索与标签分页同时返回快照信息。

设置私有的 `POKETTO_AUTH_INITIALIZATION_TOKEN` 以一次性创建 owner。浏览器在初始化或登录前读取 `/api/auth/csrf`，登录或退出后重新获取令牌。通过 `POKETTO_SECURITY_ALLOWED_ORIGINS` 配置精确的站点 origin。会话默认使用安全 Cookie；本地 HTTP 开发需显式设置 `POKETTO_SESSION_COOKIE_SECURE=false`。`/api/admin/repository` 下的认证入口提供文件树、文件读取、搜索和原子补丁。补丁携带基准提交，以及每个路径的 revision 或明确的不存在前置条件；冲突或结果不明确时必须重新读取后再决定是否重试。
## 部署

每个通过校验的 `main` 提交都会发布 `ghcr.io/core607/poketto`，标签为 `sha-<commit>`。在主机上把 `deploy/compose.yaml`、`deploy/deploy.sh` 和填好的 `deploy/.env.example` 副本放进同一个部署目录，然后运行 `deploy.sh --app-image <镜像> --app-revision <提交>`；之后不带参数运行 `deploy.sh` 会按记录的固定版本重新部署。脚本会校验配置、版本固定、目录与磁盘空间，核对镜像的 revision 标签，只有 `/actuator/health` 回答 `UP` 才算成功。走这条路主机得能拉到镜像：私有的 GHCR 包需要先用只读 token 在主机上 `docker login ghcr.io`，或者把包设为公开。主机完全连不上 GHCR 时，`deploy/transfer.sh` 把镜像经 SSH 传过去并调用同一个入口。GitHub Actions 的自动部署默认关闭，直到配置了仓库变量 `POKETTO_DEPLOY_ENABLED` 和 `production` 环境；详见[持续交付笔记](notes/implemented/2026-09-03-continuous-delivery.md)。

## 授权

代码与项目文档：[Apache-2.0](LICENSE)。
美术素材与站点发布的创作内容：[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans)。
