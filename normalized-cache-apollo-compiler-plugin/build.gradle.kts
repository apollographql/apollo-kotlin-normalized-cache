import com.gradleup.librarian.gradle.Librarian

plugins {
  id("org.jetbrains.kotlin.jvm")
}

lib()

dependencies {
  compileOnly(libs.apollo.compiler)
  testImplementation(libs.apollo.compiler)
  testImplementation(gradleTestKit())
  implementation(libs.apollo.ast)
  implementation(libs.kotlin.poet)
  testImplementation(libs.kotlin.test)
}

tasks.withType(Test::class.java).configureEach {
  dependsOn("publishAllPublicationsToLocalRepository")
  dependsOn(":normalized-cache:publishAllPublicationsToLocalRepository")
}