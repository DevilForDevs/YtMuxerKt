plugins {
    kotlin("jvm") version "2.2.0"
}

group = "org.ytmuxer"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
}

tasks.register<Jar>("exportJar") {
    from(sourceSets.main.get().output)
    archiveBaseName.set("YtMuxer")
    destinationDirectory.set(layout.buildDirectory.dir("exports"))
}

kotlin {
    jvmToolchain(11)
}
