import java.util.Properties

plugins { java }
group = "dev.entitybridge"
val versions = Properties().apply { file("../build-identity/version.properties").inputStream().use(::load) }
version = versions.getProperty("version")
repositories { mavenCentral(); maven("https://repo.papermc.io/repository/maven-public/") }
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly("com.google.code.gson:gson:2.11.0")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)); withSourcesJar() }
tasks.compileJava {
    listOf("build/BuildIdentity", "stewardship/ProtectedAreaPolicy", "stewardship/ProtectedAreaHomePermit",
        "stewardship/EntityDispositionPolicy", "farm/ManagedFarmPolicy").forEach {
        source(file("../core/src/main/java/dev/entity/core/$it.java"))
    }
    options.encoding = "UTF-8"
    options.release.set(21)
}
val identity = file("../build/identity/entity2-build-identity.properties")
val generateIdentity by tasks.registering(Exec::class) {
    workingDir(file(".."))
    commandLine("pwsh", "-NoProfile", "-File", file("../build-identity/Generate-Entity2BuildIdentity.ps1").absolutePath,
        "-RepositoryRoot", file("..").absolutePath, "-RequireClean")
    outputs.file(identity)
    outputs.upToDateWhen { false }
}
tasks.processResources {
    dependsOn(generateIdentity)
    inputs.file(identity)
    from(identity)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
tasks.jar {
    archiveBaseName.set("EntityBridge-v2")
    manifest.attributes["paperweight-mappings-namespace"] = "mojang+yarn"
}
