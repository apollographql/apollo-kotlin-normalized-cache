import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
  id("build.logic").apply(false)
  alias(libs.plugins.kotlin.multiplatform).apply(false)
  alias(libs.plugins.apollo).apply(false)
}

allprojects {
  tasks.withType(AbstractTestTask::class.java).configureEach {
    testLogging {
      events(TestLogEvent.STARTED, TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED)
      exceptionFormat = TestExceptionFormat.FULL
    }
  }
}
