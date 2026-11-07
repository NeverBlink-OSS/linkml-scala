package eu.neverblink.linkml.generator.javagen

import eu.neverblink.linkml.generator.javagen.JavaGenerator.{Code, javaString}

/** Classes for Jackson, using only the annotations of `com.fasterxml.jackson.annotation`, shared by
  * both Jackson 2 and 3.
  *
  * Jackson converts JSON values to the type of a field when it can, such as `"2"` to an integer.
  * With [[strict]], the factory instead checks it with the helper class `LinkMLJson`.
  */
private[javagen] final class JacksonWriter(strict: Boolean) extends StyleWriter {

  /** Hides the public getters and setters from Jackson, so that it uses only the annotated members.
    */
  private val autoDetect =
    """@JsonAutoDetect(
      |    getterVisibility = JsonAutoDetect.Visibility.NONE,
      |    isGetterVisibility = JsonAutoDetect.Visibility.NONE,
      |    setterVisibility = JsonAutoDetect.Visibility.NONE)""".stripMargin

  override def classAnnotations(cls: JavaClass): Seq[String] = {
    val keys = cls.allFields.map(f => javaString(f.key))
    val order =
      if keys.isEmpty then Nil
      else if keys.map(_.length + 2).sum + 22 <= 100 then
        Seq(keys.mkString("@JsonPropertyOrder({", ", ", "})"))
      else Seq(keys.mkString("@JsonPropertyOrder({\n    ", ",\n    ", ",\n})"))
    val union = cls.union.toSeq.flatMap { (key, members) =>
      val types = members.map { (name, values) =>
        val names =
          if values.sizeIs == 1 then s"name = ${javaString(values.head)}"
          else values.map(javaString).mkString("names = {", ", ", "}")
        s"    @JsonSubTypes.Type(value = $name.class, $names),"
      }
      Seq(
        s"""@JsonTypeInfo(
           |    use = JsonTypeInfo.Id.NAME,
           |    include = JsonTypeInfo.As.EXISTING_PROPERTY,
           |    property = ${javaString(key)},
           |    visible = true)""".stripMargin,
        types.mkString("@JsonSubTypes({\n", "\n", "\n})"),
      )
    }
    Seq(autoDetect, "@JsonInclude(JsonInclude.Include.NON_NULL)") ++ order ++ union
  }

  /** Marks the fields that Jackson writes as they are. Dates, times and dicts have their own
    * getters.
    */
  override def fieldAnnotations(field: JavaField): Seq[String] = field.tpe match {
    case FieldType.Native(_) | FieldType.NativeList(_) =>
      Seq(s"@JsonProperty(${javaString(field.key)})")
    case FieldType.Strict(_, reader, _) if !textual(reader) =>
      Seq(s"@JsonProperty(${javaString(field.key)})")
    case _ => Nil
  }

  /** Whether the value is a date or time, written as text. */
  private def textual(reader: Reader): Boolean = reader match {
    case Reader.Of(scalar) => scalar.textual
    case _ => false
  }

  private def call(reader: Reader, arg: String): String = reader match {
    case Reader.Of(scalar) => s"LinkMLJson.${scalar.read}($arg)"
    case Reader.Enum(name) => s"$name.fromValue($arg)"
    case Reader.AnyOf(members) =>
      val refs = members.map {
        case Reader.Of(scalar) if scalar.textual => s"LinkMLJson::${scalar.read}Text"
        case member => ref(member)
      }
      s"LinkMLJson.anyOf($arg, ${javaString(reader.expected)}, ${refs.mkString(", ")})"
  }

  private def ref(reader: Reader): String = reader match {
    case Reader.Of(scalar) => s"LinkMLJson::${scalar.read}"
    case Reader.Enum(name) => s"$name::fromValue"
    case _: Reader.AnyOf => s"v -> ${call(reader, "v")}"
  }

  private def keyReader(scalar: Scalar): String = scalar match {
    case Scalar.Str | Scalar.Text => "k -> k"
    case Scalar.Int => "LinkMLJson::integerKey"
    case Scalar.Num => "LinkMLJson::numberKey"
    case Scalar.Bool => "LinkMLJson::boolKey"
    case Scalar.Date | Scalar.DateTime | Scalar.Time => s"LinkMLJson::${scalar.read}"
  }

  private def withKeys(dict: FieldType.Dict, arg: String): String = {
    val supplier = if dict.construct then s"${dict.valueClass}::new" else "null"
    val key = dict.keyField
    s"LinkMLJson.withKeys($arg, $supplier, ${keyReader(dict.keyScalar)}, " +
      s"${dict.valueClass}::${key.getter}, ${dict.valueClass}::${key.setter}, " +
      s"${javaString(key.key)})"
  }

  /** Leaves the key slot out of each object of a dict. */
  private def withoutKeys(dict: FieldType.Dict): String =
    s"@JsonIgnoreProperties(value = ${javaString(dict.keyField.key)}, allowSetters = true)"

  private def keyed(dict: FieldType.Dict, arg: String): String =
    s"LinkMLJson.keyed($arg, ${if dict.bare then s"${dict.valueClass}::json$$bare" else "null"})"

  override def jsonMembers(cls: JavaClass, out: Code): Unit = {
    out.blank()
    out.line(1, "// Reading and writing JSON, for Jackson")
    cls.allFields.filter(f => strict && f.checks.pattern.isDefined).foreach { f =>
      out.blank()
      out.line(
        1,
        s"private static final Pattern ${patternName(f)} =\n" +
          s"        Pattern.compile(${javaString(f.checks.pattern.get.java)});",
      )
    }
    if !cls.isAbstract then writeCreator(cls, out)
    cls.fields.foreach { f =>
      val name = f.name
      f.tpe match {
        case FieldType.Strict(_, reader, multivalued) if textual(reader) =>
          out.blank()
          out.line(1, s"@JsonGetter(${javaString(f.key)})")
          out.line(1, s"private Object json$$$name() {")
          out.line(2, s"return ${writeText(reader, name, multivalued)};")
          out.line(1, "}")
        case dict: FieldType.Dict =>
          out.blank()
          out.line(1, s"@JsonGetter(${javaString(f.key)})")
          out.line(1, withoutKeys(dict))
          out.line(1, s"private Map<Object, Object> json$$$name() {")
          out.line(2, s"return ${keyed(dict, name)};")
          out.line(1, "}")
        case _ =>
      }
    }
    writeBare(cls, out)
    writeExtra(cls, out)
  }

  private def patternName(f: JavaField): String = s"json$$${f.name}Pattern"

  private def writeText(reader: Reader, arg: String, multivalued: Boolean): String = {
    val method = reader match {
      case Reader.Of(Scalar.Date) => "jsonDate"
      case Reader.Of(Scalar.DateTime) => "jsonDateTime"
      case _ => "jsonTime"
    }
    if multivalued then s"LinkMLJson.jsonList($arg, LinkMLJson::$method)"
    else s"LinkMLJson.$method($arg)"
  }

  /** Whether the factory takes the raw JSON value and converts it itself: always with [[strict]],
    * and for dates and times, which Jackson 2 can't read without a module.
    */
  private def readsItself(tpe: FieldType): Boolean = tpe match {
    case FieldType.Strict(_, reader, _) => strict || textual(reader)
    case _ => false
  }

  private def parameterType(tpe: FieldType): String =
    if readsItself(tpe) then "Object" else tpe.javaType

  /** Jackson checks that required properties are present and that lists contain no nulls. With
    * [[strict]], it also rejects null for a required property.
    */
  private def parameterAnnotations(f: JavaField): String = {
    val property =
      if f.checks.required then s"@JsonProperty(value = ${javaString(f.key)}, required = true)"
      else s"@JsonProperty(${javaString(f.key)})"
    val list = !readsItself(f.read) && (f.read match {
      case FieldType.NativeList(_) | FieldType.Strict(_, _, true) => true
      case _ => false
    })
    val nulls = Seq(
      Option.when(strict && f.checks.required)("nulls = Nulls.FAIL"),
      Option.when(list)("contentNulls = Nulls.FAIL"),
    ).flatten
    if nulls.isEmpty then property else s"$property @JsonSetter(${nulls.mkString(", ")})"
  }

  /** The `@JsonCreator` factory, which takes all properties at once. With [[strict]], it also
    * checks the constraints of the schema.
    */
  private def writeCreator(cls: JavaClass, out: Code): Unit = {
    out.blank()
    out.line(1, "@JsonCreator")
    val parameters =
      cls.allFields.map(f => s"${parameterAnnotations(f)} ${parameterType(f.read)} ${f.name}")
    if parameters.isEmpty then out.line(1, s"private static ${cls.name} json$$create() {")
    else {
      out.line(1, s"private static ${cls.name} json$$create(")
      out.line(3, parameters.mkString(",\n") + ") {")
    }
    out.line(2, s"${cls.name} json$$item = new ${cls.name}();")
    cls.allFields.foreach { f =>
      val line = s"json$$item.${f.setter}(${read(f)});"
      if line.length + 8 <= 100 then out.line(2, line)
      else out.line(2, s"json$$item.${f.setter}(\n        ${read(f)});")
    }
    out.line(2, "return json$item;")
    out.line(1, "}")
  }

  /** The argument for the setter of [[f]]: its parameter, converted and checked. */
  private def read(f: JavaField): String = {
    val key = javaString(f.key)
    val c = if strict then f.checks else Checks()
    def scalar(e: String, reader: Reader): String = reader match {
      case Reader.Of(Scalar.Str | Scalar.Text)
          if c.minLength.isDefined || c.maxLength.isDefined || c.pattern.isDefined =>
        val pattern = if c.pattern.isDefined then patternName(f) else "null"
        s"LinkMLJson.checkText($e, ${c.minLength.getOrElse(-1)}, ${c.maxLength.getOrElse(-1)}, " +
          s"$pattern, $key)"
      case Reader.Of(Scalar.Int | Scalar.Num) => bounded(e)
      case _ => e
    }
    def bounded(e: String): String =
      if c.minValue.isEmpty && c.maxValue.isEmpty then e
      else
        s"LinkMLJson.between($e, ${c.minValue.fold("null")(javaString)}, " +
          s"${c.maxValue.fold("null")(javaString)}, $key)"
    val name = f.name
    val value = f.read match {
      case tpe @ FieldType.Strict(_, reader, false) =>
        if readsItself(tpe) then scalar(call(reader, name), reader) else name
      case tpe @ FieldType.Strict(_, reader, true) =>
        if readsItself(tpe) then {
          val item = scalar(call(reader, "v"), reader)
          val each = if item == call(reader, "v") then ref(reader) else s"v -> $item"
          s"LinkMLJson.list($name, $each)"
        } else name
      case FieldType.Native("BigDecimal") => bounded(name)
      case FieldType.Native(_) | FieldType.NativeList(_) => name
      case dict: FieldType.Dict => withKeys(dict, name)
    }
    val counted =
      if c.minCount.isEmpty && c.maxCount.isEmpty then value
      else
        s"LinkMLJson.count($value, ${c.minCount.getOrElse(-1)}, ${c.maxCount.getOrElse(-1)}, $key)"
    // Java generics are not covariant, so a List<CarPart> is copied into a List<Part>
    if f.read.javaType == f.tpe.javaType then counted
    else
      f.read match {
        case FieldType.NativeList(_) => s"LinkMLJson.copyList($counted)"
        case _: FieldType.Dict => s"LinkMLJson.copyMap($counted)"
        case _ => counted
      }
  }

  /** For a simple dict: a factory that reads an entry written as just its value, and `json$bare()`,
    * which returns that value if the object has nothing else.
    */
  private def writeBare(cls: JavaClass, out: Code): Unit =
    cls.bare.foreach { (key, value) =>
      val (param, arg) = StyleWriter.bareParameter(value.tpe).get
      out.blank()
      out.line(1, "@JsonCreator(mode = JsonCreator.Mode.DELEGATING)")
      out.line(1, s"private static ${cls.name} json$$of($param value) {")
      out.line(2, s"${cls.name} json$$item = new ${cls.name}();")
      out.line(2, s"json$$item.${value.setter}($arg);")
      out.line(2, "return json$item;")
      out.line(1, "}")
      out.blank()
      out.javadoc(
        Seq(
          s"The JSON value of `${value.key}` for the short form in a dict, if the object has",
          s"nothing else but `${key.key}`. Otherwise null.",
        ),
        1,
      )
      out.line(1, "Object json$bare() {")
      val others = cls.allFields.filter(f => f.name != key.name && f.name != value.name)
        .map(f => s"${f.getter}() == null") ++
        Option.when(cls.open)("getExtraProperties().isEmpty()")
      val json = value.tpe match {
        case FieldType.Strict(_, reader, _) if textual(reader) =>
          writeText(reader, s"${value.getter}()", false)
        case _ => s"${value.getter}()"
      }
      if others.isEmpty then out.line(2, s"return $json;")
      else {
        out.line(2, s"if (${others.mkString(" && ")}) {")
        out.line(3, s"return $json;")
        out.line(2, "}")
        out.line(2, "return null;")
      }
      out.line(1, "}")
    }

  /** Handles properties that are not slots: an open class keeps them, and a closed one rejects
    * them.
    */
  private def writeExtra(cls: JavaClass, out: Code): Unit =
    if !cls.parentOpen.contains(cls.open) then {
      val overrides = cls.parentOpen.isDefined
      out.blank()
      if overrides then out.line(1, "@Override")
      out.line(1, "@JsonAnySetter")
      out.line(1, "protected void json$extra(String key, Object value) {")
      if cls.open then out.line(2, "extraProperties.put(key, value);")
      else
        out.line(
          2,
          "throw new IllegalArgumentException(\"unknown property \\\"\" + key + \"\\\"\");",
        )
      out.line(1, "}")
      out.blank()
      if overrides then out.line(1, "@Override")
      out.line(1, "@JsonAnyGetter")
      out.line(1, "protected Map<String, Object> json$extra() {")
      out.line(2, if cls.open then "return getExtraProperties();" else "return null;")
      out.line(1, "}")
    }

  override def enumValueAnnotations: Seq[String] = Seq("@JsonValue")

  override def enumFactoryAnnotations: Seq[String] = Seq("@JsonCreator")

  override def document(doc: JavaDocument): String = {
    val out = Code()
    val tpe = doc.root.javaType
    out.javadoc(doc.doc, 0)
    out.line(0, autoDetect)
    out.line(0, s"public final class ${doc.name} {")
    out.line(1, s"private final $tpe root;")
    out.blank()
    out.line(1, "@JsonCreator(mode = JsonCreator.Mode.DELEGATING)")
    out.line(1, s"public ${doc.name}($tpe root) {")
    doc.root match {
      case dict: FieldType.Dict => out.line(2, s"this.root = ${withKeys(dict, "root")};")
      case _ =>
        out.line(2, if strict then "this.root = LinkMLJson.noNulls(root);" else "this.root = root;")
    }
    out.line(1, "}")
    out.blank()
    doc.root match {
      case _: FieldType.Dict =>
      case _ => out.line(1, "@JsonValue")
    }
    out.line(1, s"public $tpe getRoot() {")
    out.line(2, "return root;")
    out.line(1, "}")
    out.blank()
    out.line(1, "@Override")
    out.line(1, "public boolean equals(Object o) {")
    out.line(2, s"return o instanceof ${doc.name} && Objects.equals(root, ((${doc.name}) o).root);")
    out.line(1, "}")
    out.blank()
    out.line(1, "@Override")
    out.line(1, "public int hashCode() {")
    out.line(2, "return Objects.hashCode(root);")
    out.line(1, "}")
    out.blank()
    out.line(1, "@Override")
    out.line(1, "public String toString() {")
    out.line(2, s"return ${javaString(doc.name + "{root=")} + root + \"}\";")
    out.line(1, "}")
    doc.root match {
      case dict: FieldType.Dict =>
        out.blank()
        out.line(1, "// Writing JSON, for Jackson")
        out.blank()
        out.line(1, "@JsonAnyGetter")
        out.line(1, withoutKeys(dict))
        out.line(1, "private Map<Object, Object> json$root() {")
        out.line(2, s"return ${keyed(dict, "root")};")
        out.line(1, "}")
      case _ =>
    }
    out.line(0, "}")
    out.result
  }

  override def supportFiles(sources: Iterable[String]): Seq[(String, String)] =
    JacksonHelper(sources).map(JacksonHelper.name -> _).toSeq
}

private[javagen] object JacksonWriter {

  /** The annotations the generated code may use, all in `com.fasterxml.jackson.annotation`. */
  val annotations: Seq[String] = Seq(
    "JsonAnyGetter",
    "JsonAnySetter",
    "JsonAutoDetect",
    "JsonCreator",
    "JsonGetter",
    "JsonIgnoreProperties",
    "JsonInclude",
    "JsonProperty",
    "JsonPropertyOrder",
    "JsonSetter",
    "JsonSubTypes",
    "JsonTypeInfo",
    "JsonValue",
    "Nulls",
  )
}
