import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.io.File

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.ripenai"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.ripenai"
    minSdk = 26
    targetSdk = 34
    versionCode = 5
    versionName = "2.1.4"

    val questionApiUrl = providers.gradleProperty("QUESTION_API_URL").orNull.orEmpty()
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
    buildConfigField("String", "QUESTION_API_URL", "\"$questionApiUrl\"")

    manifestPlaceholders["allowCleartext"] = false

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Use the default debug signing config automatically provided by Gradle
      manifestPlaceholders["allowCleartext"] = true
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

googleServices {
  missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN
}


// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  implementation("org.tensorflow:tensorflow-lite:2.14.0") {
      exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
  }
  implementation(files("libs/tensorflow-lite-api-patched.aar"))
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

tasks.register("patchTfLiteApi") {
    val aarUrl = "https://repo1.maven.org/maven2/org/tensorflow/tensorflow-lite-api/2.14.0/tensorflow-lite-api-2.14.0.aar"
    val libsDir = file("libs")
    val patchedAar = file("libs/tensorflow-lite-api-patched.aar")

    inputs.property("url", aarUrl)
    outputs.file(patchedAar)

    doLast {
        if (!libsDir.exists()) {
            libsDir.mkdirs()
        }
        val tempFile = File(temporaryDir, "tflite-api.aar")
        println("Downloading $aarUrl...")
        URL(aarUrl).openStream().use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        println("Patching AndroidManifest.xml inside AAR...")
        ZipFile(tempFile).use { zipFile ->
            ZipOutputStream(patchedAar.outputStream()).use { zos ->
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    zipFile.getInputStream(entry).use { inputStream ->
                        if (entry.name == "AndroidManifest.xml") {
                            val content = inputStream.bufferedReader().readText()
                            val patchedContent = content.replace("package=\"org.tensorflow.lite\"", "package=\"org.tensorflow.lite.api\"")
                            zos.putNextEntry(ZipEntry(entry.name))
                            zos.write(patchedContent.toByteArray(Charsets.UTF_8))
                            zos.closeEntry()
                        } else {
                            zos.putNextEntry(ZipEntry(entry.name))
                            inputStream.copyTo(zos)
                            zos.closeEntry()
                        }
                    }
                }
            }
        }
        println("Patched AAR saved to ${patchedAar.absolutePath}")
    }
}

tasks.named("preBuild") {
    dependsOn("patchTfLiteApi")
}
