package eu.neverblink.linkml.optiongen

import eu.neverblink.linkml.metamodel.{AnnotationImpl, CommonMetadata, PresenceEnum, SlotDefinition}
import eu.neverblink.linkml.schemaview.*
import eu.neverblink.linkml.validation.{IssueSeverity, SchemaFatal, SchemaIssue}
import org.virtuslab.yaml.{Node, NodeOps, Tag, parseYaml}

import scala.util.control.NonFatal

object OptionSchemaReader {
  def read(
      yaml: String,
      source: String = "<memory>",
      expectedGeneratorIds: Set[String] = Set.empty,
  ): Either[Vector[Diagnostic], Catalog] =
    run(source, expectedGeneratorIds, yaml)(SchemaView.loadSchemaViewFromString(yaml))

  def load(
      path: String,
      expectedGeneratorIds: Set[String] = Set.empty,
  ): Either[Vector[Diagnostic], Catalog] = {
    val importer = new RootImporter
    run(
      path,
      expectedGeneratorIds,
      importer.rootText.getOrElse(fail(path, "Missing root schema text")),
    )(
      SchemaView.loadSchemaViewFromUri(path, importer),
    )
  }

  private class RootImporter extends StringImporter {
    var rootText: Option[String] = None
    def read(path: String): String = {
      val yaml = FileSystemImporter.read(path)
      if rootText.isEmpty then rootText = Some(yaml)
      yaml
    }
  }

  private case class Invalid(diagnostic: Diagnostic) extends RuntimeException(diagnostic.toString)

  private def fail(location: String, message: String): Nothing =
    throw Invalid(Diagnostic(location, message))

  private def located[A](location: String)(body: => A): A =
    try body
    catch {
      case e: Invalid => throw e
      case NonFatal(e) => fail(location, Option(e.getMessage).getOrElse(e.toString))
    }

  private def diagnostic(source: String, issue: SchemaIssue): Diagnostic =
    Diagnostic(source, SchemaIssues.verbose(issue.infer()))

  private def run(source: String, ids: Set[String], yaml: => String)(
      loaded: => Either[Seq[SchemaFatal], SchemaView],
  ): Either[Vector[Diagnostic], Catalog] =
    try {
      loaded match {
        case Left(errors) => Left(errors.map(diagnostic(source, _)).toVector)
        case Right(sv) =>
          located(source) {
            val errors = sv.validationProblems
            if errors.nonEmpty then Left(errors.map(diagnostic(source, _)).toVector)
            else Right(new Reader(sv, source, yaml).catalog(ids))
          }
      }
    } catch {
      case e: Invalid => Left(Vector(e.diagnostic))
      case NonFatal(e) =>
        Left(Vector(Diagnostic(source, Option(e.getMessage).getOrElse(e.toString))))
    }

  private def obj(node: Node, location: String, allowed: Set[String]): Map[String, Node] = {
    val entries = node match {
      case n: Node.MappingNode =>
        n.mappings.toVector.map { case (key, value) =>
          text(key, location) -> value
        }
      case _ => fail(location, s"Expected an object at $location")
    }
    unique(entries.map(_._1), location, "key")
    val unknown = entries.map(_._1).toSet -- allowed
    if unknown.nonEmpty then
      fail(location, s"Unknown keys: ${unknown.toVector.sorted.mkString(", ")}")
    entries.toMap
  }

  private def required(fields: Map[String, Node], key: String, location: String): Node =
    fields.getOrElse(key, fail(location, s"Missing $key"))

  private def text(node: Node, location: String): String = node match {
    case n: Node.ScalarNode if n.tag == Tag.str => n.value
    case _ => fail(location, s"Expected a string at $location")
  }

  private def sequence(node: Node, location: String): Vector[Node] = node match {
    case n: Node.SequenceNode => n.nodes.toVector
    case _ =>
      fail(location, s"Expected a list, found ${node.getClass.getSimpleName}: ${node.asYaml.trim}")
  }

  private def unique[A](values: Vector[A], location: String, kind: String): Unit = {
    val duplicates = values.groupMapReduce(identity)(_ => 1)(_ + _).collect {
      case (value, count) if count > 1 => value
    }
    if duplicates.nonEmpty then fail(location, s"Duplicate $kind: ${duplicates.mkString(", ")}")
  }

