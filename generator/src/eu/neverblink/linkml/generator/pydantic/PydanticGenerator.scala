package eu.neverblink.linkml.generator.pydantic

import eu.neverblink.linkml.generator.CharDocumentGenerator
import eu.neverblink.linkml.generator.util.{DocLines, GeneratedHeader, PruningMode}
import eu.neverblink.linkml.rdf.io.{CharSink, StringSink}
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.runtime.{HasMetadataLanguage, LinkmlAny}
import eu.neverblink.linkml.runtime.StringUtils.hexDigit
import eu.neverblink.linkml.schemaview.*

import scala.collection.mutable
import scala.util.control.NonFatal

/** Generates Python classes based on pydantic v2 that load and dump the JSON form of the data 1:1,
  * the same JSON as described by the JSON Schema generator. Data is loaded with
  * `X.model_validate_json(text)` and dumped with `x.model_dump_json()`. The generated code needs
  * pydantic 2.11 or later and Python 3.10 or later, and nothing else.
  */
final class PydanticGenerator(using sv: SchemaView)
    extends CharDocumentGenerator[PydanticGenerator.Options],
      PydanticRenamer {
  import PydanticGenerator.*

  override protected def defaultOptions: Options = Options()

  /** Name of the synthetic union type standing for a class and its subclasses, if the class needs
    * one.
    */
  def unionName(cls: ClassView): Option[String] =
    if cls.isTypeDesignatorUnion && cls.typeDesignatorMembers.sizeIs > 1 then
      Some("Any".concat(className(cls)))
    else None

  /** Python type to use where the class is the range of a slot: the union if there is one, the only
    * class a designator can pick, or the class itself.
    */
  private def classRef(cls: ClassView): String =
    unionName(cls).getOrElseFast {
      if cls.isTypeDesignatorUnion then className(cls.typeDesignatorMembers.head)
      else className(cls)
    }

  /** Field names of a class by JSON key. */
  private def fieldNames(cls: ClassView, ctx: Context): Map[String, String] =
    ctx.fieldNames.getOrElseUpdate(
      cls.name, {
        val names = cls.sortedAttributeViews.map { a =>
          val key = slotName(a.slotView)
          key -> PydanticRenamer.attribute(key, ctx.moduleNames)
        }
        val seen = mutable.HashMap.empty[String, String]
        names.foreach { (key, name) =>
          seen.put(name, key).foreachFast { other =>
            throw new IllegalArgumentException(
              s"Slots '$other' and '$key' of class '${cls.name}' both map to the Python name " +
                s"'$name'. Rename or alias one of them to generate Python.",
            )
          }
        }
        names.toMap
      },
    )

  private def keyedArgs(cls: ClassView, form: DictForm): String = {
    val keySlot = cls.attributeViews(form.key)
    val key = pyString(slotName(keySlot.slotView))
    val value = form match {
      case CollectionForm.SimpleDict(_, value) =>
        ", ".concat(pyString(slotName(cls.attributeViews(value).slotView)))
      case _ => ""
    }
    val parse = keySlot match {
      case t: TypeAttributeView if t.typeView.coreType == IntegerType => ", int"
      case _ => ""
    }
    (key, value, parse) match {
      case (k, "", "") => k
      case (k, "", p) => s"$k, None$p"
      case (k, v, p) => s"$k$v$p"
    }
  }

  /** Python type of a class inlined in the given form. */
  private def inlinedType(cls: ClassView, inlineType: InlineType, ctx: Context): String = {
    val ref = classRef(cls)
    inlineType match {
      case InlineType.plain | InlineType.optional => ref
      case InlineType.list => s"list[$ref]"
      case InlineType.dict(form) => keyedDict(ref, cls, form, ctx)
    }
  }

  private def keyedDict(ref: String, cls: ClassView, form: DictForm, ctx: Context): String = {
    ctx.uses(Helper.keyed)
    ctx.imports.typing("Annotated")
    val args = keyedArgs(cls, form)
    val dumpArgs = form match {
      case CollectionForm.SimpleDict(key, value) =>
        s"${pyString(slotName(cls.attributeViews(key).slotView))}, " +
          pyString(slotName(cls.attributeViews(value).slotView))
      case _ => pyString(slotName(cls.attributeViews(form.key).slotView))
    }
    s"Annotated[dict[str, $ref], _with_keys($args), _without_keys($dumpArgs)]"
  }

  /** Python type of a type range, with the constraints of the slot and the type. */
  private def scalarType(tav: TypeAttributeView, ctx: Context): String =
    if tav.typeView.isUnion then
      // Each member keeps its own constraints, as they only apply to values of that member
      tav.typeView.unionAlternatives.map { member =>
        constrainedType(TypeAttributeView(tav.slotView, tav.definingClassView, member), ctx)
      }.distinct.mkString(" | ")
    else constrainedType(tav, ctx)

  /** Python type of a type range that is not a union, with the constraints of the slot and the
    * type. A `LocalizedText` type, such as `langstring`, is a plain string or a non-empty dict from
    * language tag to text. The lengths and the pattern apply to the plain string and to the text in
    * each language.
    */
  private def constrainedType(tav: TypeAttributeView, ctx: Context): String = {
    val base = runtimeType(tav.typeView, ctx)
    val constraints = mutable.ListBuffer.empty[String]
    if base == "str" then {
      tav.pattern.foreachFast(p => constraints.addOne("pattern=".concat(pyPattern(p.linkml))))
      tav.minimumLength.foreachFast(n => constraints.addOne("min_length=" + n))
      tav.maximumLength.foreachFast(n => constraints.addOne("max_length=" + n))
    }
    if numericTypes(base) then {
      number(tav.minimumValue).foreachFast(v => constraints.addOne("ge=".concat(v)))
      number(tav.maximumValue).foreachFast(v => constraints.addOne("le=".concat(v)))
    }
    val text =
      if constraints.isEmpty then base
      else {
        ctx.imports.typing("Annotated")
        ctx.imports.pydantic("Field")
        s"Annotated[$base, Field(${constraints.mkString(", ")})]"
      }
    if (tav.typeView.runtimeType eq LocalizedTextType) {
      ctx.imports.typing("Annotated")
      ctx.imports.pydantic("Field")
      s"$text | Annotated[dict[str, $text], Field(min_length=1)]"
    } else text
  }

  private def runtimeType(tv: TypeView, ctx: Context): String = tv.runtimeType match {
    case IntegerType =>
      ctx.imports.pydantic("StrictInt")
      "StrictInt"
    case FloatType | DoubleType =>
      ctx.imports.pydantic("StrictFloat")
      "StrictFloat"
    case BooleanType =>
      ctx.imports.pydantic("StrictBool")
      "StrictBool"
    case DecimalType =>
      ctx.uses(Helper.decimal)
      "JsonDecimal"
    case DateType =>
      ctx.uses(Helper.iso)
      ctx.uses(Helper.date)
      "JsonDate"
    case DateTimeType =>
      ctx.uses(Helper.iso)
      ctx.uses(Helper.dateTime)
      "JsonDateTime"
    case TimeType =>
      ctx.uses(Helper.iso)
      ctx.uses(Helper.time)
      "JsonTime"
    case AnyType | UnknownType =>
      ctx.imports.typing("Any")
      "Any"
    // [[constrainedType]] adds the dict form of LocalizedText
    case StringType | UriOrCurieType | UriType | CurieType | NcNameType | LocalizedTextType => "str"
  }

  /** Python type of an attribute, without `| None` for optional slots. */
  private def attributeType(attribute: AttributeView, owner: ClassView, ctx: Context): String = {
    val multivalued = attribute.slotView.slot.multivalued
    val tpe = attribute match {
      case _: AnyView =>
        ctx.imports.typing("Any")
        listIf(multivalued, "Any")
      case ClassInlineAttributeView(_, _, classView, inlineType) =>
        inlinedType(classView, inlineType, ctx)
      case ClassReferenceAttributeView(_, _, _, identifierView) =>
        listIf(multivalued, scalarType(identifierView, ctx))
      case tav: TypeAttributeView =>
        val literals = designatorValues(tav, owner)
        val base =
          if literals.isEmpty then scalarType(tav, ctx)
          else {
            ctx.imports.typing("Literal")
            literals.map(pyString).mkString("Literal[", ", ", "]")
          }
        listIf(multivalued, base)
      case EnumAttributeView(_, _, enumView) =>
        listIf(multivalued, enumType(enumView))
    }
    tpe
  }

  private def enumType(ev: EnumView): String =
    if (definesEnum(ev)) enumName(ev) else "str"

  /** In a concrete class, the values the type designator can take there. */
  private def designatorValues(tav: TypeAttributeView, owner: ClassView): Seq[String] =
    if tav.slotView.slot.designatesType && owner.isConcrete then owner.typeDesignatorValues
    else Nil

  private def isAny(attribute: AttributeView): Boolean = attribute match {
    case _: AnyView => !attribute.slotView.slot.multivalued
    case tav: TypeAttributeView =>
      !tav.slotView.slot.multivalued && !tav.typeView.isUnion &&
      (tav.typeView.runtimeType == AnyType || tav.typeView.runtimeType == UnknownType)
    case _ => false
  }

  private def writeField(
      sink: CharSink,
      attribute: AttributeView,
      owner: ClassView,
      ctx: Context,
  )(using Options): Unit = {
    val slot = attribute.slotView.slot
    val key = slotName(attribute.slotView)
    val name = fieldNames(owner, ctx)(key)
    val any = isAny(attribute)
    val tpe = attributeType(attribute, owner, ctx)
    val default = attribute match {
      case tav: TypeAttributeView => designatorValues(tav, owner).lastOption.map(pyString)
      case _ => None
    }
    val optional = !slot.required
    val args = mutable.ListBuffer.empty[String]
    default.orElseFast(Option.when(optional)("None")).foreachFast(args.addOne)
    if name != key then args.addOne("alias=".concat(pyString(key)))
    if slot.multivalued then {
      slot.minimumCardinality.orElseFast(slot.exactCardinality)
        .foreachFast(n => args.addOne(s"min_length=$n"))
      slot.maximumCardinality.orElseFast(slot.exactCardinality)
        .foreachFast(n => args.addOne(s"max_length=$n"))
    }
    sink.append("    ")
    sink.append(name)
    sink.append(": ")
    sink.append(tpe)
    if optional && !any && default.isEmpty then sink.append(" | None")
    if args.nonEmpty then {
      sink.append(" = ")
      if args.sizeIs == 1 && default.isDefined || args.toList == List("None") then
        sink.append(args.head)
      else {
        ctx.imports.pydantic("Field")
        sink.append("Field(")
        sink.append(args.mkString(", "))
        sink.append(')')
      }
    }
    sink.append('\n')
    val doc = DocLines(slot) ++ (attribute match {
      case ref: ClassReferenceAttributeView => Seq(s"Reference to ${className(ref.classView)}")
      case _ => Nil
    })
    writeDoc(sink, doc, "    ")
  }

  /** The Python base classes: `is_a`, then the mixins, leaving out any that another one already
    * inherits.
    */
  private def bases(cls: ClassView, generated: Set[String]): Seq[ClassView] = {
    val direct = (cls.cls.isA.toSeq ++ cls.cls.mixins).map(r => sv.classes(r.value))
      .filter(c => generated(c.name)).distinctBy(_.name)
    direct.filterNot(b =>
      direct.exists(o => o.name != b.name && o.ancestorsWithSelf.exists(_.name == b.name)),
    )
  }

  /** Python's C3 method resolution order of a class, or an error if Python can't make one. */
  private def mro(
      cls: ClassView,
      generated: Set[String],
      memo: mutable.HashMap[String, List[String]],
  ): List[String] =
    memo.getOrElseUpdate(
      cls.name, {
        val parents = bases(cls, generated)
        var lists = (parents.map(p => mro(p, generated, memo)) :+ parents.map(_.name).toList)
          .filter(_.nonEmpty).toList
        val result = mutable.ListBuffer(cls.name)
        while lists.nonEmpty do {
          val head = lists.map(_.head).find(h => !lists.exists(_.tail.contains(h))).getOrElse {
            throw new IllegalArgumentException(
              s"Python can't order the parents of class '${cls.name}' " +
                s"(${parents.map(_.name).mkString(", ")}), because their own parents come in " +
                "conflicting orders. Change the order of its mixins to generate Python.",
            )
          }
          result.addOne(head)
          lists = lists.map(l => if l.head == head then l.tail else l).filter(_.nonEmpty)
        }
        result.toList
      },
    )

  private def writeClass(
      sink: CharSink,
      cls: ClassView,
      parents: Seq[ClassView],
      ctx: Context,
  )(using Options): Unit = {
    sink.append("class ")
    sink.append(className(cls))
    sink.append('(')
    sink.append(if parents.isEmpty then "LinkMLModel" else parents.map(className).mkString(", "))
    sink.append("):\n")
    val doc = DocLines(cls.cls)
    writeDoc(sink, doc, "    ")
    // `extra_slots` is not inherited, but pydantic's config is, so a subclass of a class that allows
    // extra data closes itself again
    val extra =
      if ctx.options.open then None
      else if cls.allowsExtraSlots then Some("allow")
      else if cls.ancestorsWithSelf.exists(a => a.name != cls.name && a.allowsExtraSlots) then
        Some("forbid")
      else None
    extra.foreachFast { value =>
      if doc.nonEmpty then sink.append('\n')
      sink.append(s"    model_config = ConfigDict(extra=${pyString(value)})\n")
    }
    // A field renamed by an alias in a `slot_usage` is written under its new name, but the field
    // under the old name is still inherited from the parent (LNK-235).
    val attributes = cls.sortedAttributeViews
    if (doc.nonEmpty || extra.isDefined) && attributes.nonEmpty then sink.append('\n')
    attributes.foreach(a => writeField(sink, a, cls, ctx))
    if doc.isEmpty && extra.isEmpty && attributes.isEmpty then sink.append("    pass\n")
  }

  private def writeUnion(sink: CharSink, cls: ClassView, name: String, ctx: Context): Unit = {
    ctx.imports.typing("Annotated")
    ctx.imports.typing("Union")
    ctx.imports.pydantic("Field")
    val designator = cls.typeDesignator.get
    val member = cls.typeDesignatorMembers.head
    val field = fieldNames(member, ctx)(slotName(member.derivedAttributes(designator.name)))
    sink.append(name)
    sink.append(" = Annotated[\n    Union[")
    val members = cls.typeDesignatorMembers.map(className)
    if members.map(_.length + 2).sum + 10 <= 100 then sink.append(members.mkString(", "))
    else {
      members.foreach { m =>
        sink.append("\n        ")
        sink.append(m)
        sink.append(',')
      }
      sink.append("\n    ")
    }
    sink.append("],\n    Field(discriminator=")
    sink.append(pyString(field))
    sink.append("),\n]\n")
    val what =
      if cls.isConcrete then s"${className(cls)} or any of its subclasses"
      else s"Any subclass of ${className(cls)}"
    writeDoc(sink, Seq(s"$what, told apart by `$field`."), "")
  }

  private def writeEnum(
      sink: CharSink,
      ev: EnumView,
      ctx: Context,
  )(using Options): Unit = {
    ctx.imports.add("enum", "Enum")
    sink.append("class ")
    sink.append(enumName(ev))
    sink.append("(str, Enum):\n")
    val doc = DocLines(ev._enum)
    writeDoc(sink, doc, "    ")
    if doc.nonEmpty then sink.append('\n')
    val seen = mutable.HashMap.empty[String, String]
    ev._enum.permissibleValues.values.foreach { pv =>
      val value = permissibleValueName(ev, pv)
      val member = PydanticRenamer.member(value)
      seen.put(member, value).foreachFast { other =>
        throw new IllegalArgumentException(
          s"Values '$other' and '$value' of enum '${ev.name}' both map to the Python name " +
            s"'$member'. Rename one of them to generate Python.",
        )
      }
      sink.append("    ")
      sink.append(member)
      sink.append(" = ")
      sink.append(pyString(value))
      sink.append('\n')
      writeDoc(sink, DocLines(pv), "    ")
    }
  }

  /** An exception class for a class that a function uses in its `errors`, so that an implementation
    * can raise it. The class itself is a model and cannot also be an exception.
    */
  private def writeError(sink: CharSink, cls: ClassView, ctx: Context)(using Options): Unit = {
    sink.append("class ")
    sink.append(errorName(cls))
    sink.append("(Exception):\n")
    writeDoc(sink, Seq(s"Raised when a function fails with `${cls.name}`."), "    ")
    sink.append("\n    def __init__(self, error: ")
    sink.append(className(cls))
    sink.append(") -> None:\n        super().__init__(error)\n        self.error = error\n")
  }

  /** The functions of the schema, as one `Protocol` with a method for each.
    */
  private def writeFunctions(sink: CharSink, functions: Seq[FunctionView], ctx: Context)(using
      Options,
  ): Unit = {
    ctx.imports.typing("Protocol")
    sink.append("class ")
    sink.append(functionsProtocolName(sv))
    sink.append("(Protocol):\n")
    writeDoc(sink, Seq(s"The functions of the `${sv.root.name}` schema."), "    ")
    functions.foreach { fv =>
      sink.append('\n')
      writeFunction(sink, fv, ctx)
    }
  }

  /** The Python name of a parameter of a function. The method itself already takes `self`. */
  private def parameterName(attribute: AttributeView): String =
    PydanticRenamer.attribute(attribute.slotView.baseName, _ == "self")

  private def writeFunction(sink: CharSink, fv: FunctionView, ctx: Context)(using
      Options,
  ): Unit = {
    val attributes = fv.parameters.foldFast(Seq.empty[AttributeView])(_.sortedAttributeViews)
    // A parameter without a default cannot follow one with a default, so only the optional
    // parameters at the end of the list get `= None`.
    var optionalFrom = attributes.length
    while optionalFrom > 0 && !attributes(optionalFrom - 1).slotView.slot.required do
      optionalFrom -= 1
    var index = 0
    val arguments = attributes.map { attribute =>
      val slot = attribute.slotView
      val name = parameterName(attribute)
      val tpe = attributeType(attribute, fv.parameters.get, ctx)
      val argument =
        if slot.slot.required then s"$name: $tpe"
        else if index >= optionalFrom then s"$name: $tpe | None = None"
        else s"$name: $tpe | None"
      index += 1
      argument
    }
    val returned = fv.returns.foldFast("None") {
      case cv: ClassView =>
        if cv.isAny then {
          ctx.imports.typing("Any")
          "Any"
        } else classRef(cv)
      case tv: TypeView => runtimeType(tv, ctx)
      case ev: EnumView => enumType(ev)
      case _ =>
        ctx.imports.typing("Any")
        "Any"
    }
    sink.append("    def ")
    sink.append(functionName(fv))
    val self = "self" +: arguments
    // One argument per line if the whole signature would be long
    if self.map(_.length + 2).sum + functionName(fv).length + returned.length + 12 <= 100 then
      sink.append(self.mkString("(", ", ", ")"))
    else sink.append(self.mkString("(\n        ", ",\n        ", ",\n    )"))
    sink.append(" -> ")
    sink.append(returned)
    sink.append(":\n")
    val tags = attributes.map { attribute =>
      val text = DocLines(attribute.slotView.slot).mkString(" ")
      s":param ${parameterName(attribute)}: $text".strip
    } ++ fv.errors.map { cls =>
      val text = DocLines(cls.cls).mkString(" ")
      s":raises ${errorName(cls)}: ${if text.isEmpty then cls.name else text}"
    }
    val description = DocLines(fv.function)
    val separator = if description.nonEmpty && tags.nonEmpty then Seq("") else Nil
    writeDoc(sink, description ++ separator ++ tags, "        ")
    sink.append("        ...\n")
  }

  /** The type of the whole document, if the tree root needs one: a designated union, or a form
    * other than a single object.
    */
  private def documentType(root: ClassView, ctx: Context): Option[String] = {
    val ref = classRef(root)
    root.treeRootInlineType(None) match {
      case InlineType.plain => Option.when(ref != className(root))(ref)
      case InlineType.optional => Some(ref.concat(" | None"))
      case InlineType.list => Some(s"list[$ref]")
      case InlineType.dict(form) => Some(keyedDict(ref, root, form, ctx))
    }
  }

  /** Check that no two module-level names clash. */
  private def checkNames(
      classes: Seq[ClassView],
      enums: Seq[EnumView],
      document: Option[(ClassView, String)],
      functions: Seq[FunctionView],
      errors: Seq[ClassView],
  ): Unit = {
    val seen = mutable.HashMap.empty[String, String]
    def claim(name: String, owner: String): Unit =
      seen.put(name, owner).foreachFast { other =>
        throw new IllegalArgumentException(
          s"$owner and $other both map to the Python name '$name'. " +
            "Rename one of them to generate Python.",
        )
      }
    classes.foreach { cls =>
      claim(className(cls), s"class '${cls.name}'")
      unionName(cls).foreachFast(name =>
        claim(name, s"the union of class '${cls.name}' and its subclasses"),
      )
    }
    enums.foreach(ev => claim(enumName(ev), s"enum '${ev.name}'"))
    document.foreachFast((root, name) =>
      claim(name, s"the document type of tree root '${root.name}'"),
    )
    errors.foreach(cls => claim(errorName(cls), s"the error class of '${cls.name}'"))
    if functions.nonEmpty then claim(functionsProtocolName(sv), "the functions of the schema")
  }

  /** The classes, enums and document type that the module defines with [[options]]. */
  private def definitions(options: Options): Definitions = {
    val query =
      sv.withTypeDesignatorMembers(options.pruningMode.derivedQuery(true, true), true, true)
    val classes = sv.sortedClasses.filter(c => !c.isAny && query.reachable(c))
    val enums = sv.enums.values.filter(ev => query.reachable(ev) && definesEnum(ev))
      .toVector.sortBy(ev => sv.elementOrder(ev._enum))
    val treeRootOverride = options.pruningMode match {
      case PruningMode.treeRoot(o) => o
      case _ => None
    }
    val root = sv.treeRootWithOverride(treeRootOverride).get
      .filter(r => classes.exists(_.name == r.name))
    val functions = sv.functions.values.filter(query.reachable).toVector
      .sortBy(fv => sv.elementOrder(fv.function))
    Definitions(classes, enums, root.map(r => (r, className(r).concat("Document"))), functions)
  }

  /** The names of the classes, class unions, enums and document type that the module defines with
    * [[options]]. A field whose name is one of them gets `_` at the end, as in
    * [[PydanticRenamer.attribute]].
    */
  def moduleNames(options: Options): Set[String] = namesOf(definitions(options))

  private def namesOf(defs: Definitions): Set[String] =
    (defs.classes.flatMap(c => className(c) +: unionName(c).toSeq) ++
      defs.enums.map(enumName) ++ defs.document.map(_._2) ++
      defs.functions.flatMap(_.errors).map(errorName) ++
      Option.when(defs.functions.nonEmpty)(functionsProtocolName(sv))).toSet

  override protected def writeChars(sink: CharSink, options: Options): Unit = {
    given Options = options
    val defs = definitions(options)
    val classes = defs.classes
    val enums = defs.enums
    val root = defs.document.map(_._1)
    val ctx = Context(options, namesOf(defs))
    val functions = defs.functions
    val errors = functions.flatMap(_.errors).distinctBy(_.name)
      .sortBy(cls => sv.elementOrder(cls.cls))
    checkNames(classes, enums, defs.document, functions, errors)

    val body = new StringSink
    enums.foreach { ev =>
      body.append("\n\n")
      writeEnum(body, ev, ctx)
    }
    // Parents first, since Python needs a base class before its subclasses
    val generated = classes.map(_.name).toSet
    val memo = mutable.HashMap.empty[String, List[String]]
    val written = mutable.HashSet.empty[String]
    def write(cls: ClassView): Unit =
      if written.add(cls.name) then {
        val parents = bases(cls, generated)
        parents.foreach(write)
        mro(cls, generated, memo)
        body.append("\n\n")
        writeClass(body, cls, parents, ctx)
      }
    classes.foreach(write)
    classes.foreach { cls =>
      unionName(cls).foreachFast { name =>
        body.append("\n\n")
        writeUnion(body, cls, name, ctx)
      }
    }
    errors.foreach { cls =>
      body.append("\n\n")
      writeError(body, cls, ctx)
    }
    if functions.nonEmpty then {
      body.append("\n\n")
      writeFunctions(body, functions, ctx)
    }
    root.foreachFast { r =>
      documentType(r, ctx).foreachFast { tpe =>
        ctx.imports.pydantic("RootModel")
        body.append("\n\nclass ")
        body.append(className(r).concat("Document"))
        body.append("(RootModel[")
        body.append(tpe)
        body.append("]):\n")
        writeDoc(body, Seq(s"A whole document: ${documentDoc(r)}."), "    ")
      }
    }

    // The base class and helpers come before the classes, but only now do we know what they need
    val helpers = new StringSink
    writeBase(helpers, ctx)
    Helper.values.foreach { h =>
      if ctx.helpers(h) then {
        h.imports.foreach((module, name) => ctx.imports.add(module, name))
        helpers.append("\n\n")
        helpers.append(h.code)
      }
    }

    sink.append(GeneratedHeader("#"))
    sink.append("from __future__ import annotations\n")
    ctx.imports.write(sink)
    sink.append(helpers.result)
    sink.append(body.result)
  }

  private def documentDoc(root: ClassView): String =
    root.treeRootInlineType(None) match {
      case InlineType.plain => s"a ${root.name}"
      case InlineType.optional => s"a ${root.name} or null"
      case InlineType.list => s"a list of ${root.name}"
      case InlineType.dict(_) => s"a dict of ${root.name} by key"
    }

  private def writeBase(sink: CharSink, ctx: Context): Unit = {
    val im = ctx.imports
    Seq("BaseModel", "ConfigDict", "model_serializer").foreach(im.pydantic)
    im.typing("Any")
    sink.append(
      """
        |
        |class LinkMLModel(BaseModel):
        |    '''Base of the generated classes. Loads and dumps the JSON form of the data, as
        |    described by the JSON Schema generated from the same LinkML model.'''
        |
        |    model_config = ConfigDict(
        |""".stripMargin.replace("'''", "\"\"\""),
    )
    sink.append(s"        extra=${pyString(if ctx.options.open then "allow" else "forbid")},\n")
    sink.append(
      """        protected_namespaces=(),
        |        regex_engine="python-re",
        |        serialize_by_alias=True,
        |        validate_assignment=True,
        |        validate_by_name=True,
        |    )
        |""".stripMargin.replace("'''", "\"\"\""),
    )
    if ctx.options.includeNull then
      sink.append(
        """
          |    @model_serializer(mode="wrap")
          |    def _skip_unset(self, handler: Any) -> Any:
          |        # Skip the fields that were never set, rather than writing null
          |        data = handler(self)
          |        unset = set()
          |        for name, field in type(self).model_fields.items():
          |            if name not in self.model_fields_set:
          |                unset.add(name)
          |                if field.alias:
          |                    unset.add(field.alias)
          |        return {k: v for k, v in data.items() if v is not None or k not in unset}
          |""".stripMargin,
      )
    else
      sink.append(
        """
          |    @model_serializer(mode="wrap")
          |    def _skip_none(self, handler: Any) -> Any:
          |        # Skip the fields without a value, rather than writing null
          |        return {k: v for k, v in handler(self).items() if v is not None}
          |""".stripMargin,
      )
  }

  private def writeDoc(sink: CharSink, lines: Seq[String], indent: String): Unit =
    if lines.nonEmpty then {
      val escaped = lines.map(docText)
      sink.append(indent)
      sink.append("\"\"\"")
      if escaped.sizeIs == 1 then sink.append(quoteEnd(escaped.head))
      else {
        escaped.foreach { line =>
          sink.append('\n')
          if line.nonEmpty then {
            sink.append(indent)
            sink.append(line)
          }
        }
        sink.append('\n')
        sink.append(indent)
      }
      sink.append("\"\"\"\n")
    }
}

