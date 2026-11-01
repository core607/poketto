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
val executorRustExport = executorServiceBuild.map { it.dir("rust-export") }

val buildExecutorRust = tasks.register<Exec>("buildExecutorRust") {
    group = "build"
    description = "Build the pinned static Linux sandbox programs through the executor test image."
    inputs.files(executorRustInputs)
    outputs.dir(executorBinaries)
    timeout.set(Duration.ofMinutes(10))
    doFirst {
        commandLine("docker", "build", "--target", "binaries",
            "--file", executorServiceSource.file("Dockerfile.tests").asFile.absolutePath,
            "--output", "type=local,dest=${executorRustExport.get().asFile.absolutePath}",
            executorServiceSource.asFile.absolutePath)
    }
    doLast {
        project.sync {
            from(executorRustExport) { include("sandcrab") }
            into(executorBinaries)
        }
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

tasks.register<Sync>("stageGameWorker") {
    group = "build"
    description = "Stage Sandcrab, the game profile and a synthetic example for native acceptance."
    dependsOn(buildExecutorRust)
    from(executorBinaries.map { it.file("sandcrab") })
    from(executorServiceSource) {
        include("game-config.example.toml", "sandcrab-games.service", "pokettogames.slice")
    }
    from(layout.projectDirectory.file("examples/pocket-game/rules.mjs")) { rename { "game-example.mjs" } }
    into(layout.buildDirectory.dir("game-worker"))
}

val stageExecutorServiceTests = tasks.register<Sync>("stageExecutorServiceTests") {
    dependsOn(buildExecutorRust)
    from(executorServiceSource) {
        include("resource_pool.py", "test_*.py", "rust/**", "run_tests.py", "Dockerfile.tests", ".dockerignore")
        exclude("rust/target/**", "**/__pycache__/**")
    }
    from(executorBinaries) {
        include("sandcrab")
        into("bin")
    }
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
                "--report", reports.resolve("TEST-executor-service.xml").absolutePath,
            )
        }
    }
}

tasks.named("check") { dependsOn("executorServiceTests", "executorRustCheck") }
