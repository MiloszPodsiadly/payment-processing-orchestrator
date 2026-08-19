import sbt.librarymanagement.UpdateReport

object ArchitecturePolicy {
  final case class DependencyCoordinate(organization: String, name: String) {
    override def toString: String = s"$organization:$name"
  }

  val infrastructureNeutralCompileDependencies: Set[DependencyCoordinate] =
    Set(
      DependencyCoordinate("org.scala-lang", "scala3-library_3"),
      DependencyCoordinate("org.scala-lang", "scala-library")
    )

  def compileDependencyCoordinates(report: UpdateReport): Set[DependencyCoordinate] =
    report.configurations
      .filter(configuration => configuration.configuration.name == "compile")
      .flatMap(_.modules)
      .map(module => DependencyCoordinate(module.module.organization, module.module.name))
      .toSet

  def unapprovedDependencyViolations(
      module: String,
      actual: Set[DependencyCoordinate],
      approved: Set[DependencyCoordinate]
  ): Seq[String] =
    actual
      .diff(approved)
      .toSeq
      .sortBy(_.toString)
      .map(dependency => s"$module compile dependency requires architecture approval: $dependency")

  def verifyFixtures(approved: Set[DependencyCoordinate]): Unit = {
    val unexpectedDependency = DependencyCoordinate("com.example", "new-http-client")
    val fixtureViolations =
      unapprovedDependencyViolations("domain", approved + unexpectedDependency, approved)

    if (
      fixtureViolations != Seq(
        "domain compile dependency requires architecture approval: com.example:new-http-client"
      )
    ) {
      sys.error(
        "Architecture dependency allowlist fixture did not fail for an unapproved dependency"
      )
    }
  }
}
