val frontendDirectory = layout.projectDirectory.dir("frontend")
val npmCommand = if (System.getProperty("os.name").startsWith("Windows")) listOf("cmd", "/c", "npm.cmd") else listOf("npm")

val frontendRuntimeCheck = tasks.register("frontendRuntimeCheck") {
    group = "verification"
    description = "Requires the pinned frontend Node.js and npm versions."
    doLast {
        val nodeVersion = providers.exec { commandLine("node", "--version") }.standardOutput.asText.get().trim()
        val npmVersion = providers.exec { commandLine(npmCommand + "--version") }.standardOutput.asText.get().trim()
        check(nodeVersion == "v24.19.0" && npmVersion == "12.0.2") {
            "Frontend checks require Node.js 24.19.0 and npm 12.0.2; found $nodeVersion / $npmVersion"
        }
    }
}

val frontendInstall = tasks.register<Exec>("frontendInstall") {
    group = "build"
    description = "Installs the locked frontend dependency graph."
    dependsOn(frontendRuntimeCheck)
    workingDir(frontendDirectory)
    environment("NEXT_TELEMETRY_DISABLED", "1")
    inputs.files(frontendDirectory.file("package.json"), frontendDirectory.file("package-lock.json"), frontendDirectory.file(".npmrc"))
    outputs.dir(frontendDirectory.dir("node_modules"))
    commandLine(npmCommand + listOf("ci", "--ignore-scripts"))
}

val frontendCheck = tasks.register<Exec>("frontendCheck") {
    group = "verification"
    description = "Checks frontend formatting, types, behavior, and the production build."
    dependsOn(frontendInstall)
    workingDir(frontendDirectory)
    environment("NEXT_TELEMETRY_DISABLED", "1")
    commandLine(npmCommand + listOf("run", "check"))
}

tasks.named("check") { dependsOn(frontendCheck) }
