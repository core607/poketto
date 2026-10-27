import java.time.Duration

val executorServiceSource = layout.projectDirectory.dir("executor-service")
val executorServiceBuild = layout.buildDirectory.dir("executor-service")
val executorServiceReports = layout.buildDirectory.dir("test-results/executorServiceTests")
val executorServiceWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val executorRustInputs = fileTree(executorServiceSource) {
    include("rust/**", "Dockerfile.tests", ".dockerignore")
    exclude("rust/target/**")
}
val executorBinaries = executorServiceBuild.map { it.dir("bin") }
val executorSupervisorFixture = executorServiceBuild.map { it.dir("supervisor-fixture") }

val buildExecutorRust = tasks.register<Exec>("buildExecutorRust") {
    group = "build"
    description = "Build the pinned static Linux sandbox programs through the executor test image."
    inputs.files(executorRustInputs)
    outputs.dir(executorBinaries)
    timeout.set(Duration.ofMinutes(10))
    doFirst {
        commandLine("docker", "build", "--target", "binaries",
            "--file", executorServiceSource.file("Dockerfile.tests").asFile.absolutePath,
            "--output", "type=local,dest=${executorBinaries.get().asFile.absolutePath}",
            executorServiceSource.asFile.absolutePath)
    }
}

tasks.register<Exec>("executorRustCheck") {
    group = "verification"
    description = "Check formatting, Clippy and tests with the pinned Rust toolchain and lockfile."
    inputs.files(executorRustInputs)
    timeout.set(Duration.ofMinutes(10))
    commandLine("docker", "build", "--target", "rust-check",
        "--file", executorServiceSource.file("Dockerfile.tests").asFile.absolutePath,
        executorServiceSource.asFile.absolutePath)
}

val buildExecutorSupervisorFixture = tasks.register<Exec>("buildExecutorSupervisorFixture") {
    group = "verification"
    description = "Build the separate protocol fixture; it is absent from installed worker bundles."
    inputs.files(executorRustInputs)
    outputs.file(executorSupervisorFixture.map { it.file("poketto-supervisor-fixture") })
    timeout.set(Duration.ofMinutes(10))
    doFirst {
        commandLine("docker", "build", "--target", "fixture-binary",
            "--file", executorServiceSource.file("Dockerfile.tests").asFile.absolutePath,
            "--output", "type=local,dest=${executorSupervisorFixture.get().asFile.absolutePath}",
            executorServiceSource.asFile.absolutePath)
    }
}

tasks.register<Sync>("stageExecutorWorker") {
    group = "build"
    description = "Stage the worker sources and shared static programs for native acceptance."
    dependsOn(buildExecutorRust)
    from(executorServiceSource) {
        include("*.py", "requirements.txt", "poketto-executor.service")
        exclude("test_*.py", "run_tests.py")
    }
    from(executorBinaries) {
        exclude("poketto-supervisor", "poketto-launch")
        into("bin")
    }
    from(executorBinaries.map { it.file("poketto-supervisor") })
    from(executorBinaries.map { it.file("poketto-launch") })
    into(executorServiceBuild.map { it.dir("worker") })
}

tasks.register<Sync>("stageGameWorker") {
    group = "build"
    description = "Stage the independent game worker with the exact shared browser rule runtime."
    dependsOn(buildExecutorRust)
    from(executorBinaries.map { it.file("poketto-launch") })
    from(executorServiceSource) {
        include("*.py", "requirements.txt", "game-entry.mjs", "game-config.example.json", "poketto-games.service", "pokettogames.slice")
        exclude("test_*.py", "native_*.py", "disk_pool_probe.py", "run_tests.py")
    }
    from(layout.projectDirectory.file("frontend/public/games/runtime.mjs")) { rename { "game-runtime.mjs" } }
    from(layout.projectDirectory.file("examples/pocket-game/rules.mjs")) { rename { "game-example.mjs" } }
    into(layout.buildDirectory.dir("game-worker"))
}

val stageExecutorServiceTests = tasks.register<Sync>("stageExecutorServiceTests") {
    dependsOn(buildExecutorRust, buildExecutorSupervisorFixture)
    from(executorServiceSource) {
        include("worker.py", "game_worker.py", "command_channel.py", "disk_pool.py", "resource_pool.py", "session_files.py", "binary_capture.py", "materialize.py", "artifacts.py", "bridge.py", "test_*.py", "testdata/**", "rust/**", "run_tests.py", "requirements.txt", "Dockerfile.tests", ".dockerignore", "java-frames.json", "config.example.json", "README.md")
        exclude("rust/target/**", "**/__pycache__/**")
    }
    from(executorBinaries) {
        exclude("poketto-supervisor", "poketto-launch")
        into("bin")
    }
    from(executorBinaries.map { it.file("poketto-supervisor") })
    from(executorBinaries.map { it.file("poketto-launch") })
    from(executorSupervisorFixture)
    into(executorServiceBuild.map { it.dir("source") })
}

val buildExecutorServiceTestImage = if (executorServiceWindows) {
    tasks.register<Exec>("buildExecutorServiceTestImage") {
        dependsOn(stageExecutorServiceTests)
        timeout.set(Duration.ofMinutes(5))
        inputs.files(stageExecutorServiceTests)
        outputs.file(executorServiceBuild.map { it.file("image.id") })
        // An external Docker cache may be removed independently of Gradle's outputs.
        outputs.upToDateWhen { false }
        doFirst {
            val source = executorServiceBuild.get().dir("source").asFile
            commandLine(
                "docker", "build", "--iidfile", executorServiceBuild.get().file("image.id").asFile.absolutePath,
                "--file", source.resolve("Dockerfile.tests").absolutePath, source.absolutePath,
            )
        }
    }
} else null

tasks.register<Exec>("executorServiceTests") {
    group = "verification"
    description = "Run required executor protocol and lifecycle tests on Linux, without skipped tests."
    dependsOn(stageExecutorServiceTests)
    if (buildExecutorServiceTestImage != null) dependsOn(buildExecutorServiceTestImage)
    timeout.set(Duration.ofMinutes(5))
    inputs.files(stageExecutorServiceTests)
    outputs.file(executorServiceReports.map { it.file("TEST-executor-service.xml") })
    outputs.upToDateWhen { false }
    doFirst {
        val reports = executorServiceReports.get().asFile
        reports.mkdirs()
        if (executorServiceWindows) {
            val image = executorServiceBuild.get().file("image.id").asFile.readText().trim()
            require(Regex("sha256:[0-9a-f]{64}").matches(image)) { "Invalid executor test image identifier" }
            commandLine(
                "docker", "run", "--rm", "--read-only", "--network", "none", "--user", "65534:65534",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--memory", "256m", "--cpus", "1",
                "--pids-limit", "64", "--tmpfs", "/tmp:rw,size=16777216,mode=1777,nosuid,nodev",
                "--mount", "type=bind,source=${reports.absolutePath},target=/reports", image,
            )
        } else {
            commandLine(
                "python3", executorServiceBuild.get().file("source/run_tests.py").asFile.absolutePath,
                "--environment", executorServiceBuild.get().dir("venv").asFile.absolutePath,
                "--report", reports.resolve("TEST-executor-service.xml").absolutePath,
            )
        }
    }
}

tasks.named("check") { dependsOn("executorServiceTests", "executorRustCheck") }
