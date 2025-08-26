package com.openbankproject.commons.util

import com.openbankproject.commons.util.Functions.Implicits._
import net.liftweb
import net.liftweb.json
import net.liftweb.json.JsonAST._
import net.liftweb.json.JsonDSL._
import net.liftweb.json.JsonParser.ParseException
import net.liftweb.json.{Diff, JDouble, JInt, JNothing, JNull, JString}
import org.apache.commons.lang3.{StringUtils, Validate}

import java.util.Objects
import scala.reflect.runtime.universe
import scala.reflect.runtime.universe.typeOf

object JsonUtils {
  /* match string that end with '[number]', e.g: hobby[3] */
  private val RegexArrayIndex = """(.*?)\[(\d+)\]$""".r
  /* match string that end with '[]', e.g: hobby[] */
  private val RegexArray = """(.*?)\[\]$""".r

  /* match expression that means: root is Array, get given index item,  e.g: [1] */
  private val RegexRootIndex = """^\[(\d+)\]\s*$""".r

  /* match expression that means: root is Array, get given index item's sub path,  e.g: [2].bankId.value or [2][0].value */
  private val RegexRootIndexSubPath = """^\[(\d+)\]\s*(.*)$""".r

  /* match 'boolean: true' style string, to extract boolean value */
  private val RegexBoolean = """(?i)\s*'\s*boolean\s*:\s*(.*)'\s*""".r
  /* match 'double: 123.11' style string, to extract number value */
  private val RegexDouble = """(?i)\s*'\s*(double|number)\s*:\s*(.*)'\s*""".r
  /* match 'int: 123.11' style string, to extract number value */
  private val RegexInt = """(?i)\s*'\s*int\s*:\s*(.*)'\s*""".r
  /* match 'abc' style string, to extract abc */
  private val RegexStr = """\s*'(.*)'\s*""".r

  /* one of charactor: + - * / ~ & \ | */
  private val opStr = """\+|\-|\*|/|~|&|\|"""
  /* string express: 'hello '' !', content can contains two ' */
  private val strExp = """'.+?[^']'(?!')"""
  /* plus string express: + 'hello world !' */
 // private val plusStrExp = s"""\\+\\s*$strExp"""

  /* match string !abc.efg or -abc.efg or abc.efg */
  private val RegexSingleExp = s"""\\s*([!-])?\\s*($strExp|[^$opStr]+?)\\s*""".r
  /* match string part "& abc.efg | abc.fgh" of "!abc.efg & abc.efg | abc.fgh" */
  val RegexOtherExp = s"""\\s*(\\+\\s*$strExp|[$opStr]\\s*[^$opStr]+)""".r
  /* match string -abc.efg * hij.klm or abc.efg ~ hij.klm */
  val RegexMultipleExp = s"""\\s*($strExp|[!-]?.+?)(($RegexOtherExp)+)""".r
  /* extract operation and operand, e.g: "+ abc.def" extract ("+", "abc.def")*/
  val RegexOpExp = s"""\\s*((\\+)\\s*($strExp)|([$opStr])(.+?))""".r
  val RegexSimplePath = s"""([^$opStr!']+)""".r

  /**
   * according schema and source json to build new json
   * @param source source json
   * @param schema new built json schema, name and value can have express
   * @return built json
   */
  def buildJson(source: JValue, schema: JValue): JValue = {
    if(source==JNothing) JNothing else{
    val convertedJson = transformField(schema){
      case (jField, path) if path.contains("$default") =>
        jField

      case (JField(name, v), _) if name.endsWith("$default") =>
        val newName = StringUtils.substringBeforeLast(name,"$default")
        JField(newName, v)

      case (JField(name, JString(s)), _) if name.endsWith("[]") => {
        val jValue = calculateValue(source, s)
        val jArray = jValue match {
          case array: JArray => array
          case _ @JNothing | _ @JNull => JArray(Nil)
          case v => JArray(List(v))
        }
        val newName = StringUtils.substringBeforeLast(name, "[]")
        JField(newName, jArray)
      }

      case (JField(name, jArray: JArray), _) if name.endsWith("[]") =>
        val newName = StringUtils.substringBeforeLast(name, "[]")
        JField(newName, jArray)

      case (JField(name, jObj @JObject(jFields)), _) if name.endsWith("[]") =>
        val newName = StringUtils.substringBeforeLast(name, "[]")
        if(jFields.isEmpty) {
          JField(newName, JArray(Nil))
        } else if(allValueIsSameSizeArray(jFields)) {
          buildSingleJFieldFromArray(newName,jFields)
        } else {
          JField(newName, JArray(jObj :: Nil))
        }

      // to avoid regex do check multiple times, make top case to match regex.
      case (JField(name, jValue), _) if RegexArrayIndex.findFirstIn(name).isDefined => {
        val RegexArrayIndex(newName, indexStr) = name
        val index = indexStr.toInt
        jValue match {
          case JString(s) =>
            val value = getIndexValue(calculateValue(source, s), index)
            JField(newName, value)

          case jArray: JArray => JField(newName, jArray(index))

          case jObj @JObject(jFields) =>
            if(jFields.isEmpty) {
              JField(newName, JNothing)
            } else if(allValueIsSameSizeArray(jFields)) {
              /*convert the follow structure
               * {
               *  "foo[1]": {
               *    "name": ["Ken", "Billy"],
               *    "address": [{
               *        "name": "Berlin",
               *        "PostCode" : 116100
               *      }, {
               *        "name": "DaLian",
               *        "PostCode": 11660
               *      }]
               *  }
               * }
               * to real list:
               *{
               *  "foo": [{
               *     "name": "Ken",
               *     "address": {
               *        "name": "Berlin",
               *        "PostCode" : 116100
               *      }
               *    }]
               */
              val newFields =  jFields.collect {
                case JField(fieldName, arr :JArray) => JField(fieldName, getIndexValue(arr, index))
              }
              if(newFields.forall(_.value == JNothing)) JField(newName, JNothing) else JField(newName, JObject(newFields))
            } else {
              JField(newName, jObj)
            }
          case _ => throw new RuntimeException(s"Not support json value type, value is: $jValue")
        }

      }

      case (JField(name, jObj @JObject(jFields)), _)  =>
        if(jFields.isEmpty) {
          JField(name, JObject(Nil))
        } else if(allValueIsSameSizeArray(jFields)) {
          buildSingleJFieldFromArray(name, jFields)
        } else {
          JField(name, jObj)
        }
        
      case (JField(name, JString(s)), _) =>
        JField(name, calculateValue(source, s))

    }
    convertedJson match {
        //support the root object, eg: {"$root":[]} --> [].
      case JObject(JField("$root", value)::Nil) =>
        value
      case v => v
    }
    }
  }
  
