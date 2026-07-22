package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.{
  ClassDefinition,
  Element,
  EnumDefinition,
  SlotDefinition,
  SubsetDefinition,
  TypeDefinition,
}
import eu.neverblink.linkml.runtime.*
import scala.annotation.nowarn
import scala.collection.mutable
import scala.quoted.*

/** Macro validator result container
  * @param unknownReferences
  *   References that could not be resolved in the schema
  * @param invalidRanges
  *   Ranges that are invalid in the schema
  * @param invalidDefaultRanges
  *   Ranges whose inferred defaults wouldn't resolve
  * @param isEmpty
  *   Set to true only if you are creating a ValidatorResult == ValidatorResult.ok
  */
final case class ValidatorResult private (
    unknownReferences: Seq[UnknownReference],
    invalidRanges: Seq[InvalidRange],
    invalidDefaultRanges: Seq[InvalidDefaultRange],
    isEmpty: Boolean,
):
  /** Merge the [[ValidatorResult]]s */
  def +(other: ValidatorResult): ValidatorResult =
    if other.isEmpty then this
    else
      ValidatorResult(
        unknownReferences ++ other.unknownReferences,
        invalidRanges ++ other.invalidRanges,
        invalidDefaultRanges ++ other.invalidDefaultRanges,
      )

  /** Add a [[prefix]] to each result's source path */
  def prependedPath(prefix: String): ValidatorResult =
    if isEmpty then this
    else
      ValidatorResult(
        unknownReferences.map(_.prependedPath(prefix)),
        invalidRanges.map(_.prependedPath(prefix)),
        invalidDefaultRanges.map(_.prependedPath(prefix)),
      )

object ValidatorResult {
  val ok: ValidatorResult = ValidatorResult(Seq.empty, Seq.empty, Seq.empty, isEmpty = true)

  def apply(
      unknownReferences: Seq[UnknownReference] = Seq(),
      invalidRanges: Seq[InvalidRange] = Seq(),
      invalidDefaultRanges: Seq[InvalidDefaultRange] = Seq(),
  ): ValidatorResult =
    new ValidatorResult(
      unknownReferences,
      invalidRanges,
      invalidDefaultRanges,
      isEmpty = false,
    )
}

/** A reference that could not be resolved in the [[SchemaView]]
  *
  * @param path
  *   JSON path to the invalid reference
  * @param referenceValue
  *   Value of the invalid reference
  */
final case class UnknownReference(path: String, referenceValue: String):
  /** Add a [[prefix]] to this class' path */
  def prependedPath(prefix: String): UnknownReference =
    copy(path = prefix + path)

/** A `range` reference that is resolvable, but points to an invalid value
  *
  * @param path
  *   JSON path to the reference
  * @param value
  *   Value of the reference
  * @param actualType
  *   The definition type that this reference actually points to
  */
final case class InvalidRange(path: String, value: String, actualType: String):
  /** Add a [[prefix]] to this class' path */
  def prependedPath(prefix: String): InvalidRange =
    copy(path = prefix + path)

/** An inferred default `range`, that is not allowed as the `default_range` slot is not resolvable.
  *
  * @param path
  *   JSON path to the reference
  */
final case class InvalidDefaultRange(path: String):
  /** Add a [[prefix]] to this class' path */
  def prependedPath(prefix: String): InvalidDefaultRange =
    copy(path = prefix + path)

private trait MacroValidator[T] {
  def validate(t: T)(using SchemaView, ValidatorContext): ValidatorResult
}

/** Context for the validator
  *
  * @param defaultRangeAllowed
  *   Whether to treat omitted ranges as an error
  * @param isRange
  *   Whether this particular slot is a range and should be additionally checked
  */
final class ValidatorContext private (val defaultRangeAllowed: Boolean, val isRange: Boolean):
  /** Mark continue validating this slot as a range. SHOULD NOT BE USED OUTSIDE THE MACRO */
  def asRange: ValidatorContext =
    new ValidatorContext(defaultRangeAllowed, true)

object ValidatorContext:
  def apply(defaultRangeAllowed: Boolean): ValidatorContext =
    new ValidatorContext(defaultRangeAllowed, false)

private object MacroValidator {
  given MacroValidator[LinkmlAny] = new MacroValidator[LinkmlAny] {
    def validate(t: LinkmlAny)(using SchemaView, ValidatorContext): ValidatorResult =
      ValidatorResult.ok
  }

  given MacroValidator[UriOrCurie] = new MacroValidator[UriOrCurie] {
    def validate(t: UriOrCurie)(using SchemaView, ValidatorContext): ValidatorResult =
      ValidatorResult.ok
  }

