package eu.neverblink.linkml.generator.javagen

import eu.neverblink.linkml.generator.options.{JavaGeneratorOptions, JavaStyle}
import eu.neverblink.linkml.generator.util.{DocLines, GeneratedHeader, Pruning}
import eu.neverblink.linkml.generator.util.Pruning.pruning
import eu.neverblink.linkml.generator.util.metadataLanguage
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.runtime.LinkmlAny
import eu.neverblink.linkml.runtime.StringUtils.hexDigit
import eu.neverblink.linkml.schemaview.*

import scala.collection.mutable
import scala.util.control.NonFatal

/** Generates Java classes that load and dump the same JSON as the JSON Schema generator describes,
  * one file per class, enum and document type. How they read and write JSON depends on the
  * [[JavaGeneratorOptions.style style]]. See: [[StyleWriter]].
  */
final class JavaGenerator(using sv: SchemaView) extends JavaRenamer {
  import JavaGenerator.*
  import JavaRenamer.{keywords, unreserved}

  /** The generated files, by file name. */
  def generate(options: JavaGeneratorOptions = JavaGeneratorOptions()): Seq[(String, String)] = {
    given JavaGeneratorOptions = options
    val writer: StyleWriter = options.style match {
      case JavaStyle.Jackson => JacksonWriter(options.strict)
    }
    val pkg = packageName(options)
    val defs = definitions(options)
    checkNames(defs)
    val ctx = Context(defs, writer)
    val header = GeneratedHeader("//")

    def file(name: String, body: String): (String, String) =
      s"$name.java" -> JavaGenerator.file(header, pkg, body)

    val classes = defs.classes.map { cls =>
      val model = javaClass(cls, ctx)
      file(model.name, writeClass(model, ctx))
    }
    val enums = defs.enums.map(ev => file(enumName(ev), writeEnum(ev, writer)))
    val document = defs.document.map { (root, name) =>
      val model =
        JavaDocument(name, documentType(root, ctx), Seq(s"A whole document: ${documentDoc(root)}."))
      file(name, writer.document(model))
    }
    val errors = defs.errors.map(cls => file(errorName(cls), writeError(cls)))
    val functions = Option.when(defs.functions.nonEmpty) {
      file(functionsInterfaceName(sv), writeFunctions(defs.functions, ctx))
    }
    val types = classes ++ enums ++ document ++ errors ++ functions
    val support = writer.supportFiles(types.map(_._2)).map((name, body) => file(name, body))
    (types ++ support).sortBy(_._1)
  }

  /** The package of the generated classes: the option, or the schema's name. */
  def packageName(options: JavaGeneratorOptions): String =
    options.`package`.getOrElseFast {
      val name = sv.root.name.toLowerCase.filter(c => Case.isAlphaLower(c) || Case.isNumeric(c))
      if name.isEmpty || !Case.isAlphaLower(name.head) || keywords(name) then "model" else name
    }

  private def definitions(options: JavaGeneratorOptions): Definitions = {
    val query =
      sv.withTypeDesignatorMembers(options.pruning.derivedQuery(true, true), true, true)
    val classes = sv.sortedClasses.filter(c => !c.isAny && query.reachable(c))
    val enums = sv.enums.values.filter(ev => query.reachable(ev) && definesEnum(ev))
      .toVector.sortBy(ev => sv.elementOrder(ev._enum))
    val treeRootOverride = options.pruning match {
      case Pruning.treeRoot(o) => o
      case _ => None
    }
    val root = sv.treeRootWithOverride(treeRootOverride).get
      .filter(r => classes.exists(_.name == r.name))
      .filter { r =>
        r.treeRootInlineType(None) match {
          case InlineType.list | InlineType.dict(_) => true
          case _ => false
        }
      }
    val functions = sv.functions.values.filter(query.reachable).toVector
      .sortBy(fv => sv.elementOrder(fv.function))
    val errors = functions.flatMap(_.errors).distinctBy(_.name)
      .sortBy(cls => sv.elementOrder(cls.cls))
    Definitions(
      classes,
      enums,
      root.map(r => (r, unreserved(className(r).concat("Document")))),
      functions,
      errors,
    )
  }

