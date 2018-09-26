import java.time.{LocalDate, ZoneOffset}
import java.time.format.DateTimeFormatter

import com.amazonaws.auth.{EnvironmentVariableCredentialsProvider, InstanceProfileCredentialsProvider}
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import com.typesafe.sbt.packager.docker.Cmd
import mesosphere.maven.MavenSettings.{loadM2Credentials, loadM2Resolvers}
import mesosphere.raml.RamlGeneratorPlugin
import NativePackagerHelper.directory

import scalariform.formatter.preferences._

lazy val IntegrationTest = config("integration") extend Test

credentials ++= loadM2Credentials(streams.value.log)
resolvers ++= loadM2Resolvers(sLog.value)

resolvers += Resolver.sonatypeRepo("snapshots")
addCompilerPlugin("org.psywerx.hairyfotr" %% "linter" % "0.1.17")

cleanFiles += baseDirectory { base => base / "sandboxes" }.value

lazy val formatSettings = SbtScalariform.scalariformSettings ++ Seq(
  ScalariformKeys.preferences := FormattingPreferences()
    .setPreference(AlignArguments, false)
    .setPreference(AlignParameters, false)
    .setPreference(AlignSingleLineCaseStatements, false)
    .setPreference(CompactControlReadability, false)
    .setPreference(DoubleIndentClassDeclaration, true)
    .setPreference(DanglingCloseParenthesis, Preserve)
    .setPreference(FormatXml, true)
    .setPreference(IndentSpaces, 2)
    .setPreference(IndentWithTabs, false)
    .setPreference(MultilineScaladocCommentsStartOnFirstLine, false)
    .setPreference(PlaceScaladocAsterisksBeneathSecondAsterisk, true)
    .setPreference(PreserveSpaceBeforeArguments, true)
    .setPreference(SpacesAroundMultiImports, true)
    .setPreference(SpaceBeforeColon, false)
    .setPreference(SpaceInsideBrackets, false)
    .setPreference(SpaceInsideParentheses, false)
    .setPreference(SpacesWithinPatternBinders, true)
)

// Pass arguments to Scalatest runner:
// http://www.scalatest.org/user_guide/using_the_runner
lazy val testSettings =
  inConfig(IntegrationTest)(Defaults.testTasks) ++
  Seq(
  (coverageDir in Test) := target.value / "test-coverage",
  (coverageDir in IntegrationTest) := target.value / "integration-coverage",
  (coverageMinimum in IntegrationTest) := 58,
  testWithCoverageReport in IntegrationTest := TestWithCoveragePlugin.runTestsWithCoverage(IntegrationTest).value,

  testListeners := Seq(new PhabricatorTestReportListener(target.value / "phabricator-test-reports")),
  parallelExecution in Test := true,
  testForkedParallel in Test := true,
  testListeners := Nil, // TODO(MARATHON-8215): Remove this line
  testOptions in Test := Seq(
    Tests.Argument(
      "-u", "target/test-reports", // TODO(MARATHON-8215): Remove this line
      "-o", "-eDFG",
      "-l", "mesosphere.marathon.IntegrationTest",
      "-y", "org.scalatest.WordSpec")),
  fork in Test := true,

  fork in IntegrationTest := true,
  testOptions in IntegrationTest := Seq(
    Tests.Argument(
      "-u", "target/test-reports/integration", // TODO(MARATHON-8215): Remove this line
      "-o", "-eDFG",
      "-n", "mesosphere.marathon.IntegrationTest",
      "-y", "org.scalatest.WordSpec")),
  parallelExecution in IntegrationTest := true,
  testForkedParallel in IntegrationTest := true,
  concurrentRestrictions in IntegrationTest := Seq(Tags.limitAll(math.max(1, java.lang.Runtime.getRuntime.availableProcessors() / 2))),
  javaOptions in (IntegrationTest, test) ++= Seq(
    "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-min=2",
    "-Dakka.actor.default-dispatcher.fork-join-executor.factor=1",
    "-Dakka.actor.default-dispatcher.fork-join-executor.parallelism-max=4",
    "-Dscala.concurrent.context.minThreads=2",
    "-Dscala.concurrent.context.maxThreads=32"
  ),
  concurrentRestrictions in IntegrationTest := Seq(Tags.limitAll(math.max(1, java.lang.Runtime.getRuntime.availableProcessors() / 2)))
)

