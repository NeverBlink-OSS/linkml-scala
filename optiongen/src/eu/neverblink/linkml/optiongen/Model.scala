package eu.neverblink.linkml.optiongen

enum ValueType {
  case Text, Bool, Int32, OptionalText, JsonFormat, RdfFormat, Pruning, PruningKind
}

enum PruningKind {
  case Skip, Schema, TreeRoot
}

enum Value {
  case Text(value: String)
  case Bool(value: Boolean)
  case Int32(value: Int)
  case Absent
  case Enum(value: String)
  case Pruning(mode: PruningKind, treeRoot: Option[String])
}

enum Interface {
  case Native, Python, Js, Cli
}

enum PruningComponent {
  case Mode, TreeRoot
}

enum Adapter {
  case PruningOptions, TranslationTargets
}

enum ScalaParent {
  case RdfOptions
}

final case class Diagnostic(location: String, message: String) {
  override def toString: String = s"$location: $message"
}

final case class Selector(field: String, component: Option[PruningComponent] = None)

final case class FieldDef(
    name: String,
    rank: Int,
    valueType: ValueType,
    default: Value,
    description: String,
    location: String,
)

final case class Parameter(
    selector: Selector,
    name: String,
    valueType: ValueType,
    default: Option[Value],
    description: String,
    tsName: Option[String] = None,
    adapter: Option[Adapter] = None,
)

final case class Profile(parameters: Vector[Parameter], omitted: Set[String])

final case class GeneratorDef(
    id: String,
    className: String,
    description: String,
    fields: Vector[FieldDef],
    profiles: Map[Interface, Profile],
    scalaParent: Option[ScalaParent],
)

final case class EnumMember(name: String, description: String)

final case class SemanticDef(
    name: String,
    valueType: ValueType,
    description: String,
    members: Vector[EnumMember],
    attributeDescriptions: Map[String, String] = Map.empty,
)

final case class Catalog(
    generators: Vector[GeneratorDef],
    bindings: Vector[SemanticDef],
    warnings: Vector[Diagnostic],
)
