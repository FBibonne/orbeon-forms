/**
 * Copyright (C) 2013 Orbeon, Inc.
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
package org.orbeon.oxf.fb

import org.junit.Test
import org.orbeon.dom._
import org.orbeon.oxf.fr.ResourcesPatcher
import org.orbeon.oxf.properties.PropertyStore
import org.orbeon.oxf.resources.URLFactory
import org.orbeon.oxf.test.DocumentTestBase
import org.orbeon.oxf.util.CollectionUtils._
import org.orbeon.oxf.util.IOUtils._
import org.orbeon.oxf.util.XPath
import org.orbeon.oxf.xml.Dom4j.elemToDocument
import org.orbeon.oxf.xml.TransformerUtils
import org.orbeon.saxon.om.{DocumentInfo, NodeInfo}
import org.scalatest.junit.AssertionsForJUnit

// NOTE: Test this in the `form-builder` module as we depend on Form Builder's `resources.xml`.
class ResourcesPatcherTest extends DocumentTestBase with AssertionsForJUnit {

  @Test def patchingScenarios(): Unit = {

    val propertySet = {
      val properties: Document =
        <properties xmlns:xs="http://www.w3.org/2001/XMLSchema">
          <property as="xs:string"  name="oxf.fr.resource.*.*.en.detail.buttons.existing" value="Existing"/>
          <property as="xs:string"  name="oxf.fr.resource.*.*.fr.detail.buttons.existing" value="Existant"/>
          <property as="xs:string"  name="oxf.fr.resource.*.*.de.detail.buttons.existing" value="Vorhanden"/>
          <property as="xs:string"  name="oxf.fr.resource.*.*.en.detail.labels.missing"   value="Missing"/>
          <property as="xs:string"  name="oxf.fr.resource.*.*.fr.detail.labels.missing"   value="Manquant"/>
          <property as="xs:string"  name="oxf.fr.resource.*.*.de.detail.labels.missing"   value="Vermisst"/>
          <property as="xs:string"  name="oxf.fr.resource.*.*.*.detail.buttons.acme"      value="Acme Existing"/>
          <property as="xs:string"  name="oxf.fr.resource.*.*.*.detail.labels.acme"       value="Acme Missing"/>
        </properties>

      new PropertyStore(properties).getGlobalPropertySet
    }

    def newDoc: Document =
      <resources>
        <resource xml:lang="en">
          <buttons>
            <existing>OVERRIDE ME</existing>
            <acme>OVERRIDE ME</acme>
          </buttons>
        </resource>
        <resource xml:lang="fr">
          <buttons>
            <existing>OVERRIDE ME</existing>
            <acme>OVERRIDE ME</acme>
          </buttons>
        </resource>
      </resources>

    val expected: Document =
      <resources>
        <resource xml:lang="en">
          <buttons>
            <existing>Existing</existing>
            <acme>Acme Existing</acme>
          </buttons>
          <detail>
            <labels>
              <missing>Missing</missing>
              <acme>Acme Missing</acme>
            </labels>
          </detail>
        </resource>
        <resource xml:lang="fr">
          <buttons>
            <existing>Existant</existing>
            <acme>Acme Existing</acme>
          </buttons>
          <detail>
            <labels>
              <missing>Manquant</missing>
              <acme>Acme Missing</acme>
            </labels>
          </detail>
        </resource>
      </resources>

    val initial = newDoc

    ResourcesPatcher.transform(initial, "*", "*")(propertySet)

    assertXMLDocumentsIgnoreNamespacesInScope(initial, expected)
  }

  @Test def testResourcesConsistency(): Unit = {

    import org.orbeon.scaxon.XML._

    def hasLang(lang: String)(e: NodeInfo) = (e attValue "*:lang") == "en"

    val urls = Seq(
      "oxf:/apps/fr/i18n/resources.xml",
      "oxf:/forms/orbeon/builder/form/resources.xml",
      "oxf:/xbl/orbeon/dialog-select/dialog-select-resources.xml"
    )

    // - allow "item" and "choices" because we use this for itemsets
    // - allow "type" because it's used for the FB list of types
    val AllowedDuplicateNames = Set("item", "choices", "type")

    for (url ??? urls) {

      val doc =
        useAndClose(URLFactory.createURL(url).openStream()) { is ???
          TransformerUtils.readTinyTree(XPath.GlobalConfiguration, is, null, false, false)
        }

      // Baseline is "en"
      val englishResource = doc / * / "resource" filter hasLang("en") head

      // Recursively compare element presence and order. All other nodes, including text and attribute nodes, are
      // ignored.
      def compareElements(left: NodeInfo, right: NodeInfo, lang: String): Boolean = (left, right) match {
        case (left: DocumentInfo, right: DocumentInfo) ???
          compareElements(left.rootElement, right.rootElement, lang)
        case (left: NodeInfo, right: NodeInfo) if isElement(left) ???

          def commonMessageSuffix = s" (url=$url and lang=$lang)"

          assert(left.name === right.name, s"different names$commonMessageSuffix")

          // Ignore children of "div" because it can contain XHTML which is different per language
          left.name == right.name && (left.name == "div" || {
            val leftChildren  = left  / *
            val rightChildren = right / *

            val duplicates = (leftChildren map (_.name)).findDuplicates filterNot AllowedDuplicateNames

            assert(
              duplicates.isEmpty,
              s"duplicate names under `${left.name}`: ${duplicates mkString ", "}$commonMessageSuffix"
            )

            def elemNames(elems: Seq[NodeInfo]) =
              elems map (_.name) mkString ("[", ", ", "]")

            def errorSuffix =
              s"$commonMessageSuffix (left=${elemNames(leftChildren)}, right=${elemNames(rightChildren)}"

            assert(leftChildren.size === rightChildren.size, s"different sizes$errorSuffix")

            leftChildren.size == rightChildren.size && {
              (leftChildren zip rightChildren) forall {
                case (l, r) ??? compareElements(l, r, lang)
              }
            }
          })
        case _ ???
          // Ignore all other nodes
          true
      }

      for {
        resource ??? doc / * / "resource" filterNot hasLang("en")
        lang     = resource attValue "*:lang"
      } locally {
        assert(compareElements(englishResource, resource, lang))
      }
    }
  }
}