object PydanticGenerator {

  /** Whether the module defines a class for [[ev]]. An enum without permissible values is a `str`
    * instead.
    */
  def definesEnum(ev: EnumView): Boolean = ev._enum.permissibleValues.nonEmpty

  /** Options for [[PydanticGenerator]].
    *
    * @param pruningMode
    *   Which classes and enums to generate. `skip` (the default) generates all of them. The
    *   ancestors of the classes it keeps and the members of any designated union are always
    *   generated with them.
    * @param includeNull
    *   Whether dumps include the null values that were loaded or set. If false, dumps skip every
    *   field without a value or set to null. Default: false
    * @param open
    *   Whether classes should accept and keep additional properties. Default: false
    * @param metadataLanguage
    *   Which language to use for titles and descriptions in docstrings.
    */
  final case class Options(
      pruningMode: PruningMode = PruningMode.skip,
      includeNull: Boolean = false,
      open: Boolean = false,
      metadataLanguage: String = "en",
  ) extends HasMetadataLanguage

  /** The classes, enums, document type (with its name) and functions that the module defines. */
  private final case class Definitions(
      classes: Seq[ClassView],
      enums: Seq[EnumView],
      document: Option[(ClassView, String)],
      functions: Seq[FunctionView],
  )

  /** Mutable state of one generator run. */
  private final case class Context(options: Options, moduleNames: Set[String]) {
    val imports: Imports = Imports()
    val helpers: mutable.Set[Helper] = mutable.Set.empty
    val fieldNames: mutable.HashMap[String, Map[String, String]] = mutable.HashMap.empty

    def uses(helper: Helper): Unit = helpers.add(helper)
  }

