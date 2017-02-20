package io.kaitai.struct.exprlang

import io.kaitai.struct.exprlang.Ast.expr
import io.kaitai.struct.format._

/**
  * A collection of case objects and classes that are used to represent internal
  * Kaitai Struct type system.
  */
object DataType {
  abstract class IntWidth(val width: Int)
  case object Width1 extends IntWidth(1)
  case object Width2 extends IntWidth(2)
  case object Width4 extends IntWidth(4)
  case object Width8 extends IntWidth(8)

  sealed trait Endianness
  case object LittleEndian extends Endianness {
    override def toString = "le"
  }
  case object BigEndian extends Endianness {
    override def toString = "be"
  }
  object Endianness {
    def fromString(s: Option[String], defaultEndian: Option[Endianness], dt: String, path: List[String]) = s match {
      case Some("le") => LittleEndian
      case Some("be") => BigEndian
      case None =>
        defaultEndian match {
          case Some(e) => e
          case None => throw new YAMLParseException(s"unable to use type '$dt' without default endianness", path ++ List("type"))
        }
    }
  }

  sealed trait BaseType

  /**
    * A common trait for all types that can be read with a simple,
    * parameterless KaitaiStream API call.
    */
  trait ReadableType extends BaseType {
    def apiCall: String
  }

  abstract class NumericType extends BaseType
  abstract class IntType extends NumericType
  case object CalcIntType extends IntType
  case class Int1Type(signed: Boolean) extends IntType with ReadableType {
    override def apiCall: String = if (signed) "s1" else "u1"
  }
  case class IntMultiType(signed: Boolean, width: IntWidth, endian: Endianness) extends IntType with ReadableType {
    override def apiCall: String = {
      val ch1 = if (signed) 's' else 'u'
      s"$ch1${width.width}${endian.toString}"
    }
  }
  case object BitsType1 extends BaseType
  case class BitsType(width: Int) extends IntType

  abstract class FloatType extends NumericType
  case object CalcFloatType extends FloatType
  case class FloatMultiType(width: IntWidth, endian: Endianness) extends FloatType with ReadableType {
    override def apiCall: String = {
      s"f${width.width}${endian.toString}"
    }
  }

  trait Processing {
    def process: Option[ProcessExpr]
  }

  abstract class BytesType extends BaseType with Processing
  case object CalcBytesType extends BytesType {
    override def process = None
  }
  case class FixedBytesType(contents: Array[Byte], override val process: Option[ProcessExpr]) extends BytesType
  case class BytesEosType(
    terminator: Option[Int],
    include: Boolean,
    padRight: Option[Int],
    override val process: Option[ProcessExpr]
  ) extends BytesType
  case class BytesLimitType(
    size: Ast.expr,
    terminator: Option[Int],
    include: Boolean,
    padRight: Option[Int],
    override val process: Option[ProcessExpr]
  ) extends BytesType
  case class BytesTerminatedType(
    terminator: Int,
    include: Boolean,
    consume: Boolean,
    eosError: Boolean,
    override val process: Option[ProcessExpr]
  ) extends BytesType

  abstract class StrType extends BaseType
  case object CalcStrType extends StrType
  case class StrFromBytesType(bytes: BytesType, encoding: String) extends StrType

  case object BooleanType extends BaseType
  case class ArrayType(elType: BaseType) extends BaseType

  abstract class UserType(val name: List[String], val forcedParent: Option[Ast.expr]) extends BaseType {
    var classSpec: Option[ClassSpec] = None
    def isOpaque = classSpec.get.meta match {
      case None => false
      case Some(meta) => meta.isOpaque
    }
  }
  case class UserTypeInstream(_name: List[String], _forcedParent: Option[Ast.expr]) extends UserType(_name, _forcedParent)
  case class UserTypeFromBytes(
    _name: List[String],
    _forcedParent: Option[Ast.expr],
    bytes: BytesType,
    override val process: Option[ProcessExpr]
  ) extends UserType(_name, _forcedParent) with Processing

  case object AnyType extends BaseType
  case object KaitaiStructType extends BaseType
  case object KaitaiStreamType extends BaseType

  case class EnumType(name: List[String], basedOn: IntType) extends BaseType {
    var enumSpec: Option[EnumSpec] = None
  }

  case class SwitchType(on: Ast.expr, cases: Map[Ast.expr, BaseType]) extends BaseType

  object SwitchType {
    /**
      * Constant that would be used for "else" case in SwitchType case class "cases" map.
      */
    val ELSE_CONST = Ast.expr.Name(Ast.identifier("_"))
  }

