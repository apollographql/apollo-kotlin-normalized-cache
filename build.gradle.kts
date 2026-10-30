import com.gradleup.librarian.gradle.Librarian
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
  id("build.logic").apply(false)
}

Librarian.root(project)

allprojects {
  tasks.withType(AbstractTestTask::class.java).configureEach {
    testLogging {
      events(TestLogEvent.STARTED, TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED)
      exceptionFormat = TestExceptionFormat.FULL
    }
  }
}
