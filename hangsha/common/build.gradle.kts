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
    mavenCentral()
}



dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework:spring-web")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(kotlin("stdlib"))

    // oci sdk for storage service
    implementation("com.oracle.oci.sdk:oci-java-sdk-objectstorage:3.80.1")
    implementation("com.oracle.oci.sdk:oci-java-sdk-common-httpclient-jersey3:3.80.1")

}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
