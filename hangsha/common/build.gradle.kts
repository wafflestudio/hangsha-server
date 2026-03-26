plugins {
    kotlin("jvm")
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
