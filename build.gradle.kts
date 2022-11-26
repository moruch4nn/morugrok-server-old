import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import net.researchgate.release.ReleaseExtension

plugins {
    kotlin("jvm") version "1.7.20"
    application
    id("net.researchgate.release") version "3.0.2"
}

group = "dev.mr3n"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}


tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClass.set("dev.mr3n.morugrok.MainKt")
}

tasks.withType<Jar> {
    from(configurations.compileClasspath.get().map { if (it.isDirectory()) it else zipTree(it) })
    manifest {
        attributes["Main-Class"] = "dev.mr3n.morugrok.MainKt"
    }
}