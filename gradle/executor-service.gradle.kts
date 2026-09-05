import java.time.Duration

val executorServiceSource = layout.projectDirectory.dir("executor-service")
val executorServiceBuild = layout.buildDirectory.dir("executor-service")
val executorServiceReports = layout.buildDirectory.dir("test-results/executorServiceTests")
val executorServiceWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

val stageExecutorServiceTests = tasks.register<Sync>("stageExecutorServiceTests") {
    from(executorServiceSource) {
        include("worker.py", "test_worker.py", "run_tests.py", "requirements.txt", "Dockerfile.tests")
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
                "--environment", executorServiceBuild.get().dir("venv").asFile.absolutePath,
                "--report", reports.resolve("TEST-executor-service.xml").absolutePath,
            )
        }
    }
}

tasks.named("check") { dependsOn("executorServiceTests") }
