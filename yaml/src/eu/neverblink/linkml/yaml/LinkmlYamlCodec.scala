package eu.neverblink.linkml.yaml

import eu.neverblink.linkml.runtime.*
import org.virtuslab.yaml.*
import scala.annotation.nowarn
import scala.collection.mutable
import scala.quoted.*
import scala.util.control.NoStackTrace

abstract class LinkmlYamlCodec[T] {
  def decode(node: Node, id: Option[Any] = None): T

  def encode(x: T, skipId: Boolean = false): Node
}

object LinkmlYamlCodec {
  def decodeError(msg: String, node: Node): Nothing = throw new DecodeError(node.pos match {
    case Some(pos) =>
      s"Expected $msg at ${pos.start.line}:${pos.start.column} but got:\n${pos.errorMsg}"
    case _ => s"Expected $msg but got:\n$node"
  })

  implicit val anythingCodec: LinkmlYamlCodec[Anything] = new LinkmlYamlCodec[Anything] {
    override def decode(node: Node, id: Option[Any]): Anything = Anything.apply(node.asYaml)

    override def encode(x: Anything, skipId: Boolean): Node =
      parseYaml(x.toString).getOrElse(Node.ScalarNode(null))
  }

  implicit val anyValueCodec: LinkmlYamlCodec[AnyValue] = new LinkmlYamlCodec[AnyValue] {
    override def decode(node: Node, id: Option[Any]): AnyValue = AnyValue.apply(node.asYaml)

    override def encode(x: AnyValue, skipId: Boolean): Node =
      parseYaml(x.toString).getOrElse(Node.ScalarNode(null))
  }

  implicit val uriOrCurieCodec: LinkmlYamlCodec[UriOrCurie] = new LinkmlYamlCodec[UriOrCurie] {
    override def decode(node: Node, id: Option[Any]): UriOrCurie = node match {
      case n: Node.ScalarNode if Tag.str eq n.tag => UriOrCurie(n.value)
      case n => decodeError("URI or CURIE string value", n)
    }

    override def encode(x: UriOrCurie, skipId: Boolean): Node = Node.ScalarNode(x.original)
  }

  inline def derived[T]: LinkmlYamlCodec[T] = ${ LinkmlYamlCodecImpl.make }

  def getFields(n: Node.MappingNode): Map[String, Node] =
    n.mappings.collect { case (n: Node.ScalarNode, v) if Tag.str eq n.tag => (n.value, v) }
}

private object LinkmlYamlCodecImpl {
  def make[T: Type](using Quotes): Expr[LinkmlYamlCodec[T]] = new LinkmlYamlCodecImpl().make[T]
}

private class LinkmlYamlCodecImpl(using Quotes) {
  import quotes.reflect._

