import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

// composite build 依赖替换：宿主 includeBuild 时按 group:name 自动替换远端坐标
group = "com.apex.browser"
version = "2.0.0"

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.None(), sourcesJar = true))
    coordinates(
        groupId = "com.apex.browser",
        artifactId = "browser-core",
        version = "2.0.0",
    )
    pom {
        name.set("Apex Kit Browser :: Core")
        description.set("Pure-Kotlin DOM model, parser and injection scripts for WebView automation (zero Android deps)")
        url.set("https://github.com/Ultra-Guru/apex-kit-browser")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer { id.set("Ultra-Guru") }
        }
        scm {
            connection.set("scm:git:git@github.com:Ultra-Guru/apex-kit-browser.git")
            url.set("https://github.com/Ultra-Guru/apex-kit-browser")
        }
    }
}