  /** Fails if two types of the package have the same name. Case is ignored, as each type is a file.
    */
  private def checkNames(defs: Definitions): Unit = {
    val seen = mutable.HashMap.empty[String, String]
    def claim(name: String, owner: String): Unit =
      seen.put(name.toLowerCase, owner).foreachFast { other =>
        throw new IllegalArgumentException(
          s"$owner and $other both map to the Java name '$name'. " +
            "Rename one of them to generate Java.",
        )
      }
    defs.classes.foreach(cls => claim(className(cls), s"class '${cls.name}'"))
    defs.enums.foreach(ev => claim(enumName(ev), s"enum '${ev.name}'"))
    defs.document.foreachFast((root, name) =>
      claim(name, s"the document type of tree root '${root.name}'"),
    )
    defs.errors.foreach(cls => claim(errorName(cls), s"the error class of '${cls.name}'"))
    if defs.functions.nonEmpty then claim(functionsInterfaceName(sv), "the functions of the schema")
  }

  /** Java field names of a class by JSON key. */
  private def fieldNames(cls: ClassView, ctx: Context): Map[String, String] =
    ctx.fieldNames.getOrElseUpdate(
      cls.name, {
        val names = cls.sortedAttributeViews.map { a =>
          val key = slotName(a.slotView)
          key -> JavaRenamer.field(key)
        }
        val seen = mutable.HashMap.empty[String, String]
        names.foreach { (key, name) =>
          seen.put(name, key).foreachFast { other =>
            throw new IllegalArgumentException(
              s"Slots '$other' and '$key' of class '${cls.name}' both map to the Java name " +
                s"'$name'. Rename or alias one of them to generate Java.",
            )
          }
        }
        names.toMap
      },
    )

  /** The `is_a` parent, if it is generated. Mixins are not Java types: a class declares the slots
    * of its mixins itself.
    */
  private def parentOf(cls: ClassView, ctx: Context): Option[ClassView] =
    cls.cls.isA.flatMap(r => sv.classes.get(r.value)).filter(p => ctx.generated(p.name))

  /** Whether the JSON library can pick the class of a value by its type designator. */
  private def isDesignatedUnion(cls: ClassView): Boolean =
    cls.isTypeDesignatorUnion && cls.typeDesignatorMembers.nonEmpty &&
      cls.typeDesignatorMembers.forall(_.typeDesignatorValues.nonEmpty) &&
      cls.typeDesignatorView.exists(isString)

  private def isString(attribute: AttributeView): Boolean = attribute match {
    case tav: TypeAttributeView =>
      !tav.slotView.slot.multivalued && !tav.typeView.isUnion && scalarOf(tav.typeView) ==
        Right(Scalar.Str)
    case _ => false
  }

  private def isOpen(cls: ClassView, ctx: Context): Boolean =
    ctx.options.open || cls.allowsExtraSlots

  private def field(attribute: AttributeView, owner: ClassView, ctx: Context): JavaField = {
    val key = slotName(attribute.slotView)
    val doc = DocLines(attribute.slotView.slot)(using metadataLanguage(using ctx.options)) ++
      referenceDoc(attribute)
    val tpe = fieldType(attribute, ctx)
    JavaField(key, fieldNames(owner, ctx)(key), tpe, tpe, checks(attribute), doc)
  }

  /** The constraints of the schema on the values of a slot. Not for the members of a union. */
  private def checks(attribute: AttributeView): Checks = {
    val slot = attribute.slotView.slot
    val counts = Checks(
      required = slot.required,
      minCount = if slot.multivalued then slot.minimumCardinality.orElseFast(slot.exactCardinality)
      else None,
      maxCount = if slot.multivalued then slot.maximumCardinality.orElseFast(slot.exactCardinality)
      else None,
    )
    val values = Option.when(attribute.rangeAlternatives.isEmpty)(attribute).collect {
      case tav: TypeAttributeView if !tav.typeView.isUnion => tav
      case ClassReferenceAttributeView(_, _, _, id) if !id.typeView.isUnion => id
    }
    values.foldFast(counts) { tav =>
      scalarOf(tav.typeView) match {
        case Right(Scalar.Str | Scalar.Text) =>
          counts.copy(
            minLength = tav.minimumLength,
            maxLength = tav.maximumLength,
            pattern = tav.pattern,
          )
        case Right(Scalar.Int | Scalar.Num) | Left("BigDecimal") =>
          counts.copy(minValue = number(tav.minimumValue), maxValue = number(tav.maximumValue))
        case _ => counts
      }
    }
  }

