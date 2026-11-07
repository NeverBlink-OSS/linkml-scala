package eu.neverblink.linkml.generator.javagen

/** The `LinkMLJson` helper class, with only the members that the generated classes use. */
private[javagen] object JacksonHelper {

  val name = "LinkMLJson"

  /** A method or nested class of the helper, with the other members it calls. */
  private final case class Member(name: String, uses: Seq[String], code: String)

  /** The helper with the members used by [[sources]]. None if they use none. */
  def apply(sources: Iterable[String]): Option[String] = {
    val used = sources.iterator
      .flatMap(s => s"""$name(?:\\.|::)(\\w+)""".r.findAllMatchIn(s).map(_.group(1)))
      .toSet
    val byName = members.map(m => m.name -> m).toMap
    def closure(names: Set[String]): Set[String] = {
      val more = names ++ names.flatMap(n => byName.get(n).toSeq.flatMap(_.uses))
      if more == names then names else closure(more)
    }
    val needed = closure(used.filter(byName.contains))
    Option.when(needed.nonEmpty) {
      val body = members.filter(m => needed(m.name)).map(_.code.stripMargin).mkString("\n")
      s"""/**
         | * Reads and writes the JSON values of the generated classes, for Jackson. Its methods
         | * throw an {@link IllegalArgumentException} for a value they can't read, which Jackson
         | * reports with the location in the JSON.
         | */
         |final class $name {
         |    private $name() {
         |    }
         |
         |$body}
         |""".stripMargin
    }
  }

  /** Reads a value of one JSON type, and rejects any other. */
  private def reader(method: String, java: String, what: String, accept: String): Member =
    Member(
      method,
      Seq("expected"),
      s"""|    static $java $method(Object value) {
          |        if (value == null || value instanceof $accept) {
          |            return ($java) value;
          |        }
          |        throw expected("$what", value);
          |    }
          |""",
    )

  /** Reads a date or time from text. */
  private def temporal(method: String, java: String, what: String): Seq[Member] = Seq(
    Member(
      method,
      Seq("expected"),
      s"""|    static $java $method(Object value) {
          |        if (value == null) {
          |            return null;
          |        }
          |        try {
          |            if (value instanceof String) {
          |                return $java.parse((String) value);
          |            }
          |        } catch (DateTimeParseException e) {
          |            // reported below
          |        }
          |        throw expected("$what", value);
          |    }
          |""",
    ),
    // In a union, a date or time stays text, so that it is written back unchanged
    Member(
      s"${method}Text",
      Seq(method),
      s"""|    static String ${method}Text(Object value) {
          |        $method(value);
          |        return (String) value;
          |    }
          |""",
    ),
  )

  private def json(method: String, java: String, format: String): Member =
    Member(
      method,
      Nil,
      s"""|    static String $method($java value) {
          |        return value == null ? null : $format;
          |    }
          |""",
    )

  /** Parses a dict key that is not text. */
  private def key(method: String, java: String, what: String, parse: String): Member =
    Member(
      method,
      Seq("expected"),
      s"""|    static $java $method(String key) {
          |        try {
          |            return $parse;
          |        } catch (IllegalArgumentException e) {
          |            throw expected("$what", key);
          |        }
          |    }
          |""",
    )

  // The generated class has them in this order
  private val members: Seq[Member] = Seq(
    reader("string", "String", "a string", "String"),
    Member(
      "integer",
      Seq("expected"),
      """|    static Long integer(Object value) {
         |        if (value == null || value instanceof Long) {
         |            return (Long) value;
         |        }
         |        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
         |            return ((Number) value).longValue();
         |        }
         |        if (value instanceof BigInteger) {
         |            try {
         |                return ((BigInteger) value).longValueExact();
         |            } catch (ArithmeticException e) {
         |                throw new IllegalArgumentException("integer out of range: " + value);
         |            }
         |        }
         |        throw expected("an integer", value);
         |    }
         |""",
    ),
    Member(
      "number",
      Seq("expected"),
      """|    static Double number(Object value) {
         |        if (value == null || value instanceof Double) {
         |            return (Double) value;
         |        }
         |        if (value instanceof Number) {
         |            return ((Number) value).doubleValue();
         |        }
         |        throw expected("a number", value);
         |    }
         |""",
    ),
    reader("bool", "Boolean", "a boolean", "Boolean"),
  ) ++ temporal("date", "LocalDate", "a date") ++
    temporal("dateTime", "OffsetDateTime", "a date and time with an offset") ++
    temporal("time", "OffsetTime", "a time with an offset") ++ Seq(
      Member(
        "text",
        Seq("expected"),
        """|    /** A string, or a non-empty object from language tag to string. */
           |    static Object text(Object value) {
           |        if (value == null || value instanceof String) {
           |            return value;
           |        }
           |        if (value instanceof Map && !((Map<?, ?>) value).isEmpty()) {
           |            for (Object text : ((Map<?, ?>) value).values()) {
           |                if (!(text instanceof String)) {
           |                    throw expected("a string", text);
           |                }
           |            }
           |            return value;
           |        }
           |        throw expected("a string or an object of strings by language", value);
           |    }
           |""",
      ),
      Member(
        "list",
        Seq("expected"),
        """|    static <T> List<T> list(Object value, Function<Object, T> item) {
           |        if (value == null) {
           |            return null;
           |        }
           |        if (!(value instanceof List)) {
           |            throw expected("a list", value);
           |        }
           |        List<T> result = new ArrayList<>();
           |        for (Object element : (List<?>) value) {
           |            if (element == null) {
           |                throw expected("a value in the list", null);
           |            }
           |            result.add(item.apply(element));
           |        }
           |        return result;
           |    }
           |""",
      ),
      Member(
        "anyOf",
        Seq("expected"),
        """|    /** Reads the value with the first member that accepts it. */
           |    @SafeVarargs
           |    static Object anyOf(Object value, String expected, Function<Object, ?>... members) {
           |        if (value == null) {
           |            return null;
           |        }
           |        for (Function<Object, ?> member : members) {
           |            try {
           |                return member.apply(value);
           |            } catch (IllegalArgumentException e) {
           |                // try the next one
           |            }
           |        }
           |        throw expected(expected, value);
           |    }
           |""",
      ),
      Member(
        "noNulls",
        Seq("expected"),
        """|    static <T> List<T> noNulls(List<T> list) {
           |        if (list != null && list.contains(null)) {
           |            throw expected("a value in the list", null);
           |        }
           |        return list;
           |    }
           |""",
      ),
      json("jsonDate", "LocalDate", "value.toString()"),
      json(
        "jsonDateTime",
        "OffsetDateTime",
        "DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value)",
      ),
      json("jsonTime", "OffsetTime", "DateTimeFormatter.ISO_OFFSET_TIME.format(value)"),
      Member(
        "jsonList",
        Nil,
        """|    static <T> List<String> jsonList(List<T> values, Function<T, String> item) {
           |        if (values == null) {
           |            return null;
           |        }
           |        List<String> result = new ArrayList<>();
           |        for (T value : values) {
           |            result.add(item.apply(value));
           |        }
           |        return result;
           |    }
           |""",
      ),
      key("integerKey", "Long", "an integer key", "Long.valueOf(key)"),
      key("numberKey", "Double", "a number key", "Double.valueOf(key)"),
      Member(
        "boolKey",
        Seq("expected"),
        """|    static Boolean boolKey(String key) {
           |        if (key.equals("true") || key.equals("false")) {
           |            return Boolean.valueOf(key);
           |        }
           |        throw expected("a boolean key", key);
           |    }
           |""",
      ),
      Member(
        "withKeys",
        Seq("expected"),
        """|    /**
           |     * Reads a dict of objects. Each key is copied into the key slot of its object, which
           |     * the JSON may leave out. A null entry becomes a new object with just the key, if
           |     * {@code empty} is given.
           |     */
           |    static <T, K> Map<String, T> withKeys(
           |            Map<String, T> entries,
           |            Supplier<T> empty,
           |            Function<String, K> parse,
           |            Function<T, K> get,
           |            BiConsumer<T, K> set,
           |            String slot) {
           |        if (entries == null) {
           |            return null;
           |        }
           |        for (Map.Entry<String, T> entry : entries.entrySet()) {
           |            K key = parse.apply(entry.getKey());
           |            T item = entry.getValue();
           |            if (item == null) {
           |                if (empty == null) {
           |                    throw expected("an object for \"" + entry.getKey() + "\"", null);
           |                }
           |                item = empty.get();
           |                entry.setValue(item);
           |            }
           |            K own = get.apply(item);
           |            if (own == null) {
           |                set.accept(item, key);
           |            } else if (!own.equals(key)) {
           |                throw new IllegalArgumentException(
           |                        "the entry \"" + entry.getKey() + "\" has " + slot + " " + own);
           |            }
           |        }
           |        return entries;
           |    }
           |""",
      ),
      Member(
        "keyed",
        Seq("Key"),
        """|    /**
           |     * Prepares a dict of objects for writing. The {@code @JsonIgnoreProperties} of the getter
           |     * leaves out the key slot. If {@code bare} returns a value for an object, the value is
           |     * written instead of the object.
           |     */
           |    static <T> Map<Object, Object> keyed(Map<String, T> entries, Function<T, Object> bare) {
           |        if (entries == null) {
           |            return null;
           |        }
           |        Map<Object, Object> result = new LinkedHashMap<>();
           |        for (Map.Entry<String, T> entry : entries.entrySet()) {
           |            T item = entry.getValue();
           |            Object value = bare == null || item == null ? null : bare.apply(item);
           |            result.put(new Key(entry.getKey()), value != null ? value : item);
           |        }
           |        return result;
           |    }
           |""",
      ),
      Member(
        "checkText",
        Seq("invalid"),
        """|    /** Checks the length and pattern of a string, or of each string of a language map. */
           |    static <T> T checkText(T value, int min, int max, Pattern pattern, String key) {
           |        if (value instanceof Map) {
           |            for (Object text : ((Map<?, ?>) value).values()) {
           |                checkOneText((String) text, min, max, pattern, key);
           |            }
           |        } else if (value != null) {
           |            checkOneText((String) value, min, max, pattern, key);
           |        }
           |        return value;
           |    }
           |
           |    private static void checkOneText(
           |            String text, int min, int max, Pattern pattern, String key) {
           |        int length = text.codePointCount(0, text.length());
           |        if (min >= 0 && length < min) {
           |            throw invalid(key, "is shorter than " + min + " characters");
           |        }
           |        if (max >= 0 && length > max) {
           |            throw invalid(key, "is longer than " + max + " characters");
           |        }
           |        if (pattern != null && !pattern.matcher(text).find()) {
           |            throw invalid(key, "does not match " + pattern.pattern());
           |        }
           |    }
           |""",
      ),
      Member(
        "between",
        Seq("invalid"),
        """|    static <T extends Number> T between(T value, String min, String max, String key) {
           |        if (value == null) {
           |            return null;
           |        }
           |        BigDecimal number =
           |                value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(value.toString());
           |        if (min != null && number.compareTo(new BigDecimal(min)) < 0) {
           |            throw invalid(key, "is less than " + min);
           |        }
           |        if (max != null && number.compareTo(new BigDecimal(max)) > 0) {
           |            throw invalid(key, "is greater than " + max);
           |        }
           |        return value;
           |    }
           |""",
      ),
      Member(
        "count",
        Seq("invalid"),
        """|    /** Checks the number of entries of a list or dict. */
           |    static <T> T count(T value, int min, int max, String key) {
           |        if (value == null) {
           |            return null;
           |        }
           |        int size = value instanceof Map ? ((Map<?, ?>) value).size() : ((List<?>) value).size();
           |        if (min >= 0 && size < min) {
           |            throw invalid(key, "has fewer than " + min + " values");
           |        }
           |        if (max >= 0 && size > max) {
           |            throw invalid(key, "has more than " + max + " values");
           |        }
           |        return value;
           |    }
           |""",
      ),
      Member(
        "copyList",
        Nil,
        """|    static <T> List<T> copyList(List<? extends T> list) {
           |        return list == null ? null : new ArrayList<>(list);
           |    }
           |""",
      ),
      Member(
        "copyMap",
        Nil,
        """|    static <K, V> Map<K, V> copyMap(Map<K, ? extends V> map) {
           |        return map == null ? null : new LinkedHashMap<>(map);
           |    }
           |""",
      ),
      Member(
        "invalid",
        Nil,
        """|    private static IllegalArgumentException invalid(String key, String problem) {
           |        return new IllegalArgumentException("\"" + key + "\" " + problem);
           |    }
           |""",
      ),
      Member(
        "expected",
        Nil,
        """|    private static IllegalArgumentException expected(String what, Object value) {
           |        String got;
           |        if (value == null) {
           |            got = "null";
           |        } else if (value instanceof String) {
           |            got = "\"" + value + "\"";
           |        } else if (value instanceof Map) {
           |            got = "an object";
           |        } else if (value instanceof List) {
           |            got = "a list";
           |        } else {
           |            got = String.valueOf(value);
           |        }
           |        return new IllegalArgumentException("expected " + what + ", got " + got);
           |    }
           |""",
      ),
      Member(
        "Key",
        Nil,
        """|    /**
           |     * A dict key, which Jackson writes as text. Unlike a String key, it is not removed by
           |     * {@code @JsonIgnoreProperties} on the dict when it equals an ignored property name.
           |     */
           |    static final class Key {
           |        private final String key;
           |
           |        Key(String key) {
           |            this.key = key;
           |        }
           |
           |        @Override
           |        public boolean equals(Object o) {
           |            return o instanceof Key && key.equals(((Key) o).key);
           |        }
           |
           |        @Override
           |        public int hashCode() {
           |            return key.hashCode();
           |        }
           |
           |        @Override
           |        public String toString() {
           |            return key;
           |        }
           |    }
           |""",
      ),
    )
}
