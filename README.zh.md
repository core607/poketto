# Poketto

**面向人与 Agent 的 Git 原生内容工作空间。**

通过浏览器和 MCP 访问同一内容仓库。Agent 在隔离环境中使用 shell、Python 和 Git，Poketto 控制授权、持久化与发布。

[English](README.md) · [线上实例](https://poketto.top) · [开始使用](docs/usage.zh.md) · [架构](notes/implemented/2026-08-25-requirements-and-architecture.zh.md)

## 项目亮点

- CodeAct 工作流：以 `repo_exec` 和工作区 CLI 承载内容操作，Agent 使用通用工具自主组合任务。
- Git 作为内容权威：文本、目录与媒体引用可检查、可追踪；浏览器和 Agent 共用受版本检查保护的写入路径。
- 持久工作副本，隔离命令执行：副本按账号、空间和读取范围共享，存储使用磁盘硬配额，命令串行执行并受独立资源限制。
- 宿主控制写入：仓库凭证留在沙箱之外；选定保存、冲突检查与不确定结果恢复由宿主处理。
- 公开与私有分离：目录表达发布状态，发布受权限控制；公开读取使用不包含私密文件及原始历史的独立投影。
- 文本与原件分离：Git 保存媒体路径和版本，独立存储保存不可变原件，内容通过相对路径引用。

## 演进方向

以多用户内容空间为基础，面向 SaaS 托管与 Agent 内容社区演进。私有空间承载持续创作，公开视图承载展示、发现与分发。

## 开始使用

- 网站：[poketto.top](https://poketto.top)。创作与 Agent 访问需要账号和空间授权，当前采用邀请注册。
- MCP：`https://poketto.top/mcp`。在支持 OAuth 的 MCP 客户端中连接，选择空间并授权。
- 使用与自托管：[安装、连接和命令参考](docs/usage.zh.md)。

部署需要 Linux、PostgreSQL、私有 Git 仓库和独立执行服务。技术栈为 Java、Spring Boot 和 Next.js。项目处于开发阶段，接口与仓库格式可能变化，备份由运维管理。

[参与开发](AGENTS.md) · [客户端验收](acceptance/clients/README.md) · [沙箱验证](executor-native/README.md) · [交付范围](notes/implemented/2026-09-15-multiuser-daily-use-acceptance.md)

## 授权

代码与项目文档：[Apache-2.0](LICENSE)。
美术素材与站点发布的创作内容：[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans)。
