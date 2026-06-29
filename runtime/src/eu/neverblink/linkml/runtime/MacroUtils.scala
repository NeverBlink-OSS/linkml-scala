package eu.neverblink.linkml.runtime

import eu.neverblink.linkml.runtime.*

import scala.collection.mutable
import scala.quoted.*

/** A collection of utility methods and classes to facilitate Scala 3 metaprogramming (macros) for
  * the LinkML runtime. * Provides extraction, caching, and evaluation of type structures, algebraic
  * data types (ADTs), and LinkML-specific annotations (e.g., `@named`, `@id`, `@value`).
  */
trait MacroUtils(using val quotes: Quotes) {
  import quotes.reflect.*

  /** Represents structural information about a class type during macro expansion.
    *
    * @param tpe
    *   The type representation (`TypeRepr`) of the class.
    * @param tpeTypeArgs
    *   A list of type arguments applied to this class type.
    * @param primaryConstructor
    *   The symbol representing the primary constructor of the class.
    * @param paramLists
    *   A nested list of `FieldInfo` corresponding to the parameter lists of the primary
    *   constructor.
    */
  class ClassInfo(
      val tpe: TypeRepr,
      val tpeTypeArgs: List[TypeRepr],
      val primaryConstructor: Symbol,
      val paramLists: List[List[FieldInfo]],
  ) {

    /** A flattened list of all fields defined in the primary constructor parameter lists. */
    val fields: List[FieldInfo] = paramLists.flatten

    // Initialization block to validate fields for LinkML serialization rules.
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

    /** Generates an Abstract Syntax Tree (AST) `Term` that instantiates this class using the
      * provided arguments.
      *
      * @param argss
      *   The lists of arguments to pass to the primary constructor.
      * @return
      *   A `Term` representing the `new Class(...)` expression.
      */
    def genNew(argss: List[List[Term]]): Term =
      val constructorNoTypes = Select(New(Inferred(tpe)), primaryConstructor)
      val constructor =
        if (tpeTypeArgs eq Nil) constructorNoTypes
        else TypeApply(constructorNoTypes, tpeTypeArgs.map(Inferred(_)))
      argss.tail.foldLeft(Apply(constructor, argss.head))(Apply(_, _))
  }

  /** Represents macro-level information about a specific field within a class.
    *
    * @param symbol
    *   The symbol of the field.
    * @param mappedName
    *   The name of the field to be used during serialization (potentially overridden by `@named`).
    * @param getterOrField
    *   The symbol for the getter method or the field itself.
    * @param defaultValue
    *   An optional AST `Term` representing the field's default value, if defined.
    * @param resolvedTpe
    *   The fully resolved type representation (`TypeRepr`) of the field.
    * @param kind
    *   The structural role of this field in LinkML mappings (e.g., ID, Value, Dictionary).
    */
  class FieldInfo(
      val symbol: Symbol,
      val mappedName: String,
      val getterOrField: Symbol,
      val defaultValue: Option[Term],
      val resolvedTpe: TypeRepr,
      val kind: FieldKind,
  )

  /** Defines the specific mapping behavior or structural role a field has within a LinkML schema.
    */
  enum FieldKind {

    /** A standard field mapped directly to its key/value. */
    case Regular

    /** A field acting as the unique identifier (`@id`). */
    case Id

    /** A field acting as the primary value carrier (`@value`). */
    case Value

    /** A field mapped as a simple dictionary (`@simpleDict`). */
    case SimpleDict

    /** A field mapped as a compact dictionary (`@compactDict`). */
    case CompactDict

    /** A field mapped as an expanded dictionary (`@expandedDict`). */
    case ExpandedDict
  }

  // --- Common cached TypeRefs and TypeReprs for faster macro expansion ---
  val intTpe: TypeRef = defn.IntClass.typeRef
  val booleanTpe: TypeRef = defn.BooleanClass.typeRef
  val stringTpe: TypeRef = defn.StringClass.typeRef
  val anyTpe: TypeRef = defn.AnyClass.typeRef
  val wildcardBounds: TypeBounds = TypeBounds(defn.NothingClass.typeRef, anyTpe)
  val optionOfAnyTpe: TypeRepr = defn.OptionClass.typeRef.appliedTo(anyTpe)
  val optionOfWildcardTpe: TypeRepr = defn.OptionClass.typeRef.appliedTo(wildcardBounds)
  val referenceValidatorTpe: TypeRef =
    Symbol.requiredClass("eu.neverblink.linkml.schemaview.MacroValidator").typeRef
  val seqOfWildcardTpe: TypeRepr =
    Symbol.requiredClass("scala.collection.immutable.Seq").typeRef.appliedTo(wildcardBounds)
  val mapOfWildcardsTpe: TypeRepr = Symbol.requiredClass(
    "scala.collection.immutable.Map",
  ).typeRef.appliedTo(wildcardBounds :: wildcardBounds :: Nil)

  /** Reports an error during macro expansion and immediately aborts the compilation.
    *
    * @param msg
    *   The error message to display to the developer.
    * @return
    *   `Nothing` as it throws an exception to stop compilation.
    */
  def fail(msg: String): Nothing = report.errorAndAbort(msg, Position.ofMacroExpansion)

  /** Extracts the type arguments from an applied type.
    *
    * @param tpe
    *   The type representation to analyze.
    * @return
    *   A list of type representations representing the type arguments, or an empty list if none
    *   exist.
    */
  def typeArgs(tpe: TypeRepr): List[TypeRepr] = tpe match {
    case AppliedType(_, typeArgs) => typeArgs.map(_.dealias)
    case _ => Nil
  }

