/**
 * Copyright (C) 2015 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.xbl

import org.orbeon.dom.{Document, Element, QName}
import org.orbeon.oxf.properties.{Property, PropertySet}
import org.orbeon.oxf.util.Logging
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.oxf.xforms.XFormsConstants._
import org.orbeon.oxf.xml.dom4j.Dom4jUtils
import org.orbeon.oxf.xml.{Dom4j, XMLParsing}
import org.xml.sax.Attributes

import scala.collection.JavaConverters._
import scala.collection.{breakOut, mutable}

trait BindingLoader extends Logging {

  private val XBLMappingPropertyPrefix = "oxf.xforms.xbl.mapping."
  private val XBLLibraryProperty       = "oxf.xforms.xbl.library"
  private val XBLBaselineProperty      = "oxf.xforms.resources.baseline"

  def getPropertySet: PropertySet
  def lastModifiedByPath(path: String): Long
  def existsByPath(path: String): Boolean
  def contentAsDOM4J(path: String): Document

  def getUpToDateLibraryAndBaseline(
    index         : BindingIndex[IndexableBinding],
    checkUpToDate : Boolean // 2016-10-06: always set to `true`
  ): (BindingIndex[IndexableBinding], Set[String], List[String], List[String]) = {

    var originalOrUpdatedIndex = index

    val (scripts, styles, checkedPaths) = {

      val propertySet = getPropertySet

      val libraryProperty  = propertySet.getPropertyOrThrow(XBLLibraryProperty)
      val baselineProperty = propertySet.getPropertyOrThrow(XBLBaselineProperty)

      def reloadLibraryAndBaseline = {

        debug("reloading library and baseline")

        // 1: Read and index bindings
        val urlMappings = readURLMappingsCacheAgainstProperty

        def propertyQNames(property: Property) =
          stringToSet(property.value.toString) map
            (Dom4jUtils.extractTextValueQName(property.namespaces.asJava, _, true))

        def pathsForQNames(qNames: Set[QName]) =
          qNames flatMap { qName ???
            findBindingPathByNameUseMappings(urlMappings, qName.getNamespaceURI, qName.getName)
          } partition (_._2) match {
            case (found, notFound) ??? (found map (_._1), notFound map (_._1))
          }

        val (foundBaselinePaths, notFoundBaselinePaths) =
          pathsForQNames(propertyQNames(baselineProperty))

        val (baselineIndex, baselineBindings) =
          extractAndIndexFromPaths(
            GlobalBindingIndex.Empty, // note the empty index
            foundBaselinePaths
          )

        val (foundLibraryPaths, notFoundLibraryPaths) =
          pathsForQNames(propertyQNames(libraryProperty))

        val foundLibraryPathsNotInBaseline = foundLibraryPaths  -- foundBaselinePaths

        val (newIndex, _) =
          extractAndIndexFromPaths(
            baselineIndex,
            foundLibraryPathsNotInBaseline
          )

        originalOrUpdatedIndex = newIndex

        // 2: Collect resource baselines
        import XBLAssets._

        def collectUniqueReferenceElements(getHeadElements : AbstractBinding ??? Seq[HeadElement]) =
          (orderedHeadElements(baselineBindings, getHeadElements).collect{ case e: ReferenceElement ??? e.src }(breakOut): mutable.LinkedHashSet[String]).to[List]

        val allCheckedPaths =
          foundBaselinePaths    ++
          notFoundBaselinePaths ++
          foundLibraryPaths     ++
          notFoundLibraryPaths

        (collectUniqueReferenceElements(_.scripts), collectUniqueReferenceElements(_.styles), allCheckedPaths)
      }

      // We read and associate the value with 2 properties, but evaluation occurs at most once
      lazy val lazyEvaluatedValue = reloadLibraryAndBaseline
      def evaluate(property: Property) = lazyEvaluatedValue

      libraryProperty.associatedValue(evaluate)
      baselineProperty.associatedValue(evaluate)

      // Right here, `reloadLibraryAndBaseline` has been called exactly once if a property has changed, and none otherwise
    }

    // If the index is unmodified, it might contain out-of-date bindings. If it is modified, it is guaranteed by
    // evaluate() above to be a new index with up-to-date library bindings.
    if (checkUpToDate && (originalOrUpdatedIndex eq index))
      originalOrUpdatedIndex = updateOutOfDateBindings(index, checkedPaths)

    debug(
      "library and baseline paths",
      List(
        "paths"   ??? (checkedPaths mkString ", "),
        "scripts" ??? (scripts      mkString ", "),
        "styles"  ??? (styles       mkString ", ")
      )
    )

    (originalOrUpdatedIndex, checkedPaths, scripts, styles)
  }

  def findMostSpecificBinding(
    index        : BindingIndex[IndexableBinding],
    checkedPaths : Option[Set[String]],
    uri          : String,
    localname    : String,
    atts         : Attributes
  ): (BindingIndex[IndexableBinding], Set[String], Option[IndexableBinding]) = {

    var currentIndex        = index
    var currentCheckedPaths = checkedPaths getOrElse Set.empty

    def mustCheckBindingPath(binding: IndexableBinding) =
      checkedPaths.isDefined && (
        binding.path match {
          case Some(path) if ! currentCheckedPaths(path) ??? true
          case _                                         ??? false
        }
      )

    def findFromAlreadyLoadedBindings: Option[IndexableBinding] = {

      val qName   = QName.get(localname, "", uri)
      val attsSeq = convertAttributes(atts)

      BindingIndex.findMostSpecificBinding(currentIndex, qName, attsSeq) match {
        case Some((binding, true)) if mustCheckBindingPath(binding) ???

          // We found a binding by name, but we haven't checked if that path is up-to-date yet. So make sure
          // the binding is removed or updated, and add the path to the list of paths we have checked.
          val newIndex = updateBindingIfOutOfDate(currentIndex, binding.path.get, binding.lastModified)

          val bindingUpdated = newIndex ne currentIndex

          currentIndex = newIndex
          currentCheckedPaths += binding.path.get

          // If the binding was updated, try again but only once
          if (bindingUpdated)
            BindingIndex.findMostSpecificBinding(currentIndex, qName, attsSeq) map (_._1)
          else
            Some(binding)

        case Some((binding, _)) ???
          // Binding is either not by name, or it's by name and we don't need to check that it is up-to-date
          Some(binding)
        case _ ???
          None
      }
    }

    def findFromAutomaticBinding: Option[AbstractBinding] =
      findBindingPathByName(uri, localname) match {
        case Some((path, true)) ???
          val (newIndex, newBindings) = extractAndIndexFromPaths(currentIndex, List(path))

          currentIndex = newIndex
          currentCheckedPaths += path

          newBindings.headOption
        case Some((path, false)) ???
          currentCheckedPaths += path
          None
        case None ???
          None
      }

    // Since type selectors (i.e. by element name) have the lowest priority, AND attribute selectors are loaded
    // preemptively, we can first check the bindings already loaded, and only after that check automatic bindings,
    // as those are guaranteed to have only type selectors, and so are of the lowest priority.

    val result = findFromAlreadyLoadedBindings orElse findFromAutomaticBinding

    (currentIndex, currentCheckedPaths, result)
  }

  def extractAndIndexFromElements(
    index    : BindingIndex[IndexableBinding],
    elements : Seq[Element]
  ): (BindingIndex[IndexableBinding], List[AbstractBinding]) = {

    val newBindings =
      for {
        elem       ??? elements.to[List]
        newBinding ??? extractXBLBindings(None, -1, elem)
      } yield
        newBinding

    val currentIndex =
      indexBindings(index, newBindings)

    (currentIndex, newBindings)
  }

  private def extractAndIndexFromPaths(
    index : BindingIndex[IndexableBinding],
    paths : TraversableOnce[String]
  ): (BindingIndex[IndexableBinding], List[AbstractBinding]) = {

    val newBindings =
      for {
        path                 ??? paths.to[List]
        (elem, lastModified) = readXBLResource(path)
        newBinding           ??? extractXBLBindings(Some(path), lastModified, elem)
      } yield
        newBinding

    val currentIndex =
      indexBindings(index, newBindings)

    (currentIndex, newBindings)
  }

  private def indexBindings(index: BindingIndex[IndexableBinding], bindings: List[AbstractBinding]) = {
    var currentIndex = index

    bindings foreach { binding ???
      currentIndex = BindingIndex.indexBinding(currentIndex, binding)
    }

    currentIndex
  }

  private def updateBindingIfOutOfDate(index: BindingIndex[IndexableBinding], path: String, lastModified: Long) = {

    var currentIndex = index

    val resourceLastModified = lastModifiedByPath(path)

    val hasNoDate   = resourceLastModified <= 0
    val isOutOfDate = resourceLastModified > lastModified

    if (hasNoDate || isOutOfDate)
      currentIndex = BindingIndex.deIndexBindingByPath(currentIndex, path)

    if (isOutOfDate)
      currentIndex = extractAndIndexFromPaths(currentIndex, List(path))._1

    currentIndex
  }

  // Missing or out of date bindings are removed, and out-of-date bindings are reloaded
  private def updateOutOfDateBindings(
    index : BindingIndex[IndexableBinding],
    paths : Set[String]
  ): BindingIndex[IndexableBinding] = {

    var currentIndex = index

    for {
      path    ??? paths
      binding ??? BindingIndex.distinctBindingsForPath(currentIndex, path)
    } locally {
      currentIndex = updateBindingIfOutOfDate(currentIndex, path, binding.lastModified)
    }

    currentIndex
  }

  // E.g. http://orbeon.org/oxf/xml/form-runner ??? orbeon
  // NOTE: The caching is not optimal, as we need, to cache, to iterate all properties with propertiesStartsWith!
  // Would need a way to cache against PropertySet.
  private def readURLMappingsCacheAgainstProperty: Map[String, String] = {

    val propertySet = getPropertySet

    val mappingProperties = propertySet.propertiesStartsWith(XBLMappingPropertyPrefix, matchWildcards = false)

    def evaluate(property: Property) = (
      for {
        propertyName ??? mappingProperties
        uri          = propertySet.getNonBlankString(propertyName) getOrElse ""
        prefix       = propertyName.substring(XBLMappingPropertyPrefix.length)
      } yield
        uri ??? prefix
    ) toMap

    // Associate result with the property so it won't be computed until properties are reloaded
    mappingProperties.headOption match {
      case Some(firstPropertyName) ???
        propertySet.getPropertyOrThrow(firstPropertyName).associatedValue(evaluate)
      case None ???
        Map()
    }
  }

  def bindingPathByName(prefix: String, localname: String) =
    s"/xbl/$prefix/$localname/$localname.xbl"

  // E.g. fr:tabview ??? oxf:/xbl/orbeon/tabview/tabview.xbl
  private def findBindingPathByNameUseMappings(mappings: Map[String, String], uri: String, localname: String) =
    mappings.get(uri) map { prefix ???
      val path = bindingPathByName(prefix, localname)
      (path, existsByPath(path))
    }

  private def findBindingPathByName(uri: String, localname: String) =
    findBindingPathByNameUseMappings(readURLMappingsCacheAgainstProperty, uri, localname)

  private def readXBLResource(path: String) = {

    val sourceXBL    = contentAsDOM4J(path)
    val lastModified = lastModifiedByPath(path)

    (Transform.transformXBLDocumentIfNeeded(path, sourceXBL, lastModified).getRootElement, lastModified)
  }

  private def extractXBLBindings(
    path         : Option[String],
    lastModified : Long,
    xblElement   : Element
  ): List[AbstractBinding] = {

    // Extract xbl:xbl/xbl:script
    // TODO: should do this differently, in order to include only the scripts and resources actually used
    val scriptElements = Dom4j.elements(xblElement, XBL_SCRIPT_QNAME) map XBLAssets.HeadElement.apply

    // Create binding for all xbl:binding[@element]
    for {
      bindingElement          ??? Dom4j.elements(xblElement, XBL_BINDING_QNAME).to[List]
      currentElementAttribute ??? Option(bindingElement.attributeValue(ELEMENT_QNAME))
    } yield
      AbstractBinding.fromBindingElement(bindingElement, path, lastModified, scriptElements)
  }

  private def convertAttributes(atts: Attributes) =
    for (i ??? 0 until atts.getLength)
      yield QName.get(atts.getLocalName(i), "", atts.getURI(i)) ??? atts.getValue(i)
}

object BindingLoader extends BindingLoader {

  import org.orbeon.oxf.properties.Properties
  import org.orbeon.oxf.resources.ResourceManagerWrapper

  private val rm = ResourceManagerWrapper.instance

  def getPropertySet = Properties.instance.getPropertySet

  def lastModifiedByPath(path: String): Long = {
    debug("checking last modified", List("path" ??? path))
    rm.lastModified(path, true)
  }

  def existsByPath(path: String): Boolean = {
    debug("checking existence", List("path" ??? path))
    rm.exists(path)
  }

  def contentAsDOM4J(path: String): Document = {
    debug("reading content", List("path" ??? path))
    rm.getContentAsDOM4J(path, XMLParsing.ParserConfiguration.XINCLUDE_ONLY, false)
  }
}