  /*convert the follow structure
   * {
   *  "foo[]": {
   *    "name": ["Ken", "Billy"],
   *    "address": [{
   *        "name": "Berlin",
   *        "PostCode" : 116100
   *      }, {
   *        "name": "DaLian",
   *        "PostCode": 11660
   *      }]
   *  }
   * }
   * to real list:
   *{
   *  "foo": [{
   *     "name": "Ken",
   *     "address": {
   *        "name": "Berlin",
   *        "PostCode" : 116100
   *      }
   *    }, {
   *    "name": "Billy",
   *    "address": {
   *        "name": "DaLian",
   *        "PostCode": 11660
   *    }
   * }]
   */
  //TODO, this method is in processing, may contain bugs here....
  private def buildSingleJFieldFromArray(newName: String, jFields: List[JField]) = {
    val arraySize = jFields.head.value.asInstanceOf[JArray].arr.size
    val allFields = for {
      i <- (0 until arraySize).toList
      newJFields = jFields.collect {
        //This is for [][], eg: "photoUrls[][]": "field5", search for it in the JsonUtilsTest.scala, you will know the usages.
        case JField(fieldName, JArray(arr)) if fieldName.endsWith("[]") => {
          val newFileName = StringUtils.substringBeforeLast(fieldName, "[]")
          JField(newFileName, JArray(List(arr(i))))
        }
        case JField(fieldName, JArray(arr)) => JField(fieldName, arr(i))
      }
    } yield {
      JObject(newJFields)
    }
    JField(newName, JArray(allFields))
  }

  def buildJson(source: String, schema: JValue): JValue = buildJson(json.parse(source), schema)

  def buildJson(source: String, schema: String): JValue = buildJson(source, json.parse(schema))
  
  def buildJson(source: JValue, schema: String): JValue = buildJson(source, json.parse(schema))

  /**
   * Get given index value from path value, get direct index value if it JArray, escape IndexOutOfBoundsException
   * @param jValue
   * @param index
   * @return JNothing or array element of given index
   */
  private def getIndexValue(jValue: JValue, index: Int): JValue = jValue match {
    case JArray(arr) => arr.lift(index)
    case _ => JNothing
  }

  /**
   * check whither all the JField value is same size JArray
   * @param jFields
   * @return
   */
  def allValueIsSameSizeArray(jFields: List[JField]): Boolean = {
    val firstFieldSize = jFields.headOption.collect {
      case JField(_, JArray(arr)) => arr.size
    }

    firstFieldSize.exists( arraySize => jFields.tail.forall {
      case JField(_, JArray(arr)) => arr.size == arraySize
      case _ => false
    })
  }

