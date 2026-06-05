plugins {
    kotlin("jvm") version "2.3.0"
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

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.5")
    }
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    implementation("org.springframework:spring-web")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