  private def identifier(value: String, location: String): String = {
    if !value.matches("[A-Za-z_][A-Za-z0-9_]*") then
      fail(location, s"Unsupported identifier '$value'")
    value
  }

  private def description(value: CommonMetadata): String = value.description.fold("")(_.plain)

  private def annotations(
      values: Map[String, AnnotationImpl],
      allowed: Set[String],
      location: String,
  ): Unit = {
    values.foreach { case (name, annotation) =>
      if name.startsWith("optgen:") then {
        if !allowed(name) then fail(location, s"Unknown annotation '$name'")
        if annotation.extensionTag.original != name then
          fail(location, s"Mismatched annotation tag '$name'")
        if annotation.annotations.nonEmpty || annotation.extensions.nonEmpty then
          fail(location, s"Unsupported nested metadata in '$name'")
      }
    }
  }

  private val bindingTypes = Map(
    "json_format" -> ValueType.JsonFormat,
    "rdf_format" -> ValueType.RdfFormat,
    "pruning_kind" -> ValueType.PruningKind,
    "pruning" -> ValueType.Pruning,
  )

  private val enumValues = Map(
    ValueType.JsonFormat -> Set("yaml", "json"),
    ValueType.RdfFormat -> Set("ttl", "nt"),
    ValueType.PruningKind -> Set("skip", "schema", "tree_root"),
  )

  private class Reader(sv: SchemaView, source: String, yaml: String) {
    private val root = sv.root
    private val rawRoot = parseYaml(yaml).fold(error => fail(source, error.toString), identity)
    private lazy val bindings = readBindings()
    private lazy val boundTypes = bindings.map(b => b.name -> b.valueType).toMap

    // LinkmlAny's YAML round-trip loses empty collections. Read metadata from the original nodes.
    private def annotation(
        values: Map[String, AnnotationImpl],
        key: String,
        location: String,
    ): Node = {
      if !values.contains(key) then fail(location, s"Missing annotation '$key'")
      val path = location.stripPrefix(source).split('/').filter(_.nonEmpty).toVector ++ Vector(
        "annotations",
        key,
      )
      val node = path.foldLeft(rawRoot) { (parent, name) =>
        val entries = parent match {
          case mapping: Node.MappingNode =>
            mapping.mappings.collect {
              case (k: Node.ScalarNode, value) if k.tag == Tag.str && k.value == name => value
            }
          case _ => fail(location, s"Expected schema object containing '$name'")
        }
        if entries.size != 1 then fail(location, s"Expected exactly one '$name' entry")
        entries.head
      }
      node match {
        case _: Node.MappingNode =>
          required(
            obj(node, location, Set("tag", "value", "annotations", "extensions")),
            "value",
            location,
          )
        case other => other
      }
    }

    def catalog(expectedIds: Set[String]): Catalog = {
      annotations(root.annotations, Set("optgen:catalog"), source)
      val catalogLocation = s"$source/annotations/optgen:catalog"
      val contract =
        obj(annotation(root.annotations, "optgen:catalog", source), catalogLocation, Set("version"))
      required(contract, "version", catalogLocation) match {
        case n: Node.ScalarNode if n.tag == Tag.int && n.value == "1" =>
        case _ => fail(catalogLocation, "Unsupported optgen catalog version, expected 1")
      }
      root.types.values.foreach(t =>
        annotations(t.annotations, Set.empty, s"$source/types/${t.name}"),
      )
      root.slotDefinitions.values.foreach(s =>
        annotations(s.annotations, Set.empty, s"$source/slots/${s.name}"),
      )
      for (name, expected) <- Vector(
          "string" -> StringType,
          "boolean" -> BooleanType,
          "integer" -> IntegerType,
        )
      do
        if sv.types.get(name).map(_.runtimeType) != Some(expected) then
          fail(source, s"Imported primitive '$name' has an unsupported range")

      val generators =
        root.classes.values.toVector.filter(_.annotations.contains("optgen:generator"))
          .map(c => located(s"$source/classes/${c.name}")(readGenerator(sv.classes(c.name))))
          .sortBy(_.id)
      if generators.isEmpty then fail(source, "No generator definitions")
      unique(generators.map(_.id), source, "generator id")
      if expectedIds.nonEmpty && generators.map(_.id).toSet != expectedIds then {
        val actual = generators.map(_.id).toSet
        fail(
          source,
          s"Generator registry mismatch: missing ${(expectedIds -- actual).toVector.sorted.mkString(", ")}, unexpected ${(actual -- expectedIds).toVector.sorted.mkString(", ")}",
        )
      }
      val warnings = sv.lintProblems.filter(_.severity == IssueSeverity.Warning).map(
        diagnostic(source, _),
      ).toVector
      Catalog(generators, bindings, warnings)
    }