  /**
   * according callback function to transform all nested fields
   * @param jValue
   * @param f
   * @return
   */
  def mapField(jValue: JValue)(f: (JField, String) => JField): JValue = {
    //get the path of the field, if it is root, just return the field name, if not, concatenate its parent. 
    //This will be used in recursive function, so it will store the full path of the field.
    def buildPath(parentPath: String, currentFieldName: String): String =
      if(parentPath == "") currentFieldName else s"$parentPath.$currentFieldName"
    //If it is a JObject or JArray, it will call `rec` itself until it is single object, eg:  
    // (JNothing, JString, JDouble, JBool, JInt or JNUll)
    def rec(v: JValue, path: String): JValue = v match {
      //If it is the JObject, we need to loop the JObject(obj: List[JField]) obj field: 
      //for each field, we need to call `rec` until to Stop flag                                                                      
      case JObject(l) => JObject(l.map { field =>
        f(field.copy(value = rec(field.value, buildPath(path, field.name))), path)
      }
      )
      case JArray(l) => JArray(l.map(rec(_, path)))
       //stop Flag: other JValue cases: mean the following ones: we just need to return it directly.
      // They do not have any nest classes, so do not need call `rec` again.
      //JNothing
      //JString
      //JDouble
      //JBool
      //JInt 
      //JNull
      case x => x
    }
    
    //path="", mean it is the root of the JValue.
    rec(jValue, "")
  }

  /**
   * according callback function to transform all fulfill fields
   * @param jValue
   * @param f
   * @return
   */
  def transformField(jValue: JValue)(f: PartialFunction[(JField, String), JField]): JValue = mapField(jValue) { (field, path) =>
    if (f.isDefinedAt(field, path)) f(field, path) else field
  }

  /**
   * peek all nested fields, call callback function for every field
   * @param jValue
   * @param f
   * @return Unit
   */
  def peekField(jValue: JValue)(f: (JField, String) => Unit): Unit = {
    def buildPath(parentPath: String, currentFieldName: String): String =
      if(parentPath == "") currentFieldName else s"$parentPath.$currentFieldName"

    def rec(v: JValue, path: String): Unit = v match {
      case JObject(l) => l.foreach { field =>
          rec(field.value, buildPath(path, field.name))
          f(field, path)
        }

      case JArray(l) => l.foreach(rec(_, path))
      case v => v // do nothing, just placeholder
    }
    rec(jValue, "")
  }

  /**
   * according predicate function to collect all fulfill fields
   * @param jValue
   * @param predicate
   * @return JField to field path
   */
  def collectField(jValue: JValue)(predicate: (JField, String) => Boolean): List[(JField, String)] = {
    val fields = scala.collection.mutable.ListBuffer[(JField, String)]()
    peekField(jValue) { (field, path) =>
      if(predicate(field, path))
        fields += field -> path
    }
    fields.toList
  }

