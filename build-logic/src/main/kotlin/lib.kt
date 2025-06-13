import com.gradleup.librarian.gradle.Librarian
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import java.net.URI

fun Project.lib() {
  Librarian.module(this)

  extensions.findByType(PublishingExtension::class.java)?.apply {
    repositories {
      maven {
        name = "local"
        url = URI("file://" + rootProject.layout.buildDirectory.dir("m2").get().asFile.path)
      }
    }
  }
}