    private def readBindings(): Vector[SemanticDef] = {
      val enums = root.enums.values.toVector.flatMap { enumDef =>
        val location = s"$source/enums/${enumDef.name}"
        annotations(enumDef.annotations, Set("optgen:binding"), location)
        enumDef.permissibleValues.values.foreach { value =>
          annotations(value.annotations, Set.empty, s"$location/permissible_values/${value.text}")
        }
        enumDef.annotations.get("optgen:binding").map { _ =>
          if enumDef.inherits.nonEmpty || enumDef.include.nonEmpty || enumDef.minus.nonEmpty ||
            enumDef.reachableFrom.nonEmpty || enumDef.matches.nonEmpty ||
            enumDef.concepts.nonEmpty || enumDef.codeSet.nonEmpty || enumDef.pvFormula.nonEmpty ||
            enumDef.isA.nonEmpty || enumDef.mixins.nonEmpty
          then fail(location, "Unsupported derivation on a bound enum")
          val valueType = binding(enumDef.annotations, location)
          val expected =
            enumValues.getOrElse(valueType, fail(location, "Pruning binding requires a class"))
          if enumDef.permissibleValues.keySet != expected then
            fail(location, s"Binding values must be ${expected.toVector.sorted.mkString(", ")}")
          SemanticDef(
            enumDef.name,
            valueType,
            description(enumDef),
            enumDef.permissibleValues.values.toVector.map(p => EnumMember(p.text, description(p))),
          )
        }
      }
      val classes = root.classes.values.toVector.flatMap { cls =>
        val location = s"$source/classes/${cls.name}"
        annotations(cls.annotations, Set("optgen:binding", "optgen:generator"), location)
        cls.attributes.values.foreach(s =>
          annotations(s.annotations, Set("optgen:default"), s"$location/${s.name}"),
        )
        cls.annotations.get("optgen:binding").map { _ =>
          if binding(cls.annotations, location) != ValueType.Pruning || cls.annotations.contains(
              "optgen:generator",
            )
          then fail(location, "Only a supporting pruning class can use a class binding")
          if cls.isA.nonEmpty || cls.mixins.nonEmpty || cls.slots.nonEmpty ||
            cls.slotUsage.nonEmpty || cls.anyOf.nonEmpty || cls.allOf.nonEmpty ||
            cls.noneOf.nonEmpty || cls.exactlyOneOf.nonEmpty || cls.unionOf.nonEmpty ||
            cls.uniqueKeys.nonEmpty || cls.classificationRules.nonEmpty ||
            cls.definingSlots.nonEmpty || cls.slotConditions.nonEmpty || cls.extraSlots.nonEmpty
          then fail(location, "Unsupported structure on the pruning support class")
          if cls.attributes.keySet != Set("mode", "treeRoot") then
            fail(location, "Pruning requires mode and treeRoot attributes")
          val mode = cls.attributes("mode")
          val treeRoot = cls.attributes("treeRoot")
          val kindName = enums.find(_.valueType == ValueType.PruningKind).map(_.name)
            .getOrElse(fail(location, "Pruning requires a pruning_kind binding"))
          if !mode.required || mode.range.map(_.value) != Some(kindName) || mode.multivalued ||
            treeRoot.required || treeRoot.range.map(_.value) != Some(
              "string",
            ) || treeRoot.multivalued
          then fail(location, "Pruning requires a required mode and optional string treeRoot")
          Vector(mode, treeRoot).foreach { slot =>
            val loc = s"$location/attributes/${slot.name}"
            checkShape(slot, loc)
            if slot.inlined then fail(loc, "Unsupported inlined pruning attribute")
            if slot.ifabsent.nonEmpty || slot.annotations.contains("optgen:default") then
              fail(loc, "Unsupported default on a pruning attribute")
          }
          cls.rules match {
            case Seq(rule) =>
              if rule.deactivated || rule.bidirectional || rule.openWorld ||
                rule.elseconditions.nonEmpty
              then fail(location, "Unsupported rule flags on the pruning support class")
              val conditions = Vector(
                ("preconditions", rule.preconditions, "mode", "equals_string_in"),
                ("postconditions", rule.postconditions, "treeRoot", "value_presence"),
              ).map { case (kind, expression, slot, allowed) =>
                val loc = s"$location/rules/0/$kind"
                val e = expression.getOrElse(fail(loc, s"Unsupported pruning rule without $kind"))
                if e.isA.nonEmpty || e.anyOf.nonEmpty || e.allOf.nonEmpty || e.noneOf.nonEmpty ||
                  e.exactlyOneOf.nonEmpty
                then fail(loc, s"Unsupported boolean $kind in the pruning rule")
                if e.slotConditions.keySet != Set(slot) then
                  fail(loc, s"Unsupported $kind, expected a single condition on $slot")
                val condition = e.slotConditions(slot)
                checkConstraints(condition, s"$loc/slot_conditions/$slot", Set(allowed))
                condition
              }
              if conditions(0).equalsStringIn.toSet != Set("skip", "schema") ||
                conditions(1).valuePresence != Some(PresenceEnum.Absent)
              then
                fail(
                  location,
                  "Unsupported pruning rule, expected mode in [skip, schema] to imply an absent treeRoot",
                )
            case _ => fail(location, "Unsupported pruning rule count, expected exactly one")
          }
          SemanticDef(
            cls.name,
            ValueType.Pruning,
            description(cls),
            Vector.empty,
            cls.attributes.view.mapValues(description).toMap,
          )
        }
      }
      val result = (enums ++ classes).sortBy(_.name)
      unique(result.map(_.valueType), source, "semantic binding")
      result
    }