  /**
   * enhance JValue, to support operations: !,+,-*,/,&,|
   * @param jValue
   */
  implicit class EnhancedJValue(jValue: JValue) {
    def unary_- : JValue = jValue match{
      case JDouble(num) => JDouble(-num)
      case JInt(num) => JInt(-num)
      case JArray(arr) => JArray(arr.map(-_))
      case n @(JNothing | JNull) => n
      case _ => throw new IllegalArgumentException(s"$jValue is not number or Array[number] type, not support - operation")
    }
    def unary_! : JValue = jValue match{
      case JBool(value) => JBool(!value)
      case JArray(arr) => JArray(arr.map(!_))
      case n @(JNothing | JNull) => n
      case _ => throw new IllegalArgumentException(s"$jValue is not boolean or Array[boolean] type, not support ! operation")
    }

    def &(v: JValue): JValue = (jValue, v) match {
      case ((JNothing | JNull), JBool(_)) => JBool(false)
      case (JBool(_), (JNothing | JNull)) => JBool(false)
      case (JBool(v1), JBool(v2)) => JBool(v1 & v2)
      case (JArray(v1), JArray(v2)) =>
        val indexes = (0 until Math.max(v1.size, v2.size)).toList
        val newArray = indexes map {index => getIndexValue(jValue, index) & getIndexValue(v, index) }
        JArray(newArray)
      case (JArray(v1), _: JBool) =>
        val newArray = v1 map {_ & v }
        JArray(newArray)
      case (_: JBool, JArray(v1)) =>
        val newArray = v1 map {jValue & _}
        JArray(newArray)
      case _ => throw new IllegalArgumentException(s"operand of operation & must be two boolean or Array[boolean] value, but current values: $jValue , $v")
    }

    def |(v: JValue): JValue = (jValue, v) match {
      case ((JNothing | JNull), b :JBool) => b
      case (b: JBool, (JNothing | JNull)) => b
      case (JBool(v1), JBool(v2)) => JBool(v1 || v2)
      case (JArray(v1), JArray(v2)) =>
        val indexes = (0 until Math.max(v1.size, v2.size)).toList
        val newArray = indexes map {index => getIndexValue(jValue, index) | getIndexValue(v, index) }
        JArray(newArray)
      case (JArray(v1), _: JBool) =>
        val newArray = v1 map {_ | v }
        JArray(newArray)
      case (_: JBool, JArray(v1)) =>
        val newArray = v1 map {jValue | _}
        JArray(newArray)
      case _ => throw new IllegalArgumentException(s"operand of operation | must be two boolean or Array[boolean] value, but current values: $jValue , $v")
    }

    def ~(v: JValue): JValue = (jValue, v) match {
      case ((JNothing | JNull), _: JObject) => v
      case (_: JObject, (JNothing | JNull)) => jValue
      case (v1: JObject, v2: JObject) => v1 ~ v2
      case (JArray(v1), JArray(v2)) =>
        val indexes = (0 until Math.max(v1.size, v2.size)).toList
        val newArray = indexes map {index => getIndexValue(jValue, index) ~ getIndexValue(v, index) }
        JArray(newArray)
      case (JArray(v1), _: JObject) =>
        val newArray = v1 map { _ ~ v }
        JArray(newArray)
      case (_: JObject, JArray(v1)) =>
        val newArray = v1 map {jValue ~ _}
        JArray(newArray)
      case _ => throw new IllegalArgumentException(s"operand of operation ~ must be two object or Array[object] value, but current values: $jValue , $v")
    }

    def +(v: JValue): JValue = (jValue, v) match {
      case ((JNothing | JNull), _) => v
      case (_, (JNothing | JNull)) => jValue
      case (JDouble(v1), JDouble(v2)) => JDouble((BigDecimal(v1) + BigDecimal(v2)).toDouble)
      case (JInt(v1), JInt(v2)) => JInt(v1 + v2)
      case (JDouble(v1), JInt(v2)) => JDouble((BigDecimal(v1) + BigDecimal(v2)).toDouble)
      case (JInt(v1), JDouble(v2)) => JDouble((BigDecimal(v1) + BigDecimal(v2)).toDouble)
      case (JString(v1), JString(v2)) => JString(v1 + v2)
      case (JArray(v1), JArray(v2)) =>
        val indexes = (0 until Math.max(v1.size, v2.size)).toList
        val newArray = indexes map {index => getIndexValue(jValue, index) + getIndexValue(v, index) }
        JArray(newArray)
      case (JArray(v1), (_:JInt | _: JDouble | _: JString)) =>
        val newArray = v1 map {_ + v }
        JArray(newArray)
      case ((_:JInt | _: JDouble | _: JString), JArray(v1)) =>
        val newArray = v1 map {jValue + _}
        JArray(newArray)
      case _ => throw new IllegalArgumentException(s"operand of operation + must be String, number, Array[String] or Array[number], but current values: $jValue , $v")
    }

    def -(v: JValue): JValue = (jValue, v) match {
      case (JDouble(v1), JDouble(v2)) => JDouble((BigDecimal(v1) - BigDecimal(v2)).toDouble)
      case (JInt(v1), JInt(v2)) => JInt(v1 - v2)
      case (JDouble(v1), JInt(v2)) => JDouble((BigDecimal(v1) - BigDecimal(v2)).toDouble)
      case (JInt(v1), JDouble(v2)) => JDouble((BigDecimal(v1) - BigDecimal(v2)).toDouble)
      case (JArray(v1), JArray(v2)) =>
        val indexes = (0 until Math.max(v1.size, v2.size)).toList
        val newArray = indexes map {index => getIndexValue(jValue, index) - getIndexValue(v, index) }
        JArray(newArray)
      case (JArray(v1), (_:JInt| _: JDouble)) =>
        val newArray = v1 map {_ - v }
        JArray(newArray)
      case ((_:JInt| _: JDouble), JArray(v1)) =>
        val newArray = v1 map {jValue - _}
        JArray(newArray)
      case _ => throw new IllegalArgumentException(s"operand of operation - must be number or Array[number], but current values: $jValue , $v")
    }

    def *(v: JValue): JValue = (jValue, v) match {
      case (JDouble(v1), JDouble(v2)) => JDouble((BigDecimal(v1) * BigDecimal(v2)).toDouble)
      case (JInt(v1), JInt(v2)) => JInt(v1 * v2)
      case (JDouble(v1), JInt(v2)) => JDouble((BigDecimal(v1) * BigDecimal(v2)).toDouble)
      case (JInt(v1), JDouble(v2)) => JDouble((BigDecimal(v1) * BigDecimal(v2)).toDouble)
      case (JArray(v1), JArray(v2)) =>
        val indexes = (0 until Math.max(v1.size, v2.size)).toList
        val newArray = indexes map {index => getIndexValue(jValue, index) * getIndexValue(v, index) }
        JArray(newArray)
      case (JArray(v1), (_:JInt| _: JDouble)) =>
        val newArray = v1 map {_ * v }
        JArray(newArray)
      case ((_:JInt| _: JDouble), JArray(v1)) =>
        val newArray = v1 map {jValue * _}
        JArray(newArray)
      case _ => throw new IllegalArgumentException(s"operand of operation * must be number or Array[number], but current values: $jValue , $v")
    }

    def /(v: JValue): JValue = (jValue, v) match {
      case (JDouble(v1), JDouble(v2)) => JDouble((BigDecimal(v1) / BigDecimal(v2)).toDouble)
      case (JInt(v1), JInt(v2)) => JInt(v1 / v2)
      case (JDouble(v1), JInt(v2)) => JDouble((BigDecimal(v1) / BigDecimal(v2)).toDouble)
      case (JInt(v1), JDouble(v2)) => JDouble((BigDecimal(v1) / BigDecimal(v2)).toDouble)
      case (JArray(v1), JArray(v2)) =>
        val indexes = (0 until Math.max(v1.size, v2.size)).toList
        val newArray = indexes map {index => getIndexValue(jValue, index) / getIndexValue(v, index) }
        JArray(newArray)
      case (JArray(v1), (_:JInt| _: JDouble)) =>
        val newArray = v1 map {_ / v }
        JArray(newArray)
      case ((_:JInt| _: JDouble), JArray(v1)) =>
        val newArray = v1 map {jValue / _}
        JArray(newArray)
      case _ => throw new IllegalArgumentException(s"operand of operation / must be number or Array[number], but current values: $jValue , $v")
    }
  }

