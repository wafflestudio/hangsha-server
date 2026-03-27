plugins {
    kotlin("jvm")
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.spring")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.9")
    }
}

group = "com.team1"
version = "unspecified"

repositories {
    mavenCentral() // 1. 공용 저장소
    maven {        // 2. 와플스튜디오 사내 저장소
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
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework:spring-web")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(kotlin("stdlib"))

    implementation("com.wafflestudio.spring:spring-boot-starter-waffle-oci-vault:1.1.0")

}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
