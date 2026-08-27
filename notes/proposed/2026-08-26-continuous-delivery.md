# 持续交付与单机部署

Date: 2026-08-26
Status: Proposed

## 问题

[需求与架构](../implemented/2026-08-25-requirements-and-architecture.zh.md)规定应用镜像发布到 GHCR，并为无法稳定访问镜像仓库的网络提供经 SSH 传输 `docker save` 产物的备用路径。[开发基线](../implemented/2026-08-26-development-baseline.md)目前只在 pull request 和 `main` push 上运行完整校验，不构建可部署镜像，也没有可重复执行的生产 Compose 与部署脚本。

Poketto 面向单机自托管。第一条 CD 路径只需保证通过校验的提交能够产生不可变镜像，并由一个受约束的 SSH 入口部署到 Linux + Docker Compose 主机。自动回退、制品晋升和发布证明在出现实际需求前不进入首轮实现。

## 提案

### 验证与镜像发布

- 保留现有 pull request `check`。规范仓库的 `main` push 在同一提交通过 `check` 后构建应用和 PostgreSQL 17 + zhparser OCI 镜像，并发布到 GHCR。
- 镜像携带源码 commit 标签和标准 revision 元数据。部署使用 registry 返回的不可变 digest；移动标签只用于发现，不能决定生产实际版本。
- 发布 job 只获得 `contents: read` 与 `packages: write`。验证 job、pull request 和部署 job 不继承包写权限。所有第三方 Actions 固定到完整 commit SHA。
- 镜像不包含 `.env`、API Key、内容仓、数据库卷、blob 目录或其他运行时状态。

### 可选自动部署

- 未配置生产目标时，流水线在镜像发布后成功结束。生产部署只有在仓库变量显式启用并配置 GitHub `production` environment 后运行；开源仓库不依赖任何维护者的私有服务器才能通过 CI。
- 部署 job 使用固定 concurrency group，并且不取消已经开始的部署。连续 `main` push 不得并发修改同一主机。
- `production` environment 保存 SSH 私钥、固定 host key 和必要的 registry 凭据。流水线不使用个人 SSH key、`StrictHostKeyChecking=no` 或生产主机上的自托管 Actions runner。
- GitHub Actions 摘要记录源码 commit、镜像 digest、目标 environment 与健康结果，不打印 secret、远端环境文件或敏感命令参数。

### Compose 与 SSH 脚本

- 仓库提供通用生产 Compose 文件和无交互部署脚本。主机、端口、域名、持久化目录与 secret 只存在于操作者环境或 GitHub environment。
- 部署账户只服务 Poketto，并只拥有运行部署入口所需的权限。远端脚本取得部署锁，校验 Compose、环境文件、持久化目录和磁盘空间，再更新容器；它不得删除、重建或回滚数据卷。
- 默认由生产主机按 digest 从 GHCR 拉取镜像。受限网络脚本允许操作者或 GitHub-hosted runner 拉取同一 digest，通过 `docker save`、SSH 和 `docker load` 传输，然后调用相同远端部署入口。
- 应用与数据库都提供 Compose 健康检查。部署只有在真实服务通过健康检查后才成功；`docker compose up` 的退出码不足以证明部署完成。
- 失败部署返回失败并保留诊断信息，不自动猜测可安全运行的旧镜像。操作者可以用先前记录的精确 digest 重新运行同一脚本；首轮不建立候选清单、自动晋升或自动回退状态机。
- 对同一组 digest 重复执行脚本是幂等的。中断后重试从主机上的实际镜像与容器状态重新判断，不依赖上一条 workflow 的文字状态。

### 持久化边界

容器部署只改变镜像和 Compose 管理的进程，不恢复或迁移内容仓、blob 和 PostgreSQL 权威表。包含不兼容持久化变更的功能提案必须定义自己的迁移、失败恢复和旧版本可否启动；部署脚本不能根据文件差异猜测兼容性。

