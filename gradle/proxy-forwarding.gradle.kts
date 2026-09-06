val proxyForwardingCheck = tasks.register<Exec>("proxyForwardingCheck") {
    group = "verification"
    description = "Verifies trusted Caddy forwarding and real login buckets with isolated Docker clients and PostgreSQL."
    dependsOn("stageAcceptanceRuntime")
    outputs.upToDateWhen { false }
    timeout.set(java.time.Duration.ofMinutes(25))
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
        layout.projectDirectory.file("deploy/tests/validate_proxy_forwarding.py").asFile.absolutePath)
}
tasks.named("check") { dependsOn(proxyForwardingCheck) }