    private def binding(values: Map[String, AnnotationImpl], location: String): ValueType = {
      val name = text(annotation(values, "optgen:binding", location), location)
      bindingTypes.getOrElse(name, fail(location, s"Unknown binding '$name'"))
    }

    private def valueType(
        range: ElementView[?, ?],
        required: Boolean,
        location: String,
    ): ValueType = {
      val result = range match {
        case t: TypeView =>
          if t.ancestorsWithSelf.exists(_.unionMembers.nonEmpty) then
            fail(location, "Unsupported union range")
          t.ancestorsWithSelf.map(_.inner).find { d =>
            d.pattern.nonEmpty || d.structuredPattern.nonEmpty || d.equalsString.nonEmpty ||
            d.equalsStringIn.nonEmpty || d.equalsNumber.nonEmpty || d.minimumValue.nonEmpty ||
            d.maximumValue.nonEmpty || d.unit.nonEmpty || d.implicitPrefix.nonEmpty ||
            d.anyOf.nonEmpty || d.allOf.nonEmpty || d.noneOf.nonEmpty || d.exactlyOneOf.nonEmpty
          }.foreach(d => fail(location, s"Unsupported constraints on range type '${d.name}'"))
          t.runtimeType match {
            case StringType => ValueType.Text
            case BooleanType => ValueType.Bool
            case IntegerType => ValueType.Int32
            case _ => fail(location, s"Unsupported range '${range.name}'")
          }
        case _ => boundTypes.getOrElse(range.name, fail(location, s"Unbound range '${range.name}'"))
      }
      if required then result
      else if result == ValueType.Text then ValueType.OptionalText
      else fail(location, s"Unsupported optional range '${range.name}'")
    }