  /** Whether a value of type [[read]] can be stored in a field of type [[declared]]. */
  private def fits(read: FieldType, declared: FieldType, ctx: Context): Boolean = {
    def subclass(child: String, parent: String) = child == parent ||
      ctx.classByJavaName.get(child).exists(_.ancestorsWithSelf.exists(a => className(a) == parent))
    def single(tpe: FieldType) = tpe match {
      case FieldType.Native(_) | FieldType.Strict(_, _, false) => true
      case _ => false
    }
    (read, declared) match {
      // Any single value can go into an Object
      case (r, FieldType.Native("Object") | FieldType.Strict("Object", _, false)) if single(r) =>
        true
      case (FieldType.Native(c), FieldType.Native(p)) => subclass(c, p)
      case (FieldType.NativeList(c), FieldType.NativeList(p)) => subclass(c, p)
      case (c: FieldType.Dict, p: FieldType.Dict) =>
        subclass(c.valueClass, p.valueClass) && c.keyField.name == p.keyField.name
      case (FieldType.Strict(c, _, cm), FieldType.Strict(p, _, pm)) => c == p && cm == pm
      case _ => read == declared
    }
  }

  private def javaClass(cls: ClassView, ctx: Context): JavaClass =
    ctx.classes.getOrElseUpdate(cls.name, makeClass(cls, ctx))

  private def makeClass(cls: ClassView, ctx: Context): JavaClass = {
    val parent = parentOf(cls, ctx)
    val parentFields = parent.foldFast(Map.empty[String, JavaField])(p =>
      javaClass(p, ctx).allFields.map(f => f.key -> f).toMap,
    )
    // An inherited field keeps its declared type. A subclass that narrows the range reads the
    // narrower type, if it fits the field.
    // The objects of a dict may leave out the key, so it is not required
    val keyOfDict = ctx.dictKeys.get(cls.name)
    val all = cls.sortedAttributeViews.map { a =>
      val f = field(a, cls, ctx)
      if keyOfDict.contains(a.slotView.name) then f.copy(checks = f.checks.copy(required = false))
      else f
    }.map { f =>
      parentFields.get(f.key).fold(f) { p =>
        f.copy(tpe = p.tpe, read = if fits(f.read, p.tpe, ctx) then f.read else p.tpe)
      }
    }
    val inherited = parentFields.keySet
    val designator =
      if !cls.isConcrete then None
      else
        cls.typeDesignatorView.filter(isString).flatMap { d =>
          val key = slotName(cls.derivedAttributes(d.slotView.name))
          cls.typeDesignatorValues.lastOption.flatMap(v => all.find(_.key == key).map(_ -> v))
        }
    val union = Option.when(isDesignatedUnion(cls)) {
      val key = slotName(cls.derivedAttributes(cls.typeDesignatorView.get.slotView.name))
      key -> cls.typeDesignatorMembers.map(m => className(m) -> m.typeDesignatorValues)
    }
    val bare = bareFields(cls, ctx)
    JavaClass(
      cls,
      className(cls),
      parent.map(className),
      all.filterNot(f => inherited(f.key)),
      all,
      !cls.isConcrete && union.isDefined,
      isOpen(cls, ctx),
      parent.map(isOpen(_, ctx)),
      designator,
      union,
      bare,
      DocLines(cls.cls)(using metadataLanguage(using ctx.options)),
    )
  }

  /** For a class inlined as a simple dict: its key and value fields, if an object can be written as
    * just its value. Not for a class with subclasses, whose objects may have more fields.
    */
  private def bareFields(cls: ClassView, ctx: Context): Option[(JavaField, JavaField)] =
    ctx.simpleDicts.get(cls.name).filter(_ => !cls.hasDescendants).flatMap { form =>
      val value = cls.attributeViews(form.value)
      Option.unless(value.isInstanceOf[ClassInlineAttributeView]) {
        (field(cls.attributeViews(form.key), cls, ctx), field(value, cls, ctx))
      }.filter((_, v) => StyleWriter.bareParameter(v.tpe).isDefined)
    }

  /** The [[Scalar]] of a type, or the Java type that Jackson reads as is. */
  private def scalarOf(tv: TypeView): Either[String, Scalar] = tv.runtimeType match {
    case IntegerType => Right(Scalar.Int)
    case FloatType | DoubleType => Right(Scalar.Num)
    case BooleanType => Right(Scalar.Bool)
    case DecimalType => Left("BigDecimal")
    case DateType => Right(Scalar.Date)
    case DateTimeType => Right(Scalar.DateTime)
    case TimeType => Right(Scalar.Time)
    case LocalizedTextType => Right(Scalar.Text)
    case AnyType | UnknownType => Left("Object")
    case StringType | UriOrCurieType | UriType | CurieType | NcNameType => Right(Scalar.Str)
  }

