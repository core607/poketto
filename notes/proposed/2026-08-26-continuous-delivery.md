# 持续交付与单机自动部署

Date: 2026-08-26
Status: Proposed

## 问题

[需求与架构](../implemented/2026-08-25-requirements-and-architecture.zh.md)规定应用镜像发布到 GHCR，并为无法稳定访问镜像仓库的网络提供经 SSH 传输 `docker save` 产物的备用路径。[开发基线](../implemented/2026-08-26-development-baseline.md)目前只在 pull request 和 `main` push 上运行完整校验，不构建可部署应用镜像，也不记录或执行部署。

Poketto 面向单机自托管。如果每次合并后仍需在服务器上手动构建、选择镜像、修改 Compose 配置并判断是否回退，部署结果会依赖操作者当时执行的步骤，已通过 CI 的提交也不能自然抵达实际运行环境。CD 需要把同一提交的验证、制品身份和部署结果连成一条可追踪路径，同时避免让 pull request 代码、镜像标签或长期凭据获得不必要的生产权限。

## 提案

### 流水线与触发条件

- 使用一条 GitHub Actions 流水线承载验证、发布和部署。pull request 只运行现有 `check`；`main` push 在同一个已验证提交上依次运行 `check`、镜像发布和可选生产部署。发布与部署 job 通过 `needs` 直接依赖前序 job，不通过有特权的 `workflow_run` 消费另一条工作流的缓存或制品。
- 镜像发布对规范仓库的 `main` 自动启用。生产部署需要仓库变量显式启用并配置 `production` environment；未配置生产目标时，流水线停在可交付镜像，不把缺少某个维护者的私有服务器视为开源构建失败。
- `production` environment 只允许受保护的 `main` 部署并持有部署凭据，默认不要求逐次人工批准。每次部署在 GitHub 中留下 environment deployment 记录。
- 生产部署使用固定 concurrency group，绝不取消正在运行的部署。默认只保留最新的一个 pending 部署，使已经开始的版本完整结束，同时跳过被更新提交取代、尚未开始的中间版本。
- 提供 `workflow_dispatch` 运维入口，用于重新部署已由本仓库发布的精确镜像 digest，或回到上一份成功部署清单。手动入口不能部署任意外部镜像，也不能绕过镜像来源和生产并发检查。

### 不可变制品

- 为应用和 PostgreSQL 17 + zhparser 分别构建 OCI 镜像，发布到 GHCR。每次 `main` 构建记录源码 commit，提供 commit 标签和便于发现的移动标签；部署只接受构建输出的不可变 digest，不使用移动标签决定实际版本。
- 生成一份部署清单，至少记录源码 commit、应用镜像 digest、数据库镜像 digest、构建时间和流水线 run。发布 job 将清单作为流水线制品保存，部署 job 原样消费；服务器保存当前与上一份成功清单以支持重部署和运行时回退。
- 镜像携带标准源码与 revision 标签。GitHub Actions 为两个镜像生成 build provenance attestation；发布 job 使用最小的 `contents: read`、`packages: write`、`attestations: write` 和 `id-token: write` 权限，验证 job 与部署 job 不继承这些写权限。
- 镜像不包含 `.env`、API key、内容仓库、数据库卷、图片目录或其他运行时持久化数据。所有第三方 Actions 继续固定到完整 commit SHA。

### 单机部署

