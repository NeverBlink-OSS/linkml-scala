package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.LinkmlAny
import eu.neverblink.ohi.cli.generated.projecterror.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ErrorOutputSpec extends AnyWordSpec, Matchers {
  "human catalog failures" should {
    Seq(
      (
        "INSPECT",
        404,
        "Release not found.",
        "Check the URL or find the release with 'ohi search'.",
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
    "show a saved declaration and unwritten lock before the resolved recovery commands" in {
      val error = ProjectError(
        code = ProjectErrorCode.OperationFailed,
        operation = ProjectOperation.Add,
        projectDirectory = "/work/study",
        declarationChanged = true,
        lockChanged = false,
        lockError = Some(
          LockError(
            LockErrorCode.StorageError,
            "Permission denied",
            LockOperation.Write,
            Some("/work/study/ohi.lock.json"),
          ),
        ),
        message = "Legacy JSON message",
      )
      val text = ProjectOutput.error(
        error,
        json = false,
        recoveryCommands = Seq(
          "ohi lock --project /work/study",
          "ohi get https://example.org/file.ttl --to ./ontologies",
        ),
      )
      text should include("Error: Could not write the project lock.")
      text should include("Details: Permission denied")
      text should include(
        "Project files\n  Saved /work/study/ohi.yaml\n  Not written /work/study/ohi.lock.json\n  The ontology file was not downloaded.",
      )
      text should endWith(
        "  ohi lock --project /work/study\n  ohi get https://example.org/file.ttl --to ./ontologies",
      )
      ProjectOutput.error(error, json = true) should include("\"message\":\"Legacy JSON message\"")
    }

    "retain both completed records when the download service fails" in {
      val error = ProjectError(
        code = ProjectErrorCode.OperationFailed,
        operation = ProjectOperation.Add,
        projectDirectory = "/work/study",
        declarationChanged = true,
        lockChanged = true,
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
        recoveryCommands = Seq("ohi get https://example.org/file.ttl --to ./ontologies"),
      )
      text should include("Error: The project was updated, but the ontology download failed.")
      text should include("Cause: The download service is unavailable.")
      text should include("  Saved /work/study/ohi.yaml\n  Saved /work/study/ohi.lock.json")
      text should include("The ontology file was not downloaded.")
      text should not include "HTTP"
      text should not include "ohi lock --project"
      text should not include "--timeout-ms"
    }
  }
}
