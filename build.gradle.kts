import org.gradle.kotlin.dsl.implementation

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"

    id("dev.detekt") version "2.0.0-alpha.2" // or latest published 2.x alpha

    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
}

group = "com.redspace"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-cassandra")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.security:spring-security-oauth2-resource-server")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    runtimeOnly("io.netty:netty-resolver-dns-native-macos:4.2.12.Final:osx-x86_64")
    runtimeOnly("io.netty:netty-resolver-dns-native-macos:4.2.12.Final:osx-aarch_64")

    // logging
    implementation("io.github.microutils:kotlin-logging-jvm:2.0.11")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-test-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-webtestclient")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.4"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-cassandra")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    resources.srcDir("src/integrationTest/resources")
    compileClasspath += sourceSets["main"].output + sourceSets["test"].output
    runtimeClasspath += output + compileClasspath
}

configurations.named("integrationTestImplementation") {
    extendsFrom(configurations["testImplementation"])
}

configurations.named("integrationTestRuntimeOnly") {
    extendsFrom(configurations["testRuntimeOnly"])
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

ktlint {
    version.set("1.5.0")
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("config/detekt/detekt.yml"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<Test>("test") {
    description = "Runs unit tests (default Gradle test task)."
}

// ---------------------------------------------------------------------------
// unitTest — fast unit-test suite, no external services needed
// ---------------------------------------------------------------------------
tasks.register<Test>("unitTest") {
    group = "verification"
    description = "Runs unit tests only. No Docker or external services required."
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
}

// ---------------------------------------------------------------------------
// ciTest — CI stage task: runs the full unit-test suite
//   Usage: ./gradlew ciTest
// ---------------------------------------------------------------------------
tasks.register("ciTest") {
    group = "ci"
    description = "CI stage: runs all unit tests. No external services required."
    dependsOn("unitTest")
    doLast {
        logger.lifecycle("✅ ciTest passed: all unit tests reported no failures.")
    }
}

// ---------------------------------------------------------------------------
// integrationTest — requires Docker + running Cassandra (and Redis) containers
//   Start services first:  docker compose up -d
//   Run tests:             ./gradlew integrationTest
//   Stop services:         docker compose down
// ---------------------------------------------------------------------------
tasks.register<Test>("integrationTest") {
    group = "ci"
    description = """
        Runs integration tests only.
        ⚠️  Requires Docker and running Cassandra/Redis containers.
        Start them with: docker compose up -d
    """.trimIndent()
    useJUnitPlatform()
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath

    // Ordering hint: if both unitTest and integrationTest are in the task graph,
    // run unit tests first — but integrationTest does NOT depend on unitTest,
    // so it can still be executed independently in its own CI stage.
    shouldRunAfter("unitTest", "ciTest")

    // Fail fast with a clear message when Docker is not available.
    doFirst {
        val dockerAvailable = try {
            val proc = ProcessBuilder("docker", "info")
                .redirectErrorStream(true)
                .start()
            proc.waitFor() == 0
        } catch (_: Exception) {
            false
        }
        require(dockerAvailable) {
            "integrationTest requires Docker. Please start Docker and run: docker compose up -d"
        }
        logger.lifecycle("🐳 Docker detected — proceeding with integration tests.")
    }
}

// ---------------------------------------------------------------------------
// lintKotlin — aggregates detekt (static analysis) + ktlint (formatting)
//   Usage: ./gradlew lintKotlin
//   Runs independently; no test or Docker dependency.
// ---------------------------------------------------------------------------
tasks.register("lintKotlin") {
    group = "ci"
    description = "CI stage: runs detekt static analysis and ktlint formatting checks. Fails on any violation."

    dependsOn("detekt", "ktlintCheck")

    // Lint is independent of test tasks — ordering only applies when tasks share the same run.
    mustRunAfter("ciTest", "integrationTest")

    doLast {
        logger.lifecycle("✅ lintKotlin passed: detekt and ktlint reported no violations.")
    }
}

// Always show full stacktraces when detekt or ktlint tasks run,
// so violation locations and rule names are visible in CI logs.
tasks.matching { it.name == "detekt" || it.name.startsWith("ktlint") }.configureEach {
    doFirst {
        gradle.startParameter.showStacktrace = ShowStacktrace.ALWAYS_FULL
    }
}
