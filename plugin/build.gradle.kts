plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
}

group = "com.crystalai"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 1.20.5+ servers run Mojang mappings: ship the dev jar as is, no reobfuscation.
paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION

tasks {
    test {
        useJUnitPlatform()
    }
    compileJava {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(21)
    }
    processResources {
        // protocol/schema.json is the single source of truth, shared with the Python trainer
        from(rootProject.layout.projectDirectory.file("../protocol/schema.json")) {
            into("protocol")
        }
        filteringCharset = Charsets.UTF_8.name()
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
    // Copies the plugin jar into ../server/plugins
    register<Copy>("deployToServer") {
        group = "crystalai"
        description = "Builds the plugin and copies it into ../server/plugins"
        from(jar.flatMap { it.archiveFile })
        into(rootProject.layout.projectDirectory.dir("../server/plugins"))
        rename { "CrystalAI.jar" }
    }
}
