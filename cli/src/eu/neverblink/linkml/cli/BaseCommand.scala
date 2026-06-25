package eu.neverblink.linkml.cli

import caseapp.Command
import eu.neverblink.linkml.schemaview.SchemaView

import scala.util.control.NonFatal

trait BaseCommand {
  this: Command[?] =>

  protected final def err(message: String): Nothing = {
    printLine(message, toStderr = true)
    exit(1)
  }

  def loadSchema(inFile: Option[String]): SchemaView =
    inFile match {
      case None => err("Input file is required."); null
      case Some(inputName) =>
        try SchemaView.loadSchemaViewFromUri(inputName)
        catch {
          case ex if NonFatal(ex) =>
            err("Cannot load schema: " + ex.getMessage); null
        }
    }
}
