plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "3.5.9"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.team1"
description = "hangsha"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/wafflestudio/spring-waffle")
        credentials {
            username = "wafflestudio"
            password = findProperty("gpr.key") as String?
                ?: System.getenv("GITHUB_TOKEN")
                ?: runCatching {
                    ProcessBuilder("gh", "auth", "token")
                        .start()
                        .inputStream
                        .bufferedReader()
                        .readText()
                        .trim()
                }.getOrDefault("")
        }
    }
}

dependencies {
    implementation(project(":common"))
    implementation("com.wafflestudio.spring:spring-boot-starter-waffle-oci-vault:1.1.0")
    implementation("com.oracle.oci.sdk:oci-java-sdk-objectstorage:3.80.1")
    // TODO: common의 dependency를 root api, batch에 공유하는 과정에서 문제 -> 나중에 해결하기.

    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.15")
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.jsonwebtoken:jjwt-api:0.11.5")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    runtimeOnly("com.mysql:mysql-connector-j")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.11.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.11.5")
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation(kotlin("stdlib"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