  /** The names to import, by module. */
  private final class Imports {
    private val names = mutable.TreeMap.empty[String, mutable.TreeSet[String]]

    /** Import `name` from `module`, or the module itself if `name` is empty. */
    def add(module: String, name: String): Unit = {
      val set = names.getOrElseUpdate(module, mutable.TreeSet.empty)
      if name.nonEmpty then set.add(name)
    }

    def typing(name: String): Unit = add("typing", name)

    def pydantic(name: String): Unit = add("pydantic", name)

    def write(sink: CharSink): Unit = {
      def group(modules: Iterable[String]): Unit =
        if modules.nonEmpty then {
          sink.append('\n')
          modules.foreach { module =>
            val list = names(module)
            if list.isEmpty then {
              sink.append("import ")
              sink.append(module)
              sink.append('\n')
            } else {
              val line = s"from $module import ${list.mkString(", ")}"
              if line.length <= 100 then {
                sink.append(line)
                sink.append('\n')
              } else {
                sink.append(s"from $module import (\n")
                list.foreach(n => sink.append(s"    $n,\n"))
                sink.append(")\n")
              }
            }
          }
        }
      // The standard library first, then pydantic
      group(names.keys.filter(_ != "pydantic").toSeq.sortBy(m => (names(m).nonEmpty, m)))
      group(names.keys.filter(_ == "pydantic"))
    }
  }