  /** The type of one value of a type, which may be a `union_of`. */
  private def typeValue(tv: TypeView): Single =
    if tv.isUnion then union(tv.unionAlternatives.map(m => typeValue(m)))
    else
      scalarOf(tv) match {
        case Right(s) => Single.Read(s.java, Reader.Of(s))
        case Left(java) => Single.Plain(java)
      }

  /** The type of a value that may have any of these types. */
  private def union(members: Seq[Single]): Single = {
    val distinct = members.distinct
    if distinct.sizeIs == 1 then distinct.head
    else {
      val readers = distinct.collect {
        case Single.Read(_, r) => r
        case Single.Plain(java, true) => Reader.Enum(java)
      }
      if readers.sizeIs == distinct.size then
        Single.Read(
          "Object",
          Reader.AnyOf(readers.flatMap {
            case Reader.AnyOf(inner) => inner
            case r => Seq(r)
          }.distinct),
        )
      else Single.Plain("Object")
    }
  }

  /** The type of one value of an attribute's range, which can't be a dict. */
  private def single(attribute: AttributeView): Single = attribute match {
    case _: AnyView => Single.Plain("Object")
    case ClassInlineAttributeView(_, _, classView, _) =>
      if classView.isAny then Single.Plain("Object") else Single.Plain(className(classView))
    case ClassReferenceAttributeView(_, _, _, identifierView) => typeValue(identifierView.typeView)
    case tav: TypeAttributeView => typeValue(tav.typeView)
    case EnumAttributeView(_, _, ev) =>
      if definesEnum(ev) then Single.Plain(enumName(ev), true)
      else Single.Read("String", Reader.Of(Scalar.Str))
  }

  private def fieldType(attribute: AttributeView, ctx: Context): FieldType = {
    val multivalued = attribute.slotView.slot.multivalued
    val alternatives = attribute.rangeAlternatives
    val value =
      if alternatives.nonEmpty then union(alternatives.map(single(_)))
      else
        attribute match {
          case ClassInlineAttributeView(_, _, cls, InlineType.dict(form)) if !cls.isAny =>
            return dictType(cls, form, ctx)
          case _ => single(attribute)
        }
    value match {
      case Single.Plain(java, _) =>
        if multivalued then FieldType.NativeList(java) else FieldType.Native(java)
      case Single.Read(java, reader) => FieldType.Strict(java, reader, multivalued)
    }
  }

  private def dictType(cls: ClassView, form: DictForm, ctx: Context): FieldType.Dict = {
    val keyAttribute = cls.attributeViews(form.key)
    val keyField = field(keyAttribute, cls, ctx)
    val keyScalar = keyAttribute match {
      case tav: TypeAttributeView => scalarOf(tav.typeView).getOrElse(Scalar.Str)
      case _ => Scalar.Str
    }
    val construct = !(!cls.isConcrete && isDesignatedUnion(cls))
    val bare = form.isInstanceOf[CollectionForm.SimpleDict] && bareFields(cls, ctx).isDefined
    FieldType.Dict(className(cls), keyField, keyScalar, construct, bare)
  }

  private def documentType(root: ClassView, ctx: Context): FieldType =
    root.treeRootInlineType(None) match {
      case InlineType.dict(form) => dictType(root, form, ctx)
      case _ => FieldType.NativeList(className(root))
    }

  private def documentDoc(root: ClassView): String =
    root.treeRootInlineType(None) match {
      case InlineType.dict(_) => s"a dict of ${root.name} by key"
      case _ => s"a list of ${root.name}"
    }

  private def referenceDoc(attribute: AttributeView): Option[String] = {
    val alternatives = attribute.rangeAlternatives
    val classes = (if alternatives.isEmpty then Seq(attribute) else alternatives).collect {
      case ref: ClassReferenceAttributeView => className(ref.classView)
    }
    Option.when(classes.nonEmpty)(classes.mkString("Reference to ", " or ", ""))
  }

