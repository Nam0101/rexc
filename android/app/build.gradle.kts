import com.diffplug.spotless.LineEnding
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dagger.hilt.android)
  alias(libs.plugins.ksp)
  alias(libs.plugins.baselineprofile)
  alias(libs.plugins.spotless)
  alias(libs.plugins.detekt)
}

android {
  namespace = "dev.namnv.rexc"
  compileSdk = 37

  defaultConfig {
    applicationId = "dev.namnv.rexc"
    minSdk = 28
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
    ndk { abiFilters += listOf("arm64-v8a") }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  packaging {
    jniLibs.useLegacyPackaging = false
  }
}

kotlin {
  jvmToolchain(21)
}

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_21)
    freeCompilerArgs.addAll(
      "-opt-in=kotlin.RequiresOptIn",
      "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
      "-opt-in=kotlinx.coroutines.FlowPreview",
    )
  }
}

// Same bar as the showcase app: `./gradlew spotlessCheck detekt testDebugUnitTest`.
spotless {
  kotlin {
    target("src/**/*.kt")
    // The UniFFI bindings are generated; formatting them only makes noise.
    targetExclude("src/main/java/uniffi/**/*.kt", "**/build/**/*.kt")
    ktlint(libs.versions.ktlint.get())
    trimTrailingWhitespace()
    leadingTabsToSpaces(4)
    endWithNewline()
  }
  format("xml") {
    target("src/**/res/**/*.xml")
    trimTrailingWhitespace()
    leadingTabsToSpaces(4)
    endWithNewline()
    lineEndings = LineEnding.UNIX
  }
  kotlinGradle {
    target("*.gradle.kts")
    ktlint(libs.versions.ktlint.get())
    trimTrailingWhitespace()
    endWithNewline()
  }
}

detekt {
  source.setFrom(files("src/main/java/dev", "src/test/java"))
  config.from(rootProject.file("config/detekt/detekt.yml"))
  allRules = true
  buildUponDefaultConfig = true
  parallel = true
  autoCorrect = true
}

dependencies {
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.material3)
  implementation(libs.compose.material.icons)
  implementation(libs.compose.animation)
  implementation(libs.compose.ui.tooling.preview)
  debugImplementation(libs.compose.ui.tooling)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.hilt.navigation.compose)
  implementation(libs.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.collections.immutable)
  implementation(libs.dagger.hilt.android)
  ksp(libs.dagger.hilt.compiler)
  implementation(libs.timber)
  implementation(libs.androidx.profileinstaller)
  baselineProfile(project(":baselineprofile"))
  implementation("${libs.jna.get()}@aar")

  testImplementation(libs.junit)
  testImplementation(libs.kotlin.test.junit)
  testImplementation(libs.coroutines.test)
  testImplementation(libs.turbine)
  testImplementation(libs.mockk)
}
