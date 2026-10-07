package eu.neverblink.linkml.generator.pydantic

/** Code the generated module includes only when it is used. */
private enum Helper(val code: String, val imports: Seq[(String, String)]) {
  case iso
      extends Helper(
        """def _iso(pattern: str) -> Any:
        |    '''Accept a date or time only as ISO 8601 text, as JSON Schema does.'''
        |    regex = re.compile(pattern)
        |
        |    def check(value: Any) -> Any:
        |        if isinstance(value, (int, float)) or isinstance(value, str) and not regex.match(value):
        |            raise ValueError("expected ISO 8601 text")
        |        return value
        |
        |    return BeforeValidator(check)
        |""".stripMargin.replace("'''", "\"\"\""),
        Seq("re" -> "", "typing" -> "Any", "pydantic" -> "BeforeValidator"),
      )
  case date
      extends Helper(
        "JsonDate = Annotated[date, _iso(r\"^\\d{4}-\\d{2}-\\d{2}$\")]\n",
        Seq("datetime" -> "date", "typing" -> "Annotated"),
      )
  case dateTime
      extends Helper(
        "JsonDateTime = Annotated[datetime, _iso(r\"^\\d{4}-\\d{2}-\\d{2}[Tt ]\")]\n",
        Seq("datetime" -> "datetime", "typing" -> "Annotated"),
      )
  case time
      extends Helper(
        "JsonTime = Annotated[time, _iso(r\"^\\d{2}:\\d{2}\")]\n",
        Seq("datetime" -> "time", "typing" -> "Annotated"),
      )
  case decimal
      extends Helper(
        """def _number(value: Any) -> Any:
        |    if isinstance(value, (str, bool)):
        |        raise ValueError("expected a number")
        |    return value
        |
        |
        |def _decimal_to_json(value: Decimal) -> int | float:
        |    if value == value.to_integral_value() and int(value.as_tuple().exponent) >= 0:
        |        return int(value)
        |    return float(value)
        |
        |
        |JsonDecimal = Annotated[
        |    Decimal, BeforeValidator(_number), PlainSerializer(_decimal_to_json, when_used="json")
        |]
        |'''A decimal, read from and written as a JSON number.'''
        |""".stripMargin.replace("'''", "\"\"\""),
        Seq(
          "decimal" -> "Decimal",
          "typing" -> "Annotated",
          "typing" -> "Any",
          "pydantic" -> "BeforeValidator",
          "pydantic" -> "PlainSerializer",
        ),
      )
  case keyed
      extends Helper(
        """def _with_keys(key: str, value: str | None = None, parse: Any = None) -> Any:
        |    '''Read a dict of objects by key: each entry's key is the `key` slot of the object,
        |    which may leave it out. With `value`, an entry can also be just the value of that slot.'''
        |
        |    def load(data: Any) -> Any:
        |        if not isinstance(data, dict):
        |            return data
        |        objects = {}
        |        for k, entry in data.items():
        |            own = parse(k) if parse else k
        |            if entry is None:
        |                entry = {key: own}
        |            elif isinstance(entry, dict):
        |                if key not in entry:
        |                    entry = {**entry, key: own}
        |                elif entry[key] != own:
        |                    raise ValueError(f"the entry {k!r} has {key} {entry[key]!r}")
        |            elif value is not None and not isinstance(entry, BaseModel):
        |                entry = {key: own, value: entry}
        |            objects[k] = entry
        |        return objects
        |
        |    return BeforeValidator(load)
        |
        |
        |def _without_keys(key: str, value: str | None = None) -> Any:
        |    '''Write a dict of objects by key, leaving the key out of the objects. With `value`, an
        |    object that has nothing else but that slot is written as just its value.'''
        |
        |    def dump(data: Any, handler: Any) -> Any:
        |        result = handler(data)
        |        if not isinstance(result, dict):
        |            return result
        |        entries = {}
        |        for k, entry in result.items():
        |            if isinstance(entry, dict):
        |                entry = {f: v for f, v in entry.items() if f != key}
        |                if value is not None and list(entry) == [value] and not isinstance(entry[value], dict):
        |                    entry = entry[value]
        |            entries[k] = entry
        |        return entries
        |
        |    return WrapSerializer(dump)
        |""".stripMargin.replace("'''", "\"\"\""),
        Seq(
          "typing" -> "Any",
          "pydantic" -> "BaseModel",
          "pydantic" -> "BeforeValidator",
          "pydantic" -> "WrapSerializer",
        ),
      )
}
