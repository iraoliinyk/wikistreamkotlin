plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
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

val integrationTestSourceSet =
    sourceSets.create("integrationTest") {
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += output + compileClasspath
    }

configurations.named("integrationTestImplementation") {
    extendsFrom(configurations["testImplementation"])
}

configurations.named("integrationTestRuntimeOnly") {
    extendsFrom(configurations["testRuntimeOnly"])
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-cassandra")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.security:spring-security-oauth2-resource-server")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(project(":lib:core"))
    implementation("io.github.microutils:kotlin-logging-jvm:2.0.11")


    // Micrometer Prometheus for metrics
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    runtimeOnly("io.netty:netty-resolver-dns-native-macos:4.2.12.Final:osx-x86_64")
    runtimeOnly("io.netty:netty-resolver-dns-native-macos:4.2.12.Final:osx-aarch_64")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.4"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-cassandra")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

// ---------------------------------------------------------------------------
// integrationTest — requires Docker daemon (Testcontainers provisions dependencies)
//   Run tests:  ./gradlew integrationTest
//   Testcontainers will automatically provision: Cassandra, Redis, Redpanda
// ---------------------------------------------------------------------------
tasks.register<Test>("integrationTest") {
    group = "ci"
    description =
        """
        Runs integration tests only.
        ⚠️  Requires Docker daemon.
        Testcontainers automatically provisions Cassandra, Redis, and Redpanda.
        """.trimIndent()
    useJUnitPlatform()
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath

    // Ordering hint: if both unitTest and integrationTest are in the task graph,
    // run unit tests first — but integrationTest does NOT depend on unitTest,
    // so it can still be executed independently in its own CI stage.
    shouldRunAfter("test")

    // Fail fast with a clear message when Docker is not available.
    doFirst {
        val dockerAvailable =
            try {
                val proc =
                    ProcessBuilder("docker", "info")
                        .redirectErrorStream(true)
                        .start()
                proc.waitFor() == 0
            } catch (_: Exception) {
                false
            }
        require(dockerAvailable) {
            "integrationTest requires Docker daemon. Please start Docker and try again."
        }
        logger.lifecycle("🐳 Docker detected — proceeding with integration tests.")
    }
}
