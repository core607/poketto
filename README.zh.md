# Poketto

自托管的个人知识库，公开面是博客。同一份 Markdown 内容，既支撑公开发布，也通过 MCP 作为受信 AI 的长期记忆。

[English](README.md)

## 状态

筹备中。需求与架构已定（[需求文档](notes/implemented/2026-08-25-requirements-and-architecture.zh.md)）；开发尚未开始；目前没有可运行的代码。

## 适合谁

- 希望笔记、剪藏和博客就是 git 仓库里的一份 Markdown、数据库只是可重建的检索投影的人。
- 希望自己的 AI 助手通过 MCP 和作用域 API Key 读写内容、而不是交出 shell 权限的人。
- 用一台小服务器跑东西、组件宁少勿多的人。
- 对「为 agent 开发而设计的仓库」感兴趣的人——从 [AGENTS.md](AGENTS.md) 看起。

## 目录

```
AGENTS.md            本仓库的 agent 工作规则（从这里开始）
.agents/skills/      可复用工序：文风、评审、检查、笔记生命周期
notes/               决策记录：proposed / implemented / rejected / archived
```

## 授权

代码与项目文档：[Apache-2.0](LICENSE)。
美术素材与站点发布的创作内容：[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans)。