  private def writeClass(cls: JavaClass, ctx: Context): String = {
    val out = Code()
    val writer = ctx.writer
    out.javadoc(cls.doc, 0)
    writer.classAnnotations(cls).foreach(out.line(0, _))
    val modifiers = if cls.isAbstract then "public abstract class " else "public class "
    out.line(0, modifiers + cls.name + cls.parent.fold("")(p => s" extends $p") + " {")

    var first = true
    def gap(): Unit = if first then first = false else out.blank()
    cls.fields.foreach { f =>
      gap()
      writer.fieldAnnotations(f).foreach(out.line(1, _))
      out.line(1, s"private ${f.tpe.javaType} ${f.name};")
    }
    if cls.open && !cls.parentOpen.contains(true) then {
      gap()
      out.line(1, "private final Map<String, Object> extraProperties = new LinkedHashMap<>();")
    }

    cls.designator.foreachFast { (f, value) =>
      gap()
      // javac -Xlint warns about calling a non-final setter in a constructor
      out.line(1, "@SuppressWarnings(\"this-escape\")")
      out.line(1, s"public ${cls.name}() {")
      out.line(2, s"${f.setter}(${javaString(value)});")
      out.line(1, "}")
    }

    cls.fields.foreach { f =>
      out.blank()
      out.javadoc(f.doc, 1)
      out.line(1, s"public ${f.tpe.javaType} ${f.getter}() {")
      out.line(2, s"return ${f.name};")
      out.line(1, "}")
      out.blank()
      out.line(1, s"public void ${f.setter}(${f.tpe.javaType} ${f.name}) {")
      out.line(2, s"this.${f.name} = ${f.name};")
      out.line(1, "}")
    }
    if cls.open && !cls.parentOpen.contains(true) then {
      out.blank()
      out.javadoc(Seq("The properties beyond the ones in the schema, by key."), 1)
      out.line(1, "public Map<String, Object> getExtraProperties() {")
      out.line(2, "return extraProperties;")
      out.line(1, "}")
    }

    writeEquals(cls, out)
    writeToString(cls, out)

    writer.jsonMembers(cls, out)
    out.line(0, "}")
    out.result
  }

  private def writeEquals(cls: JavaClass, out: Code): Unit = {
    val ownsExtra = cls.open && !cls.parentOpen.contains(true)
    val compared = cls.fields.map(_.name) ++ Option.when(ownsExtra)("extraProperties")
    out.blank()
    out.line(1, "@Override")
    out.line(1, "public boolean equals(Object o) {")
    out.line(2, "if (this == o) {")
    out.line(3, "return true;")
    out.line(2, "}")
    if cls.parent.isDefined then {
      out.line(2, "if (!super.equals(o)) {")
      out.line(3, "return false;")
      out.line(2, "}")
    } else {
      out.line(2, "if (o == null || getClass() != o.getClass()) {")
      out.line(3, "return false;")
      out.line(2, "}")
    }
    if compared.isEmpty then out.line(2, "return true;")
    else {
      out.line(2, s"${cls.name} other = (${cls.name}) o;")
      val checks = compared.map(n => s"Objects.equals($n, other.$n)")
      out.line(2, "return " + checks.mkString("\n" + indent(1) + "&& ") + ";")
    }
    out.line(1, "}")
    out.blank()
    out.line(1, "@Override")
    out.line(1, "public int hashCode() {")
    val hashed = Option.when(cls.parent.isDefined)("super.hashCode()").toSeq ++ compared
    if hashed.isEmpty then out.line(2, s"return ${javaString(cls.name)}.hashCode();")
    else out.line(2, s"return Objects.hash(${hashed.mkString(", ")});")
    out.line(1, "}")
  }

  private def writeToString(cls: JavaClass, out: Code): Unit = {
    out.blank()
    out.line(1, "@Override")
    out.line(1, "public String toString() {")
    val parts = cls.allFields.map(f => s"${f.name}=\" + ${f.getter}()") ++
      Option.when(cls.open)("extraProperties=\" + getExtraProperties()")
    if parts.isEmpty then out.line(2, s"return ${javaString(cls.name + "{}")};")
    else {
      out.line(2, s"return \"${cls.name}{\"")
      parts.zipWithIndex.foreach { (p, i) =>
        out.line(3, s"+ \"${if i == 0 then "" else ", "}$p")
      }
      out.line(3, "+ \"}\";")
    }
    out.line(1, "}")
  }

