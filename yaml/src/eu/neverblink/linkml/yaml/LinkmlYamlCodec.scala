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

  implicit val anythingCodec: LinkmlYamlCodec[LinkMlAny] = new LinkmlYamlCodec[LinkMlAny] {
    override def decode(node: Node, id: Option[Any]): LinkMlAny = LinkMlAny.apply(node.asYaml)

    override def encode(x: LinkMlAny, skipId: Boolean): Node =
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

private class LinkmlYamlCodecImpl(using Quotes) extends MacroUtils {
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

  private def findImplicitCodec(tpe: TypeRepr): Option[Expr[LinkmlYamlCodec[?]]] =
    inferredCodecs.getOrElseUpdate(
      tpe, {
        Implicits.search(linkmlYamlCodecTpe.appliedTo(tpe)) match
          case s: ImplicitSearchSuccess =>
            new Some(s.tree.asExpr.asInstanceOf[Expr[LinkmlYamlCodec[?]]])
          case _ => None
      },
    )

  private val inferredCodecs = new mutable.HashMap[TypeRepr, Option[Expr[LinkmlYamlCodec[?]]]]
  private val decodeRefs = new mutable.HashMap[TypeRepr, Ref]
  private val encodeRefs = new mutable.HashMap[TypeRepr, Ref]
  private val defs = new mutable.ListBuffer[Definition]
  private val enumMaps = new mutable.HashMap[TypeRepr, Ref]
  private val reverseEnumMaps = new mutable.HashMap[TypeRepr, Ref]
  private val nodeTpe = Symbol.requiredClass("org.virtuslab.yaml.Node").typeRef
  private val linkmlYamlCodecTpe =
    Symbol.requiredClass("eu.neverblink.linkml.yaml.LinkmlYamlCodec").typeRef
}

class DecodeError(msg: String) extends RuntimeException(msg), NoStackTrace
