package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.{LinkmlAny, Uri}
import eu.neverblink.ohi.cli.generated.projecterror.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ErrorOutputSpec extends AnyWordSpec, Matchers {
  "human project results" should {
    "separate project guidance from the install summary while retaining absolute JSON paths" in {
      val directory = os.pwd.toString
      val result = generated.projectresult.ProjectResult(
        operation = generated.projectresult.ProjectOperation.Add,
        projectDirectory = directory,
        declarationChanged = true,
        declarationCreated = true,
        lockChanged = true,
        files = Seq(
          generated.projectresult.LockFileResult(
            path = (os.pwd / ".ohi" / "w3c" / "dcat" / "3" / "dcat.linkml.yaml").toString,
            url = Uri("https://example.org/dcat.yaml"),
            status = generated.projectresult.LockFileStatus.Downloaded,
          ),
        ),
      )
      val added = generated.declaration.ProjectDependency(
        "w3c/dcat@3",
        generated.declaration.Format.LinkmlYaml,
      )
      ProjectOutput.result(result, json = false, added = Some(added)) shouldBe
        "Created an ohi project.\n" +
        "You can edit ohi.yaml to manage your ontologies and project settings.\n" +
        "Downloaded ontologies go in .ohi/.\n\n" +
        "New to ohi? Check out the guide on how to manage ontology projects:\n" +
        "  https://ohi.dev/docs/guides/projects/\n\n" +
        "Added w3c/dcat@3 (linkml-yaml)\nDownloaded 1 ontology file."
      val json = ujson.read(ProjectOutput.result(result, json = true))
      json("project_directory").str shouldBe directory
      json("files")(0)(
        "path",
      ).str shouldBe (os.pwd / ".ohi" / "w3c" / "dcat" / "3" / "dcat.linkml.yaml").toString
      json("files")(0)("status").str shouldBe "DOWNLOADED"
      json.obj.contains("delivery") shouldBe false
    }

    "report an existing selection while retaining downloaded and verified repair counts" in {
      val result = generated.projectresult.ProjectResult(
        operation = generated.projectresult.ProjectOperation.Add,
        projectDirectory = os.pwd.toString,
        declarationChanged = false,
        declarationCreated = false,
        lockChanged = true,
        files = Seq(
          generated.projectresult.LockFileResult(
            path = (os.pwd / ".ohi" / "test" / "example" / "1" / "example.owl.ttl").toString,
            url = Uri("https://example.org/example.ttl"),
            status = generated.projectresult.LockFileStatus.Downloaded,
          ),
          generated.projectresult.LockFileResult(
            path = (os.pwd / ".ohi" / "test" / "dependency" / "1" / "dependency.owl.ttl").toString,
            url = Uri("https://example.org/dependency.ttl"),
            status = generated.projectresult.LockFileStatus.Verified,
          ),
        ),
      )
      val added = generated.declaration.ProjectDependency(
        "test/example@1",
        generated.declaration.Format.OwlTurtle,
      )
      ProjectOutput.result(result, json = false, added = Some(added)) shouldBe
        "Already exists: test/example@1 (owl-turtle)\n" +
        "Downloaded 1 ontology file. Verified 1 existing ontology file."
    }
  }

  "human catalog failures" should {
    Seq(
      (
        "INSPECT",
        404,
        "Release not found.",
        "Check the reference or URL, or find the release with 'ohi search'.",
      ),
      ("GET", 404, "File not found.", "Check the file URL"),
      ("INSPECT", 403, "Access denied.", "Check that you can access this URL"),
      ("SEARCH", 503, "The search service is unavailable.", "Try the same command again later."),
      ("GET", 503, "The download service is unavailable.", "Try the same command again later."),
    ).foreach { (operation, status, summary, correction) =>
      s"explain $operation failure $status without exposing the transport diagnostic" in {
        val (diagnostic, recovery) = ErrorOutput.catalog(
          "HTTP_ERROR",
          operation,
          s"HTTP $status for https://example.org/release/",
          Some("https://example.org/release/"),
          Some(status),
          None,
          timeoutOption = true,
        )
        diagnostic shouldBe s"Error: $summary\nURL: https://example.org/release/"
        recovery should include(correction)
        diagnostic should not include status.toString
      }
    }

    "preserve a service's expression explanation without dumping its structured response" in {
      val (diagnostic, _) = ErrorOutput.catalog(
        "HTTP_ERROR",
        "SEARCH",
        "HTTP 400",
        None,
        Some(400),
        Some(LinkmlAny("""{"message":"Unknown filter field 'topic'","status":400}""")),
        timeoutOption = true,
      )
      diagnostic shouldBe "Error: The service rejected the request.\nDetails: Unknown filter field 'topic'"
    }

    "offer the timeout option only to commands that support it" in {
      def recovery(supported: Boolean) = ErrorOutput.catalog(
        "TIMEOUT",
        "SEARCH",
        "request timed out",
        None,
        None,
        None,
        supported,
      )._2
      recovery(
        true,
      ) shouldBe "Check connectivity and try again. If the service needs more time, increase --timeout-ms."
      recovery(false) shouldBe "Check connectivity and try again."
    }

    Seq(
      ("CONNECTION_ERROR", "Connection reset", "Could not complete the connection."),
      ("INVALID_RESPONSE", "/artifacts/0: missing sha256", "Cannot read the release metadata."),
    ).foreach { (code, detail, summary) =>
      s"retain the diagnostic for $code" in {
        val (diagnostic, _) = ErrorOutput.catalog(
          code,
          "INSPECT",
          detail,
          Some("https://example.org/release/"),
          None,
          None,
          timeoutOption = true,
        )
        diagnostic shouldBe s"Error: $summary\nURL: https://example.org/release/\nDetails: $detail"
      }
    }
  }

  "human project failures" should {
    "report an incomplete file and completed downloads when sync reuses the lock" in {
      val error = ProjectError(
        code = ProjectErrorCode.OperationFailed,
        operation = ProjectOperation.Sync,
        projectDirectory = (os.pwd / "study").toString,
        declarationChanged = false,
        declarationCreated = false,
        lockChanged = false,
        files = Seq(
          LockFileResult(
            path = (os.pwd / "study" / ".ohi" / "completed.ttl").toString,
            status = LockFileStatus.Downloaded,
            url = Uri("https://example.org/completed.ttl"),
          ),
        ),
        fileError = Some(
          CliFileError(
            code = CliFileErrorCode.FileWriteFailed,
            directory = (os.pwd / "study" / ".ohi").toString,
            message = "Disk full. The incomplete file could not be removed.",
            path = Some((os.pwd / "study" / ".ohi" / "partial.ttl").toString),
            partialPath = Some((os.pwd / "study" / ".ohi" / "partial.ttl").toString),
            url = "https://example.org/partial.ttl",
          ),
        ),
        message = "Download failed",
      )
      val text = ProjectOutput.error(
        error,
        json = false,
        recoveryCommand = Some("ohi sync --project study"),
      )
      text should include("Error: Could not finish writing the downloaded file.")
      text should include("Incomplete file retained: study/.ohi/partial.ttl")
      text should include("Downloaded 1 ontology file.")
      text should include("No project files were written.")
      text should endWith(
        "Correct the storage problem and remove the incomplete file before retrying.\n" +
          "  ohi sync --project study",
      )
    }

    "offer the timeout option after a sync download fails without changing project records" in {
      val error = ProjectError(
        code = ProjectErrorCode.OperationFailed,
        operation = ProjectOperation.Sync,
        projectDirectory = (os.pwd / "study").toString,
        declarationChanged = false,
        declarationCreated = false,
        lockChanged = false,
        files = Seq.empty,
        catalogError = Some(
          CatalogError(
            CatalogErrorCode.Timeout,
            "request timed out",
            CatalogOperation.Get,
            url = Some("https://example.org/file.ttl"),
          ),
        ),
        message = "request timed out",
      )
      val text = ProjectOutput.error(error, json = false)
      text should include("No project files were written.")
      text should endWith(
        "Check connectivity and try again. If the service needs more time, increase --timeout-ms.",
      )
    }

    "show a saved declaration and unwritten lock before the original add retry" in {
      val error = ProjectError(
        code = ProjectErrorCode.OperationFailed,
        operation = ProjectOperation.Add,
        projectDirectory = (os.pwd / "study").toString,
        declarationChanged = true,
        declarationCreated = true,
        lockChanged = false,
        files = Seq.empty,
        lockError = Some(
          LockError(
            LockErrorCode.StorageError,
            "Permission denied",
            LockOperation.Write,
            Some((os.pwd / "study" / "ohi.lock.json").toString),
          ),
        ),
        message = "Legacy JSON message",
      )
      val text = ProjectOutput.error(
        error,
        json = false,
        recoveryCommand = Some("ohi add w3c/dcat@3 --project study"),
      )
      text should include("Error: Could not write the project lock.")
      text should include("Created an ohi project in study.")
      text should include(
        "You can edit study/ohi.yaml to manage your ontologies and project settings.",
      )
      text should include("https://ohi.dev/docs/guides/projects/")
      text should include("Details: Permission denied")
      text should include(
        "Project files\n  Saved study/ohi.yaml\n  Not written study/ohi.lock.json",
      )
      text should endWith(
        "  ohi add w3c/dcat@3 --project study",
      )
      ujson.read(ProjectOutput.error(error, json = true))(
        "message",
      ).str shouldBe "Legacy JSON message"
      ujson.read(ProjectOutput.error(error, json = true))("declaration_created").bool shouldBe true
      ujson.read(ProjectOutput.error(error, json = true))("project_directory").str shouldBe
        (os.pwd / "study").toString
    }

    "retain both completed records when the download service fails" in {
      val error = ProjectError(
        code = ProjectErrorCode.OperationFailed,
        operation = ProjectOperation.Add,
        projectDirectory = (os.pwd / "study").toString,
        declarationChanged = true,
        declarationCreated = false,
        lockChanged = true,
        files = Seq(
          LockFileResult(
            url = Uri("https://example.org/completed.ttl"),
            path = (os.pwd / "study" / ".ohi" / "completed.ttl").toString,
            status = LockFileStatus.Downloaded,
          ),
        ),
        catalogError = Some(
          CatalogError(
            CatalogErrorCode.HttpError,
            "HTTP 503",
            CatalogOperation.Get,
            status = Some(503),
            url = Some("https://example.org/file.ttl"),
          ),
        ),
        message = "HTTP 503\nlegacy recovery",
      )
      val text = ProjectOutput.error(
        error,
        json = false,
        recoveryCommand = Some("ohi sync --project study"),
      )
      text should include("Error: The project was updated, but the ontology download failed.")
      text should include("Cause: The download service is unavailable.")
      text should include("  Saved study/ohi.yaml\n  Saved study/ohi.lock.json")
      text should include("Downloaded 1 ontology file.")
      text should not include "HTTP"
      text should not include "ohi lock --project"
      text should not include "--timeout-ms"
      text should not include "Created an ohi project"
    }
  }
}
