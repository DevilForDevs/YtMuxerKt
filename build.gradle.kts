plugins {
    kotlin("jvm") version "2.2.0"
}

group = "org.ytmuxer"
version = "1.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")
}

tasks.register<Jar>("exportJar") {
    from(sourceSets.main.get().output)
    archiveBaseName.set("YtMuxer")
    destinationDirectory.set(layout.buildDirectory.dir("exports"))
}

kotlin {
    jvmToolchain(11)
}