  private def formatRangeType(el: Element): String = {
    el match {
      case _: SubsetDefinition => "SubsetDefinition"
      case _: SlotDefinition => "SlotDefinition"
      case _ => "???"
    }
  }

  given referenceValidator[T <: Element]: MacroValidator[Reference[T]] =
    new MacroValidator[Reference[T]] {
      def validate(
          t: Reference[T],
      )(using sv: SchemaView, vc: ValidatorContext): ValidatorResult = t.resolve match {
        case Some(value) =>
          if !vc.isRange
            || value.isInstanceOf[TypeDefinition]
            || value.isInstanceOf[ClassDefinition]
            || value.isInstanceOf[EnumDefinition]
          then ValidatorResult.ok
          else
            ValidatorResult(invalidRanges = Seq(InvalidRange("", t.value, formatRangeType(value))))
        case None => ValidatorResult(unknownReferences = Seq(UnknownReference("", t.value)))
      }
    }

  inline def derived[T]: MacroValidator[T] = ${ ReferenceValidatorImpl.make }
}

private object ReferenceValidatorImpl {
  def make[T: Type](using Quotes): Expr[MacroValidator[T]] =
    new ReferenceValidatorImpl().make[T]
}

private class ReferenceValidatorImpl(using Quotes) extends MacroUtils {
  import quotes.reflect.*

  def make[T: Type]: Expr[MacroValidator[T]] = {
    val rootTpe = TypeRepr.of[T].dealias
    inferredValidators.put(rootTpe, None)
    val validatorDef = '{
      new MacroValidator[T] {
        override def validate(
            t: T,
        )(using sv: SchemaView, vc: ValidatorContext): ValidatorResult = ${
          genValidator[T](rootTpe, 't, 'sv, 'vc)
        }
      }
    }
    val validator =
      Block(defs.toList, validatorDef.asTerm).asExpr.asInstanceOf[Expr[MacroValidator[T]]]
//    report.info(
//      s"Generated reference validator for type '${rootTpe.show}':\n${codec.show}",
//      Position.ofMacroExpansion,
//    )
    validator
  }

