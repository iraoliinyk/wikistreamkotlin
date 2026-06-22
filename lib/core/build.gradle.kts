plugins {
    kotlin("jvm") version "2.3.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.google.protobuf") version "0.9.4"
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

    implementation("com.google.protobuf:protobuf-java:4.28.0")
    implementation("com.google.protobuf:protobuf-kotlin:4.28.0")

    testImplementation(kotlin("test"))
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.28.0"
    }

    generateProtoTasks {
        all().forEach { task ->
            task.builtins.create("kotlin")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}
