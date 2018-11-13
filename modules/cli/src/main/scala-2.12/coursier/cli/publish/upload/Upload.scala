package coursier.cli.publish.upload

import java.io.PrintStream
import java.time.Instant

import coursier.cli.publish.Content
import coursier.cli.publish.fileset.FileSet
import coursier.core.Authentication
import coursier.maven.MavenRepository
import coursier.util.Task

/**
  * Uploads / sends content to a repository.
  *
  * Also downloads stuff, actually.
  */
trait Upload {

  def exists(url: String, authentication: Option[Authentication], logger: Upload.Logger): Task[Boolean]
  def downloadIfExists(url: String, authentication: Option[Authentication], logger: Upload.Logger): Task[Option[(Option[Instant], Array[Byte])]]

  // TODO Support chunked content?

  /**
    * Uploads content at the passed `url`.
    *
    * @param url: URL to upload content at
    * @param authentication: optional authentication parameters
    * @param content: content to upload
    * @param logger
    * @return an optional [[Upload.Error]], non-empty in case of error
    */
  def upload(url: String, authentication: Option[Authentication], content: Array[Byte], logger: Upload.Logger): Task[Option[Upload.Error]]

  /**
    * Uploads a whole [[FileSet]].
    *
    * @param repository
    * @param fileSet
    * @param logger
    * @return
    */
  final def uploadFileSet(repository: MavenRepository, fileSet: FileSet, logger: Upload.Logger): Task[Seq[(FileSet.Path, Content, Upload.Error)]] = {

    val baseUrl0 = repository.root.stripSuffix("/")

    // TODO Add exponential back off for transient errors

    // uploading stuff sequentially for now
    // stops at first error
    def doUpload(id: Object) = fileSet
      .elements
      .foldLeft(Task.point(Option.empty[(FileSet.Path, Content, Upload.Error)])) {
        case (acc, (path, content)) =>
          val url = s"$baseUrl0/${path.elements.mkString("/")}"

          for {
            previousErrorOpt <- acc
            errorOpt <- {
              previousErrorOpt
                .map(e => Task.point(Some(e)))
                .getOrElse {
                  for {
                    _ <- Task.delay(logger.uploading(url, Some(id)))
                    a <- content.contentTask.flatMap(b =>
                      upload(url, repository.authentication, b, logger).map(_.map((path, content, _)))
                    ).attempt
                    // FIXME Also failed if a.isLeft …
                    _ <- Task.delay(logger.uploaded(url, Some(id), a.right.toOption.flatMap(_.map(_._3))))
                    res <- Task.fromEither(a)
                  } yield res
                }
            }
          } yield errorOpt
      }
      .map(_.toSeq)

    val before = Task.delay {
      val id = new Object
      logger.start()
      logger.uploadingSet(id, fileSet)
      id
    }

    def after(id: Object) = Task.delay {
      logger.uploadedSet(id, fileSet)
      logger.stop()
    }

    for {
      id <- before
      a <- doUpload(id).attempt
      _ <- after(id)
      res <- Task.fromEither(a)
    } yield res
  }
}

object Upload {

  sealed abstract class Error(val transient: Boolean, message: String, cause: Throwable = null) extends Exception(message, cause)

  object Error {
    final class HttpError(code: Int, headers: Map[String, Seq[String]], response: String) extends Error(transient = code / 100 == 5, s"HTTP $code\n$response")
    final class Unauthorized(url: String, realm: Option[String]) extends Error(transient = false, s"Unauthorized ($url, ${realm.getOrElse("[no realm]")})")
    final class DownloadError(exception: Throwable) extends Error(transient = false, "Download error", exception)
    final class FileException(exception: Throwable) extends Error(transient = false, "I/O error", exception) // can some exceptions be transient?
  }

  trait Logger {
    def uploadingSet(id: Object, fileSet: FileSet): Unit = ()
    def uploadedSet(id: Object, fileSet: FileSet): Unit = ()
    def uploading(url: String, idOpt: Option[Object]): Unit =
      uploading(url)
    def uploaded(url: String, idOpt: Option[Object], errorOpt: Option[Error]): Unit =
      uploaded(url, errorOpt)

    def uploading(url: String): Unit = ()
    def uploaded(url: String, errorOpt: Option[Error]): Unit = ()

    def checking(url: String): Unit = ()
    def checked(url: String, exists: Boolean, errorOpt: Option[Throwable]): Unit = ()

    def downloadingIfExists(url: String): Unit = ()
    def downloadedIfExists(url: String, size: Option[Long], errorOpt: Option[Throwable]): Unit = ()

    def start(): Unit = ()
    def stop(keep: Boolean = true): Unit = ()
  }

  object Logger {
    val nop: Logger =
      new Logger {}

    def apply(ps: PrintStream): Logger =
      new Logger {
        override def uploading(url: String) =
          ps.println(s"Uploading $url")
        override def uploaded(url: String, errorOpt: Option[Error]) = {
          val msg = errorOpt match {
            case None =>
              s"Uploaded $url"
            case Some(error) =>
              s"Failed to upload $url: $error"
          }
          ps.println(msg)
        }
      }
  }

}
