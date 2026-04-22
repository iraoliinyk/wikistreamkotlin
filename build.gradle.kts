import org.gradle.kotlin.dsl.implementation

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native") version "0.10.3"
}

group = "com.redspace"
version = "0.0.1-SNAPSHOT"

// Java 24 used for both JVM and native-image builds
// (Kotlin doesn't yet support Java 25 target, so we match at 24)
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // Netty native DNS support (platform-specific, excluded from native build)
    runtimeOnly("io.netty:netty-resolver-dns-native-macos:4.2.12.Final:osx-x86_64")
    runtimeOnly("io.netty:netty-resolver-dns-native-macos:4.2.12.Final:osx-aarch_64")

    /* logging */
    implementation("io.github.microutils:kotlin-logging-jvm:2.0.11")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Kotlin configuration with proper JVM target alignment (Java 24)
kotlin {
    jvmToolchain(24)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// GraalVM Native Image configuration for Spring Boot AOT (optional, for nativeCompile task)
/*
graalvmNative {
    binaries {
        main {
            // Enable aggressive inlining and optimization
            buildArgs.add("--strict-image-heap")
            buildArgs.add("-H:+UnlockExperimentalVMOptions")
            buildArgs.add("-H:EnableURLProtocols=http,https")
            buildArgs.add("--enable-https")
            buildArgs.add("--enable-all-security-services")
            buildArgs.add("-H:+ReportExceptionStackTraces")
        }
    }
}
*/