lazy val commonSettings = testSettings ++
  aspectjSettings ++ Seq(
  autoCompilerPlugins := true,
  organization := "mesosphere.marathon",
  scalaVersion := "2.11.11",
  crossScalaVersions := Seq(scalaVersion.value),
  scalacOptions in Compile ++= Seq(
    "-encoding", "UTF-8",
    "-target:jvm-1.8",
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xfuture",
    "-Xlog-reflective-calls",
    "-Xlint",
    //FIXME: CORE-977 and MESOS-7368 are filed and need to be resolved to re-enable this
    // "-Xfatal-warnings",
    "-Yno-adapted-args",
    "-Ywarn-numeric-widen",
    //"-Ywarn-dead-code", We should turn this one on soon
    "-Ywarn-inaccessible",
    "-Ywarn-infer-any",
    "-Ywarn-nullary-override",
    "-Ywarn-nullary-unit",
    //"-Ywarn-unused", We should turn this one on soon
    "-Ywarn-unused-import",
    //"-Ywarn-value-discard", We should turn this one on soon.
    "-Yclosure-elim",
    "-Ydead-code"
  ),
  // Don't need any linting, etc for docs, so gain a small amount of build time there.
  scalacOptions in (Compile, doc) := Seq("-encoding", "UTF-8", "-deprecation", "-feature", "-Xfuture"),
  javacOptions in Compile ++= Seq(
    "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint:unchecked", "-Xlint:deprecation"
  ),
  resolvers ++= Seq(
    "Typesafe Releases" at "https://repo.typesafe.com/typesafe/releases/",
    "Apache Shapshots" at "https://repository.apache.org/content/repositories/snapshots/",
    "Mesosphere Public Repo" at "https://downloads.mesosphere.com/maven"
  ),
  cancelable in Global := true,
  publishTo := Some(s3resolver.value(
    "Mesosphere Public Repo (S3)",
    s3("downloads.mesosphere.io/maven")
  )),
  s3credentials := new EnvironmentVariableCredentialsProvider() | new InstanceProfileCredentialsProvider(),

  scapegoatVersion := "1.3.0",

  coverageMinimum := 70,
  coverageFailOnMinimum := true,

  fork in run := true,
  AspectjKeys.aspectjVersion in Aspectj := "1.8.10",
  AspectjKeys.inputs in Aspectj += compiledClasses.value,
  products in Compile := (products in Aspectj).value,
  products in Runtime := (products in Aspectj).value,
  products in Compile := (products in Aspectj).value,
  AspectjKeys.showWeaveInfo := true,
  AspectjKeys.verbose := true,
  // required for AJC compile time weaving
  javacOptions in Compile += "-g",
  javaOptions in run ++= (AspectjKeys.weaverOptions in Aspectj).value,
  javaOptions in Test ++= (AspectjKeys.weaverOptions in Aspectj).value,
  git.useGitDescribe := true,
  // TODO: There appears to be a bug where uncommitted changes is true even if nothing is committed.
  git.uncommittedSignifier := None
)


lazy val packageDebianForLoader = taskKey[File]("Create debian package for active serverLoader")
lazy val packageRpmForLoader = taskKey[File]("Create rpm package for active serverLoader")

/**
  * The documentation for sbt-native-package can be foound here:
  * - General, non-vendor specific settings (such as launch script):
  *     http://sbt-native-packager.readthedocs.io/en/latest/archetypes/java_app/index.html#usage
  *
  * - Linux packaging settings
  *     http://sbt-native-packager.readthedocs.io/en/latest/archetypes/java_app/index.html#usage
  */
