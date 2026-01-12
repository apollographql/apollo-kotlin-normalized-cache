
import com.apollographql.apollo.annotations.ApolloExperimental
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
  id("org.jetbrains.kotlin.android")
  id("com.android.library")
  id("com.apollographql.apollo")
}

android {
  namespace = "com.apollographql.apollo.cache.test"
  compileSdk = 36

  defaultConfig {
    minSdk = 23
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testInstrumentationRunnerArguments["clearPackageData"] = "false"
  }

  testOptions.targetSdk = 30

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

java {
  toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.withType(KotlinJvmCompile::class.java).configureEach {
  compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
  implementation(libs.apollo.runtime)

  implementation("com.apollographql.cache:normalized-cache-sqlite")

  androidTestImplementation("androidx.test.ext:junit:1.3.0")
  androidTestImplementation("androidx.test:runner:1.7.0")
  androidTestImplementation("com.apollographql.cache:test-utils")
  androidTestImplementation(libs.kotlin.test)
}

apollo {
  service("service") {
    packageName.set("test")

    @OptIn(ApolloExperimental::class)
    plugin("com.apollographql.cache:normalized-cache-apollo-compiler-plugin") {
      argument("com.apollographql.cache.packageName", packageName.get())
    }
  }
}

