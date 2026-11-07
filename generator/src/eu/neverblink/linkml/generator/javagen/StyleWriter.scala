package eu.neverblink.linkml.generator.javagen

import eu.neverblink.linkml.generator.javagen.JavaGenerator.Code

/** The parts of the generated Java that depend on the
  * [[eu.neverblink.linkml.generator.options.JavaStyle style]]: how the classes are read from JSON
  * and written to it. [[JavaGenerator]] writes the rest: the classes with their fields, accessors,
  * `equals`, `hashCode` and `toString`, the enums, and the functions of the schema.
  */
private[javagen] trait StyleWriter {

  /** The annotations of a class, one per string, which may span lines. */
  def classAnnotations(cls: JavaClass): Seq[String]

  def fieldAnnotations(field: JavaField): Seq[String]

  /** The members that read and write the JSON of a class, at the end of the class. */
  def jsonMembers(cls: JavaClass, out: Code): Unit

  /** The annotations of an enum's `getValue()`, which returns the value in the JSON data. */
  def enumValueAnnotations: Seq[String]

  /** The annotations of an enum's `fromValue(Object)`, which finds the constant for a value. */
  def enumFactoryAnnotations: Seq[String]

  /** The body of the file of a document class. */
  def document(doc: JavaDocument): String

  /** Other files that the generated files need, by class name. [[sources]] are the bodies of the
    * generated files.
    */
  def supportFiles(sources: Iterable[String]): Seq[(String, String)]
}

private[javagen] object StyleWriter {

  /** For a factory that makes an object from the value of one field: the type of its parameter
    * `value`, and the argument to pass to the field's setter. None if the field's type doesn't
    * allow this.
    */
  def bareParameter(tpe: FieldType): Option[(String, String)] = tpe match {
    case FieldType.Strict(_, Reader.Of(scalar), false) =>
      Some(scalar match {
        case Scalar.Str | Scalar.Text => "String" -> "value"
        case Scalar.Int => "long" -> "value"
        case Scalar.Num => "double" -> "value"
        case Scalar.Bool => "boolean" -> "value"
        case Scalar.Date | Scalar.DateTime | Scalar.Time =>
          "String" -> s"LinkMLJson.${scalar.read}(value)"
      })
    case _ => None
  }
}
