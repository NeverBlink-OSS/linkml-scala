package eu.neverblink.linkml.runtime

import eu.neverblink.linkml.runtime.*

import scala.collection.mutable
import scala.quoted.*

trait MacroUtils(using val quotes: Quotes) {
  import quotes.reflect.*

  protected class ClassInfo(
      val tpe: TypeRepr,
      val tpeTypeArgs: List[TypeRepr],
      val primaryConstructor: Symbol,
      val paramLists: List[List[FieldInfo]],
  ) {
    val fields: List[FieldInfo] = paramLists.flatten
    {
      val collisions = duplicated(fields.map(_.mappedName))
      if (collisions.nonEmpty) {
        val formattedCollisions = collisions.mkString("'", "', '", "'")
        fail(
          s"Duplicated yaml key(s) defined for '${tpe.show}': $formattedCollisions. Keys are derived from " +
            s"field names of the class and can be overridden by '${TypeRepr.of[named].show}' annotation(s).",
        )
      }
      if (fields.count(_.kind == FieldKind.Id) > 1) {
        fail(s"More than one field is defined with '@id' annotation in '${tpe.show}'.")
      }
      if (fields.count(_.kind == FieldKind.Value) > 1) {
        fail(s"More than one field is defined with '@value' annotation in '${tpe.show}'.")
      }
    }

    def genNew(argss: List[List[Term]]): Term =
      val constructorNoTypes = Select(New(Inferred(tpe)), primaryConstructor)
      val constructor =
        if (tpeTypeArgs eq Nil) constructorNoTypes
        else TypeApply(constructorNoTypes, tpeTypeArgs.map(Inferred(_)))
      argss.tail.foldLeft(Apply(constructor, argss.head))(Apply(_, _))
  }

  protected class FieldInfo(
      val symbol: Symbol,
      val mappedName: String,
      val getterOrField: Symbol,
      val defaultValue: Option[Term],
      val resolvedTpe: TypeRepr,
      val kind: FieldKind,
  )

  enum FieldKind {
    case Regular, Id, Value, SimpleDict, CompactDict, ExpandedDict
  }

  private val classInfos = new mutable.HashMap[TypeRepr, ClassInfo]
  private val namedTpe = Symbol.requiredClass("eu.neverblink.linkml.runtime.named").typeRef
  private val idTpe = Symbol.requiredClass("eu.neverblink.linkml.runtime.id").typeRef
  private val valueTpe = Symbol.requiredClass("eu.neverblink.linkml.runtime.value").typeRef
  private val simpleDictTpe =
    Symbol.requiredClass("eu.neverblink.linkml.runtime.simpleDict").typeRef
  private val compactDictTpe =
    Symbol.requiredClass("eu.neverblink.linkml.runtime.compactDict").typeRef
  private val expandedDictTpe =
    Symbol.requiredClass("eu.neverblink.linkml.runtime.expandedDict").typeRef

  protected def fail(msg: String): Nothing = report.errorAndAbort(msg, Position.ofMacroExpansion)

  protected def typeArgs(tpe: TypeRepr): List[TypeRepr] = tpe match {
    case AppliedType(_, typeArgs) => typeArgs.map(_.dealias)
    case _ => Nil
  }

  protected def typeArg1(tpe: TypeRepr): TypeRepr = tpe match {
    case AppliedType(_, typeArg1 :: _) => typeArg1.dealias
    case _ => fail(s"Cannot get 1st type argument in '${tpe.show}'")
  }

  protected def typeArg2(tpe: TypeRepr): TypeRepr = tpe match {
    case AppliedType(_, _ :: typeArg2 :: _) => typeArg2.dealias
    case _ => fail(s"Cannot get 2nd type argument in '${tpe.show}'")
  }

  protected def duplicated[A](xs: collection.Seq[A]): collection.Seq[A] = xs.filter {
    val seen = new mutable.HashSet[A]
    x => !seen.add(x)
  }