  private def writeEnum(ev: EnumView, writer: StyleWriter)(using
      options: JavaGeneratorOptions,
  ): String = {
    val out = Code()
    val name = enumName(ev)
    out.javadoc(DocLines(ev._enum), 0)
    out.line(0, s"public enum $name {")
    val seen = mutable.HashMap.empty[String, String]
    val values = ev._enum.permissibleValues.values.toSeq
    values.zipWithIndex.foreach { (pv, i) =>
      val value = permissibleValueName(ev, pv)
      val constant = JavaRenamer.constant(value)
      seen.put(constant, value).foreachFast { other =>
        throw new IllegalArgumentException(
          s"Values '$other' and '$value' of enum '${ev.name}' both map to the Java name " +
            s"'$constant'. Rename one of them to generate Java.",
        )
      }
      if i > 0 && DocLines(pv).nonEmpty then out.blank()
      out.javadoc(DocLines(pv), 1)
      out.line(1, s"$constant(${javaString(value)})${if i == values.size - 1 then ";" else ","}")
    }
    out.blank()
    out.line(1, "private final String value;")
    out.blank()
    out.line(1, s"$name(String value) {")
    out.line(2, "this.value = value;")
    out.line(1, "}")
    out.blank()
    out.javadoc(Seq("The value in the JSON data."), 1)
    writer.enumValueAnnotations.foreach(out.line(1, _))
    out.line(1, "public String getValue() {")
    out.line(2, "return value;")
    out.line(1, "}")
    out.blank()
    out.javadoc(
      Seq(
        "The constant with the given value in the JSON data.",
        "",
        "@throws IllegalArgumentException if no constant has it",
      ),
      1,
      tags = true,
    )
    writer.enumFactoryAnnotations.foreach(out.line(1, _))
    out.line(1, s"public static $name fromValue(Object value) {")
    out.line(2, s"for ($name constant : values()) {")
    out.line(3, "if (constant.value.equals(value)) {")
    out.line(4, "return constant;")
    out.line(3, "}")
    out.line(2, "}")
    out.line(
      2,
      s"throw new IllegalArgumentException(${javaString(s"not a value of $name: ")} + value);",
    )
    out.line(1, "}")
    out.blank()
    out.line(1, "@Override")
    out.line(1, "public String toString() {")
    out.line(2, "return value;")
    out.line(1, "}")
    out.line(0, "}")
    out.result
  }

  /** An exception that wraps an error class of a function, so that an implementation can throw it.
    */
  private def writeError(cls: ClassView): String = {
    val out = Code()
    val name = errorName(cls)
    val error = className(cls)
    out.javadoc(Seq(s"Thrown when a function fails with `${cls.name}`."), 0)
    out.line(0, s"public class $name extends Exception {")
    out.line(1, "private static final long serialVersionUID = 1L;")
    out.blank()
    out.line(1, s"private final transient $error error;")
    out.blank()
    out.line(1, s"public $name($error error) {")
    out.line(2, "super(String.valueOf(error));")
    out.line(2, "this.error = error;")
    out.line(1, "}")
    out.blank()
    out.line(1, s"public $error getError() {")
    out.line(2, "return error;")
    out.line(1, "}")
    out.line(0, "}")
    out.result
  }

  /** The functions of the schema, as one interface with a method for each. */
  private def writeFunctions(functions: Seq[FunctionView], ctx: Context)(using
      JavaGeneratorOptions,
  ): String = {
    val out = Code()
    out.javadoc(Seq(s"The functions of the `${sv.root.name}` schema."), 0)
    out.line(0, s"public interface ${functionsInterfaceName(sv)} {")
    functions.zipWithIndex.foreach { (fv, i) =>
      if i > 0 then out.blank()
      writeFunction(fv, out, ctx)
    }
    out.line(0, "}")
    out.result
  }