    private def readGenerator(cls: ClassView): GeneratorDef = {
      val location = s"$source/classes/${cls.name}"
      val metadata = obj(
        annotation(cls.inner.annotations, "optgen:generator", location),
        location,
        Set("id", "interfaces", "scala_parent"),
      )
      val id = identifier(text(required(metadata, "id", location), location), location)
      if cls.inner.isA.nonEmpty || cls.inner.mixins.nonEmpty || cls.inner.slots.nonEmpty ||
        cls.inner.anyOf.nonEmpty || cls.inner.allOf.nonEmpty || cls.inner.exactlyOneOf.nonEmpty || cls.inner.noneOf.nonEmpty
      then fail(location, "Option classes require direct attributes without inheritance or unions")
      if cls.inner.rules.nonEmpty || cls.inner.slotConditions.nonEmpty ||
        cls.inner.uniqueKeys.nonEmpty || cls.inner.classificationRules.nonEmpty ||
        cls.inner.definingSlots.nonEmpty || cls.inner.unionOf.nonEmpty ||
        cls.inner.extraSlots.nonEmpty
      then fail(location, "Unsupported rules or constraints on an option class")
      val fields = cls.derivedAttributes.values.toVector.map { slot =>
        val loc = s"$location/attributes/${slot.name}"
        located(loc) {
          val s = slot.slot
          identifier(s.name, loc)
          if Literals.pythonKeywords(s.name) || Literals.scalaMemberNames(s.name) then
            fail(loc, s"Field name '${s.name}' is reserved in an emitted language")
          annotations(s.annotations, Set("optgen:default"), loc)
          checkShape(s, loc)
          val rank = s.rank.filter(_ > 0).getOrElse(fail(loc, "Missing or nonpositive rank"))
          val range = sv.resolve(slot.derivedRange).getOrElse(fail(loc, "Unresolved range"))
          val tpe = valueType(range, s.required, loc)
          if s.inlined && tpe != ValueType.Pruning then
            fail(loc, s"Unsupported inlined range '${range.name}'")
          FieldDef(s.name, rank, tpe, coreDefault(slot, range, tpe, loc), description(s), loc)
        }
      }.sortBy(_.rank)
      unique(fields.map(_.rank), location, "rank")
      if fields.isEmpty then fail(location, "Option class has no fields")
      val profileNodes = obj(
        required(metadata, "interfaces", location),
        s"$location/interfaces",
        Set("native", "python", "js", "cli"),
      )
      val profiles = Interface.values.map { interface =>
        val key = interface.toString.toLowerCase
        interface -> profile(
          required(profileNodes, key, location),
          fields,
          interface,
          id,
          s"$location/interfaces/$key",
        )
      }.toMap
      val parent = metadata.get("scala_parent").map { node =>
        if text(node, location) != "rdf_options" || !fields.exists(f =>
            f.name == "format" && f.valueType == ValueType.RdfFormat,
          )
        then fail(location, "scala_parent must be rdf_options with an RDF format field")
        ScalaParent.RdfOptions
      }
      GeneratorDef(id, cls.name, description(cls.inner), fields, profiles, parent)
    }

    private def checkShape(slot: SlotDefinition, location: String): Unit = {
      if slot.multivalued then fail(location, "Unsupported multivalued option")
      if slot.array.nonEmpty || slot.minimumCardinality.nonEmpty || slot.maximumCardinality.nonEmpty || slot.exactCardinality.nonEmpty
      then fail(location, "Unsupported option cardinality")
      if slot.anyOf.nonEmpty || slot.allOf.nonEmpty || slot.noneOf.nonEmpty || slot.exactlyOneOf.nonEmpty || slot.unionOf.nonEmpty || slot.rangeExpression.nonEmpty
      then fail(location, "Unsupported union or range expression")
      checkConstraints(slot, location)
    }

