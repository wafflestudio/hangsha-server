plugins {
    kotlin("jvm")
    id("io.spring.dependency-management") version "1.1.7"
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.9")
    }
}

group = "com.team1"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
