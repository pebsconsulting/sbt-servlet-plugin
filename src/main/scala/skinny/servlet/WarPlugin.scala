package skinny.servlet

import sbt.{ `package` => _, _ }
import Def.Initialize
import Keys._
import ServletKeys._
import _root_.sbt.Defaults.{ packageTaskSettings, inDependencies }

object WarPlugin extends Plugin {

  private def copyFlat(sources: Iterable[File], destinationDirectory: File): Set[File] = {
    IO.copy(sources.map { source =>
      (source.asFile, destinationDirectory / source.getName)
    })
  }

  def packageWarTask(classpathConfig: Configuration): Initialize[Task[Seq[(File, String)]]] =
    (
      classesAsJar,
      name,
      version,
      webappResources,
      target,
      fullClasspath in classpathConfig in packageWar,
      excludeFilter,
      warPostProcess,
      streams
    ) map {
        (classesAsJar, name, version, webappResources, target, fullClasspath, filter, postProcess, s) =>

          val classpath = fullClasspath.map(_.data)
          val warPath = target / "webapp"
          val log = s.log.asInstanceOf[AbstractLogger]
          val webInfPath = warPath / "WEB-INF"
          val webLibDirectory = webInfPath / "lib"
          val classesTargetDirectory = webInfPath / "classes"

          val (libs, directories) = classpath.toList.filter(_.exists).partition(!_.isDirectory)
          val wcToCopy = for {
            dir <- webappResources.reverse
            file <- (dir ** (-filter)).get
            target = Path.rebase(dir, warPath)(file).get
          } yield (file, target)

          if (log.atLevel(Level.Debug)) {
            directories foreach { d =>
              log.debug(" Copying the contents of directory " + d + " to " + classesTargetDirectory)
            }
          }

          val copiedWebapp = IO.copy(wcToCopy, overwrite = true, preserveLastModified = true)
          val copiedLibs = copyFlat(libs, webLibDirectory)

          val toRemove = scala.collection.mutable.HashSet((warPath ** "*").get.toSeq: _*)
          if (classesAsJar) {
            val classesAndResources = for {
              dir <- directories.reverse
              file <- (dir ** (-filter)).get
              target = Path.rebase(dir, "")(file).get
            } yield (file, target)
            val classesAndResourcesJar = webLibDirectory / (name + "-" + version + ".jar")
            IO.jar(classesAndResources, classesAndResourcesJar, new java.util.jar.Manifest)
            toRemove -= classesAndResourcesJar
          } else {
            val classesAndResources = for {
              dir <- directories.reverse
              file <- (dir ** (-filter)).get
              target = Path.rebase(dir, classesTargetDirectory)(file).get
            } yield (file, target)
            val copiedClasses = IO.copy(classesAndResources, overwrite = true, preserveLastModified = true)
            toRemove --= copiedClasses
          }

          toRemove --= copiedWebapp
          toRemove --= copiedLibs

          val (dirs, files) = toRemove.toList.partition(_.isDirectory)
          if (log.atLevel(Level.Debug)) {
            files.foreach(r => log.debug("Pruning file " + r))
          }
          IO.delete(files)
          IO.deleteIfEmpty(dirs.toSet)
          postProcess(warPath)
          warPath ** (-filter) pair (relativeTo(warPath) | flat)
      }

  def warSettings0(classpathConfig: Configuration): Seq[Setting[_]] = {
    packageTaskSettings(packageWar, packageWarTask(classpathConfig)) ++
      Seq(
        webappResources := Seq(sourceDirectory.value / "webapp"),
        // TODO: inDependencies deprecation - Use the new <key>.all(<ScopeFilter>) API
        webappResources ++= inDependencies(webappResources, ref => Nil, false).apply(_.flatten).value,
        artifact in packageWar := Artifact(moduleName.value, "war", "war"),
        publishArtifact in packageBin := false,
        warPostProcess := { _ => () },
        classesAsJar := false,
        `package` := (packageWar dependsOn packageWebapp).value,
        packageWebapp := packageWarTask(classpathConfig).value
      )
  }

  private def warSettings0: Seq[Setting[_]] = warSettings0(DefaultClasspathConf)

  def globalWarSettings: Seq[Setting[_]] = {
    Seq(addArtifact(artifact in (DefaultConf, packageWar), packageWar in DefaultConf): _*)
  }

  private def warSettings(classpathConfig: Configuration): Seq[Setting[_]] = {
    inConfig(DefaultConf)(warSettings0(classpathConfig)) ++ globalWarSettings
  }

  private def warSettings: Seq[Setting[_]] = warSettings(DefaultClasspathConf)

}