  /** Extracts the first type argument from an applied type. Aborts compilation if it cannot be
    * found.
    */
  def typeArg1(tpe: TypeRepr): TypeRepr = tpe match {
    case AppliedType(_, typeArg1 :: _) => typeArg1.dealias
    case _ => fail(s"Cannot get 1st type argument in '${tpe.show}'")
  }

  /** Extracts the second type argument from an applied type. Aborts compilation if it cannot be
    * found.
    */
  def typeArg2(tpe: TypeRepr): TypeRepr = tpe match {
    case AppliedType(_, _ :: typeArg2 :: _) => typeArg2.dealias
    case _ => fail(s"Cannot get 2nd type argument in '${tpe.show}'")
  }

  /** Filters a sequence to return only the elements that appear more than once.
    *
    * @param xs
    *   The sequence to evaluate.
    * @return
    *   A sequence of duplicated elements.
    */
  def duplicated[A](xs: collection.Seq[A]): collection.Seq[A] = xs.filter {
    val seen = new mutable.HashSet[A]
    x => !seen.add(x)
  }

  /** Extracts the string value from a `@named("value")` annotation.
    *
    * @param namedAnnotation
    *   An optional AST `Term` representing the annotation.
    * @param tpe
    *   The context type where this annotation resides (used for error reporting).
    * @return
    *   An `Option` containing the extracted string, if present and valid.
    */
  def namedValueOpt(namedAnnotation: Option[Term], tpe: TypeRepr): Option[String] =
    namedAnnotation.map { case Apply(_, List(param)) =>
      param match
        case Literal(StringConstant(s)) => s
        case _ =>
          fail(
            s"Cannot evaluate a parameter of the '@named' annotation in type '${tpe.show}': $param.",
          )
    }

  /** Checks if a given type representation points to a concrete (non-abstract) class.
    */
  def isNonAbstractClass(tpe: TypeRepr): Boolean = tpe.classSymbol.fold(false) { symbol =>
    val flags = symbol.flags
    !(flags.is(Flags.Abstract) || flags.is(Flags.JavaDefined) || flags.is(Flags.Trait))
  }

  /** Checks if a given type representation points to an abstract class, a trait, or an enum.
    */
  def isAbstractClassOrTraitOrEnum(tpe: TypeRepr): Boolean = tpe.classSymbol.fold(false) { symbol =>
    val flags = symbol.flags
    flags.is(Flags.Abstract) || flags.is(Flags.Trait) || flags.is(Flags.Enum)
  }

  /** Checks if a given type representation points specifically to an enum value.
    */
  def isEnumValue(tpe: TypeRepr): Boolean = tpe.termSymbol.flags.is(Flags.Enum)

  /** Checks if a given type representation points to an enum value or a module (singleton object).
    */
  def isEnumOrModuleValue(tpe: TypeRepr): Boolean =
    isEnumValue(tpe) || tpe.typeSymbol.flags.is(Flags.Module)

  /** Returns a reference (`Term`) to an enum value or a companion module/object.
    *
    * @param tpe
    *   The type representation of the enum value or module.
    * @return
    *   A `Ref` pointing to the term or companion module.
    */
  def enumOrModuleValueRef(tpe: TypeRepr): Term = Ref {
    if (isEnumValue(tpe)) tpe.termSymbol
    else tpe.typeSymbol.companionModule
  }

  /** Creates a new symbol representing a value (`val`).
    *
    * @param name
    *   The name of the symbol.
    * @param tpe
    *   The type of the symbol.
    * @param flags
    *   The flags applicable to the symbol (defaults to EmptyFlags).
    * @return
    *   A newly created `Symbol`.
    */
  def symbol(name: String, tpe: TypeRepr, flags: Flags = Flags.EmptyFlags): Symbol =
    Symbol.newVal(Symbol.spliceOwner, name, tpe, flags, Symbol.noSymbol)

  /** Analyzes a class type to construct a `ClassInfo` object containing its parsed fields,
    * constructors, and applied type boundaries. Results are cached to optimize macro performance.
    *
    * @param tpe
    *   The class type representation to analyze.
    * @return
    *   The constructed (or cached) `ClassInfo`.
    */
  def getClassInfo(tpe: TypeRepr): ClassInfo = classInfos.getOrElseUpdate(
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

  /** Recursively finds all "leaf" objects (singleton modules or enum values) within an Algebraic
    * Data Type (ADT) hierarchy.
    *
    * @param adtBaseTpe
    *   The base type representation (e.g., a sealed trait) of the ADT.
    * @return
    *   A sequence of type representations for all concrete leaf objects.
    */
  def adtLeafObjects(adtBaseTpe: TypeRepr): Seq[TypeRepr] = {
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

  /** Finds the immediate child types extending a given sealed type representation, resolving type
    * arguments along the way.
    *
    * @param tpe
    *   The parent type representation.
    * @return
    *   A sequence of child type representations.
    */
  def adtChildren(tpe: TypeRepr): Seq[TypeRepr] = {
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

  /** Resolves the string name representing an enum or module value, accounting for `@named`
    * annotations or standard symbol names.
    *
    * @param tpe
    *   The type representation of the enum value.
    * @return
    *   The extracted string name to be used for serialization.
    */
  def enumValueName(tpe: TypeRepr): String =
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
}