    private def checkConstraints(
        slot: SlotDefinition,
        location: String,
        allowed: Set[String] = Set.empty,
    ): Unit = {
      val present = Vector(
        "pattern" -> slot.pattern.nonEmpty,
        "structured_pattern" -> slot.structuredPattern.nonEmpty,
        "equals_string" -> slot.equalsString.nonEmpty,
        "equals_string_in" -> slot.equalsStringIn.nonEmpty,
        "equals_number" -> slot.equalsNumber.nonEmpty,
        "equals_expression" -> slot.equalsExpression.nonEmpty,
        "minimum_value" -> slot.minimumValue.nonEmpty,
        "maximum_value" -> slot.maximumValue.nonEmpty,
        "unit" -> slot.unit.nonEmpty,
        "implicit_prefix" -> slot.implicitPrefix.nonEmpty,
        "value_presence" -> slot.valuePresence.nonEmpty,
        "inlined_as_list" -> slot.inlinedAsList,
        "identifier" -> slot.identifier,
        "key" -> slot.key,
        "designates_type" -> slot.designatesType,
        "enum_range" -> slot.enumRange.nonEmpty,
        "has_member" -> slot.hasMember.nonEmpty,
        "all_members" -> slot.allMembers.nonEmpty,
      )
      present.collectFirst { case (key, true) if !allowed(key) => key }.foreach { key =>
        fail(location, s"Unsupported slot constraint '$key'")
      }
    }

    private def coreDefault(
        slot: SlotView,
        range: ElementView[?, ?],
        tpe: ValueType,
        location: String,
    ): Value = {
      val annotationDefault = slot.slot.annotations.get("optgen:default")
      if annotationDefault.nonEmpty && slot.slot.ifabsent.nonEmpty then
        fail(location, "Conflicting default declarations")
      if tpe == ValueType.Pruning then {
        if slot.slot.ifabsent.nonEmpty then
          fail(location, "Pruning default requires optgen:default")
        literal(
          annotation(slot.slot.annotations, "optgen:default", location),
          tpe,
          s"$location/default",
        )
      } else {
        if annotationDefault.nonEmpty then
          fail(location, "optgen:default is only supported for pruning")
        slot.slot.ifabsent match {
          case None if tpe == ValueType.OptionalText => Value.Absent
          case None => fail(location, "Required option has no default")
          case Some(expr) =>
            located(s"$location/ifabsent") {
              tpe match {
                case ValueType.Bool if Set("True", "true", "False", "false")(expr) =>
                  Value.Bool(expr == "True" || expr == "true")
                case ValueType.Int32 if expr.matches("int\\(-?[0-9]+\\)") =>
                  Value.Int32(
                    expr.substring(4, expr.length - 1).toIntOption.getOrElse(
                      fail(location, "ifabsent integer exceeds Int range"),
                    ),
                  )
                case ValueType.Text | ValueType.OptionalText
                    if expr.matches("(?s)string\\([^()]*\\)") =>
                  Value.Text(expr.substring(7, expr.length - 1))
                case ValueType.JsonFormat | ValueType.RdfFormat =>
                  range match {
                    case enumView: EnumView => Value.Enum(slot.ifAbsent(enumView).get.text)
                    case _ => fail(location, "Enum ifabsent requires an enum range")
                  }
                case _ => fail(location, s"Unsupported ifabsent '$expr' for $tpe")
              }
            }
        }
      }
    }

    private def literal(node: Node, tpe: ValueType, location: String): Value = (tpe, node) match {
      case (ValueType.Text | ValueType.OptionalText, n: Node.ScalarNode) if n.tag == Tag.str =>
        Value.Text(n.value)
      case (ValueType.OptionalText, n: Node.ScalarNode) if n.tag == Tag.nullTag => Value.Absent
      case (ValueType.Bool, n: Node.ScalarNode) if n.tag == Tag.boolean =>
        Value.Bool(n.value.toBoolean)
      case (ValueType.Int32, n: Node.ScalarNode) if n.tag == Tag.int =>
        Value.Int32(n.value.toIntOption.getOrElse(fail(location, "Invalid default integer")))
      case (ValueType.JsonFormat | ValueType.RdfFormat | ValueType.PruningKind, n: Node.ScalarNode)
          if n.tag == Tag.str && enumValues(tpe)(n.value) =>
        Value.Enum(n.value)
      case (ValueType.Pruning, _) =>
        val fields = obj(node, location, Set("mode", "treeRoot"))
        val mode = text(required(fields, "mode", location), location) match {
          case "skip" => PruningKind.Skip
          case "schema" => PruningKind.Schema
          case "tree_root" => PruningKind.TreeRoot
          case other => fail(location, s"Unknown pruning default mode '$other'")
        }
        val root = fields.get("treeRoot").map(text(_, location))
        if root.nonEmpty && mode != PruningKind.TreeRoot then
          fail(location, "treeRoot requires tree_root pruning")
        Value.Pruning(mode, root)
      case _ => fail(location, s"Invalid default for $tpe")
    }

