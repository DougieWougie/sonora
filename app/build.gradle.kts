// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "org.sonora"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.sonora"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug { enableUnitTestCoverage = true }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // JUnit 5 for the plain tests; the vintage engine runs the JUnit 4 Compose/Robolectric ones.
        unitTests.all { it.useJUnitPlatform() }
    }

    // Compose UI tests live in one place and run both on the JVM (Robolectric) and on a device.
    sourceSets {
        getByName("test").kotlin.directories.add("src/sharedTest/kotlin")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/kotlin")
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Material 3 Expressive, shared element transitions and Media3 are opt-in APIs by design.
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.animation.ExperimentalSharedTransitionApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.coroutines.guava)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kxml2)
    testImplementation(libs.xmlpull)

    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Compose's transitive Espresso predates Android 17 (InputManager.getInstance is gone).
    androidTestImplementation(libs.androidx.test.espresso.core)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Robolectric loads classes through its own classloader; without this JaCoCo reports them as uncovered.
afterEvaluate {
    tasks.withType<Test>().configureEach {
        extensions.findByType(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java)?.apply {
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
    }
}

// Fails when line coverage of the hand-written code drops below the floor; raise it as coverage improves.
val minimumLineCoverage = 70.0

// Room, Hilt and Dagger output would otherwise count towards (and flatter) the figure.
val generatedClass = Regex(
    "_Impl|/Hilt_|_HiltModules|_GeneratedInjector|_Factory|_MembersInjector|^dagger/|hilt_aggregated_deps|/BuildConfig|ComposableSingletons",
)

val verifyCoverage by tasks.registering {
    group = "verification"
    description = "Fails if debug unit-test line coverage of hand-written code is below $minimumLineCoverage%."
    dependsOn("createDebugUnitTestCoverageReport")
    val report = layout.buildDirectory.file("reports/coverage/test/debug/report.xml")
    doLast {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val classes = factory.newDocumentBuilder().parse(report.get().asFile).getElementsByTagName("class")
        var covered = 0.0
        var missed = 0.0
        for (i in 0 until classes.length) {
            val cls = classes.item(i) as org.w3c.dom.Element
            if (generatedClass.containsMatchIn(cls.getAttribute("name"))) continue
            val children = cls.childNodes
            for (c in 0 until children.length) {
                val counter = children.item(c) as? org.w3c.dom.Element ?: continue
                if (counter.tagName == "counter" && counter.getAttribute("type") == "LINE") {
                    covered += counter.getAttribute("covered").toDouble()
                    missed += counter.getAttribute("missed").toDouble()
                }
            }
        }
        val percent = 100 * covered / (covered + missed)
        logger.lifecycle("Line coverage (hand-written): %.1f%% (minimum %.1f%%)".format(percent, minimumLineCoverage))
        check(percent >= minimumLineCoverage) {
            "Line coverage %.1f%% is below the %.1f%% minimum".format(percent, minimumLineCoverage)
        }
    }
}

tasks.named("check") { dependsOn(verifyCoverage) }