  protected def namedValueOpt(namedAnnotation: Option[Term], tpe: TypeRepr): Option[String] =
    namedAnnotation.map { case Apply(_, List(param)) =>
      param match
        case Literal(StringConstant(s)) => s
        case _ =>
          fail(
            s"Cannot evaluate a parameter of the '@named' annotation in type '${tpe.show}': $param.",
          )
    }

  protected def isNonAbstractClass(tpe: TypeRepr): Boolean = tpe.classSymbol.fold(false) { symbol =>
    val flags = symbol.flags
    !(flags.is(Flags.Abstract) || flags.is(Flags.JavaDefined) || flags.is(Flags.Trait))
  }

  protected def isAbstractClassOrTraitOrEnum(tpe: TypeRepr): Boolean = tpe.classSymbol.fold(false) {
    symbol =>
      val flags = symbol.flags
      flags.is(Flags.Abstract) || flags.is(Flags.Trait) || flags.is(Flags.Enum)
  }

  protected def isEnumValue(tpe: TypeRepr): Boolean = tpe.termSymbol.flags.is(Flags.Enum)

  protected def isEnumOrModuleValue(tpe: TypeRepr): Boolean =
    isEnumValue(tpe) || tpe.typeSymbol.flags.is(Flags.Module)

  protected def enumOrModuleValueRef(tpe: TypeRepr): Term = Ref {
    if (isEnumValue(tpe)) tpe.termSymbol
    else tpe.typeSymbol.companionModule
  }

  protected def symbol(name: String, tpe: TypeRepr, flags: Flags = Flags.EmptyFlags): Symbol =
    Symbol.newVal(Symbol.spliceOwner, name, tpe, flags, Symbol.noSymbol)

  protected def getClassInfo(tpe: TypeRepr): ClassInfo = classInfos.getOrElseUpdate(
    tpe, {
      val tpeTypeArgs = typeArgs(tpe)
      val tpeClassSym = tpe.classSymbol.get
      val primaryConstructor = tpeClassSym.primaryConstructor
      val caseFields = tpeClassSym.caseFields
      var fieldMembers: List[Symbol] = null
      var companionRefAndClass: (Ref, Symbol) = null

      def createFieldInfos(params: List[Symbol], typeParams: List[Symbol]): List[FieldInfo] =
        params.map {
          var i = 0
          symbol =>
            i += 1
            val name = symbol.name
            var fieldTpe = tpe.memberType(symbol).dealias
            if (tpeTypeArgs ne Nil) fieldTpe = fieldTpe.substituteTypes(typeParams, tpeTypeArgs)
            fieldTpe match
              case _: TypeLambda =>
                fail(
                  s"Type lambdas are not supported for type '${tpe.show}' with field type for $name '${fieldTpe.show}'",
                )
              case _: TypeBounds =>
                fail(
                  s"Type bounds are not supported for type '${tpe.show}' with field type for $name '${fieldTpe.show}'",
                )
              case _ =>
            val defaultValue = if (symbol.flags.is(Flags.HasDefault)) new Some({
              if (companionRefAndClass eq null) {
                val typeSymbol = tpe.typeSymbol
                companionRefAndClass = (Ref(typeSymbol.companionModule), typeSymbol.companionClass)
              }
              val methodSymbol =
                companionRefAndClass._2.declaredMethod("$lessinit$greater$default$" + i).head
              val dvSelectNoTypes = Select(companionRefAndClass._1, methodSymbol)
              methodSymbol.paramSymss match
                case Nil => dvSelectNoTypes
                case List(params) if params.exists(_.isTypeParam) =>
                  TypeApply(dvSelectNoTypes, tpeTypeArgs.map(Inferred(_)))
                case paramss =>
                  fail(
                    s"Default method for $name of class ${tpe.show} have a complex parameter list: $paramss",
                  )
            })
            else None
            val getterOrField = caseFields.find(_.name == name) match
              case Some(caseField) => caseField
              case _ =>
                if (fieldMembers eq null) fieldMembers = tpeClassSym.fieldMembers
                fieldMembers.find(_.name == name) match
                  case Some(fieldMember) => fieldMember
                  case _ => Symbol.noSymbol
            if (!getterOrField.exists || getterOrField.flags.is(Flags.PrivateLocal)) {
              fail(
                s"Getter or field '$name' of '${tpe.show}' is private. It should be defined as 'val' or 'var' in the primary constructor.",
              )
            }
            var named: Option[Term] = None
            var kind: FieldKind = FieldKind.Regular
            getterOrField.annotations.foreach { annotation =>
              val aTpe = annotation.tpe
              if (aTpe =:= namedTpe) {
                if (named eq None) named = new Some(annotation)
                else fail(s"Duplicated '${namedTpe.show}' defined for '$name' of '${tpe.show}'.")
              } else {
                if (kind != FieldKind.Regular) {
                  fail(
                    s"Expected only one of annotation: '@id', '@value', '@simpleDict', '@compactDict', or '@expandedDict' for '$name' of '${tpe.show}'.",
                  )
                }
                if (aTpe =:= idTpe) kind = FieldKind.Id
                else if (aTpe =:= valueTpe) kind = FieldKind.Value
                else if (aTpe =:= simpleDictTpe) kind = FieldKind.SimpleDict
                else if (aTpe =:= compactDictTpe) kind = FieldKind.CompactDict
                else if (aTpe =:= expandedDictTpe) kind = FieldKind.ExpandedDict
              }
            }
            val mappedName = namedValueOpt(named, tpe) match
              case Some(name1) => name1
              case _ => name
            new FieldInfo(symbol, mappedName, getterOrField, defaultValue, fieldTpe, kind)
        }

      new ClassInfo(
        tpe,
        tpeTypeArgs,
        primaryConstructor,
        primaryConstructor.paramSymss match {
          case tps :: pss if tps.exists(_.isTypeParam) => pss.map(ps => createFieldInfos(ps, tps))
          case pss => pss.map(ps => createFieldInfos(ps, Nil))
        },
      )
    },
  )

