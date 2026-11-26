import java.nio.file.Files
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

group = "ru.gureev"

version = "1.1.2"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.swing)
    implementation(libs.gson)
    implementation(libs.re2j)
    implementation(libs.bundles.markdown)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.engine)
    testImplementation(libs.coroutines.test)
}

kotlin { jvmToolchain(17) }

tasks.test {
    useJUnitPlatform()
    systemProperty("test.runtimeClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}

tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
    exclude("**/__pycache__/**", "**/*.pyc", "**/*.pyo")
}

compose.desktop {
    application {
        mainClass = "MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xmx2048m")

        nativeDistributions {
            outputBaseDir.set(layout.buildDirectory.dir("compose/binaries-${project.version}"))
            appResourcesRootDir.set(layout.projectDirectory.dir("desktop-resources"))
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "DroidDebugHunterByCompose"
            packageVersion = "1.1.2"
            description = "Android debugging and traffic inspection"
            vendor = "DroidDebugHunter"
            modules("java.desktop", "java.net.http", "java.prefs", "jdk.unsupported")
        }
    }
}

// A rebuild can replace build/libs/*.jar while an older JVM still loads its coroutine classes.
val developmentRunSnapshot = objects.directoryProperty()
val cleanDevelopmentRunSnapshot =
    tasks.register("cleanDevelopmentRunSnapshot") {
        doLast {
            developmentRunSnapshot.orNull?.asFile?.canonicalFile?.let { snapshot ->
                val temporaryRoot = file(System.getProperty("java.io.tmpdir")).canonicalFile
                check(snapshot.parentFile == temporaryRoot && snapshot.name.startsWith("ddhc-run-"))
                check(project.delete(snapshot)) {
                    "Cannot remove development runtime snapshot: $snapshot"
                }
            }
        }
    }

tasks
    .withType<JavaExec>()
    .matching { it.name == "run" }
    .configureEach {
        val applicationJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
        finalizedBy(cleanDevelopmentRunSnapshot)
        doFirst {
            val source = applicationJar.get().asFile.canonicalFile
            val snapshot = Files.createTempDirectory("ddhc-run-").toFile()
            developmentRunSnapshot.set(snapshot)
            val copy = source.copyTo(snapshot.resolve(source.name))
            classpath = files(classpath.files.map { if (it.canonicalFile == source) copy else it })
        }
    }

// Native resources must retain execution permissions after the staging copy on Unix hosts.
tasks
    .matching { it.name == "prepareAppResources" }
    .configureEach {
        doLast {
            if (!System.getProperty("os.name").startsWith("Windows", true)) {
                outputs.files.asFileTree
                    .matching {
                        include(
                            "**/tools/platform-tools/adb",
                            "**/tools/platform-tools/fastboot",
                            "**/tools/python/bin/python*",
                        )
                    }
                    .forEach { it.setExecutable(true, false) }
            }
        }
    }