  private val numericTypes = Set("StrictInt", "StrictFloat", "JsonDecimal")

  /** A bound as a Python number literal, or None if it is not a number. */
  private def number(value: Option[LinkmlAny]): Option[String] =
    try value.mapFast(v => BigDecimal(v.value.trim).bigDecimal.toPlainString)
    catch {
      case ex if NonFatal(ex) => None
    }

  private def listIf(condition: Boolean, tpe: String): String =
    if condition then s"list[$tpe]" else tpe

  /** A pattern as a Python string literal: raw if possible. */
  def pyPattern(pattern: String): String = {
    val trailingBackslashes = pattern.reverseIterator.takeWhile(_ == '\\').size
    if pattern.forall(c => c >= ' ' && c != '"' && c != '\u007f') && trailingBackslashes % 2 == 0
    then s"r\"$pattern\""
    else pyString(pattern)
  }

  /** A double-quoted Python string literal. */
  def pyString(value: String): String = {
    val sb = new java.lang.StringBuilder(value.length + 2)
    sb.append('"')
    val len = value.length
    var i = 0
    while (i < len) {
      value.charAt(i) match {
        case '"' => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case c
            if c < ' ' || c == '\u007f' || Character.isSurrogate(c) ||
              Character.getType(c) == Character.LINE_SEPARATOR ||
              Character.getType(c) == Character.PARAGRAPH_SEPARATOR =>
          if Character.isHighSurrogate(c) && i + 1 < len &&
            Character.isLowSurrogate(value.charAt(i + 1))
          then {
            sb.append(c).append(value.charAt(i + 1))
            i += 1
          } else
            sb.append('\\').append('u').append(hexDigit(c >> 12)).append(hexDigit(c >> 8))
              .append(hexDigit(c >> 4)).append(hexDigit(c))
        case c => sb.append(c)
      }
      i += 1
    }
    sb.append('"').toString
  }

  /** A line of docstring text, with what would end the docstring or start an escape escaped. */
  private def docText(line: String): String =
    line.replace("\\", "\\\\").replace("\"\"\"", "\\\"\\\"\\\"")

  /** Escape a quote at the end of a one-line docstring, which would otherwise join the closing
    * quotes.
    */
  private def quoteEnd(line: String): String =
    if line.endsWith("\"") then line.dropRight(1).concat("\\\"") else line
}