  def make[T: Type]: Expr[LinkmlYamlCodec[T]] = {
    val rootTpe = TypeRepr.of[T].dealias
    inferredCodecs.put(rootTpe, None)
    val codecDef = '{
      new LinkmlYamlCodec[T] {
        def decode(node: Node, id: Option[Any]): T = ${ genDecode[T](rootTpe, 'node, 'id) }

        def encode(x: T, skipId: Boolean): Node = ${ genEncode[T](rootTpe, 'x, 'skipId) }
      }
    }
    val codec = Block(defs.toList, codecDef.asTerm).asExpr.asInstanceOf[Expr[LinkmlYamlCodec[T]]]
    // report.info(s"Generated Yaml codec for type '${rootTpe.show}':\n${codec.show}", Position.ofMacroExpansion)
    codec
  }

  private case class ClassInfo(
      tpe: TypeRepr,
      tpeTypeArgs: List[TypeRepr],
      primaryConstructor: Symbol,
      paramLists: List[List[FieldInfo]],
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

  private case class FieldInfo(
      symbol: Symbol,
      mappedName: String,
      getterOrField: Symbol,
      defaultValue: Option[Term],
      resolvedTpe: TypeRepr,
      kind: FieldKind,
  )

  private def genDecode[T: Type](
      tpe: TypeRepr,
      node: Expr[Node],
      id: Expr[Option[Any]],
  )(using Quotes): Expr[T] = {
    val implCodec = findImplicitCodec(tpe)
    if (implCodec.isDefined) {
      '{ ${ implCodec.get.asInstanceOf[Expr[LinkmlYamlCodec[T]]] }.decode($node, $id) }
    } else if (tpe =:= stringTpe) withDecoderFor(tpe, node, id) { (node, _) =>
      '{
        $node match {
          case n: Node.ScalarNode
              if (Tag.str eq n.tag) || (Tag.int eq n.tag) || (Tag.float eq n.tag) || (Tag.boolean eq n.tag) =>
            n.value
          case n => LinkmlYamlCodec.decodeError("string value", n)
        }
      }
    }
    else if (tpe =:= intTpe) withDecoderFor(tpe, node, id) { (node, _) =>
      '{
        $node match {
          case n: Node.ScalarNode if Tag.int eq n.tag =>
            try java.lang.Integer.parseInt(n.value)
            catch {
              case _: NumberFormatException =>
                LinkmlYamlCodec.decodeError("32-bit signed integer number value", n)
            }
          case n => LinkmlYamlCodec.decodeError("32-bit signed integer number value", n)
        }
      }
    }
    else if (tpe =:= booleanTpe) withDecoderFor(tpe, node, id) { (node, _) =>
      '{
        $node match {
          case n: Node.ScalarNode if Tag.boolean eq n.tag =>
            java.lang.Boolean.parseBoolean(n.value)
          case n => LinkmlYamlCodec.decodeError("boolean value", n)
        }
      }
    }
    else if (tpe <:< optionOfWildcardTpe) withDecoderFor(tpe, node, id) { (node, id) =>
      val tpe1 = typeArg1(tpe)
      tpe1.asType match {
        case '[t1] =>
          '{
            if (Tag.nullTag eq $node.tag) None
            else new Some(${ genDecode[t1](tpe1, node, id) })
          }
      }
    }
    else if (tpe <:< seqOfWildcardTpe) withDecoderFor(tpe, node, id) { (node, id) =>
      val tpe1 = typeArg1(tpe)
      tpe1.asType match {
        case '[t1] =>
          '{
            $node match {
              case n: Node.SequenceNode =>
                n.nodes.map(en => ${ genDecode[t1](tpe1, 'en, id) })
              case n: Node.ScalarNode =>
                if (Tag.nullTag eq n.tag) Seq.empty
                else Seq(${ genDecode[t1](tpe1, node, id) })
              case n => LinkmlYamlCodec.decodeError("sequence or null value", n)
            }
          }
      }
    }
    else if (tpe <:< mapOfWildcardsTpe) withDecoderFor(tpe, node, id) { (node, _) =>
      val tpe1 = typeArg1(tpe)
      val tpe2 = typeArg2(tpe)
      ((tpe1.asType, tpe2.asType): @nowarn) match {
        case ('[t1], '[t2]) =>
          '{
            $node match {
              case n: Node.MappingNode =>
                n.mappings.map { case (k, v) =>
                  val vId = ${ genDecode[t1](tpe1, 'k, '{ None }) }
                  (vId, ${ genDecode[t2](tpe2, 'v, '{ new Some(vId) }) })
                }
              case n: Node.ScalarNode if Tag.nullTag eq n.tag => Map.empty
              case n => LinkmlYamlCodec.decodeError("map or null value", n)
            }
          }
      }
    }
    else if (isAbstractClassOrTraitOrEnum(tpe)) withDecoderFor(tpe, node, id) { (node, _) =>
      val m = withEnumMapFor[T](tpe)
      '{
        $node match {
          case n: Node.ScalarNode if Tag.str eq n.tag =>
            val v = $m.get(n.value)
            if (v != null) v
            else LinkmlYamlCodec.decodeError("enumeration string value", n)
          case n => LinkmlYamlCodec.decodeError("enumeration string value", n)
        }
      }
    }
    else
      withDecoderFor(tpe, node, id) { (node, id) =>
        genDecodeNonAbstractClass(tpe, node, id)
      }
  }.asInstanceOf[Expr[T]]

  private def genEncode[T: Type](tpe: TypeRepr, x: Expr[T], skipId: Expr[Boolean])(using
      Quotes,
  ): Expr[Node] = {
    val implCodec = findImplicitCodec(tpe)
    if (implCodec.isDefined) {
      '{ ${ implCodec.get.asInstanceOf[Expr[LinkmlYamlCodec[T]]] }.encode($x, $skipId) }
    } else if (tpe =:= stringTpe) withEncoderFor(tpe, x, skipId) { (x, _) =>
      '{ Node.ScalarNode(${ x.asInstanceOf[Expr[String]] }) }
    }
    else if (tpe =:= intTpe || tpe =:= booleanTpe) withEncoderFor(tpe, x, skipId) { (x, _) =>
      '{ Node.ScalarNode($x.toString) }
    }
    else if (tpe <:< optionOfWildcardTpe) withEncoderFor(tpe, x, skipId) { (x, skipId) =>
      val tpe1 = typeArg1(tpe)
      tpe1.asType match {
        case '[t1] =>
          val opt = x.asInstanceOf[Expr[Option[t1]]]
          '{
            if ($opt ne None) ${ genEncode[t1](tpe1, '{ $opt.get }, skipId) }
            else Node.ScalarNode(null)
          }
      }
    }
    else if (tpe <:< seqOfWildcardTpe) withEncoderFor(tpe, x, skipId) { (x, skipId) =>
      val tpe1 = typeArg1(tpe)
      tpe1.asType match {
        case '[t1] =>
          val seq = x.asInstanceOf[Expr[Seq[t1]]]
          '{ Node.SequenceNode($seq.map(e => ${ genEncode[t1](tpe1, 'e, skipId) })*) }
      }
    }
    else if (tpe <:< mapOfWildcardsTpe) withEncoderFor(tpe, x, skipId) { (x, skipId) =>
      val tpe1 = typeArg1(tpe)
      val tpe2 = typeArg2(tpe)
      ((tpe1.asType, tpe2.asType): @nowarn) match {
        case ('[t1], '[t2]) =>
          val map = x.asInstanceOf[Expr[Map[t1, t2]]]
          '{
            Node.MappingNode($map.map { case (k, v) =>
              (${ genEncode[t1](tpe1, 'k, skipId) }, ${ genEncode[t2](tpe2, 'v, skipId) })
            })
          }
      }
    }
    else if (isAbstractClassOrTraitOrEnum(tpe)) withEncoderFor(tpe, x, skipId) { (x, _) =>
      val m = withReverseEnumMapFor[T](tpe)
      '{ Node.ScalarNode($m.get($x)) }
    }
    else
      withEncoderFor(tpe, x, skipId) { (x, skipId) =>
        genEncodeNonAbstractClass(tpe, x, skipId)
      }
  }.asInstanceOf[Expr[Node]]

  private def genDecodeNonAbstractClass[T: Type](
      tpe: TypeRepr,
      node: Expr[Node],
      id: Expr[Option[Any]],
  )(using Quotes): Expr[T] = {
    lazy val tpeName = Expr(tpe.show)
    val classInfo = getClassInfo(tpe)
    val fields = classInfo.fields

    def genDecodeFields(kvs: Expr[Map[String, Node]])(using Quotes): Expr[T] = {
      val readBlock = new mutable.ListBuffer[Statement]
      val valDefs = new mutable.ArrayBuffer[ValDef](fields.size)
      fields.foreach { fieldInfo =>
        val fTpe = fieldInfo.resolvedTpe
        fTpe.asType match
          case '[ft] =>
            val mappedName = Expr(fieldInfo.mappedName)
            val sym = symbol(s"_${fieldInfo.mappedName}", fTpe, Flags.Mutable)
            val defaultVal = fieldInfo.defaultValue.getOrElse('{ null.asInstanceOf[ft] }.asTerm)
            val valVal =
              if (fieldInfo.kind == FieldKind.Id) {
                val ftName = Expr(fTpe.show)
                '{
                  $id match {
                    case Some(s: ft) => s
                    case Some(_) =>
                      LinkmlYamlCodec.decodeError(
                        s"value of type '${$ftName}' for id field '${$mappedName}'",
                        $node,
                      )
                    case _ => ${ defaultVal.asExpr }
                  }
                }.asTerm
              } else defaultVal
            val valDef = ValDef(sym, new Some(valVal.changeOwner(sym)))
            valDefs.addOne(valDef)
            readBlock.addOne(valDef)
            readBlock.addOne('{
              $kvs.get($mappedName) match {
                case Some(v) =>
                  ${
                    Assign(Ref(valDef.symbol), genDecode[ft](fTpe, 'v, '{ None }).asTerm).asExpr
                  }
                case _ =>
                  ${
                    if (fieldInfo.defaultValue.isEmpty) {
                      if (fieldInfo.kind == FieldKind.Id) {
                        '{
                          if ($id.isEmpty) {
                            LinkmlYamlCodec.decodeError(
                              s"required field '${$mappedName}' of '${$tpeName}'",
                              $node,
                            )
                          }
                        }
                      } else {
                        '{
                          LinkmlYamlCodec.decodeError(
                            s"required field '${$mappedName}' of '${$tpeName}'",
                            $node,
                          )
                        }
                      }
                    } else {
                      '{}
                    }
                  }
              }
            }.asTerm.changeOwner(Symbol.spliceOwner))
      }
      var index = -1
      val construct =
        classInfo.genNew(classInfo.paramLists.map(_.foldLeft(new mutable.ListBuffer[Term]) {
          (params, _) =>
            index += 1
            params.addOne(Ref(valDefs(index).symbol))
        }.toList))
      Block(readBlock.result(), construct).asExpr.asInstanceOf[Expr[T]]
    }

    if (fields.size == 1) {
      val fieldInfo = fields.head
      val fTpe = fieldInfo.resolvedTpe
      fTpe.asType match {
        case '[ft] =>
          classInfo.genNew(List(List(genDecode[ft](fTpe, node, '{ None }).asTerm))).asExpr
      }
    } else {
      val valFieldInfoIndex = fields.indexWhere(_.kind == FieldKind.Value)
      val idFieldInfoIndex = fields.indexWhere(_.kind == FieldKind.Id)
      if (
        (valFieldInfoIndex | idFieldInfoIndex) >= 0 &&
        fields.forall(x =>
          x.kind == FieldKind.Value || x.kind == FieldKind.Id || x.defaultValue.isDefined,
        )
      ) {
        val vTpe = fields(valFieldInfoIndex).resolvedTpe
        vTpe.asType match {
          case '[vt] =>
            '{
              $id match {
                case Some(s) =>
                  ${
                    var index = -1
                    classInfo.genNew(
                      classInfo.paramLists.map(_.foldLeft(new mutable.ListBuffer[Term]) {
                        (params, _) =>
                          index += 1
                          params.addOne(
                            if (index == idFieldInfoIndex) {
                              val field = fields(idFieldInfoIndex)
                              val kTpe = field.resolvedTpe
                              (kTpe.asType match {
                                case '[UriOrCurie] => '{ UriOrCurie(s.toString) }
                                case '[kt] =>
                                  '{
                                    s match {
                                      case value: kt => value
                                      case _ =>
                                        LinkmlYamlCodec.decodeError(
                                          s"value of type '${$tpeName}' for id field",
                                          $node,
                                        )
                                    }
                                  }
                              }).asTerm
                            } else if (index == valFieldInfoIndex) {
                              genDecode[vt](vTpe, node, '{ None }).asTerm
                            } else fields(index).defaultValue.get,
                          )
                      }.toList),
                    ).asExpr
                  }
                case _ =>
                  val kvs = $node match {
                    case n: Node.MappingNode => LinkmlYamlCodec.getFields(n)
                    case n: Node.ScalarNode if Tag.nullTag eq n.tag => Map.empty[String, Node]
                    case n => LinkmlYamlCodec.decodeError("map or null value", n)
                  }
                  ${ genDecodeFields('kvs) }
              }
            }
        }
      } else {
        '{
          val kvs = $node match {
            case n: Node.MappingNode => LinkmlYamlCodec.getFields(n)
            case n: Node.ScalarNode if Tag.nullTag eq n.tag => Map.empty[String, Node]
            case n => LinkmlYamlCodec.decodeError("map or null value", n)
          }
          ${ genDecodeFields('kvs) }
        }
      }
    }
  }.asInstanceOf[Expr[T]]

  private def genEncodeNonAbstractClass[T: Type](
      tpe: TypeRepr,
      x: Expr[T],
      skipId: Expr[Boolean],
  )(using Quotes): Expr[Node] = {
    val classInfo = getClassInfo(tpe)
    val fields = classInfo.fields

    def genEncodeFields(kvs: Expr[mutable.Growable[(Node, Node)]])(using Quotes): Expr[Unit] = {
      Block(
        fields.map { fieldInfo =>
          val fTpe = fieldInfo.resolvedTpe
          val getter = Select(x.asTerm, fieldInfo.getterOrField).asExpr
          val fSkipId = Expr(
            fieldInfo.kind == FieldKind.SimpleDict || fieldInfo.kind == FieldKind.CompactDict,
          )
          val name = Expr(fieldInfo.mappedName)
          (fTpe.asType match {
            case '[ft] =>
              val encodeVal = genEncode[ft](fTpe, getter.asInstanceOf[Expr[ft]], fSkipId)
              fieldInfo.defaultValue match {
                case Some(d) =>
                  '{
                    if (${ getter } != ${ d.asExpr }) {
                      $kvs.addOne((Node.ScalarNode($name), $encodeVal))
                    }
                  }
                case None =>
                  if (fieldInfo.kind == FieldKind.Id) '{
                    if (! $skipId) $kvs.addOne((Node.ScalarNode($name), $encodeVal))
                  }
                  else '{ $kvs.addOne((Node.ScalarNode($name), $encodeVal)) }
              }
          }).asTerm
        },
        '{}.asTerm,
      ).asExpr.asInstanceOf[Expr[Unit]]
    }

    if (fields.size == 1) {
      val fieldInfo = fields.head
      val fTpe = fieldInfo.resolvedTpe
      val getter = Select(x.asTerm, fieldInfo.getterOrField).asExpr
      val fSkipId =
        Expr(fieldInfo.kind == FieldKind.SimpleDict || fieldInfo.kind == FieldKind.CompactDict)
      fTpe.asType match {
        case '[ft] => genEncode[ft](fTpe, getter.asInstanceOf[Expr[ft]], fSkipId)
      }
    } else if (
      fields.exists(_.kind == FieldKind.Id) && fields.exists(_.kind == FieldKind.Value) &&
      fields.forall(x =>
        x.kind == FieldKind.Id || x.kind == FieldKind.Value || x.defaultValue.isDefined,
      )
    ) {
      val fieldInfo = fields.find(_.kind == FieldKind.Value).get
      val fTpe = fieldInfo.resolvedTpe
      val getter = Select(x.asTerm, fieldInfo.getterOrField).asExpr
      val fSkipId =
        Expr(fieldInfo.kind == FieldKind.SimpleDict || fieldInfo.kind == FieldKind.CompactDict)
      fTpe.asType match {
        case '[ft] =>
          val encodeVal = genEncode[ft](fTpe, getter.asInstanceOf[Expr[ft]], fSkipId)
          '{
            if ($skipId) $encodeVal
            else {
              val kvs = Map.newBuilder[Node, Node]
              ${ genEncodeFields('kvs) }
              Node.MappingNode(kvs.result())
            }
          }
      }
    } else {
      '{
        val kvs = Map.newBuilder[Node, Node]
        ${ genEncodeFields('kvs) }
        Node.MappingNode(kvs.result())
      }
    }
  }

  private def getClassInfo(tpe: TypeRepr): ClassInfo = classInfos.getOrElseUpdate(
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

  private def withDecoderFor[T: Type](tpe: TypeRepr, node: Expr[Node], id: Expr[Option[Any]])(
      f: (Expr[Node], Expr[Option[Any]]) => Expr[T],
  ): Expr[T] =
    Apply(
      decodeRefs.getOrElse(
        tpe, {
          val sym = Symbol.newMethod(
            Symbol.spliceOwner,
            s"d${decodeRefs.size}",
            MethodType("node" :: "id" :: Nil)(_ => nodeTpe :: optionOfAnyTpe :: Nil, _ => tpe),
          )
          val ref = Ref(sym)
          decodeRefs.update(tpe, ref)
          defs.addOne(
            DefDef(
              sym,
              params => {
                val List(node, id) = params.head
                new Some(
                  f(
                    node.asExpr.asInstanceOf[Expr[Node]],
                    id.asExpr.asInstanceOf[Expr[Option[Any]]],
                  ).asTerm.changeOwner(sym),
                )
              },
            ),
          )
          ref
        },
      ),
      node.asTerm :: id.asTerm :: Nil,
    ).asExpr.asInstanceOf[Expr[T]]

  private def withEncoderFor[T: Type](tpe: TypeRepr, x: Expr[T], skipId: Expr[Boolean])(
      f: (Expr[T], Expr[Boolean]) => Expr[Node],
  ): Expr[Node] =
    Apply(
      encodeRefs.getOrElse(
        tpe, {
          val sym = Symbol.newMethod(
            Symbol.spliceOwner,
            s"e${encodeRefs.size}",
            MethodType("x" :: "skipId" :: Nil)(_ => tpe :: booleanTpe :: Nil, _ => nodeTpe),
          )
          val ref = Ref(sym)
          encodeRefs.update(tpe, ref)
          defs.addOne(
            DefDef(
              sym,
              params => {
                val List(x, skipId) = params.head
                new Some(
                  f(
                    x.asExpr.asInstanceOf[Expr[T]],
                    skipId.asExpr.asInstanceOf[Expr[Boolean]],
                  ).asTerm.changeOwner(sym),
                )
              },
            ),
          )
          ref
        },
      ),
      List(x.asTerm, skipId.asTerm),
    ).asExpr.asInstanceOf[Expr[Node]]

  private def findImplicitCodec(tpe: TypeRepr): Option[Expr[LinkmlYamlCodec[?]]] =
    inferredCodecs.getOrElseUpdate(
      tpe, {
        Implicits.search(linkmlYamlCodecTpe.appliedTo(tpe)) match
          case s: ImplicitSearchSuccess =>
            new Some(s.tree.asExpr.asInstanceOf[Expr[LinkmlYamlCodec[?]]])
          case _ => None
      },
    )

  private def withEnumMapFor[T: Type](tpe: TypeRepr)(using
      Quotes,
  ): Expr[java.util.HashMap[String, T]] =
    enumMaps.getOrElse(
      tpe, {
        val leafTpes = adtLeafObjects(tpe)
        val sym = symbol(s"em${enumMaps.size}", TypeRepr.of[java.util.HashMap[String, T]])
        val ref = Ref(sym)
        enumMaps.update(tpe, ref)
        defs.addOne(
          ValDef(
            sym,
            new Some('{
              {
                val m = new java.util.HashMap[String, T]
                ${
                  Block(
                    {
                      leafTpes.map { lTpe =>
                        val name = Expr(enumValueName(lTpe))
                        val module = enumOrModuleValueRef(lTpe).asExpr.asInstanceOf[Expr[T]]
                        '{ m.put($name, $module) }.asTerm
                      }.toList
                    },
                    '{}.asTerm,
                  ).asExpr
                }
                m
              }
            }.asTerm.changeOwner(sym)),
          ),
        )
        ref
      },
    ).asExpr.asInstanceOf[Expr[java.util.HashMap[String, T]]]

  private def withReverseEnumMapFor[T: Type](tpe: TypeRepr)(using
      Quotes,
  ): Expr[java.util.HashMap[T, String]] =
    reverseEnumMaps.getOrElse(
      tpe, {
        val leafTpes = adtLeafObjects(tpe)
        val sym = symbol(s"rem${reverseEnumMaps.size}", TypeRepr.of[java.util.HashMap[T, String]])
        val ref = Ref(sym)
        reverseEnumMaps.update(tpe, ref)
        defs.addOne(
          ValDef(
            sym,
            new Some('{
              {
                val m = new java.util.HashMap[T, String]
                ${
                  Block(
                    {
                      leafTpes.map { lTpe =>
                        val name = Expr(enumValueName(lTpe))
                        val module = enumOrModuleValueRef(lTpe).asExpr.asInstanceOf[Expr[T]]
                        '{ m.put($module, $name) }.asTerm
                      }.toList
                    },
                    '{}.asTerm,
                  ).asExpr
                }
                m
              }
            }.asTerm.changeOwner(sym)),
          ),
        )
        ref
      },
    ).asExpr.asInstanceOf[Expr[java.util.HashMap[T, String]]]

  private def adtLeafObjects(adtBaseTpe: TypeRepr): Seq[TypeRepr] = {
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

  private def adtChildren(tpe: TypeRepr): Seq[TypeRepr] = {
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

  private def enumValueName(tpe: TypeRepr): String =
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

  private def namedValueOpt(namedAnnotation: Option[Term], tpe: TypeRepr): Option[String] =
    namedAnnotation.map { case Apply(_, List(param)) =>
      param match
        case Literal(StringConstant(s)) => s
        case _ =>
          fail(
            s"Cannot evaluate a parameter of the '@named' annotation in type '${tpe.show}': $param.",
          )
    }

  private def duplicated[A](xs: collection.Seq[A]): collection.Seq[A] = xs.filter {
    val seen = new mutable.HashSet[A]
    x => !seen.add(x)
  }

  private def typeArgs(tpe: TypeRepr): List[TypeRepr] = tpe match
    case AppliedType(_, typeArgs) => typeArgs.map(_.dealias)
    case _ => Nil

  private def typeArg1(tpe: TypeRepr): TypeRepr = tpe match
    case AppliedType(_, typeArg1 :: _) => typeArg1.dealias
    case _ => fail(s"Cannot get 1st type argument in '${tpe.show}'")

  private def typeArg2(tpe: TypeRepr): TypeRepr = tpe match
    case AppliedType(_, _ :: typeArg2 :: _) => typeArg2.dealias
    case _ => fail(s"Cannot get 2nd type argument in '${tpe.show}'")

  private def isAbstractClassOrTraitOrEnum(tpe: TypeRepr): Boolean = tpe.classSymbol.fold(false) {
    symbol =>
      val flags = symbol.flags
      flags.is(Flags.Abstract) || flags.is(Flags.Trait) || flags.is(Flags.Enum)
  }

  private def isEnumValue(tpe: TypeRepr): Boolean = tpe.termSymbol.flags.is(Flags.Enum)

  private def isEnumOrModuleValue(tpe: TypeRepr): Boolean =
    isEnumValue(tpe) || tpe.typeSymbol.flags.is(Flags.Module)

  private def enumOrModuleValueRef(tpe: TypeRepr): Term = Ref {
    if (isEnumValue(tpe)) tpe.termSymbol
    else tpe.typeSymbol.companionModule
  }

  private def symbol(name: String, tpe: TypeRepr, flags: Flags = Flags.EmptyFlags): Symbol =
    Symbol.newVal(Symbol.spliceOwner, name, tpe, flags, Symbol.noSymbol)

  private def fail(msg: String): Nothing = report.errorAndAbort(msg, Position.ofMacroExpansion)

  private val classInfos = new mutable.HashMap[TypeRepr, ClassInfo]
  private val inferredCodecs = new mutable.HashMap[TypeRepr, Option[Expr[LinkmlYamlCodec[?]]]]
  private val decodeRefs = new mutable.HashMap[TypeRepr, Ref]
  private val encodeRefs = new mutable.HashMap[TypeRepr, Ref]
  private val defs = new mutable.ListBuffer[Definition]
  private val enumMaps = new mutable.HashMap[TypeRepr, Ref]
  private val reverseEnumMaps = new mutable.HashMap[TypeRepr, Ref]
  private val intTpe = defn.IntClass.typeRef
  private val booleanTpe = defn.BooleanClass.typeRef
  private val stringTpe = defn.StringClass.typeRef
  private val anyTpe = defn.AnyClass.typeRef
  private val wildcardBounds = TypeBounds(defn.NothingClass.typeRef, anyTpe)
  private val optionOfAnyTpe = defn.OptionClass.typeRef.appliedTo(anyTpe)
  private val optionOfWildcardTpe = defn.OptionClass.typeRef.appliedTo(wildcardBounds)
  private val nodeTpe = Symbol.requiredClass("org.virtuslab.yaml.Node").typeRef
  private val linkmlYamlCodecTpe =
    Symbol.requiredClass("eu.neverblink.linkml.yaml.LinkmlYamlCodec").typeRef
  private val seqOfWildcardTpe =
    Symbol.requiredClass("scala.collection.immutable.Seq").typeRef.appliedTo(wildcardBounds)
  private val mapOfWildcardsTpe = Symbol.requiredClass(
    "scala.collection.immutable.Map",
  ).typeRef.appliedTo(wildcardBounds :: wildcardBounds :: Nil)
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

private enum FieldKind {
  case Regular, Id, Value, SimpleDict, CompactDict, ExpandedDict
}

class DecodeError(msg: String) extends RuntimeException(msg), NoStackTrace