  private def writeFunction(fv: FunctionView, out: Code, ctx: Context)(using
      JavaGeneratorOptions,
  ): Unit = {
    val attributes = fv.parameters.foldFast(Seq.empty[AttributeView])(_.sortedAttributeViews)
    val parameters = attributes.map { a =>
      val name = JavaRenamer.member(Case.baseToCamel(a.slotView.baseName))
      (name, fieldType(a, ctx).javaType, a)
    }
    val returned = fv.returns.foldFast("void") {
      case cv: ClassView => if cv.isAny then "Object" else className(cv)
      case tv: TypeView =>
        typeValue(tv) match {
          case Single.Plain(java, _) => java
          case Single.Read(java, _) => java
        }
      case ev: EnumView => if definesEnum(ev) then enumName(ev) else "String"
      case _ => "Object"
    }
    val tags = parameters.map { (name, _, a) =>
      val text = DocLines(a.slotView.slot).mkString(" ")
      s"@param $name $text".strip
    } ++ fv.errors.map { cls =>
      val text = DocLines(cls.cls).mkString(" ")
      s"@throws ${errorName(cls)} ${if text.isEmpty then cls.name else text}"
    }
    val description = DocLines(fv.function)
    val separator = if description.nonEmpty && tags.nonEmpty then Seq("") else Nil
    out.javadoc(description ++ separator ++ tags, 1, tags = true)
    val throws =
      if fv.errors.isEmpty then "" else fv.errors.map(errorName).mkString(" throws ", ", ", "")
    val signature = s"$returned ${functionName(fv)}("
    val args = parameters.map((name, tpe, _) => s"$tpe $name")
    if signature.length + args.map(_.length + 2).sum + throws.length + 6 <= 100 then
      out.line(1, signature + args.mkString(", ") + ")" + throws + ";")
    else {
      out.line(1, signature)
      args.zipWithIndex.foreach { (a, i) =>
        out.line(3, a + (if i == args.size - 1 then ")" + throws + ";" else ","))
      }
    }
  }
}

object JavaGenerator {

  /** Whether the package defines an enum for [[ev]]. An enum without permissible values is a
    * `String` instead.
    */
  def definesEnum(ev: EnumView): Boolean = ev._enum.permissibleValues.nonEmpty

  /** The type of one value: [[Plain]] if Jackson reads it as is, [[Read]] if the generated code can
    * read it.
    */
  private enum Single {
    case Plain(java: String, isEnum: Boolean = false)
    case Read(java: String, reader: Reader)
  }

  private final case class Definitions(
      classes: Seq[ClassView],
      enums: Seq[EnumView],
      document: Option[(ClassView, String)],
      functions: Seq[FunctionView],
      errors: Seq[ClassView],
  )

  /** Mutable state of one generator run. */
  private final case class Context(defs: Definitions, writer: StyleWriter)(using
      val options: JavaGeneratorOptions,
  ) {
    val generated: Set[String] = defs.classes.map(_.name).toSet
    val classes: mutable.HashMap[String, JavaClass] = mutable.HashMap.empty
    val classByJavaName: Map[String, ClassView] =
      defs.classes.map(c => JavaRenamer.className(c) -> c).toMap
    val fieldNames: mutable.HashMap[String, Map[String, String]] = mutable.HashMap.empty

    /** The key slot of each class inlined as a dict, by class name. */
    lazy val dictKeys: Map[String, String] = {
      val slots = defs.classes.flatMap(_.sortedAttributeViews).collect {
        case ClassInlineAttributeView(_, _, cls, InlineType.dict(f)) => cls.name -> f.key
      }
      val document = defs.document.toSeq.flatMap { (root, _) =>
        root.treeRootInlineType(None) match {
          case InlineType.dict(f) => Seq(root.name -> f.key)
          case _ => Nil
        }
      }
      // Instances of subclasses can be in the dict too
      (slots ++ document).flatMap { (name, key) =>
        defs.classes.filter(_.ancestorsWithSelf.exists(_.name == name)).map(_.name -> key)
      }.toMap
    }

    /** The form of each class inlined as a simple dict, by class name. */
    lazy val simpleDicts: Map[String, CollectionForm.SimpleDict] = {
      val slots = defs.classes.flatMap(_.sortedAttributeViews).collect {
        case ClassInlineAttributeView(_, _, cls, InlineType.dict(f: CollectionForm.SimpleDict)) =>
          cls.name -> f
      }
      val document = defs.document.toSeq.flatMap { (root, _) =>
        root.treeRootInlineType(None) match {
          case InlineType.dict(f: CollectionForm.SimpleDict) => Seq(root.name -> f)
          case _ => Nil
        }
      }
      (slots ++ document).toMap
    }
  }

  private def indent(level: Int): String = "    " * level

  /** A bound as a decimal number, or None if it is not a number. */
  private def number(value: Option[LinkmlAny]): Option[String] =
    try value.mapFast(v => BigDecimal(v.value.trim).bigDecimal.toPlainString)
    catch {
      case ex if NonFatal(ex) => None
    }