lazy val packagingSettings = Seq(
  bashScriptExtraDefines += IO.read((baseDirectory.value / "project" / "NativePackagerSettings" / "extra-defines.bash")),
  mappings in (Compile, packageDoc) := Seq(),
  debianChangelog in Debian := Some(baseDirectory.value / "changelog.md"),

  /* Universal packaging (docs) - http://sbt-native-packager.readthedocs.io/en/latest/formats/universal.html
   */
  universalArchiveOptions in (UniversalDocs, packageZipTarball) := Seq("-pcvf"), // Remove this line once fix for https://github.com/sbt/sbt-native-packager/issues/1019 is released
  (packageName in UniversalDocs) := { packageName.value + "-docs" + "-" + version.value },
  (topLevelDirectory in UniversalDocs) := { Some((packageName in UniversalDocs).value) },
  mappings in UniversalDocs ++= directory("docs/docs"),


  /* Docker config (http://sbt-native-packager.readthedocs.io/en/latest/formats/docker.html)
   */
  dockerBaseImage := Dependency.V.OpenJDK,
  dockerRepository := Some("mesosphere"),
  daemonUser in Docker := "nobody",
  daemonGroup in Docker := "nogroup",
  version in Docker := { "v" + (version in Compile).value },
  dockerBaseImage := "debian:jessie-slim",
  (defaultLinuxInstallLocation in Docker) := "/marathon",
  dockerCommands := {
    // kind of a work-around; we need our chown /marathon command to come after the WORKDIR command, and installation
    // commands to preceed adding the Marthon artifact so that Docker can cache them
    val (prefixCommands, restCommands) = dockerCommands.value.splitAt(dockerCommands.value.indexWhere(_.makeContent.startsWith("WORKDIR ")) + 1)

    prefixCommands ++
      Seq(Cmd("RUN",
        s"""apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv E56151BF && \\
          |apt-get update -y && \\
          |apt-get upgrade -y && \\
          |echo "deb http://ftp.debian.org/debian jessie-backports main" | tee -a /etc/apt/sources.list && \\
          |echo "deb http://repos.mesosphere.com/debian jessie-testing main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \\
          |echo "deb http://repos.mesosphere.com/debian jessie main" | tee -a /etc/apt/sources.list.d/mesosphere.list && \\
          |apt-get update && \\
          |
          |# jdk setup
          |mkdir -p /usr/share/man/man1 && \\
          |apt-get install -y openjdk-8-jdk-headless openjdk-8-jre-headless ca-certificates-java=20161107~bpo8+1 && \\
          |/var/lib/dpkg/info/ca-certificates-java.postinst configure && \\
          |ln -svT "/usr/lib/jvm/java-8-openjdk-$$(dpkg --print-architecture)" /docker-java-home && \\
          |
          |apt-get install --no-install-recommends -y --force-yes mesos=${Dependency.V.MesosDebian} && \\
          |apt-get clean && \\
          |chown nobody:nogroup /marathon""".stripMargin)) ++
      restCommands ++
      Seq(
        Cmd("ENV", "JAVA_HOME /docker-java-home"),
        Cmd("RUN", "ln -sf /marathon/bin/marathon /marathon/bin/start"),
        // Continue to keep the default user root, even though we now allow the user nobody
        Cmd("USER", "root"))
  },

  /* Linux packaging settings (http://sbt-native-packager.readthedocs.io/en/latest/formats/linux.html)
   *
   * It is expected that these task (packageDebianForLoader, packageRpmForLoader) will be called with various loader
   * configuration specified (systemv, systemd, and upstart as appropriate)
   *
   * See the command alias packageLinux for the invocation */
  packageSummary := "Scheduler for Apache Mesos",
  packageDescription := "Cluster-wide init and control system for services running on\\\n\tApache Mesos",
  maintainer := "Mesosphere Package Builder <support@mesosphere.io>",
  serverLoading := None, // We override this to build for each supported system loader in the packageLinux alias
  debianPackageDependencies in Debian := Seq("java8-runtime-headless", "lsb-release", "unzip", s"mesos (>= ${Dependency.V.MesosDebian})"),
  rpmRequirements in Rpm := Seq("coreutils", "unzip", "java >= 1:1.8.0"),
  rpmVendor := "mesosphere",
  rpmLicense := Some("Apache 2"),
  daemonStdoutLogFile := Some("marathon"),
  version in Rpm := {
    // Matches e.g. 1.5.1
    val releasePattern = """^(\d+)\.(\d+)\.(\d+)$""".r
    // Matches e.g. 1.5.1-pre-42-gdeadbeef and 1.6.0-pre-42-gdeadbeef
    val snapshotPattern = """^(\d+)\.(\d+)\.(\d+)(?:-SNAPSHOT|-pre)?-\d+-g(\w+)""".r
    version.value match {
      case releasePattern(major, minor, patch) => s"$major.$minor.$patch"
      case snapshotPattern(major, minor, patch, commit) => s"$major.$minor.$patch${LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)}git$commit"
      case v =>

        System.err.println(s"Version '$v' is not fully supported, please update the git tags.")
        v
    }
  },

  packageDebianForLoader := {
    val debianFile = (packageBin in Debian).value
    val serverLoadingName = (serverLoading in Debian).value.get
    val output = target.value / "packages" / s"${serverLoadingName}-${debianFile.getName}"
    IO.move(debianFile, output)
    streams.value.log.info(s"Moved debian ${serverLoadingName} package $debianFile to $output")
    output
  },
  packageRpmForLoader := {
    val rpmFile = (packageBin in Rpm).value
    val serverLoadingName = (serverLoading in Rpm).value.get
    val output = target.value / "packages" /  s"${serverLoadingName}-${rpmFile.getName}"
    IO.move(rpmFile, output)
    streams.value.log.info(s"Moving rpm ${serverLoadingName} package $rpmFile to $output")
    output
  })

