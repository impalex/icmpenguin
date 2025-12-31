/*
 * Copyright (c) 2025 Alexander Yaburov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jreleaser.model.Active

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.dokka)
    id("maven-publish")
    alias(libs.plugins.jreleaser)
}

group = "me.impa"
version = "1.0.0-rc.1"
description = "Android ping & traceroute library with native performance"

android {
    namespace = "${project.group}.icmpenguin"
    compileSdk = 36

    defaultConfig {

        minSdk = 24
        ndkVersion = "28.1.13356709"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

buildscript {
    dependencies {
        classpath(libs.dokka.base)
    }

}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dokka {
    pluginsConfiguration.html {
        customStyleSheets.from("../assets/logo-styles.css")
        customAssets.from("../assets/logo_dokka.svg")
        footerMessage = "© 2025 Alexander Yaburov"
    }
    dokkaPublications.html {
        outputDirectory.set(layout.buildDirectory.dir("docs"))
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.annotation.jvm)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

publishing {
    repositories {
        maven {
            setUrl(layout.buildDirectory.dir("staging-deploy"))
        }
    }
    publications {
        register<MavenPublication>("release") {

            groupId = project.group as String
            artifactId = project.name
            version = project.version as String
            pom {
                name = project.name
                description = project.description ?: project.name
                url = "https://github.com/impalex/icmpenguin"
                licenses {
                    license {
                        name = "Apache 2.0"
                        url = "https://github.com/impalex/icmpenguin/blob/main/LICENSE"
                    }
                }
                developers {
                    developer {
                        id = "impa"
                        name = "Alexander Yaburov"
                        email = "dev@impa.me"
                    }
                }
                scm {
                    connection = "scm:git:git://github.com/impalex/icmpenguin.git"
                    developerConnection = "scm:git:ssh://github.com:impalex/icmpenguin.git"
                    url = "https://github.com/impalex/icmpenguin"
                }
            }
            afterEvaluate {
                from(components["release"])
            }
        }
    }
    repositories {
        mavenLocal()
    }
}

jreleaser {
    gitRootSearch = true
    project {
        inceptionYear = "2025"
        author("Alexander Yaburov")
    }
    release {
        github {
            enabled = true
            skipRelease = true
            skipTag = true
        }
    }
    signing {
        active = Active.ALWAYS
        armored = true
        verify = true
    }
    deploy {
        maven {
            mavenCentral.create("sonatype") {
                active = Active.ALWAYS
                url = "https://central.sonatype.com/api/v1/publisher"
                setAuthorization("Basic")
                sign = true
                checksums = true
                sourceJar = true
                javadocJar = true
                applyMavenCentralRules = false
                retryDelay = 60
                stagingRepository(layout.buildDirectory.dir("staging-deploy").get().asFile.path)
            }
        }
    }
}

