plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.central.publish)
    `maven-publish`
}

group = "es.bugfix"
version = providers.environmentVariable("VERSION").getOrElse("0.1.0-SNAPSHOT") // set from the v* tag by publish.yml

android {
    namespace = "es.bugfix.client"
    compileSdk = 37
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.jvmArgs("--enable-native-access=ALL-UNNAMED") }
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// No runtime dependencies: a crash reporter shouldn't bring anything that can fail during a crash.
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
}

publishing {
    publications {
        create<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }

            groupId = "es.bugfix"
            artifactId = "bugfixes-kotlin"
            version = project.version.toString()

            pom {
                name.set("Bugfixes Kotlin SDK")
                description.set("Android SDK that sends crashes, ANRs, errors and logs to Bugfixes")
                url.set("https://github.com/BugFixes/bugfixes-kotlin")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("Keloran")
                        name.set("Keloran")
                        email.set("keloran@chewedfeed.com")
                    }
                }

                scm {
                    url.set("https://github.com/BugFixes/bugfixes-kotlin")
                    connection.set("scm:git:git://github.com/BugFixes/bugfixes-kotlin.git")
                    developerConnection.set("scm:git:ssh://git@github.com/BugFixes/bugfixes-kotlin.git")
                }
            }
        }
    }
}

mavenPublishing {
    publishingType = moe.karla.maven.publishing.MavenPublishingExtension.PublishingType.USER_MANAGED

    url = "https://github.com/BugFixes/bugfixes-kotlin"
    developer("Keloran", "keloran@chewedfeed.com")
}
