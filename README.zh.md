# Poketto

自托管的个人知识库，公开面是博客。同一份 Markdown 内容，既支撑公开发布，也通过 MCP 作为受信 AI 的长期记忆。

[English](README.md)

## 状态

开发中。需求与架构已定（[需求文档](notes/implemented/2026-08-25-requirements-and-architecture.zh.md)）。可执行开发基线、工作空间隔离、内容仓、文档写入和 Git 镜像模式已经实现；投影、检索、渲染和 MCP 入口仍处于提案阶段。

## 适合谁

- 希望笔记、剪藏和博客就是 git 仓库里的一份 Markdown、数据库只是可重建的检索投影的人。
- 希望自己的 AI 助手通过 MCP 和作用域 API Key 读写内容、而不是交出 shell 权限的人。
- 用一台小服务器跑东西、组件宁少勿多的人。
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

Git 写入默认使用 `POKETTO_GIT_ACKNOWLEDGEMENT=local`：本地 `main` 成功即确认写入，已配置的 `origin` 在后台异步镜像。把它设为 `mirrored` 后，写入必须先获得远端确认。严格模式要求工作空间内容仓的 `origin` 在启动时可访问，并与本地 `main` 处于同一历史；Poketto 不会创建、合并或强推该远端。

```sh
./gradlew test repoCheck
./gradlew check
POKETTO_DATA_DIR=/srv/poketto ./gradlew bootRun
```

Windows 下先把 `$env:POKETTO_DATA_DIR` 设为绝对路径，再使用 `.\gradlew.bat`。命令表与协作规则见 [AGENTS.md](AGENTS.md#commands)。

## 授权

代码与项目文档：[Apache-2.0](LICENSE)。
美术素材与站点发布的创作内容：[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans)。
