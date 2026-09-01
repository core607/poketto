# Poketto

以仓库为核心的个人知识服务，公开面是博客。已接受的目标让每个工作空间都在 Git 中保存 Markdown、图片与历史；同一份内容既支撑公开发布，也通过 MCP 作为受信 AI 的长期记忆。Poketto 首先面向单台云服务器，同时保留由配置选择外部基础设施的[可选 serverless 部署 profile](notes/proposed/2026-09-01-optional-serverless-deployment-profile.md)。

[English](README.md)

## 状态

开发中。可执行开发基线、工作空间隔离、内容仓基础与文档写入已经实现；当前基线运行于单台云服务器，并使用本地工作空间仓库。已接受的目标仍以单机部署为主，但让所有生产工作空间使用[远程 Git 权威](notes/proposed/2026-09-01-remote-repository-authority.md)，识别[仓库原生 Markdown 与同目录图片](notes/proposed/2026-09-01-repository-native-publishing-and-assets.md)，并通过[本地资源 BlobStore](notes/proposed/2026-09-01-repository-asset-blob-store.md)提供派生图片。[C 端账号](notes/proposed/2026-09-01-consumer-accounts-and-personal-workspaces.md)、[仓库原生检索](notes/proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md)、渲染和 MCP 入口仍处于提案阶段。Serverless 仍是可选方案，需要等待真实的 OSS、共享数据库与远程 SRT 基础设施。[需求文档](notes/implemented/2026-08-25-requirements-and-architecture.zh.md)记录已实现基线，提案则标明尚未交付的目标决策。

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

应用启动需要 PostgreSQL 数据源和绝对路径形式的 `POKETTO_DATA_DIR`。运行 `bootRun` 前设置 `SPRING_DATASOURCE_URL`、数据库所需的认证信息和数据目录。Flyway 会创建工作空间目录表；应用首次启动时会创建一个持久的默认工作空间，并在 `<data-dir>/workspaces/<workspace-id>/content` 创建尚无提交的 `main` 内容仓。

```sh
./gradlew test repoCheck
./gradlew check
POKETTO_DATA_DIR=/srv/poketto ./gradlew bootRun
```

Windows 下先把 `$env:POKETTO_DATA_DIR` 设为绝对路径，再使用 `.\gradlew.bat`。命令表与协作规则见 [AGENTS.md](AGENTS.md#commands)。

## 授权

代码与项目文档：[Apache-2.0](LICENSE)。
美术素材与站点发布的创作内容：[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans)。
