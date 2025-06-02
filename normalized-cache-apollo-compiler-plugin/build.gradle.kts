import com.gradleup.librarian.gradle.Librarian

plugins {
  id("org.jetbrains.kotlin.jvm")
}

Librarian.module(project)

dependencies {
  compileOnly(libs.apollo.compiler)
  implementation(libs.apollo.ast)
  implementation(libs.kotlin.poet)
  testImplementation(libs.kotlin.test)
}
