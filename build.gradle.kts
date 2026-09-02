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
val postgresTestImage = providers.gradleProperty("poketto.postgres.image")
    .orElse("poketto-postgres:17.11-zhparser")

dependencies {
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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

val buildPostgresTestImage = tasks.register<Exec>("buildPostgresTestImage") {
    group = "verification"
    description = "Builds the PostgreSQL 17 image used by integration tests."
    inputs.dir(layout.projectDirectory.dir("infra/postgres"))
    outputs.upToDateWhen { false }
    commandLine(
        "docker",
        "build",
        "--tag",
        postgresTestImage.get(),
        layout.projectDirectory.dir("infra/postgres").asFile.absolutePath,
    )
}

val integrationTest = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs Docker-backed integration tests."
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
    dependsOn(buildPostgresTestImage)
    systemProperty("poketto.postgres.image", postgresTestImage.get())
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

tasks.check {
    dependsOn(integrationTest)
    dependsOn("repoCheck")
}
