plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

group = "school.greenwood"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:3.3.1")
    implementation("io.ktor:ktor-server-netty:3.3.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.3.1")
    implementation("io.ktor:ktor-server-cors:3.3.1")
    implementation("io.ktor:ktor-server-rate-limit:3.3.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.3.1")
    testImplementation("io.ktor:ktor-client-core:3.3.1")
}

application {
    mainClass.set("school.greenwood.community.MainKt")
}



tasks.test {
    useJUnitPlatform()
}

// Le JDK local est le 26 : Kotlin plafonne au 24 — on aligne Java dessus.
java {
    sourceCompatibility = JavaVersion.VERSION_24
    targetCompatibility = JavaVersion.VERSION_24
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_24)
    }
}
