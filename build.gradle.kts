import org.gradle.kotlin.dsl.implementation

plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.5"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.redspace"
version = "0.0.1-SNAPSHOT"

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
	implementation ("org.springframework.boot:spring-boot-starter-json")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation ("org.springframework.boot:spring-boot-starter-webflux")

//	runtimeOnly("io.netty:netty-resolver-dns-native-macos::osx-aarch_64")
//	runtimeOnly("io.netty:netty-resolver-dns-native-macos::osx-x86_64")

	/* logging */
	implementation("io.github.microutils:kotlin-logging-jvm:2.0.11")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
