import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "ru.gureev"

version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.google.re2j:re2j:1.7")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin { jvmToolchain(17) }

tasks.test {
    useJUnitPlatform()
    systemProperty("test.runtimeClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}

compose.desktop {
    application {
        mainClass = "MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xmx2048m")

        nativeDistributions {
            appResourcesRootDir.set(layout.projectDirectory.dir("desktop-resources"))
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "DroidDebugHunterByCompose"
            packageVersion = "1.0.0"
            modules("java.desktop", "java.net.http", "java.prefs", "jdk.unsupported")
        }
    }
}
