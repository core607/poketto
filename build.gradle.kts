import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.10.1"
}

spotless {
    java {
        palantirJavaFormat("2.97.0")
        importOrder("\\#", "")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.named("check") {
    dependsOn("spotlessCheck")
}

group = "io.github.core607"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(26)
    }
}

val springModulithVersion = "2.1.0"
// Immutable official PostgreSQL 17.11-bookworm manifest.
val postgresTestImage = providers.gradleProperty("poketto.postgres.image")
    .orElse("postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")

dependencies {
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc:2.0.1")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.7.1.202607240634-r")
    implementation("org.yaml:snakeyaml")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.modulith:spring-modulith-core:$springModulithVersion")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test:$springModulithVersion")
    testImplementation("org.junit.platform:junit-platform-launcher")
}

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[integrationTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[integrationTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(
        integrationTestSourceSet.implementationConfigurationName,
        platform("org.springframework.boot:spring-boot-dependencies:4.1.1"),
    )
    add(
        integrationTestSourceSet.implementationConfigurationName,
        "org.springframework.boot:spring-boot-starter-jdbc",
    )
    add(
        integrationTestSourceSet.implementationConfigurationName,
        "org.springframework.boot:spring-boot-testcontainers",
    )
    add(
        integrationTestSourceSet.implementationConfigurationName,
        "org.testcontainers:testcontainers-junit-jupiter",
    )
    add(
        integrationTestSourceSet.implementationConfigurationName,
        "org.testcontainers:testcontainers-postgresql",
    )
    add(integrationTestSourceSet.runtimeOnlyConfigurationName, "org.postgresql:postgresql")
}

val integrationTest = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs Docker-backed integration tests."
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
    systemProperty("poketto.postgres.image", postgresTestImage.get())
}

val storageTestsNeedLinux = System.getProperty("os.name").startsWith("Windows")
val linuxStorageRuntime = layout.buildDirectory.dir("linuxStorageTest/runtime")
val stageLinuxStorageTest = tasks.register<Sync>("stageLinuxStorageTest") {
    dependsOn(tasks.testClasses)
    onlyIf { storageTestsNeedLinux }
    into(linuxStorageRuntime)
    from(sourceSets.main.get().output.classesDirs) { into("classes") }
    from(sourceSets.test.get().output.classesDirs) { into("classes") }
    from(configurations.testRuntimeClasspath) { into("jars") }
}

val linuxStorageTest = tasks.register<Exec>("linuxStorageTest") {
    group = "verification"
    description = "Runs the real durable-storage JUnit suite in Linux when the host is Windows."
    dependsOn(stageLinuxStorageTest)
    onlyIf { storageTestsNeedLinux }
    inputs.files(sourceSets.test.get().runtimeClasspath)
    outputs.upToDateWhen { false }
    // Stage only compiled classes and resolved dependency JARs; never mount operator files.
    commandLine(
        "docker", "run", "--rm", "--network", "none", "--read-only",
        "--user", "65534:65534", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
        "--memory", "512m", "--pids-limit", "128", "--mount", "type=volume,target=/tmp",
        "--mount", "type=bind,source=${linuxStorageRuntime.get().asFile.absolutePath},target=/runtime,readonly",
        "--entrypoint", "java",
        "eclipse-temurin@sha256:c0fe66ea21e972724000cf402f8081c7841d960839f69cb0754f40b40f74b2cc",
        "-Xmx256m", "-Djava.io.tmpdir=/tmp", "-cp", "/runtime/classes:/runtime/jars/*",
        "io.github.core607.poketto.assets.internal.LinuxStorageTestLauncher",
    )
}

// Git for Windows ships bash beside git.exe; System32\bash.exe belongs to WSL and may be absent.
// Invoke Git Bash as a login shell so its own /usr/bin tools are available to the test scripts.
fun gitBash(): File? {
    System.getenv("POKETTO_BASH")?.takeIf { it.isNotBlank() }?.let { return File(it) }
    if (!System.getProperty("os.name").startsWith("Windows")) {
        return null
    }
    for (directory in System.getenv("PATH").orEmpty().split(';')) {
        val git = File(directory, "git.exe")
        if (!git.isFile) continue
        for (candidate in listOf("usr/bin/bash.exe", "bin/bash.exe")) {
            val bash = File(git.parentFile.parentFile, candidate)
            if (bash.isFile) return bash
        }
    }
    return null
}

val deployScriptTests = tasks.register<Exec>("deployScriptTests") {
    group = "verification"
    description = "Runs the deployment script tests against fake docker, curl, and ssh commands."
    inputs.dir(layout.projectDirectory.dir("deploy"))
    outputs.upToDateWhen { false }
    val bash = gitBash()
    commandLine(
        bash?.absolutePath ?: "bash",
        *if (bash != null && System.getProperty("os.name").startsWith("Windows")) arrayOf("--login") else emptyArray(),
        layout.projectDirectory.file("deploy/tests/run.sh").asFile.absolutePath.replace('\\', '/'),
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 26
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        events("failed", "skipped")
    }
}

apply(from = "gradle/repository-checks.gradle.kts")

apply(from = "gradle/executor-service.gradle.kts")
apply(from = "gradle/executor-native.gradle.kts")

tasks.check {
    dependsOn(integrationTest)
    dependsOn("repoCheck")
    dependsOn(deployScriptTests)
    dependsOn(linuxStorageTest)
}
