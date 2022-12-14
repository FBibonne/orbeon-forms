/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xml

import org.apache.commons.lang3.StringUtils
import org.orbeon.saxon.`type`.Type
import org.orbeon.saxon.expr.Expression
import org.orbeon.saxon.om._
import org.orbeon.saxon.pattern.{NameTest, NodeKindTest}
import org.orbeon.saxon.value.{AtomicValue, StringValue, Value}
import org.orbeon.scaxon.XML

import scala.collection.JavaConverters._

object SaxonUtils {

  // Version of StringValue which supports equals().
  // Saxon throws on equals() to make a point that a collation should be used for StringValue comparison.
  // Here, we don't really care about equality, but we want to implement equals() as e.g. Jetty calls equals() on
  // objects stored into the session. See:
  // http://forge.ow2.org/tracker/index.php?func=detail&aid=315528&group_id=168&atid=350207
  class StringValueWithEquals(value: CharSequence) extends StringValue(value) {
    override def equals(other: Any): Boolean = {
      // Compare the CharSequence
      other.isInstanceOf[StringValue] && getStringValueCS == other.asInstanceOf[StringValue].getStringValueCS
    }
  }

  def iterateExpressionTree(e: Expression): Iterator[Expression] =
    Iterator(e) ++
      (e.iterateSubExpressions.asScala.asInstanceOf[Iterator[Expression]] flatMap iterateExpressionTree)

  // Make an NCName out of a non-blank string
  // Any characters that do not belong in an NCName are converted to `_`.
  // If `keepFirstIfPossible == true`, prepend `_` if first character is allowed within NCName and keep first character.
  //@XPathFunction
  def makeNCName(name: String, keepFirstIfPossible: Boolean): String = {

    require(StringUtils.isNotBlank(name), "name must not be blank or empty")

    val name10Checker = Name10Checker.getInstance
    if (name10Checker.isValidNCName(name)) {
      name
    } else {
      val sb = new StringBuilder
      val start = name.charAt(0)

      if (name10Checker.isNCNameStartChar(start))
        sb.append(start)
      else if (keepFirstIfPossible && name10Checker.isNCNameChar(start)) {
        sb.append('_')
        sb.append(start)
      } else
        sb.append('_')

      for (i ??? 1 until name.length) {
        val ch = name.charAt(i)
        sb.append(if (name10Checker.isNCNameChar(ch)) ch else '_')
      }
      sb.toString
    }
  }

  def compareValueRepresentations(valueRepr1: ValueRepresentation, valueRepr2: ValueRepresentation): Boolean =
    (valueRepr1, valueRepr2) match {
      // Ideally we wouldn't support null here (XFormsVariableControl passes null)
      case (null,             null)            ??? true
      case (null,             _)               ??? false
      case (_,                null)            ??? false
      case (v1: Value, v2: Value)              ??? compareValues(v1, v2)
      case (v1: NodeInfo, v2: NodeInfo)        ??? v1 == v2
      // 2014-08-18: Checked Saxon class hierarchy
      // Saxon type hierarchy is closed (ValueRepresentation = NodeInfo | Value)
      case _                                   ??? throw new IllegalStateException
    }

  def compareValues(value1: Value, value2: Value): Boolean = {
    val iter1 = XML.asScalaIterator(value1.iterate)
    val iter2 = XML.asScalaIterator(value2.iterate)

    iter1.zipAll(iter2, null, null) forall (compareItems _).tupled
  }

  // Whether two sequences contain identical items
  def compareItemSeqs(nodeset1: Seq[Item], nodeset2: Seq[Item]): Boolean =
    nodeset1.size == nodeset2.size &&
      (nodeset1.iterator.zip(nodeset2.iterator) forall (compareItems _).tupled)

  def compareItems(item1: Item, item2: Item): Boolean =
    (item1, item2) match {
      // We probably shouldn't support null at all here!
      case (null,             null)            ??? true
      case (null,             _)               ??? false
      case (_,                null)            ??? false
      // StringValue.equals() throws (Saxon equality requires a collation)
      case (v1: StringValue,  v2: StringValue) ??? v1.codepointEquals(v2)
      case (v1: StringValue,  v2 )             ??? false
      case (v1,               v2: StringValue) ??? false
      // AtomicValue.equals() may throw (Saxon changes the standard equals() contract)
      case (v1: AtomicValue,  v2: AtomicValue) ??? v1 == v2
      case (v1,               v2: AtomicValue) ??? false
      case (v1: AtomicValue,  v2)              ??? false
      // NodeInfo
      case (v1: NodeInfo,     v2: NodeInfo)    ??? v1 == v2
      // 2014-08-18: Checked Saxon class hierarchy
      // Saxon type hierarchy is closed (Item = NodeInfo | AtomicValue)
      case _                                   ??? throw new IllegalStateException
    }

  def buildNodePath(node: NodeInfo): List[String] = {

    def findNodePosition(node: NodeInfo): Int = {

      val nodeTestForSameNode =
        node.getFingerprint match {
          case -1 ??? NodeKindTest.makeNodeKindTest(node.getNodeKind)
          case _  ??? new NameTest(node)
        }

      val precedingAxis =
        node.iterateAxis(Axis.PRECEDING_SIBLING, nodeTestForSameNode)

      var i: Int = 1
      while (precedingAxis.next ne null) {
        i += 1
      }
      i
    }

    def buildOne(node: NodeInfo): List[String] = {

      def buildNameTest(node: NodeInfo) =
        if (node.getURI == "")
          node.getLocalPart
        else
          s"*:${node.getLocalPart}[namespace-uri() = '${node.getURI}']"

      if (node ne null) {
        val parent = node.getParent
        node.getNodeKind match {
          case Type.DOCUMENT ???
            Nil
          case Type.ELEMENT ???
            if (parent eq null) {
              List(buildNameTest(node))
            } else {
              val pre = buildOne(parent)
              if (pre == Nil) {
                buildNameTest(node) :: pre
              } else {
                (buildNameTest(node) + '[' + findNodePosition(node) + ']') :: pre
              }
            }
          case Type.ATTRIBUTE ???
            ("@" + buildNameTest(node)) :: buildOne(parent)
          case Type.TEXT ???
            ("text()[" + findNodePosition(node) + ']') :: buildOne(parent)
          case Type.COMMENT ???
            "comment()[" + findNodePosition(node) + ']' :: buildOne(parent)
          case Type.PROCESSING_INSTRUCTION ???
            ("processing-instruction()[" + findNodePosition(node) + ']') :: buildOne(parent)
          case Type.NAMESPACE ???
            var test = node.getLocalPart
            if (test.isEmpty) {
              test = "*[not(local-name()]"
            }
            ("namespace::" + test) :: buildOne(parent)
          case _ ???
            throw new IllegalArgumentException
        }
      } else {
        throw new IllegalArgumentException
      }
    }

    buildOne(node).reverse
  }
}
