# Poketto

以仓库为核心的个人知识服务，公开面是博客。已接受的目标让每个工作空间在远程 Git 中保存 Markdown、仓库自管图片与历史，把经 Poketto 上传的图片存入 ManagedBlobStore；两类图片共用安全渲染，但永不相互同步。同一份内容既支撑公开发布，也通过 MCP 作为受信 AI 的长期记忆。Poketto 首先面向单台云服务器，同时保留由配置选择外部基础设施的[可选 serverless 部署 profile](notes/proposed/2026-09-01-optional-serverless-deployment-profile.md)。

[English](README.md)

## 状态

开发中。可执行开发基线、工作空间隔离、内容仓基础、文档写入与[远程 Git 仓库权威](notes/implemented/2026-09-01-remote-repository-authority.md)已经实现。[HTTP 入口](notes/implemented/2026-09-03-http-entrance-baseline.md)提供健康检查、RFC 9457 problem 响应，以及默认工作空间的只读公开文档 API。主要的单机部署保留一次性本地 Git 缓存，只有远端 `main` 才是仓库写入的确认点。已接受的提案将识别[仓库原生 Markdown 与只读同目录图片图库](notes/proposed/2026-09-01-repository-native-publishing-and-assets.md)，并把经 Poketto 上传的图片存入权威[本地 ManagedBlobStore，同时把仓库图片副本当作可删除缓存](notes/proposed/2026-09-01-repository-asset-blob-store.md)。[C 端账号](notes/proposed/2026-09-01-consumer-accounts-and-personal-workspaces.md)、[仓库原生检索](notes/proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md)、[Next.js 前端](notes/proposed/2026-08-30-nextjs-frontend.md)和 MCP 入口仍处于提案阶段。Serverless 仍是可选方案，需要等待真实的 OSS、共享数据库与远程 SRT 基础设施。[需求文档](notes/implemented/2026-08-25-requirements-and-architecture.zh.md)记录已实现基线，提案则标明尚未交付的目标决策。

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
src/                 应用模块及其测试
infra/postgres/      可复现的 PostgreSQL 17 + zhparser 测试镜像
notes/               决策记录：proposed / implemented / rejected / archived
```

## 开发

使用 Java 26 和仓库内的 Gradle Wrapper。数据库集成测试与完整校验需要 Docker；较快的单元测试和仓库校验不需要。

应用启动需要 PostgreSQL 数据源、绝对路径形式的 `POKETTO_DATA_DIR`，以及一个预先建好的私有 HTTPS Git 仓库。运行 `bootRun` 前设置 `SPRING_DATASOURCE_URL`、数据库认证信息、`POKETTO_REPOSITORY_REMOTE_URI`、`POKETTO_REPOSITORY_USERNAME` 与 `POKETTO_REPOSITORY_PASSWORD`。Flyway 会创建默认工作空间；应用将它绑定到远端 `main`，只在 `<data-dir>/workspaces/<workspace-id>/content` 物化一次性缓存。`POKETTO_REPOSITORY_CACHE_MAX_WORKSPACES` 与 `POKETTO_REPOSITORY_TIMEOUT_SECONDS` 可以调整默认值为 32 个工作空间和 30 秒的限制。运行中的实例通过 `GET /actuator/health` 回应部署检查，并在 `GET /api/public/documents` 提供默认工作空间的公开文档。

```sh
./gradlew test repoCheck
./gradlew check
POKETTO_DATA_DIR=/srv/poketto \
POKETTO_REPOSITORY_REMOTE_URI=https://git.example.com/owner/private-content.git \
POKETTO_REPOSITORY_USERNAME=operator \
POKETTO_REPOSITORY_PASSWORD=... \
./gradlew bootRun
```

Windows 下用 `$env:...` 设置同名变量，确保 `POKETTO_DATA_DIR` 是绝对路径，再使用 `.\gradlew.bat`。命令表与协作规则见 [AGENTS.md](AGENTS.md#commands)。

## 授权

代码与项目文档：[Apache-2.0](LICENSE)。
美术素材与站点发布的创作内容：[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans)。