  /**
   * according path express, calculate JValue
   * If jValue=JArray, it will return multiple values.
   * @param jValue source json
   * @param pathExp path expression, e.g: "result.price * 'int: 2' + result.count"
   * @return calculated JValue
   */
  private def calculateValue(jValue: JValue, pathExp: String): JValue = {
    pathExp match {
      case RegexSimplePath(p) => getValueByPath(jValue, p)
      case RegexMultipleExp(firstExp, otherExp, _, _) => {
        val firstValue = getValueByPath(jValue, firstExp)
        RegexOtherExp.findAllIn(otherExp).map {
          case RegexOpExp(_, op1, exp1, op2, exp2) => pre: JValue =>
            val op = Option(op1).getOrElse(op2)
            val exp = Option(exp1).getOrElse(exp2)
            val cur = getValueByPath(jValue, exp)
            op match {
            case "+" => pre + cur
            case "-" => pre - cur
            case "*" => pre * cur
            case "/" => pre / cur
            case "~" => pre ~ cur
            case "&" => pre & cur
            case "|" => pre | cur
          }
        }.foldLeft(firstValue)((v, fun)=> fun(v))
      }
      case RegexSingleExp(_, _) =>
        getValueByPath(jValue, pathExp)
    }
  }

  /**
   * get json nested value according path, path can have prefix ! or -
   * @param jValue source json, if path end with [], result type is JArray,
   *               if end with [number], result type is index element of Array
   * @param pathExpress path, can be prefix by - or !, e.g: "-result.count" "!value.isDeleted"
   * @return given nested field value
   */
  def getValueByPath(jValue: JValue, pathExpress: String): JValue = {
    pathExpress match {
      case str if str.trim == "$root" || str.trim.isEmpty => jValue // if path is "$root" or "", return whole original json

      case RegexRootIndex(index) => getIndexValue(jValue, index.toInt) // expression e.g: [1]

      case RegexRootIndexSubPath(index, subPath) => // expression e.g: [2].bankId.value or [3][0].value
        val subExpression = if(subPath.startsWith(".")) subPath.substring(1) else subPath
        getValueByPath(getIndexValue(jValue, index.toInt), subExpression)

      case RegexBoolean(b) => JBool(b.toBoolean)
      case RegexDouble(_, n) =>
        JDouble(n.toDouble)
      case RegexInt(n) => JInt(n.toInt)
      case RegexStr(s) => JString(s.replace("''", "'"))// escape '' to '
      case RegexSingleExp(op, s) =>
        val path = StringUtils.substringBeforeLast(s, "[").split('.').toList
        def getField(v: JValue, fieldPath: List[String]): JValue = (v, fieldPath) match {
          case (_, Nil)  => v

          case (JArray(arr), fieldName::tail) =>
            val values = arr.map(_ \ fieldName)
            val newArray = JArray(values)
            getField(newArray, tail)

          case (_, fieldName:: tail) =>
            getField(v \ fieldName, tail)
        }
        val value: JValue = (s, getField(jValue, path)) match {
          //convert Array result to no JArray type, e.g: "someValue": "data.foo.bar[0]"
          case (RegexArrayIndex(_, i), v @JArray(arr)) =>
            assume(arr.forall(it => it == JNothing || it == JNull || it.isInstanceOf[JArray]), s"the path has index: '$pathExpress', that means the result should be two-dimension Array, but the value is one-dimension Array: ${json.prettyRender(v)}")
            val index = i.toInt
            val jObj = arr.map(getIndexValue(_, index))
            JArray(jObj)
          // convert result to JArray type, e.g: "someValue": "data.foo.bar[]"
          case (RegexArray(_), v) =>
            assume(v.isInstanceOf[JArray], s"the path marked as Array: '$pathExpress', that means the result should be Array, but the value is ${json.prettyRender(v)}")
            val newArray = v.asInstanceOf[JArray].arr.map(it => JArray(it :: Nil))
            JArray(newArray)
          case (_, v) => v
        }

        op match {
          case "!" => !value
          case "-" => -value
          case _ => value
        }
    }

  }