  private def genValidator[T: Type](
      tpe: TypeRepr,
      x: Expr[T],
      sv: Expr[SchemaView],
      vc: Expr[ValidatorContext],
  )(using Quotes): Expr[ValidatorResult] = {
    val implValidator = findImplicitValidator(tpe)
    if (implValidator.isDefined) {
      '{
        ${ implValidator.get.asInstanceOf[Expr[MacroValidator[T]]] }.validate($x)(using $sv, $vc)
      }
    } else if (tpe =:= stringTpe || tpe =:= intTpe || tpe =:= booleanTpe) {
      '{ ValidatorResult.ok }
    } else if (tpe <:< optionOfWildcardTpe) withValidatorFor(tpe, x, sv, vc) { (x, sv, vc) =>
      val tpe1 = typeArg1(tpe)
      tpe1.asType match {
        case '[t1] =>
          val opt = x.asInstanceOf[Expr[Option[t1]]]
          '{
            if ($opt ne None) ${ genValidator[t1](tpe1, '{ $opt.get }, sv, vc) }
            else if $vc.isRange && ! $vc.defaultRangeAllowed then
              ValidatorResult(
                invalidDefaultRanges = Seq(InvalidDefaultRange("")),
              )
            else ValidatorResult.ok
          }
      }
    }
    else if (tpe <:< seqOfWildcardTpe) withValidatorFor(tpe, x, sv, vc) { (x, sv, vc) =>
      val tpe1 = typeArg1(tpe)
      tpe1.asType match {
        case '[t1] =>
          val seq = x.asInstanceOf[Expr[Seq[t1]]]
          '{
            $seq.zipWithIndex.map((e, idx) =>
              ${ genValidator[t1](tpe1, 'e, sv, vc) }.prependedPath(s"$idx/"),
            ).fold(ValidatorResult.ok)(_ + _)
          }
      }
    }
    else if (tpe <:< mapOfWildcardsTpe) withValidatorFor(tpe, x, sv, vc) { (x, sv, vc) =>
      val tpe1 = typeArg1(tpe)
      val tpe2 = typeArg2(tpe)
      ((tpe1.asType, tpe2.asType): @nowarn) match {
        case ('[t1], '[t2]) =>
          val map = x.asInstanceOf[Expr[Map[t1, t2]]]
          '{
            $map.map((k, v) => ${ genValidator[t2](tpe2, 'v, sv, vc) }.prependedPath(s"$k/")).fold(
              ValidatorResult.ok,
            )(_ + _)
          }
      }
    }
    else if (isNonAbstractClass(tpe)) withValidatorFor(tpe, x, sv, vc) { (x, sv, vc) =>
      genValidatorNonAbstractClass(tpe, x, sv, vc)
    }
    else if (isAbstractClassOrTraitOrEnum(tpe)) '{ ValidatorResult.ok }
    else fail(s"Unsupported type ${tpe.show}")
  }.asInstanceOf[Expr[ValidatorResult]]

  private def genValidatorNonAbstractClass[T: Type](
      tpe: TypeRepr,
      x: Expr[T],
      sv: Expr[SchemaView],
      vc: Expr[ValidatorContext],
  )(using Quotes): Expr[ValidatorResult] = {
    val classInfo = getClassInfo(tpe)
    val fields = classInfo.fields

    def genValidateFields(
        kvs: Expr[mutable.Growable[ValidatorResult]],
    )(using Quotes): Expr[Unit] = {
      Block(
        fields.map { fieldInfo =>
          val fTpe = fieldInfo.resolvedTpe
          val getter = Select(x.asTerm, fieldInfo.getterOrField).asExpr
          val name = Expr(fieldInfo.mappedName)
          (fTpe.asType match {
            case '[ft] =>
              val encodeVal = genValidator[ft](
                fTpe,
                getter.asInstanceOf[Expr[ft]],
                sv,
                if fieldInfo.mappedName != "range" then vc
                else '{ $vc.asRange },
              )
              '{
                // Skip ok results to save on array expansion and folding
                if $encodeVal ne ValidatorResult.ok then
                  $kvs.addOne($encodeVal.prependedPath(s"${$name}/"))
              }
          }).asTerm
        },
        '{}.asTerm,
      ).asExpr.asInstanceOf[Expr[Unit]]
    }
    '{
      val kvs = Seq.newBuilder[ValidatorResult]
      ${ genValidateFields('kvs) }
      kvs.result().fold(ValidatorResult.ok)(_ + _)
    }
  }

  private def withValidatorFor[T: Type](
      tpe: TypeRepr,
      x: Expr[T],
      sv: Expr[SchemaView],
      vc: Expr[ValidatorContext],
  )(
      f: (Expr[T], Expr[SchemaView], Expr[ValidatorContext]) => Expr[ValidatorResult],
  ): Expr[ValidatorResult] =
    Apply(
      validateRefs.getOrElse(
        tpe, {
          val sym = Symbol.newMethod(
            Symbol.spliceOwner,
            s"e${validateRefs.size}",
            MethodType("x" :: "sv" :: "vc" :: Nil)(
              _ => tpe :: schemaViewTpe :: validatorContextTpe :: Nil,
              _ => validatorResultTpe,
            ),
          )
          val ref = Ref(sym)
          validateRefs.update(tpe, ref)
          defs.addOne(
            DefDef(
              sym,
              params => {
                val List(x, sv, vc) = params.head
                new Some(
                  f(
                    x.asExpr.asInstanceOf[Expr[T]],
                    sv.asExpr.asInstanceOf[Expr[SchemaView]],
                    vc.asExpr.asInstanceOf[Expr[ValidatorContext]],
                  ).asTerm.changeOwner(sym),
                )
              },
            ),
          )
          ref
        },
      ),
      List(x.asTerm, sv.asTerm, vc.asTerm),
    ).asExpr.asInstanceOf[Expr[ValidatorResult]]

  private def findImplicitValidator(tpe: TypeRepr): Option[Expr[MacroValidator[?]]] =
    inferredValidators.getOrElseUpdate(
      tpe, {
        Implicits.search(referenceValidatorTpe.appliedTo(tpe)) match
          case s: ImplicitSearchSuccess =>
            new Some(s.tree.asExpr.asInstanceOf[Expr[MacroValidator[?]]])
          case _ => None
      },
    )

  private val inferredValidators =
    new mutable.HashMap[TypeRepr, Option[Expr[MacroValidator[?]]]]
  private val validateRefs = new mutable.HashMap[TypeRepr, Ref]
  private val defs = new mutable.ListBuffer[Definition]
  private val referenceValidatorTpe =
    Symbol.requiredClass("eu.neverblink.linkml.schemaview.MacroValidator").typeRef
  private val schemaViewTpe =
    Symbol.requiredClass("eu.neverblink.linkml.schemaview.SchemaView").typeRef
  private val validatorContextTpe =
    Symbol.requiredClass("eu.neverblink.linkml.schemaview.ValidatorContext").typeRef
  private val validatorResultTpe =
    Symbol.requiredClass("eu.neverblink.linkml.schemaview.ValidatorResult").typeRef
}
