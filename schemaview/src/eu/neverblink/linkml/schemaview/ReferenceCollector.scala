package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.{
  ClassDefinition,
  Element,
  EnumDefinition,
  FunctionDefinition,
  SlotDefinition,
  TypeDefinition,
}
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.schemaview.SchemaReachabilityQuery.{ElementTypeTag, TaggedReference}

import scala.annotation.nowarn
import scala.collection.mutable
import scala.quoted.*

/** Collects the references to classes, types, enums, slots and functions in a value of a metamodel
  * class, at any depth. For example, the ranges in `any_of`, `exactly_one_of`, `all_of` and
  * `none_of` lists nested in each other, in `range_expression`, in `enum_range` and in the slot
  * conditions of rules.
  *
  * The code that reads the fields is generated from the metamodel classes, so a reference is found
  * wherever it is nested, including in fields added to the metamodel later.
  */
private[schemaview] trait ReferenceCollector[T] {
  def collect(t: T, out: mutable.Growable[TaggedReference])(using SchemaView): Unit
}

private[schemaview] object ReferenceCollector {

  /** Generate a collector for `T`.
    *
    * @param skippedRootFields
    *   Names of the fields of `T` to leave out, as they are written in YAML. Fields with the same
    *   names in nested values are still read.
    */
  inline def derived[T](inline skippedRootFields: String*): ReferenceCollector[T] =
    ${ ReferenceCollectorImpl.make[T]('skippedRootFields) }
}

private object ReferenceCollectorImpl {
  def make[T: Type](skippedRootFields: Expr[Seq[String]])(using
      Quotes,
  ): Expr[ReferenceCollector[T]] = new ReferenceCollectorImpl().make[T](skippedRootFields)
}

private class ReferenceCollectorImpl(using Quotes) extends MacroUtils {
  import quotes.reflect.*

  def make[T: Type](skippedRootFields: Expr[Seq[String]]): Expr[ReferenceCollector[T]] = {
    val skipped = skippedRootFields match {
      case Varargs(names) => names.map(_.valueOrAbort).toSet
      case _ => fail("The names of the skipped fields must be string literals")
    }
    val rootTpe = TypeRepr.of[T].dealias
    val collectorDef = '{
      new ReferenceCollector[T] {
        override def collect(t: T, out: mutable.Growable[TaggedReference])(using
            sv: SchemaView,
        ): Unit = ${ genFields[T](rootTpe, 't, 'out, 'sv, skipped) }
      }
    }
    Block(defs.toList, collectorDef.asTerm).asExprOf[ReferenceCollector[T]]
  }

  private def gen[T: Type](
      tpe: TypeRepr,
      x: Expr[T],
      out: Expr[mutable.Growable[TaggedReference]],
      sv: Expr[SchemaView],
  ): Expr[Unit] =
    if (!mayHoldReferences(tpe, Nil)) '{ () }
    else if (tpe <:< referenceOfWildcardTpe) {
      val name = '{ ${ x.asExprOf[Reference[?]] }.value }
      tagOf(typeArg1(tpe)) match {
        case Some(tag) => '{ $out.addOne(($tag, $name)) }
        case _ =>
          '{
            val n = $name
            $sv.elements.get(n).foreachFast(ev => $out.addOne((ElementTypeTag(ev.inner), n)))
          }
      }
    } else if (tpe <:< optionOfWildcardTpe) {
      val tpe1 = typeArg1(tpe)
      tpe1.asType match {
        case '[t1] =>
          '{ ${ x.asExprOf[Option[t1]] }.foreachFast(v => ${ gen[t1](tpe1, 'v, out, sv) }) }
      }
    } else if (tpe <:< seqOfWildcardTpe) {
      val tpe1 = typeArg1(tpe)
      tpe1.asType match {
        case '[t1] => '{ ${ x.asExprOf[Seq[t1]] }.foreach(v => ${ gen[t1](tpe1, 'v, out, sv) }) }
      }
    } else if (tpe <:< mapOfWildcardsTpe) {
      val tpe2 = typeArg2(tpe)
      ((typeArg1(tpe).asType, tpe2.asType): @nowarn) match {
        case ('[t1], '[t2]) =>
          '{
            ${ x.asExprOf[Map[t1, t2]] }.foreachEntry((_, v) => ${ gen[t2](tpe2, 'v, out, sv) })
          }
      }
    } else withCollectorFor(tpe, x, out, sv)((x, out, sv) => genFields(tpe, x, out, sv, Set.empty))

  private def genFields[T: Type](
      tpe: TypeRepr,
      x: Expr[T],
      out: Expr[mutable.Growable[TaggedReference]],
      sv: Expr[SchemaView],
      skipped: Set[String],
  ): Expr[Unit] =
    Block(
      fieldsToRead(tpe).filter { f =>
        !skipped.contains(f.mappedName) && mayHoldReferences(f.resolvedTpe, Nil)
      }.map { f =>
        val fTpe = f.resolvedTpe
        fTpe.asType match {
          case '[ft] =>
            gen[ft](fTpe, Select(x.asTerm, f.getterOrField).asExprOf[ft], out, sv).asTerm
        }
      },
      '{ () }.asTerm,
    ).asExprOf[Unit]

  /** The fields of a metamodel class, without the ones that reference back to the elements that use
    * this one: `domain_of` of a slot and `owner` of an attribute. Following them would keep every
    * class that uses a slot as soon as the slot is kept.
    */
  private def fieldsToRead(tpe: TypeRepr): List[FieldInfo] =
    getClassInfo(tpe).fields.filter { f =>
      f.mappedName != "domain_of" && f.mappedName != "owner"
    }

  /** The tag for a reference to `target`, if it names one kind of element. */
  private def tagOf(target: TypeRepr): Option[Expr[ElementTypeTag]] =
    if (target =:= TypeRepr.of[ClassDefinition]) new Some('{ ElementTypeTag.classDef })
    else if (target =:= TypeRepr.of[TypeDefinition]) new Some('{ ElementTypeTag.typeDef })
    else if (target =:= TypeRepr.of[SlotDefinition]) new Some('{ ElementTypeTag.slotDef })
    else if (target =:= TypeRepr.of[EnumDefinition]) new Some('{ ElementTypeTag.enumDef })
    else if (target =:= TypeRepr.of[FunctionDefinition]) new Some('{ ElementTypeTag.functionDef })
    else None

  /** Whether a value of `tpe` can hold a reference to a class, type, enum, slot or function. Fields
    * that can't are not read at all.
    *
    * @param visiting
    *   The classes whose fields are being checked, to stop at recursive types
    */
  private def mayHoldReferences(tpe: TypeRepr, visiting: List[TypeRepr]): Boolean =
    holdsReferences.getOrElse(
      tpe, {
        val result =
          if (tpe <:< referenceOfWildcardTpe) {
            val target = typeArg1(tpe)
            tagOf(target).isDefined ||
            target =:= TypeRepr.of[Element] ||
            target =:= TypeRepr.of[eu.neverblink.linkml.metamodel.Definition]
          } else if (tpe <:< optionOfWildcardTpe || tpe <:< seqOfWildcardTpe)
            mayHoldReferences(typeArg1(tpe), visiting)
          else if (tpe <:< mapOfWildcardsTpe) mayHoldReferences(typeArg2(tpe), visiting)
          else if (isMetamodelClass(tpe) && !visiting.exists(_ =:= tpe)) {
            fieldsToRead(tpe).exists(f => mayHoldReferences(f.resolvedTpe, tpe :: visiting))
          } else false
        // A `false` found while other classes are being checked can be wrong, as one of them may
        // still turn out to hold references, so only those found at the top are kept.
        if (result || visiting.isEmpty) holdsReferences.update(tpe, result)
        result
      },
    )

  /** Only the classes of the metamodel hold references, the classes of the runtime don't. */
  private def isMetamodelClass(tpe: TypeRepr): Boolean =
    isNonAbstractClass(tpe) && tpe.typeSymbol.fullName.startsWith("eu.neverblink.linkml.metamodel.")

  private def withCollectorFor[T: Type](
      tpe: TypeRepr,
      x: Expr[T],
      out: Expr[mutable.Growable[TaggedReference]],
      sv: Expr[SchemaView],
  )(
      f: (Expr[T], Expr[mutable.Growable[TaggedReference]], Expr[SchemaView]) => Expr[Unit],
  ): Expr[Unit] =
    Apply(
      collectRefs.getOrElse(
        tpe, {
          val sym = Symbol.newMethod(
            Symbol.spliceOwner,
            s"c${collectRefs.size}",
            MethodType("x" :: "out" :: "sv" :: Nil)(
              _ => tpe :: growableTpe :: schemaViewTpe :: Nil,
              _ => TypeRepr.of[Unit],
            ),
          )
          val ref = Ref(sym)
          collectRefs.update(tpe, ref)
          defs.addOne(
            DefDef(
              sym,
              params => {
                val List(x, out, sv) = params.head
                new Some(
                  f(
                    x.asExprOf[T],
                    out.asExprOf[mutable.Growable[TaggedReference]],
                    sv.asExprOf[SchemaView],
                  ).asTerm.changeOwner(sym),
                )
              },
            ),
          )
          ref
        },
      ),
      List(x.asTerm, out.asTerm, sv.asTerm),
    ).asExprOf[Unit]

  private val holdsReferences = new mutable.HashMap[TypeRepr, Boolean]
  private val collectRefs = new mutable.HashMap[TypeRepr, Ref]
  private val defs = new mutable.ListBuffer[Definition]
  private val referenceOfWildcardTpe: TypeRepr =
    TypeRepr.of[Reference].appliedTo(wildcardBounds)
  private val growableTpe: TypeRepr = TypeRepr.of[mutable.Growable[TaggedReference]]
  private val schemaViewTpe: TypeRepr =
    Symbol.requiredClass("eu.neverblink.linkml.schemaview.SchemaView").typeRef
}