  def isFieldEquals(jValue: JValue, path: String, expectValue: String): Boolean = {
    getValueByPath(jValue, path) match {
      case v @(_: JObject | _: JArray) =>
        try{
          val expectJson = json.parse(expectValue)
          val Diff(changed, deleted, added) = v.diff(expectJson)
          changed == JNothing && deleted == JNothing && added == JNothing
        } catch {
          case _: ParseException => false
        }
      case JNothing | JNull => expectValue == ""
      case v => v.values.toString == expectValue
    }
  }

  def toString(jValue: JValue) = jValue match{
    case JString(s) => s
    case JInt(num) => num.toString()
    case JDouble(num) => num.toString()
    case JBool(b) => b.toString()
    case JNothing => ""
    case JNull => "null"
    case v => json.compactRender(v)
  }

  def getType(jValue: JValue): universe.Type = {
    Objects.requireNonNull(jValue)
    jValue match {
      case JNothing => typeOf[JNothing.type]
      case JNull => typeOf[JNull.type]
      case _: JInt => typeOf[JInt]
      case _: JDouble => typeOf[JDouble]
      case _: JBool => typeOf[JBool]
      case _: JString => typeOf[JString]
      case _: JObject => typeOf[JObject]
      case _: JArray => typeOf[JArray]
    }
  }

  /**
   * delete a group of field, field can be nested.
   * @param jValue
   * @param fields
   * @return a new JValue that not contains given fields.
   */
  def deleteFields(jValue: JValue, fields: List[String]) = fields match {
    case Nil => jValue
    case x => x.foldLeft(jValue)(deleteField)
  }

  /**
   * delete one field, the field can be nested, e.g: "foo.bar.barzz"
   * @param jValue
   * @param fieldName
   * @return a new JValue that not contains given field.
   */
  def deleteField(jValue:JValue, fieldName: String): JValue = jValue match {
      case JNull | JNothing => jValue
      case _: JObject =>
        if(!fieldName.contains(".")) {
          deleteField(jValue)(_.name == fieldName)
        } else {
          val Array(field, nestedField) = StringUtils.split(fieldName, ".", 2)
          jValue.transformField {
            case JField(name, value) if name == field => JField(name, deleteField(value, nestedField))
          }
        }
      case JArray(arr) => JArray(arr.map(deleteField(_, fieldName)))
    }

  /**
   * recursive delete fields of JValue.
   * what different between this function with net.liftweb.json.JsonAST.JValue#removeField:
   * this function delete fields, removeField function set field's value to JNothing, this cause error when do deserialization
 *
   * @param jValue to delete fields json
   * @param p checker of whether delete given field.
   * @return deleted some fields json
   */
  def deleteFieldRec(jValue:JValue)(p: JField => Boolean): JValue = {
    def rec(v: JValue): JValue = v match {
      case JObject(l) => JObject(l.collect {
        case field @JField(_, value) if !p(field) => field.copy(value = rec(value))
      })
      case JArray(l) => JArray(l.map(rec))
      case x => x
    }
    rec(jValue)
  }

  /**
   * delete fields of JValue.
   * what different between this function with net.liftweb.json.JsonAST.JValue#removeField:
   * this function not delete nested fields, removeField function recursive set field's value to JNothing, this cause error when do deserialization
   * @param jValue to delete fields json
   * @param p checker of whether delete given field.
   * @return deleted some fields json
   */
  def deleteField(jValue:JValue)(p: JField => Boolean): JValue = {
    def deleteFunc(v: JValue): JValue = v match {
      case JObject(l) => JObject(l.collect {
        case field if !p(field) => field
      })
      case JArray(l) => JArray(l.map(deleteFunc))
      case x => x
    }
    deleteFunc(jValue)
  }

  /**
   * return the name and path pairs:
   * eg:  case class When(frequency: String, detail: String) 
   *  JsonUtils.collectFieldNames(decompose(When("1","2"))) --> (frequency,""),(detail,"")
   *  also see the JsonUtilsTest.scala test
   * @param jValue
   * @return
   */
  def collectFieldNames(jValue: JValue): Map[String, String] = {
    val buffer = scala.collection.mutable.Map[String, String]()
    transformField(jValue){
      case (jField, path) =>
        buffer += (jField.name -> jField.value.toString)
        jField
    }

    buffer -= "$outer" // removed the nest class references
    buffer.toMap
  }


  /**
   * is jValue type of JBool|JString|JDouble|JInt
   * @param jValue
   * @return true if jValue is type of JBool|JString|JDouble|JInt
   */
  def isBasicType(jValue: JValue) = jValue match {
    case JBool(_) | JString(_) | JDouble(_) | JInt(_) => true
    case _ => false
  }

  // scala reserved word mapping escaped string: "class" -> "`class`"
  private val reservedToEscaped =
    List("abstract", "case", "catch", "class", "def", "do", "else", "extends", "false",
      "final", "finally", "for", "forSome", "if", "implicit", "import", "lazy", "match",
      "new", "null", "object", "override", "package", "private", "protected", "return",
      "sealed", "super", "this", "throw", "trait", "try", "true", "type", "val", "var",
      "while", "with", "yield")
      .toMapByValue(it => s"`$it`")

