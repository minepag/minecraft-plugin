plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.minepag"
version = "1.0.0"

val sqliteJdbcVersion = "3.53.4.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.178-stable")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:2.14.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

// A paper-api 26.x é publicada para Java 25, mas o jar sai em bytecode 21 para rodar também no Paper 1.21.x.
configurations.compileClasspath {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25) }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all,-processing,-serial,-deprecation")
}

tasks.processResources {
    val properties = mapOf("version" to project.version, "sqliteJdbcVersion" to sqliteJdbcVersion)
    inputs.properties(properties)
    filesMatching("plugin.yml") { expand(properties) }
}

tasks.jar {
    archiveClassifier.set("plain")
}

// Nome estável (MinePag.jar): é o asset do release e o nome citado no painel e na documentação.
tasks.shadowJar {
    archiveBaseName.set("MinePag")
    archiveClassifier.set("")
    archiveVersion.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed", "skipped") }
}
