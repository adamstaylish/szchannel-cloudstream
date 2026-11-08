import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()
fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "adamstaylish/szchannel-cloudstream")
    }

    android {
        namespace = "com.szchannel"
        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val implementation by configurations
        implementation("com.github.recloudstream.cloudstream:library:4.8.0")
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.11")
        implementation("org.jsoup:jsoup:1.18.3")
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

// Appends a build-unique query parameter to the plugin download URL so that
// raw.githubusercontent.com / jsDelivr never serve a stale .cs3 after a redeploy
// (a stale file causes a fileHash mismatch and CloudStream shows "خطأ").
val addCacheBuster by tasks.registering {
    doLast {
        val json = layout.buildDirectory.file("plugins.json").get().asFile
        if (!json.exists()) {
            println("addCacheBuster: build/plugins.json not found, skipping")
            return@doLast
        }
        val tag = System.getenv("GITHUB_SHA")?.take(7) ?: System.currentTimeMillis().toString()
        val original = json.readText()
        val updated = original.replace(Regex("SZChannel\\.cs3(?=\")"), "SZChannel.cs3?v=$tag")
        if (updated != original) {
            json.writeText(updated)
            println("addCacheBuster: applied ?v=$tag")
        } else {
            println("addCacheBuster: no plugin url found to rewrite")
        }
    }
}

tasks.matching { it.name == "makePluginsJson" }.configureEach {
    finalizedBy(addCacheBuster)
}