  private val optionalFieldName = "_optional_fields_"

  // if Option[Boolean], Option[Double], Option[Long] will lost type argument when do deserialize with lift-json: Option[Object]
  // this will make generated scala code can't extract json to this object, So use java.lang.Xxx for these type in Option.
  private def toScalaTypeName(jValue: JValue, isOptional: Boolean = false) = jValue match {
    case _: JBool if isOptional   => "Option[java.lang.Boolean]"
    case _: JBool                 =>  "Boolean"
    case _: JDouble if isOptional => "Option[java.lang.Double]"
    case _: JDouble               => "Double"
    case  _: JInt if isOptional   => "Option[java.lang.Long]"
    case  _: JInt                 => "Long"
    case _: JString if isOptional => "Option[String]"
    case _: JString => "String"
    case _: JObject if isOptional => "Option[AnyRef]"
    case _: JObject               =>  "AnyRef"
    case _: JArray if isOptional  => "Option[List[Any]]"
    case _: JArray                => "List[Any]"
    case null | JNull | JNothing => throw new IllegalArgumentException(s"Json value must not be null")
  }

  /**
   * validate any nested array type field have the same structure, and not empty, and have not null item
   * @param void
   */
  private def validateJArray(jvalue: JValue): Unit = {
    if(jvalue.isInstanceOf[JArray]) {
      val rootJson: JObject = "" -> jvalue
      validateJArray(rootJson)
    } else {
      peekField(jvalue) {
        case (jField @ JField(fieldName, JArray(arr)), path) =>
          val fullFieldName = if(StringUtils.isBlank(path)) fieldName else s"$path.$fieldName"
          // not empty and none item is null
          Validate.isTrue(arr.nonEmpty, s"Json $fullFieldName should not be empty array.")
          Validate.isTrue(arr.notExists(it => it == JNull || it == JNothing), s" Array json $fullFieldName should not contains null item.")
          if(arr.size > 1) {
            arr match {
              case JBool(_) :: tail => Validate.isTrue(tail.forall(_.isInstanceOf[JBool]), s"All the items of Json $fullFieldName should be Boolean type.")
              case JString(_) :: tail => Validate.isTrue(tail.forall(_.isInstanceOf[JString]), s"All the items of Json $fullFieldName should be String type.")
              case JDouble(_) :: tail  => Validate.isTrue(tail.forall(_.isInstanceOf[JDouble]), s"All the items of Json $fullFieldName should be number type.")
              case JInt(_) :: tail => Validate.isTrue(tail.forall(_.isInstanceOf[JInt]), s"All the items of Json $fullFieldName should be integer type.")
              case (head: JObject) :: tail =>
                Validate.isTrue(tail.forall(_.isInstanceOf[JObject]), s"All the items of Json $fullFieldName should be object type.")
                def fieldNameToType(jObject: JObject) = jObject.obj.map(it => it.name -> getType(it.value)).toMap
                val headFieldNameToType = fieldNameToType(head)
                val allItemsHaveSameStructure = tail.map(it => fieldNameToType(it.asInstanceOf[JObject])).forall(headFieldNameToType ==)
                Validate.isTrue(allItemsHaveSameStructure, s"All the items of Json $fullFieldName should the same structure.")
              case JArray(_) :: tail => Validate.isTrue(tail.forall(_.isInstanceOf[JArray]), s"All the items of Json $fullFieldName should be array type.")
            }
          }
        case v => v  // do nothing, just as place holder
      }
    }
  }

  private def getNestedJObjects(jObject: JObject, typeNamePrefix: String): List[String] = {
    val nestedObjects = collectField(jObject) {
      case (JField(_, _: JObject), _) => true
      case (JField(_, JArray((_: JObject) :: _)), _) => true
      case (JField(_, JArray((_: JArray) :: _)), _) => true
      case _ => false
    }

    def getParentFiledName(path: String) = path match {
      case v if v.contains('.') =>
        StringUtils.substringAfterLast(path, ".")
      case v => v
    }

    val subTypes: List[String] = nestedObjects collect {
      case (JField(name, v: JObject), path) =>
        jObjectToCaseClass(v, typeNamePrefix, name, getParentFiledName(path))
      case (JField(name, JArray((v: JObject) :: _)), path) =>
        jObjectToCaseClass(v, typeNamePrefix, name, getParentFiledName(path))
      case (JField(name, JArray(JArray((v: JObject) :: _) :: _)), path) =>
        jObjectToCaseClass(v, typeNamePrefix, name, getParentFiledName(path))
      case (JField(name, JArray(JArray(JArray((v: JObject) :: _) :: _) :: _)), path) =>
        jObjectToCaseClass(v, typeNamePrefix, name, getParentFiledName(path))
      case (JField(name, JArray(JArray(JArray(JArray((v: JObject) :: _) :: _) :: _) :: _)), path) =>
        jObjectToCaseClass(v, typeNamePrefix, name, getParentFiledName(path))
      case (JField(name, JArray(JArray(JArray(JArray(JArray((v: JObject) :: _) :: _) :: _) :: _) :: _)), path) =>
        jObjectToCaseClass(v, typeNamePrefix, name, getParentFiledName(path))
      case (JField(_, JArray(JArray(JArray(JArray(JArray(JArray(_ :: _) :: _) :: _) :: _) :: _) :: _)), path) =>
        throw new IllegalArgumentException(s"Json field $path have too much nested level, max nested level be supported is 5.")
    } toList

    subTypes
  }

