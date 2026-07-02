package eu.neverblink.linkml.schemaview

trait RuntimeUnion:
  def unionOf: Seq[RuntimeType]

sealed trait RuntimeType:
  def repr: CoreType = StringType

sealed trait CoreType extends RuntimeType:
  override def repr: CoreType = this

case object StringType extends CoreType
case object IntegerType extends CoreType
case object FloatType extends CoreType
case object DoubleType extends CoreType
case object BooleanType extends CoreType
case object DecimalType extends CoreType
case object AnyType extends CoreType

case object DateOrDateTimeType extends RuntimeType, RuntimeUnion:
  override def unionOf: Seq[RuntimeType] = Seq(DateType, DateTimeType)
case object DateType extends RuntimeType
case object DateTimeType extends RuntimeType
case object TimeType extends RuntimeType

case object UriOrCurieType extends RuntimeType, RuntimeUnion:
  def unionOf: Seq[RuntimeType] = Seq(UriType, CurieType)
case object UriType extends RuntimeType
case object CurieType extends RuntimeType
case object NcNameType extends RuntimeType

case object UnknownType extends RuntimeType:
  override def repr: CoreType = AnyType