- 仓库提供面向 Linux + Docker Compose 的通用生产 Compose 文件和无交互部署脚本。机器地址、用户、端口、数据目录、域名、密钥和镜像仓库凭据只存在于操作者环境或 GitHub environment，不写入仓库。
- 部署账户是仅服务 Poketto 的专用账户。SSH 必须校验固定 host key；流水线不使用个人 SSH key，也不使用 `StrictHostKeyChecking=no`。生产主机不运行能够执行仓库任意工作流的自托管 GitHub Actions runner。
- 首选传输模式由生产主机使用只读凭据按 digest 从 GHCR 拉取镜像。受限网络模式由 GitHub-hosted runner 拉取同一 digest，经 `docker save` 和 SSH 传给远端 `docker load`；两种模式随后调用同一个远端部署入口，不能形成两套更新与回退语义。
- 远端入口先取得部署锁，校验 Compose、环境文件、持久化目录、磁盘空间和当前清单，再拉取或载入镜像。它原子写入候选清单，更新容器但不删除或重建数据卷，等待应用和数据库健康检查通过后才把候选清单提升为当前成功版本。
- 健康检查失败、启动超时或候选容器提前退出时，脚本恢复上一份清单并重新建立上一组容器；回退结果也必须经过健康检查。流水线同时报告候选部署失败和回退是否成功，不能用成功回退掩盖失败部署。
- 应用提供不泄露敏感信息的本机健康入口。Compose 为应用和数据库声明健康检查；部署完成的判断来自实际运行的服务，而不是 `docker compose up` 的退出码。

### 持久化状态与回退边界