  /**
   * generate case class string according json structure
   * @param jvalue
   * @return case class string
   */
  def toCaseClasses(jvalue: JValue, typeNamePrefix: String = ""): String = {
    validateJArray(jvalue)
    jvalue match {
      case _: JBool     => s"type ${typeNamePrefix}RootJsonClass = Boolean"
      case _: JString   => s"type ${typeNamePrefix}RootJsonClass = String"
      case _: JDouble   => s"type ${typeNamePrefix}RootJsonClass = Double"
      case _: JInt      => s"type ${typeNamePrefix}RootJsonClass = Long"
      case jObject: JObject =>
        validateJArray(jObject)
        val allDefinitions = getNestedJObjects(jObject, typeNamePrefix) :+ jObjectToCaseClass(jObject, typeNamePrefix)
        allDefinitions mkString "\n"

      case jArray: JArray =>
        validateJArray(jvalue)

        def buildArrayType(jArray: JArray):String = jArray.arr.head match {
            case _: JBool => "List[Boolean]"
            case _: JString  => "List[String]"
            case _: JDouble => "List[Double]"
            case _: JInt => "List[Long]"
            case v: JObject =>
              val itemsType = jObjectToCaseClass(v, typeNamePrefix, "RootItem")
              s"""$itemsType
                 |List[${typeNamePrefix}RootItemJsonClass]
                 |""".stripMargin
            case v: JArray =>
              val nestedItmType = buildArrayType(v)
              // replace the last row to List[Xxx], e.g:
              /*
                case class Foo(i:Int)
                Foo
                -->
                case class Foo(i:Int)
                List[Foo]
               */
              nestedItmType.replaceAll("(^|.*\\s+)(.+)\\s*$", "$1List[$2]")
          }
        // add type alias for last row
        buildArrayType(jArray).replaceAll("(^|.*\\s+)(.+)\\s*$", s"$$1 type ${typeNamePrefix}RootJsonClass = $$2")

      case null | JNull | JNothing => throw new IllegalArgumentException(s"null value json can't generate case class")
    }

  }


  private def jObjectToCaseClass(jObject: JObject, typeNamePrefix: String, fieldName: String = "", parentFieldName: String = ""): String =  {
    val JObject(fields) = jObject
    val optionalFields = (jObject \ optionalFieldName) match {
      case JArray(arr) if arr.forall(_.isInstanceOf[JString]) =>
        arr.map(_.asInstanceOf[JString].s).toSet
      case JNull | JNothing => Set.empty[String]
      case _ => throw new IllegalArgumentException(s"Filed $optionalFieldName of $fieldName should be an array of String")
    }

    def toCaseClassName(name: String) = s"$typeNamePrefix${fieldName.capitalize}${name.capitalize}JsonClass"

    val currentCaseClass: String = fields collect {
      case JField(name, v) if isBasicType(v) =>
        val escapedFieldsName = reservedToEscaped.getOrElse(name, name)
        val fieldType = toScalaTypeName(v, optionalFields.contains(name))
        s"$escapedFieldsName: $fieldType"

      case JField(name, _: JObject) =>
        val escapedFieldsName = reservedToEscaped.getOrElse(name, name)
        val fieldType = if (optionalFields.contains(name)) s"Option[${toCaseClassName(name)}]" else toCaseClassName(name)
        s"$escapedFieldsName: $fieldType"

      case JField(name, arr: JArray) if name != optionalFieldName =>
        val isOption: Boolean = optionalFields.contains(name)
        def buildArrayType(jArray: JArray): String = jArray.arr.head match {
          case _: JBool    => "List[java.lang.Boolean]"
          case _: JDouble  => "List[java.lang.Double]"
          case _: JInt     => "List[java.lang.Long]"
          case _: JString  => "List[String]"
          case _: JObject  => s"List[${toCaseClassName(name)}]"

          case v: JArray =>
            val nestedItmType = buildArrayType(v)
            s"List[$nestedItmType]"
        }

        val fieldType = if (isOption) s"Option[${buildArrayType(arr)}]" else buildArrayType(arr)

        val escapedFieldsName = reservedToEscaped.getOrElse(name, name)
        s"$escapedFieldsName: $fieldType"
    } mkString(s"case class $typeNamePrefix${parentFieldName.capitalize}${if(fieldName.isEmpty) "Root" else fieldName.capitalize}JsonClass(", ", ", ")")

    currentCaseClass
  }
}
