/* Typescript to scala macro tree builder
 * Copyright 2013 Apyx
 * @author  Arnaud PEZEL
 */

package com.apyx.scala.ts2scala.macros

import scala.reflect.macros.Context
import scala.tools.scalajs.tsimporter.sc.Name
import scala.tools.scalajs.tsimporter.{sc => ts}

class TreeBuilder(val c:Context) {
	import c.universe._
	import scala.tools.scalajs.tsimporter.{sc => ts}

	private def canBeTopLevel(sym: ts.Symbol): Boolean = sym.isInstanceOf[ts.ContainerSymbol]

	private def isParameterlessConstructor(sym: ts.Symbol): Boolean = {
		sym match {
			case sym: ts.MethodSymbol => sym.name == Name.CONSTRUCTOR && sym.params.isEmpty
			case _ => false
		}
	}

	def symbolToTree(sym: ts.Symbol):List[Tree] = {

		sym match {

			case sym: ts.PackageSymbol => toPackageDef(sym)
					
			case sym: ts.ModuleSymbol => toModuleDef(sym) :: Nil

			case sym: ts.ClassSymbol => toClassDef(sym) :: Nil

			case sym: ts.FieldSymbol =>
				
				val tpe = toTypeTree(sym.tpe)
				
				q"var ${TermName(sym.name.name)}: $tpe = _" :: Nil

			case sym: ts.MethodSymbol =>

				val mArgs = List(sym.params.map(toValDef))

				sym.name match {
					case Name.CONSTRUCTOR if !sym.params.isEmpty => q"def this(...$mArgs) = this()" :: Nil
					case Name.CONSTRUCTOR => Nil
					case x @ _ =>
						val mType = sym.tparams.map(u => q"type ${TypeName(u.toString)}")
						q"def ${TermName(x.name)}[..$mType](...$mArgs):${toTypeTree(sym.resultType)}" :: Nil
				}

			case _ => Nil
		}
	}
	
	private def toPackageDef(sym:ts.PackageSymbol):List[Tree] = { //TODO there's no package macro but we should try to simulate it with an object
		
		val splittedPackage =
			if (sym.name == Name.EMPTY)
				Nil // root
			else
				sym.name.toString.split('.').toList
				
		var pBody:List[Tree] = memberDeclsToTree(sym)
		
		for (pName <- splittedPackage.reverse)
			pBody = q"object ${TermName(pName)} { ..$pBody }" :: Nil
		
		pBody
		
	}

	private def toClassDef(sym:ts.ClassSymbol) = {
		
		val body:List[Tree] = memberDeclsToTree(sym)
		val typeName = TypeName(sym.name.name)
	
		val cType:List[TypeDef] = sym.tparams.foldLeft(List[TypeDef]())( 
				(u, v) => u :+ q"type ${c.universe.TypeName(v.toString)}"
			)
			
		(sym.isTrait, sym.parents.isEmpty, sym.members.exists(isParameterlessConstructor)) match {
			
			case (true, true, _) =>
				q"trait $typeName[..$cType] { ..$body }"
			case (true, false, _) => 
				val ext:List[Tree] = sym.parents.toList.map(u => toTypeTree(u))
				q"trait $typeName[..$cType] extends ..$ext { ..$body }"
			case (false, true, false) =>
				q"abstract class $typeName[..$cType] protected() extends Object { ..$body }"
			case (false, false, false) => 
				val ext:List[Tree] = sym.parents.toList.map(u => toTypeTree(u))
				q"abstract class $typeName[..$cType] protected() extends ..$ext { ..$body }"
			case (false, true, true) =>
				q"abstract class $typeName[..$cType] extends Object { ..$body }"
			case (false, false, true) => 
				val ext:List[Tree] = sym.parents.toList.map(u => toTypeTree(u))
				q"abstract class $typeName[..$cType] extends ..$ext { ..$body }"
		}
	}
	
	private def toModuleDef(sym:ts.ModuleSymbol) = {
		
		val body:List[Tree] = memberDeclsToTree(sym)
		val objectName = TermName(sym.name.name)

		q"object $objectName { ..$body }"
		
	}
	
	private def toValDef(sym:ts.ParamSymbol):ValDef = {

		/* use a dummy method to construct params : needed to create a repeated param */
		val q"def $mName[..$mType](...$mmArgs):$resType = $mBody" = q"def dummy(${TermName(sym.name.name)}: ${toTypeTree(sym.tpe)}):Unit = ???"
		
		mmArgs(0)(0)
	}
	
	private def toTypeTree(typeRef:ts.TypeRef):Tree = {
		
		val (typeDef:TypeDef, tparams) = typeRef match {
			case ts.TypeRef.Repeated(tpe) => return toRepeatedTypeTree(tpe)
			
			case ts.TypeRef(qn, targs) if qn.toString() == "Function" => 
				val typeRefArgs =
					if (targs.size == 0)
						ts.TypeRef.Unit :: Nil
					else
						targs
				
				
				val fArity:String = "Function"+(typeRefArgs.size-1).toString
				
				val fTypeDefs:List[Tree] = typeRefArgs.map(x => toTypeTree(x)) match {
					case AppliedTypeTree(x, y) :: r if (x.toString() == "_root_.scala.<repeated>") => 
						toTypeTree(ts.TypeRef(Name("Seq"), ts.TypeRef(Name("Any")) :: Nil)) +: r
					case x @ _ => x
				}
				
				(q"type ${TypeName(fArity)}", fTypeDefs)
				
			case x @ _ => (q"type ${TypeName(x.toString)}", typeRef.targs.map(toTypeTree))
			
		}
		
		tparams.size match {
			case 0 => Ident(typeDef.name)
			case _ => AppliedTypeTree(Ident(typeDef.name), tparams)
		}
	}
	
	private def toRepeatedTypeTree(typeRef:ts.TypeRef):Tree = {
		
		/* use a dummy method to construct params : needed to create a repeated param */
		val q"def $mName[..$mType](...$mmArgs):$resType = $mBody" = q"def dummy(v: ${toTypeTree(typeRef)}*):Unit = ???"
		
		mmArgs(0)(0).tpt //return AppliedTypeTree
	}

	
	private def memberDeclsToTree(owner: ts.ContainerSymbol):List[Tree] = {

		var tree:List[Tree] = List()
			
		for (sym <- owner.members)
			tree ++= symbolToTree(sym)

		tree
	}

}