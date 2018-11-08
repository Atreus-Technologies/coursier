package coursier
package cli

import java.io.File

import caseapp._
import coursier.cli.options.FetchOptions
import coursier.core.Classifier

final class Fetch(options: FetchOptions, args: RemainingArgs) {

  val helper = new Helper(options.common, args.all, ignoreErrors = options.artifactOptions.force)

  val default = options.artifactOptions.default0(options.common.classifier0)

  val files0 = helper.fetch(
    sources = options.artifactOptions.sources,
    javadoc = options.artifactOptions.javadoc,
    default = default,
    artifactTypes = options.artifactOptions.artifactTypes(options.common.classifier0)
  )

  // Some progress lines seem to be scraped without this.
  Console.out.flush()

}

object Fetch extends CaseApp[FetchOptions] {

  def apply(options: FetchOptions, args: RemainingArgs): Fetch =
    new Fetch(options, args)

  def run(options: FetchOptions, args: RemainingArgs): Unit = {

    val fetch = Fetch(options, args)

    val out =
      if (options.classpath)
        fetch
          .files0
          .map(_.toString)
          .mkString(File.pathSeparator)
      else
        fetch
          .files0
          .map(_.toString)
          .mkString("\n")

    println(out)
  }

}