/* Builds all the different package configurations by modifying the session config and running the packaging tasks Note
 *  you cannot build RPM packages unless if you have a functioning `rpmbuild` command (see the alien package for
 *  debian). */
addCommandAlias("packageLinux",
  ";session clear-all" +
  ";set SystemloaderPlugin.projectSettings ++ SystemdPlugin.projectSettings" +
  ";packageDebianForLoader" +
  ";packageRpmForLoader" +

  ";session clear-all" +
  ";set SystemloaderPlugin.projectSettings ++ SystemVPlugin.projectSettings ++ NativePackagerSettings.debianSystemVSettings" +
  ";packageDebianForLoader" +
  ";packageRpmForLoader" +

  ";session clear-all" +
  ";set SystemloaderPlugin.projectSettings ++ UpstartPlugin.projectSettings  ++ NativePackagerSettings.ubuntuUpstartSettings" +
  ";packageDebianForLoader"
)

lazy val `plugin-interface` = (project in file("plugin-interface"))
    .enablePlugins(GitBranchPrompt, CopyPasteDetector, BasicLintingPlugin, TestWithCoveragePlugin)
    .configs(IntegrationTest)
    .settings(commonSettings : _*)
    .settings(formatSettings : _*)
    .settings(
      name := "plugin-interface",
      libraryDependencies ++= Dependencies.pluginInterface
    )

lazy val marathon = (project in file("."))
  .configs(IntegrationTest)
  .enablePlugins(GitBranchPrompt, JavaServerAppPackaging, DockerPlugin, DebianPlugin, RpmPlugin, JDebPackaging,
    CopyPasteDetector, RamlGeneratorPlugin, BasicLintingPlugin, GitVersioning, TestWithCoveragePlugin)
  .dependsOn(`plugin-interface`)
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .settings(packagingSettings: _*)
  .settings(
    unmanagedResourceDirectories in Compile += file("docs/docs/rest-api"),
    libraryDependencies ++= Dependencies.marathon,
    sourceGenerators in Compile += (ramlGenerate in Compile).taskValue,
    scapegoatIgnoredFiles ++= Seq(s"${sourceManaged.value.getPath}/.*"),
    mainClass in Compile := Some("mesosphere.marathon.Main"),
    packageOptions in (Compile, packageBin) ++= Seq(
      Package.ManifestAttributes("Implementation-Version" -> version.value ),
      Package.ManifestAttributes("Scala-Version" -> scalaVersion.value ),
      Package.ManifestAttributes("Git-Commit" -> git.gitHeadCommit.value.getOrElse("unknown") )
    )
  )

lazy val `mesos-simulation` = (project in file("mesos-simulation"))
  .configs(IntegrationTest)
  .enablePlugins(GitBranchPrompt, CopyPasteDetector, BasicLintingPlugin, TestWithCoveragePlugin)
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .dependsOn(marathon % "compile->compile; test->test")
  .settings(
    name := "mesos-simulation"
  )

// see also, benchmark/README.md
lazy val benchmark = (project in file("benchmark"))
  .configs(IntegrationTest)
  .enablePlugins(JmhPlugin, GitBranchPrompt, CopyPasteDetector, BasicLintingPlugin, TestWithCoveragePlugin)
  .settings(commonSettings : _*)
  .settings(formatSettings: _*)
  .dependsOn(marathon % "compile->compile; test->test")
  .settings(
    testOptions in Test += Tests.Argument(TestFrameworks.JUnit),
    libraryDependencies ++= Dependencies.benchmark,
    generatorType in Jmh := "asm"
  )