  /** A whole file, with the imports that [[body]] needs. */
  private def file(header: String, pkg: String, body: String): String = {
    val used = imports.filter((name, _) => body.contains(name) && usesName(body, name))
      .map(_._2).toSeq.sorted
    val (libraries, standard) = used.partition(!_.startsWith("java."))
    val sb = new java.lang.StringBuilder(header)
    sb.append("package ").append(pkg).append(";\n\n")
    libraries.foreach(i => sb.append("import ").append(i).append(";\n"))
    if libraries.nonEmpty && standard.nonEmpty then sb.append('\n')
    standard.foreach(i => sb.append("import ").append(i).append(";\n"))
    if used.nonEmpty then sb.append('\n')
    sb.append(body).toString
  }

  /** Whether [[name]] appears as a whole word in [[body]], possibly in a comment or string. An
    * unused import is harmless.
    */
  private def usesName(body: String, name: String): Boolean = {
    var from = body.indexOf(name)
    var found = false
    while !found && from >= 0 do {
      val end = from + name.length
      def word(i: Int) = i >= 0 && i < body.length && {
        val c = body.charAt(i)
        c.isLetterOrDigit || c == '_' || c == '$'
      }
      if !word(from - 1) && !word(end) && (from == 0 || body.charAt(from - 1) != '.') then
        found = true
      else from = body.indexOf(name, end)
    }
    found
  }

  /** The classes that generated code may use, by simple name. */
  private val imports: Map[String, String] = (Seq(
    "java.math.BigDecimal",
    "java.math.BigInteger",
    "java.time.LocalDate",
    "java.time.OffsetDateTime",
    "java.time.OffsetTime",
    "java.time.format.DateTimeFormatter",
    "java.time.format.DateTimeParseException",
    "java.util.ArrayList",
    "java.util.LinkedHashMap",
    "java.util.List",
    "java.util.Map",
    "java.util.Objects",
    "java.util.function.BiConsumer",
    "java.util.function.Function",
    "java.util.function.Supplier",
    "java.util.regex.Pattern",
  ) ++ JacksonWriter.annotations.map("com.fasterxml.jackson.annotation." + _))
    .map(fqn => fqn.substring(fqn.lastIndexOf('.') + 1) -> fqn).toMap

  /** Builds the body of a Java file, four spaces per level of indentation. */
  final class Code {
    private val sb = new java.lang.StringBuilder

    /** Writes [[text]] at [[level]]. The lines of a multi-line text keep their own indentation. */
    def line(level: Int, text: String): Unit =
      text.split('\n').foreach(l => sb.append(indent(level)).append(l).append('\n'))

    def blank(): Unit = sb.append('\n')

    /** Writes a doc comment, if there are lines. Without [[tags]], `@` is escaped so that it can't
      * start a tag.
      */
    def javadoc(lines: Seq[String], level: Int, tags: Boolean = false): Unit =
      if lines.nonEmpty then {
        val escaped = lines.map(l => docText(l, tags))
        if escaped.sizeIs == 1 && escaped.head.length + level * 4 + 7 <= 100 then
          line(level, s"/** ${escaped.head} */")
        else {
          line(level, "/**")
          escaped.foreach(l => line(level, if l.isEmpty then " *" else s" * $l"))
          line(level, " */")
        }
      }

    def result: String = sb.toString
  }

  /** A line of doc comment text, as HTML that can't end the comment or start a Unicode escape. */
  private def docText(line: String, tags: Boolean): String = {
    val sb = new java.lang.StringBuilder(line.length)
    line.foreach {
      case '&' => sb.append("&amp;")
      case '<' => sb.append("&lt;")
      case '>' => sb.append("&gt;")
      case '\\' => sb.append("&#92;")
      case '@' if !tags || sb.length() > 0 => sb.append("&#64;")
      case '/' if sb.length() > 0 && sb.charAt(sb.length() - 1) == '*' => sb.append("&#47;")
      case c => sb.append(c)
    }
    sb.toString
  }

  /** A Java string literal, with everything outside printable ASCII escaped. */
  def javaString(value: String): String = {
    val sb = new java.lang.StringBuilder(value.length + 2)
    sb.append('"')
    value.foreach {
      case '"' => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c if c < ' ' || c > '~' =>
        sb.append('\\').append('u').append(hexDigit(c >> 12)).append(hexDigit(c >> 8))
          .append(hexDigit(c >> 4)).append(hexDigit(c))
      case c => sb.append(c)
    }
    sb.append('"').toString
  }
}
