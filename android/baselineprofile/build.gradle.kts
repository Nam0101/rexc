plugins {
  alias(libs.plugins.android.test)
  alias(libs.plugins.baselineprofile)
}

android {
  namespace = "dev.namnv.rexc.baselineprofile"
  compileSdk = 37

  defaultConfig {
    minSdk = 28
    targetSdk = 36
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  targetProjectPath = ":app"

  // A headless emulator the build downloads itself, so generating never touches a phone.
  testOptions.managedDevices.localDevices {
    create("atdApi34") {
      device = "Pixel 6"
      apiLevel = 34
      systemImageSource = "aosp-atd"
    }
  }
}

baselineProfile {
  managedDevices += "atdApi34"
  useConnectedDevices = false
}

dependencies {
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.junit)
  implementation(libs.androidx.uiautomator)
  implementation(libs.benchmark.macro.junit4)
}