  protected def adtLeafObjects(adtBaseTpe: TypeRepr): Seq[TypeRepr] = {
    val seen = new mutable.HashSet[TypeRepr]
    val subTypes = new mutable.ListBuffer[TypeRepr]

    def collectRecursively(tpe: TypeRepr): Unit =
      adtChildren(tpe).foreach { subTpe =>
        if (isEnumOrModuleValue(subTpe)) {
          if (seen.add(subTpe)) subTypes.addOne(subTpe)
        } else if (isAbstractClassOrTraitOrEnum(subTpe)) collectRecursively(subTpe)
        else {
          fail(
            "Only Scala objects are supported for ADT leaf classes. Please consider using of them for ADT with " +
              s"base '${adtBaseTpe.show}' or provide a custom implicitly accessible codec for the ADT base.",
          )
        }
      }
      if (isEnumOrModuleValue(tpe)) {
        if (seen.add(tpe)) subTypes.addOne(tpe)
      }

    collectRecursively(adtBaseTpe)
    if (subTypes.isEmpty)
      fail(
        s"Cannot find leaf objects for ADT base '${adtBaseTpe.show}'. " +
          "Please add them or provide a custom implicitly accessible codec for the ADT base.",
      )
    subTypes.toList
  }

  protected def adtChildren(tpe: TypeRepr): Seq[TypeRepr] = {
    def resolveParentTypeArg(
        child: Symbol,
        fromNudeChildTarg: TypeRepr,
        parentTarg: TypeRepr,
        binding: Map[String, TypeRepr],
    ): Map[String, TypeRepr] = {
      val typeSymbol = fromNudeChildTarg.typeSymbol
      if (typeSymbol.isTypeParam) { // TODO: check for paramRef instead ?
        val paramName = typeSymbol.name
        binding.get(paramName) match
          case None => binding.updated(paramName, parentTarg)
          case Some(oldBinding) =>
            if (oldBinding =:= parentTarg) binding
            else
              fail(
                s"Type parameter $paramName in class ${child.name} appeared in the constructor of " +
                  s"${tpe.show} two times differently, with ${oldBinding.show} and ${parentTarg.show}",
              )
      } else if (fromNudeChildTarg <:< parentTarg)
        binding // TODO: assure parentTag is covariant, get covariance from type parameters
      else {
        (fromNudeChildTarg, parentTarg) match
          case (AppliedType(ctycon, ctargs), AppliedType(ptycon, ptargs)) =>
            ctargs.zip(ptargs).foldLeft(resolveParentTypeArg(child, ctycon, ptycon, binding)) {
              (b, e) =>
                resolveParentTypeArg(child, e._1, e._2, b)
            }
          case _ =>
            fail(
              s"Failed unification of type parameters of ${tpe.show} from child $child - " +
                s"${fromNudeChildTarg.show} and ${parentTarg.show}",
            )
      }
    }

    def resolveParentTypeArgs(
        child: Symbol,
        nudeChildParentTags: List[TypeRepr],
        parentTags: List[TypeRepr],
        binding: Map[String, TypeRepr],
    ): Map[String, TypeRepr] =
      nudeChildParentTags.zip(parentTags).foldLeft(binding)((b, e) =>
        resolveParentTypeArg(child, e._1, e._2, b),
      )

    val typeSymbol = tpe.typeSymbol
    typeSymbol.children.map { sym =>
      if (sym.isType) {
        if (
          sym.name == "<local child>" // scala 2 anonymous class extending typeSymbol type
          || sym == typeSymbol // scala 3 anonymous class extending typeSymbol type
        )
          fail(
            s"Local child symbols are not supported, please consider change '${tpe.show}' or " +
              "implement a custom implicitly accessible codec",
          )
        val nudeSubtype = sym.typeRef
        val tpeArgsFromChild = typeArgs(nudeSubtype.baseType(typeSymbol))
        nudeSubtype.memberType(sym.primaryConstructor) match
          case _: MethodType => nudeSubtype
          case PolyType(names, _, resPolyTp) =>
            val tpBinding = resolveParentTypeArgs(sym, tpeArgsFromChild, typeArgs(tpe), Map.empty)
            val ctArgs = names.map { name =>
              tpBinding.getOrElse(
                name,
                fail(
                  s"Type parameter '$name' of '$sym' can't be deduced from " +
                    s"type arguments of '${tpe.show}'. Please provide a custom implicitly accessible codec for it.",
                ),
              )
            }
            val polyRes = resPolyTp match
              case MethodType(_, _, resTp) => resTp
              case other => other // hope we have no multiple typed param lists yet.
            if (ctArgs.isEmpty) polyRes
            else
              polyRes match
                case AppliedType(base, _) => base.appliedTo(ctArgs)
                case AnnotatedType(AppliedType(base, _), annot) =>
                  AnnotatedType(base.appliedTo(ctArgs), annot)
                case _ => polyRes.appliedTo(ctArgs)
          case other =>
            fail(
              s"Primary constructor for '${tpe.show}' is not 'MethodType' or 'PolyType' but '$other'",
            )
      } else if (sym.isTerm) Ref(sym).tpe
      else
        fail(
          "Only Scala objects are supported for ADT leaf classes. " +
            s"Please consider using of them for ADT with base '${tpe.show}' or " +
            "provide a custom implicitly accessible codec for the ADT base.",
        )
    }
  }

  protected def enumValueName(tpe: TypeRepr): String =
    val isEnumVal = isEnumValue(tpe)
    val symbol =
      if (isEnumVal) tpe.termSymbol
      else tpe.typeSymbol
    val named = symbol.annotations.filter(_.tpe =:= namedTpe)
    if (named ne Nil) {
      if (named.size > 1) fail(s"Duplicated '${namedTpe.show}' defined for '${tpe.show}'.")
      namedValueOpt(named.headOption, tpe).get
    } else {
      val name = symbol.name
      if (symbol.flags.is(Flags.Module)) name.substring(0, name.length - 1)
      else name
    }
}