  private val ReIntType = """([us])(2|4|8)(le|be)?""".r
  private val ReFloatType = """f(4|8)(le|be)?""".r
  private val ReBitType= """b(\d+)""".r

  def fromYaml(
    dto: Option[String],
    path: List[String],
    metaDef: MetaDefaults,
    size: Option[Ast.expr],
    sizeEos: Boolean,
    encoding: Option[String],
    terminator: Option[Int],
    include: Boolean,
    consume: Boolean,
    eosError: Boolean,
    padRight: Option[Int],
    contents: Option[Array[Byte]],
    enumRef: Option[String],
    parent: Option[Ast.expr],
    process: Option[ProcessExpr]
  ): BaseType = {
    val r = dto match {
      case None =>
        contents match {
          case Some(c) => FixedBytesType(c, process)
          case _ =>
            getByteArrayType(
              size, sizeEos,
              terminator, include, consume, eosError,
              padRight,
              process, path
            )
        }
      case Some(dt) => dt match {
        case "u1" => Int1Type(false)
        case "s1" => Int1Type(true)
        case ReIntType(signStr, widthStr, endianStr) =>
          IntMultiType(
            signStr match {
              case "s" => true
              case "u" => false
            },
            widthStr match {
              case "2" => Width2
              case "4" => Width4
              case "8" => Width8
            },
            Endianness.fromString(Option(endianStr), metaDef.endian, dt, path)
          )
        case ReFloatType(widthStr, endianStr) =>
          FloatMultiType(
            widthStr match {
              case "4" => Width4
              case "8" => Width8
            },
            Endianness.fromString(Option(endianStr), metaDef.endian, dt, path)
          )
        case ReBitType(widthStr) =>
          (enumRef, widthStr.toInt) match {
            case (None, 1) =>
              // if we're not inside enum and it's 1-bit type
              BitsType1
            case (_, width) =>
              // either inside enum (any width) or (width != 1)
              BitsType(width)
          }
        case "str" | "strz" =>
          val enc = getEncoding(encoding, metaDef, path)

          // "strz" makes terminator = 0 by default
          val term = if (dt == "strz") {
            terminator.orElse(Some(0))
          } else {
            terminator
          }

          val bat = getByteArrayType(
            size, sizeEos,
            term, include, consume, eosError,
            padRight,
            process, path
          )
          StrFromBytesType(bat, enc)
        case _ =>
          val dtl = classNameToList(dt)
          if (size.isEmpty && !sizeEos && terminator.isEmpty) {
            if (process.isDefined)
              throw new YAMLParseException(s"user type '$dt': need 'size' / 'size-eos' / 'terminator' if 'process' is used", path)
            UserTypeInstream(dtl, parent)
          } else {
            val bat = getByteArrayType(
              size, sizeEos,
              terminator, include, consume, eosError,
              padRight,
              process, path
            )
            UserTypeFromBytes(dtl, parent, bat, process)
          }
      }
    }

    enumRef match {
      case Some(enumName) =>
        r match {
          case numType: IntType => EnumType(classNameToList(enumName), numType)
          case _ =>
            throw new YAMLParseException(s"tried to resolve non-integer $r to enum", path)
        }
      case None =>
        r
    }
  }

  private def getByteArrayType(
    size: Option[expr],
    sizeEos: Boolean,
    terminator: Option[Int],
    include: Boolean,
    consume: Boolean,
    eosError: Boolean,
    padRight: Option[Int],
    process: Option[ProcessExpr],
    path: List[String]
  ) = {
    (size, sizeEos) match {
      case (Some(bs: expr), false) =>
        BytesLimitType(bs, terminator, include, padRight, process)
      case (None, true) =>
        BytesEosType(terminator, include, padRight, process)
      case (None, false) =>
        terminator match {
          case Some(term) =>
            BytesTerminatedType(term, include, consume, eosError, process)
          case None =>
            throw new YAMLParseException("'size', 'size-eos' or 'terminator' must be specified", path)
        }
      case (Some(_), true) =>
        throw new YAMLParseException("only one of 'size' or 'size-eos' must be specified", path)
    }
  }

  def getEncoding(curEncoding: Option[String], metaDef: MetaDefaults, path: List[String]): String = {
    curEncoding.orElse(metaDef.encoding) match {
      case Some(enc) => enc
      case None =>
        throw new YAMLParseException("string type, but no encoding found", path)
    }
  }

  /**
    * Splits complex class name notation (which may name class hierarchy `something::like::that`)
    * into components, represented by a list of strings.
    * @param s class name notation as string
    * @return class name notation as list of components
    */
  def classNameToList(s: String): List[String] = s.split("::", -1).toList
}