    private def projection(
        field: FieldDef,
        selector: Selector,
        default: Value,
        location: String,
    ): (ValueType, Value) =
      (selector.component, default) match {
        case (None, value) => (field.valueType, value)
        case (Some(PruningComponent.Mode), Value.Pruning(mode, _)) =>
          val name = mode match {
            case PruningKind.Skip => "skip"
            case PruningKind.Schema => "schema"
            case PruningKind.TreeRoot => "tree_root"
          }
          (ValueType.PruningKind, Value.Enum(name))
        case (Some(PruningComponent.TreeRoot), Value.Pruning(_, root)) =>
          (ValueType.OptionalText, root.fold[Value](Value.Absent)(Value.Text.apply))
        case _ => fail(location, "Selector component requires a pruning field")
      }

    private def parameterDescription(field: FieldDef, selector: Selector): String =
      if selector.component.contains(PruningComponent.TreeRoot) then
        bindings.find(_.valueType == ValueType.Pruning)
          .flatMap(_.attributeDescriptions.get("treeRoot")).getOrElse(field.description)
      else field.description

    private def profile(
        node: Node,
        fields: Vector[FieldDef],
        interface: Interface,
        id: String,
        location: String,
    ): Profile = {
      val metadata = obj(node, location, Set("preset", "parameters", "omitted", "defaults"))
      metadata.get("preset") match {
        case Some(preset) =>
          if metadata.size != 1 then fail(location, "A preset cannot have additional profile keys")
          val expected = interface match {
            case Interface.Native => "core"
            case Interface.Python => "core_python"
            case _ => fail(location, "Presets are only supported for native and python")
          }
          if text(preset, location) != expected then fail(location, s"Expected preset '$expected'")
          val parameters = fields.flatMap { field =>
            val selectors =
              if interface == Interface.Python && field.valueType == ValueType.Pruning then
                Vector(
                  Selector(field.name, Some(PruningComponent.Mode)),
                  Selector(field.name, Some(PruningComponent.TreeRoot)),
                )
              else Vector(Selector(field.name))
            selectors.map { selector =>
              val (tpe, value) = projection(field, selector, field.default, location)
              val name = if selector.component.contains(PruningComponent.TreeRoot) then "tree_root"
              else if interface == Interface.Python then
                field.name.flatMap(c => if c.isUpper then s"_${c.toLower}" else c.toString)
              else field.name
              if interface == Interface.Python &&
                (Literals.pythonKeywords(name) || Literals.pythonFixedNames(name))
              then fail(location, s"Python parameter name '$name' is reserved")
              Parameter(selector, name, tpe, Some(value), parameterDescription(field, selector))
            }
          }
          unique(parameters.map(_.name), location, "parameter name")
          Profile(parameters, Set.empty)
        case None =>
          if interface == Interface.Native || interface == Interface.Python then
            fail(location, "Native/python profile requires a preset")
          val byName = fields.map(f => f.name -> f).toMap
          val overrides = metadata.get("defaults").map { defaults =>
            obj(defaults, s"$location/defaults", byName.keySet).map { case (name, value) =>
              name -> literal(value, byName(name).valueType, s"$location/defaults/$name")
            }
          }.getOrElse(Map.empty)
          val parameters =
            sequence(required(metadata, "parameters", location), location).zipWithIndex.map {
              case (parameter, index) =>
                val loc = s"$location/parameters/$index"
                val p = obj(
                  parameter,
                  loc,
                  Set("selector", "name", "default", "description", "ts_name", "adapter"),
                )
                val parts = text(required(p, "selector", loc), loc).split("\\.", -1).toVector
                val field = byName.getOrElse(
                  parts.head,
                  fail(loc, s"Unknown selector '${parts.mkString(".")}'"),
                )
                val component = parts.drop(1) match {
                  case Vector() => None
                  case Vector("mode") => Some(PruningComponent.Mode)
                  case Vector("treeRoot") => Some(PruningComponent.TreeRoot)
                  case _ => fail(loc, s"Unknown selector '${parts.mkString(".")}'")
                }
                val selector = Selector(field.name, component)
                val (tpe, inherited) =
                  projection(field, selector, overrides.getOrElse(field.name, field.default), loc)
                val default = p.get("default").map { node =>
                  val d = obj(node, s"$loc/default", Set("kind", "value"))
                  text(required(d, "kind", loc), loc) match {
                    case "none" if d.size == 1 => None
                    case "literal" => Some(literal(required(d, "value", loc), tpe, s"$loc/default"))
                    case _ => fail(loc, "Invalid default policy")
                  }
                }.getOrElse(Some(inherited))
                val name = identifier(text(required(p, "name", loc), loc), loc)
                val reserved = Set("schema", "common") ++ (interface match {
                  case Interface.Js => Literals.jsWrapperNames(id)
                  case Interface.Cli => Literals.scalaMemberNames
                  case _ => Set.empty[String]
                })
                if reserved(name) then fail(loc, s"Reserved parameter name '$name'")
                val tsName = p.get("ts_name").map { n =>
                  if interface != Interface.Js then fail(loc, "ts_name requires a JS profile")
                  val name = identifier(text(n, loc), loc)
                  if name == "schema" then fail(loc, "Reserved TS parameter name 'schema'")
                  name
                }
                if interface == Interface.Js && Literals.tsReservedWords(tsName.getOrElse(name))
                then fail(loc, s"Reserved TypeScript parameter name '${tsName.getOrElse(name)}'")
                val adapter = p.get("adapter").map { n =>
                  text(n, loc) match {
                    case "pruning_options"
                        if interface == Interface.Cli && tpe == ValueType.Pruning =>
                      Adapter.PruningOptions
                    case "translation_targets"
                        if interface == Interface.Cli && id == "translation" && field.name == "to" && tpe == ValueType.Text =>
                      Adapter.TranslationTargets
                    case other => fail(loc, s"Unsupported adapter '$other' for $interface/$tpe")
                  }
                }
                if tpe == ValueType.Pruning && !adapter.contains(Adapter.PruningOptions) then
                  fail(loc, "Whole pruning selector requires pruning_options adapter")
                Parameter(
                  selector,
                  name,
                  tpe,
                  default,
                  p.get("description").map(text(_, loc)).getOrElse(
                    parameterDescription(field, selector),
                  ),
                  tsName,
                  adapter,
                )
            }
          unique(parameters.map(_.name), location, "parameter name")
          unique(parameters.map(p => p.tsName.getOrElse(p.name)), location, "TS parameter name")
          unique(parameters.map(_.selector), location, "selector")
          val omitted = sequence(required(metadata, "omitted", location), s"$location/omitted").map(
            text(_, location),
          )
          unique(omitted, location, "omitted field")
          val exposed = parameters.map(_.selector.field).toSet
          if interface == Interface.Js then
            Entrypoints.all.find(_.python == id).toVector.flatMap(_.jsPrelude).foreach { kind =>
              if exposed.count(field => byName(field).valueType == kind) > 1 then
                fail(location, s"At most one exposed $kind field can be bound in the JS prelude")
            }
          if (exposed intersect omitted.toSet).nonEmpty then
            fail(
              location,
              s"Fields both exposed and omitted: ${(exposed intersect omitted.toSet).mkString(", ")}",
            )
          if exposed ++ omitted.toSet != byName.keySet then
            fail(
              location,
              s"Incomplete field coverage: ${((byName.keySet -- exposed -- omitted) ++ (omitted.toSet -- byName.keySet)).mkString(", ")}",
            )
          if (overrides.keySet -- exposed).nonEmpty then
            fail(location, "Default override targets an omitted field")
          parameters.groupBy(_.selector.field).foreach { case (name, entries) =>
            val components = entries.map(_.selector.component).toSet
            if components != Set(None) && components != Set(
                Some(PruningComponent.Mode),
                Some(PruningComponent.TreeRoot),
              )
            then fail(location, s"Incomplete or overlapping pruning selectors for '$name'")
          }
          Profile(parameters, omitted.toSet)
      }
    }
  }
}
