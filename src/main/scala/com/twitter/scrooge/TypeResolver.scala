package com.twitter.scrooge

import AST._
import scala.collection.mutable.ArrayBuffer

class TypeNotFoundException(name: String) extends Exception(name)
class UndefinedSymbolException(name: String) extends Exception(name)
class TypeMismatchException(name: String) extends Exception(name)

case class ResolvedDocument(document: Document, resolver: TypeResolver)
case class ResolvedDefinition(definition: Definition, resolver: TypeResolver)

case class TypeResolver(
  typeMap: Map[String, FieldType] = Map(),
  constMap: Map[String, Const] = Map(),
  includeMap: Map[String, ResolvedDocument] = Map())
{
  /**
   * Resolves all types in the given document.
   */
  def resolve(doc: Document): ResolvedDocument = {
    var resolver = this
    val includes = doc.headers.collect { case i: Include => i }
    val defBuf = new ArrayBuffer[Definition](doc.defs.size)

    for (i <- includes) {
      resolver = resolver.include(i)
    }

    for (d <- doc.defs) {
      val ResolvedDefinition(d2, r2) = resolver.resolve(d)
      resolver = r2
      defBuf += d2
    }

    ResolvedDocument(doc.copy(defs = defBuf.toSeq), resolver)
  }

  /**
   * Returns a new TypeResolver with the given include mapping added.
   */
  def include(inc: Include): TypeResolver = {
    val resolvedDocument = TypeResolver().resolve(inc.document)
    copy(includeMap = includeMap + (inc.prefix -> resolvedDocument))
  }

  /**
   * Resolves types in the given definition according to the current
   * typeMap, and then returns an updated TypeResolver with the new
   * definition bound, plus the resolved definition.
   */
  def resolve(definition: Definition): ResolvedDefinition = {
    apply(definition) match {
      case d @ Typedef(name, t) => ResolvedDefinition(d, define(name, t))
      case e @ Enum(name, _) => ResolvedDefinition(e, define(name, EnumType(e)))
      case s @ Senum(name, _) => ResolvedDefinition(s, define(name, TString))
      case s @ Struct(name, _) => ResolvedDefinition(s, define(name, StructType(s)))
      case e @ Exception_(name, _) => ResolvedDefinition(e, define(e.name, StructType(e)))
      case c @ Const(_, _, v) => ResolvedDefinition(c, define(c))
      case d => ResolvedDefinition(d, this)
    }
  }

  /**
   * Returns a new TypeResolver with the given type mapping added.
   */
  def define(name: String, `type`: FieldType): TypeResolver = {
    copy(typeMap = typeMap + (name -> `type`))
  }

  /**
   * Returns a new TypeResolver with the given constant added.
   */
  def define(const: Const): TypeResolver = {
    copy(constMap = constMap + (const.name -> const))
  }

  def apply(definition: Definition): Definition = {
    definition match {
      case d @ Typedef(name, t) => d.copy(`type` = apply(t))
      case s @ Struct(_, fs) => s.copy(fields = fs.map(apply))
      case e @ Exception_(_, fs) => e.copy(fields = fs.map(apply))
      case c @ Const(_, t, _) =>
        val `type` = apply(t)
        c.copy(`type` = `type`, value = apply(c.value, `type`))
      case s @ Service(_, _, fs) => s.copy(functions = fs.map(apply))
      case d => d
    }
  }

  def apply(f: Function): Function = f match {
    case Function(_, _, t, as, _, ts) =>
      f.copy(`type` = apply(t), args = as.map(apply), throws = ts.map(apply))
  }

  def apply(f: Field): Field = {
    val fieldType = apply(f.`type`)
    f.copy(
      `type` = fieldType,
      default = f.default.map { const => apply(const, fieldType) })
  }

  def apply(t: FunctionType): FunctionType = t match {
    case Void => Void
    case t: FieldType => apply(t)
  }

  def apply(t: FieldType): FieldType = t match {
    case ReferenceType(name) => apply(name)
    case m @ MapType(k, v, _) => m.copy(keyType = apply(k), valueType = apply(v))
    case s @ SetType(e, _) => s.copy(eltType = apply(e))
    case l @ ListType(e, _) => l.copy(eltType = apply(e))
    case _ => t
  }

  def apply(c: Constant, fieldType: FieldType): Constant = c match {
    case l @ ListConstant(elems) =>
      fieldType match {
        case ListType(eltType, _) => l.copy(elems = elems map { e => apply(e, eltType) } )
        case _ => throw new TypeMismatchException("Expecting " + fieldType + ", found " + l)
      }
    case m @ MapConstant(elems) =>
      fieldType match {
        case MapType(keyType, valType, _) =>
          m.copy(elems = elems.map { case (k, v) => (apply(k, keyType), apply(v, valType)) })
        case _ => throw new TypeMismatchException("Expecting " + fieldType + ", found " + m)
      }
    case i @ Identifier(name) =>
      fieldType match {
        case EnumType(enum) =>
          val valueName = name match {
            case QualifiedName(scope, QualifiedName(enumName, valueName)) =>
              if (apply(scope, enumName) != fieldType) {
                throw new UndefinedSymbolException(scope + "." + enumName)
              } else {
                valueName
              }
            case QualifiedName(enumName, valueName) =>
              if (enumName != enum.name) {
                throw new UndefinedSymbolException(enumName)
              } else {
                valueName
              }
            case _ => name
          }
          enum.values.find(_.name == valueName) match {
            case None => throw new UndefinedSymbolException(name)
            case Some(value) => EnumValueConstant(enum, value)
          }
        case _ => throw new UndefinedSymbolException(name)
      }
    case _ => c
  }

  def apply(name: String): FieldType = {
    name match {
      case QualifiedName(prefix, suffix) =>
        apply(prefix, suffix)
      case _ =>
        typeMap.get(name).getOrElse(throw new TypeNotFoundException(name))
    }
  }

  def apply(scope: String, name: String): FieldType = {
    val include = includeMap.get(scope).getOrElse(throw new UndefinedSymbolException(name))
    try {
      include.resolver(name)
    } catch {
      case ex: TypeNotFoundException =>
        // don't lose context
        throw new TypeNotFoundException(scope + "." + name)
    }
  }

  object QualifiedName {
    def unapply(str: String): Option[(String, String)] = {
      str.indexOf('.') match {
        case -1 => None
        case dot => Some((str.substring(0, dot), str.substring(dot + 1)))
      }
    }
  }
}