- 容器回退只恢复镜像和 Compose 配置，不等于恢复内容仓库、数据库非派生表或图片数据。部署脚本不得删除、重建或自动回滚这些持久化状态。
- 一个会产生不向后兼容的数据库 schema、内容格式或其他持久化变更的实现，必须在自己的 proposed 中定义备份、迁移、失败恢复和旧版本可否重新启动。该变更在这些条件落地前不能依赖本流程的自动镜像回退作为恢复方案。
- 常规部署在开始前检查最近一次备份状态；备份机制尚未实现时，生产自动部署保持显式关闭。满足[备份要求](../implemented/2026-08-25-requirements-and-architecture.zh.md#备份)的机制及可机器读取的新鲜度信号由后续独立提案负责，本提案不顺带实现内容、数据库和图片备份系统。
- 在开发期需要破坏性重建时，操作者先关闭自动部署并按对应变更的说明处理数据。流水线不能根据改动文件猜测一次部署是否支持数据回退。

### 可观测性与人工控制

- GitHub Actions 摘要展示 commit、两个镜像 digest、目标 environment、传输模式、部署前后清单、健康检查结果和回退结果，但不打印 secret、环境文件或远端命令中的敏感参数。
- 部署脚本输出结构化的阶段与错误信息，并在每一步失败时保持可重新运行。重复部署同一清单是无操作成功；中断后的下一次运行从服务器实际清单和容器状态重新判断，不相信上一条 workflow 的文字状态。
- 仓库文档说明如何创建 GHCR 包权限、`production` environment、专用部署账户、固定 host key、只读拉取凭据和自动部署开关。实现不通过 workflow 自动修改 required checks、environment 保护规则或其他仓库设置。

## 实现范围与依赖

第一轮实现包括应用容器构建、生产 Compose、健康入口、部署清单、GHCR 发布、provenance attestation、两种 SSH 传输路径、远端部署锁、健康确认、运行时回退、手动重部署入口及自动化测试。它不实现域名、TLS、反向代理、日志平台、数据库迁移框架或持久化数据备份。

镜像发布可在当前开发基线上独立实现。生产自动部署必须等待备份提案提供符合需求的备份机制和可机器读取的新鲜度信号；在此之前，流水线只自动发布镜像。内容基础提案与镜像发布没有实现依赖，但未来内容格式或数据库变更必须遵守本提案的持久化回退边界。

## 考虑过的替代方案

**每次部署人工批准。** GitHub environment 的人工审批能缩小错误上线窗口，但单人项目会把每次已通过校验的合并重新变成人工队列。默认自动部署更符合目标；暂停开关、手动重部署和持久化变更的显式门槛保留必要控制。

**生产机运行自托管 GitHub Actions runner。** 它免去入站 SSH，并能直接访问 Docker，但 runner 执行仓库工作流时拥有接近宿主机 root 的 Docker 权限。单机生产环境不值得承担这条长期执行面，使用权限受限的 SSH 部署账户更容易约束。

**Watchtower 或其他移动标签轮询器。** 这类工具自动化程度高，但部署决定与 CI commit、精确 digest、健康结果和 GitHub deployment 记录脱节，失败恢复也难以携带 Poketto 的状态约束。流水线显式传递部署清单。

**分离 CI 与 CD，并由 `workflow_run` 提权。** 分离能让文件职责更清楚，但有特权工作流必须谨慎处理另一工作流的缓存、制品和 checkout commit。当前规模下，同一流水线的 job 依赖更直接，也能让发布制品与通过校验的 commit 保持一致。

**只提供 SSH 脚本，由操作者手动运行。** 这满足受限网络，却没有连续交付，也无法自动串联 CI、制品身份、并发、健康确认和 deployment 记录。SSH 脚本保留为流水线的执行部件，不作为主要触发方式。

**Kubernetes、Swarm 或蓝绿双栈。** Poketto 的目标是 2 核 4 GB 单机，额外编排平面和双份常驻资源不符合约束。Docker Compose 接受短暂重启窗口，并通过健康检查和上一清单回退控制失败。

## 验收标准

- pull request 仍只运行无生产 secret、无包写权限的完整 `check`；未经合并的代码不能发布镜像或触发部署。
- `main` 的 `check` 成功后，应用和 PostgreSQL 镜像发布到 GHCR，部署清单中的 commit 与两个 digest 可回溯到同一 workflow run，并能通过本仓库的 provenance 验证。
- 未配置或未启用生产环境时，镜像发布成功且部署明确显示为跳过；错误配置的已启用环境失败并指出缺少的变量或 secret。
- 在一次可丢弃的 Linux 部署目标上，registry pull 与 `docker save` over SSH 都能部署同一份清单，服务通过真实健康入口，并且持久化卷在更新和运行时回退中保持不变。
- 一个故意无法通过健康检查的候选版本不会成为当前成功清单；上一版本被恢复并重新通过健康检查，workflow 同时以部署失败结束。
- 两次相邻 `main` push 不会并发修改生产主机；正在运行的部署完成，尚未开始的旧 pending 部署被最新提交取代。
- 手动入口能幂等地重部署当前清单，并能选择本仓库先前成功发布且仍在 GHCR 保留的精确 digest；外部镜像和只给移动标签的输入被拒绝。
- SSH host key、部署私钥、registry 凭据和远端环境文件不会出现在仓库、构建制品、Actions 摘要或测试日志中。
- 自动化测试覆盖清单校验、锁、幂等、缺失配置、传输失败、健康超时、成功提升、回退成功和回退失败；一次可丢弃主机上的端到端演练覆盖真实 Docker Compose 入口。
- 仓库的文档与 workflow 校验通过，`./gradlew check` 继续通过；实现不会自动修改远端 required checks 或 environment 设置。

## 风险

自动部署 `main` 会让通过 CI 但存在语义缺陷的变更更快进入生产。健康检查只能证明服务可运行，不能证明内容、权限或页面行为正确；操作者需要能够立即关闭自动部署并按 digest 回退。

GitHub-hosted runner 持有可连接生产机的短期运行环境和 environment secret。专用账户、固定 host key、最小远端权限和不在 pull request job 中暴露 secret 能缩小影响，但仓库 `main` 上的恶意 workflow 仍可能窃取部署凭据。`main` 保护和 workflow 变更审查仍是信任根。

镜像级回退无法撤销持久化变更。强行自动恢复旧容器可能比停止更危险，因此不兼容数据变更必须提供自己的恢复设计，不能由 CD 猜测。

每次 `main` 同时构建应用与自定义 PostgreSQL 镜像会增加 Actions 时间和 GHCR 存储。先保持单一、可证明的发布路径；只有实际成本成为问题后，才按构建上下文和已有 attestation 安全复用未变化的数据库镜像。

单机 Compose 更新会有短暂不可用窗口，registry 故障也会阻止首选传输。受限网络传输和上一清单减轻这两类问题，但本提案不承诺零停机。
