package eu.neverblink.linkml.schemaview

/** An enum-like representation of all linkml-scala runtime supported types. Generators are expected
  * to handle all cases of these types, or at least the [[CoreType]] subset.
  */
sealed trait RuntimeType:
  /** A simpler representation of this type, which should be used when full runtime support is not
    * possible.
    */
  def repr: CoreType = StringType

/** A subset of [[RuntimeType]]s which can be represented with more limited runtime support. */
sealed trait CoreType extends RuntimeType:
  override def repr: CoreType = this

case object StringType extends CoreType
case object IntegerType extends CoreType
case object FloatType extends CoreType
case object DoubleType extends CoreType
case object BooleanType extends CoreType
case object DecimalType extends CoreType

/** Should be handled the same way as linkml:Any classes.
  */
case object AnyType extends CoreType

case object DateType extends RuntimeType
case object DateTimeType extends RuntimeType
case object TimeType extends RuntimeType

case object UriOrCurieType extends RuntimeType
case object UriType extends RuntimeType
case object CurieType extends RuntimeType
case object NcNameType extends RuntimeType

/** Unknown base type */
case object UnknownType extends RuntimeType:
  override def repr: CoreType = AnyType
