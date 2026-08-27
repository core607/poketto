# Git 复制与写入确认模式

Date: 2026-08-27
Status: Proposed

## 问题

[需求与架构](../implemented/2026-08-25-requirements-and-architecture.zh.md)已经把本地内容仓的 `main` 定为真理，并以本地 commit 成功作为机器写入确认点。它只把 remote 描述为可选备份，没有规定复制时机、失败恢复、可观测性，也没有定义需要异地落盘后才确认的部署方式。

本地权威必须保持断网可写，同时允许部署者通过一个耐久策略配置把远端确认纳入写路径。两种模式共享同一内容模型和 Git 复制机制，不形成两套业务实现。

## 提案

### 权威与远端边界

- 每个工作空间的本地非裸仓库及其 `main` 是默认权威。应用是该仓的唯一机器写入器；写操作按仓串行。
- 所有者可以在服务器上的内容工作树直接编辑并 commit，作为 break-glass。v1 不支持从外部工作站直接 push 到应用检出的非裸 `main`。
- 配置的 remote `main` 是输出镜像，不是第二个编辑入口。部署文档要求禁止其他用户和自动化直接改写或 force-push 该分支。
- 远端领先或与本地分叉不是可重试网络错误。复制停止并报告 divergence；应用绝不自动 force push，也不猜测应保留哪一侧。

### 确认策略

配置 `poketto.git.acknowledgement` 接受两个值：

- `local`：默认值。本地 `main` commit 成功即确认写入；同仓复制 worker 随后异步推进 remote。
- `mirrored`：只有 remote 已接受候选 commit，且本地 `main` 已推进到该 commit 后才返回成功。没有可用 remote、凭据或一致的起始 ref 时，应用启动失败而不是退回 `local`。

策略是实例级默认值；未来如需按 workspace 覆盖必须另行增加配置契约。运行中改变策略需要重启并执行启动时一致性检查。

`mirrored` 模式不能先把候选提交暴露到本地 `main` 再尝试 push。应用在持有仓库写锁时根据当前 `main` 构造未发布 commit，将该 commit 以远端当前 ref 为前提推送；远端接受后再快进本地 `main`。push 失败只留下不可达对象，不改变可见本地历史。远端成功而本地 ref 更新前崩溃时，启动恢复可以在证明远端是本地 `main` 的后代后快进本地；其他关系均停止启动并要求人工处理。

### 异步复制与状态

- `local` 模式在每次 commit 后唤醒按仓唯一的复制 worker。连续提交可以合并为一次 push；worker 推进到启动 push 时观察到的最新本地 `main`，不为每个 commit 建立持久任务。
- 临时网络、认证服务和远端限流错误使用有上限的指数退避并持续暴露失败状态。认证失败、权限拒绝、non-fast-forward 和仓库不存在需要明确分类；永久错误不能被无尽重试掩盖。
- 写入结果返回 `commit_sha` 和当时观察到的 `indexed`、`mirrored` 布尔值。后二者是彼此独立的投影，不构成三阶段状态机，也不改变写操作按所选策略得出的成功或失败。
- 运维状态按 workspace 暴露 `local_head`、`last_mirrored_commit`、`last_indexed_commit`、各自落后 commit 数与持续时间、最后尝试时间和去敏后的失败类别。普通成员不能读取远端地址、凭据或其他空间状态。
- remote 已包含目标 commit 才算 mirrored。仅上传对象、启动 push 或记录任务成功都不能推进 `last_mirrored_commit`。

### 复制与备份的关系

远端镜像缩小主机丢失时的内容恢复点，但不能替代独立备份策略：凭据泄露、错误删除和仓库损坏可能传播到远端。[异地备份与恢复提案](2026-08-27-off-host-backup-and-restore.md)负责保留期、加密和恢复演练。

## 实现范围与依赖

本提案依赖[工作空间与租户边界](2026-08-27-workspace-tenancy.md)以及[内容仓与文档基础](2026-08-26-content-foundation.zh.md)，必须在两者之后实现。异步复制和 `mirrored` 确认共享一个 Git remote adapter、ref 比较和错误分类，可以在同一任务内完成。

第一轮包括配置绑定、启动一致性检查、候选 commit 发布、按仓复制 worker、checkpoint、状态查询和使用本地 bare remote 的故障测试。它不实现 GitHub 专用 API、外部 push 接收、自动合并、远端仓库创建或冲突裁决。

## 考虑过的替代方案

**始终以远端 push 作为确认点。** 这能获得统一的异地耐久语义，但让网络和远端服务进入每次写入，违背默认自包含运行。`mirrored` 保留为显式严格策略。

**先 commit 本地 `main`，严格模式下同步 push。** 代码更直接，但 push 失败后内容已经可见，调用方却收到失败；重试可能产生重复写入。未发布 commit 把确认失败与正式历史隔开。

**每个 commit 建立独立复制队列项。** 它提供逐项状态，但 Git push 最新 ref 已经包含全部祖先。按仓合并唤醒减少持久队列和重复网络请求，checkpoint 仍能表达复制进度。

**允许远端和本地同时接受人工写入。** 这会引入双写、拉取与合并策略，使 remote 不再是镜像。外部协作需要新的远端权威或 ingress 提案。

## 验收条件

- 中英文需求文档同步描述默认本地确认、可选 `mirrored` 确认、远端镜像边界，以及 `committed`、`mirrored`、`indexed` 相互独立的结果语义。
- `local` 模式在 remote 不可用时仍确认本地写入，并准确报告 `mirrored=false`；恢复网络后 worker 将远端推进到本地 HEAD。
- 连续本地提交可以由一次 push 全部镜像，checkpoint 指向远端实际包含的 commit。
- `mirrored` 模式在 push 被拒绝时不改变本地 `main`；成功时远端与本地都包含返回的 `commit_sha`。
- 故障测试覆盖远端成功后本地推进前崩溃、远端落后、远端领先、分叉、认证失败、non-fast-forward、网络超时和进程重启。
- divergence 从自动重试中退出并阻止后续镜像写入；任何路径都不执行 force push 或自动合并。
- 两个 workspace 的锁、remote、checkpoint、重试与状态完全独立。
- `./gradlew test`、使用本地 bare remote 的集成测试、`./gradlew repoCheck` 与 `git diff --check` 通过。

## 风险

`mirrored` 模式把远端可用性纳入写入成功率。它是部署者主动选择的耐久策略，错误信息必须区分“未提交”“已远端保存、等待本地恢复”和“本地已确认但尚未索引”，避免调用方盲目重试。

Git remote 通常只提供仓库级 ref 语义，不提供 Poketto 业务事务。实现必须以 ref 关系和实际远端读取为证据，不能把一次命令的模糊成功日志当成耐久确认。