[异地备份与恢复](2026-08-27-off-host-backup-and-restore.md)完成并提供机器可读的新鲜度信号前，生产自动部署保持关闭。镜像发布和手动执行部署脚本不依赖该提案，但操作者必须明确承担尚无自动备份门槛的风险。

## 第一轮实现范围与依赖

第一轮包括应用容器、生产 Compose、应用健康入口、两个 OCI 镜像的 GHCR 发布、按 digest 的 SSH 部署、部署锁、健康确认、受限网络传输脚本及针对性测试。

它不实现域名、TLS、反向代理、日志平台、provenance attestation、部署清单晋升、自动回退、蓝绿双栈、任意历史版本选择器或数据库迁移框架。镜像发布可以在当前开发基线上独立实现；自动部署必须等待异地备份提案的门槛实现。

## 考虑过的替代方案

**生产主机运行自托管 GitHub Actions runner。** 它能直接访问 Docker，但仓库工作流会获得接近宿主机 root 的执行面。受限 SSH 账户更容易审计和撤销。

**Watchtower 轮询移动标签。** 配置简单，但部署决定无法稳定对应通过 CI 的 commit。流水线传递不可变 digest。

**候选清单、自动晋升与自动回退。** 这些机制能改善无人值守恢复，但需要定义持久化兼容性和额外状态。首轮以失败停止和按已知 digest 重跑为边界，真实运维需求出现后再提案。

**只发布镜像，不提供脚本。** 维护者仍需临时拼接 SSH、Compose、锁和健康检查步骤，部署不可重复。一个通用脚本是最小可维护交付面。

**Kubernetes 或蓝绿部署。** 默认目标是 2 核 4 GB 单机，额外编排面和双份常驻资源不符合当前运行约束。数据归属与运行平台彼此独立，未来 K8s 部署不需要改变本提案的镜像产物。

## 验收条件

- pull request 只运行无生产 secret、无包写权限的完整 `check`；未经合并的代码不能发布镜像或部署。
- `main` 的 `check` 成功后，应用和 PostgreSQL 镜像发布到 GHCR，源码 commit 与两个不可变 digest 可从同一 workflow run 查到。
- 未配置生产环境时发布成功且部署明确跳过；启用后缺少变量或 secret 会失败并指出缺项。
- 在可丢弃 Linux 主机上，registry pull 与 `docker save` over SSH 都能使用同一 digest 启动 Compose，并通过真实健康入口。
- 两次相邻部署不会并发修改主机；中断后重试与重复部署同一 digest 不删除持久化数据。
- 健康失败使 workflow 失败且不会被成功日志掩盖；使用先前 digest 重跑同一脚本可以恢复旧镜像，自动回退不属于验收范围。
- SSH host key、部署私钥、registry 凭据和远端环境文件不出现在仓库、构建制品、Actions 摘要或测试日志中。
- 自动化测试覆盖 digest 校验、锁、幂等、缺失配置、传输失败和健康超时；一次可丢弃主机演练覆盖真实 Compose 入口。
- `./gradlew check` 与 `git diff --check` 通过；实现不修改远端 required checks 或 environment 设置。

## 风险

自动部署 `main` 会让通过 CI 但存在语义缺陷的变更更快进入生产。健康检查只能证明服务可运行；关闭开关与按精确 digest 手动恢复是首轮控制手段。

GitHub-hosted runner 持有可连接生产机的短期环境和 secret。专用账户、固定 host key、最小权限和不向 pull request 暴露 secret 可以缩小影响，但 `main` 上的 workflow 仍属于信任根。

镜像级恢复不能撤销数据库或内容格式变化。兼容性不明时自动启动旧容器可能比停机更危险，因此首轮不自动回退。

每次 `main` 构建两个镜像会增加 Actions 时间与 GHCR 存储。先保持可追踪的单一路径；实际成本出现后，再根据构建上下文复用未变化的数据库镜像。
