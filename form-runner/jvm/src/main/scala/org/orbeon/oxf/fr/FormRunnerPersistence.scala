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
package org.orbeon.oxf.fr

import java.net.URI

import enumeratum._
import org.orbeon.oxf.externalcontext.URLRewriter
import org.orbeon.oxf.fr.FormRunner.properties
import org.orbeon.oxf.fr.persistence.relational.Version._
import org.orbeon.oxf.http.Headers._
import org.orbeon.oxf.http.HttpMethod.GET
import org.orbeon.oxf.util.PathUtils._
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.oxf.util._
import org.orbeon.oxf.xforms.action.XFormsAPI._
import org.orbeon.oxf.xforms.analysis.model.ValidationLevel
import org.orbeon.oxf.xforms.control.controls.XFormsUploadControl
import org.orbeon.oxf.xml.{TransformerUtils, XMLUtils}
import org.orbeon.saxon.om.{DocumentInfo, NodeInfo}
import org.orbeon.scaxon.XML._

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

sealed abstract class FormOrData(override val entryName: String) extends EnumEntry

object FormOrData extends Enum[FormOrData] {

  val values = findValues

  case object Form extends FormOrData("form")
  case object Data extends FormOrData("data")
}

object FormRunnerPersistenceJava {
  //@XPathFunction
  def providerDataFormatVersion(app: String, form: String) =
    FormRunnerPersistence.providerDataFormatVersion(app, form)
}

object FormRunnerPersistence {

  val DataFormatVersion400                       = "4.0.0"
  val DataFormatVersion480                       = "4.8.0"
  val DataFormatVersionEdge                      = "edge"

  val DataFormatVersionName                      = "data-format-version"
  val PruneMetadataName                          = "prune-metadata"
  val ShowProgressName                           = "show-progress"
  val FormTargetName                             = "formtarget"
  val NonRelevantName                            = "nonrelevant"

  val DefaultDataFormatVersion                   = DataFormatVersion400
  val FormRunnerCurrentInternalDataFormatVersion = DataFormatVersion480

  val AllowedDataFormatVersions = Set(DataFormatVersion400, DataFormatVersion480)

  val CRUDBasePath                               = "/fr/service/persistence/crud"
  val FormMetadataBasePath                       = "/fr/service/persistence/form"
  val PersistencePropertyPrefix                  = "oxf.fr.persistence"
  val PersistenceProviderPropertyPrefix          = PersistencePropertyPrefix + ".provider"

  val StandardProviderProperties = Set("uri", "autosave", "active", "permissions")

  def findProvider(app: String, form: String, formOrData: FormOrData): Option[String] = {
    val providerProperty = PersistenceProviderPropertyPrefix :: app :: form :: formOrData.entryName :: Nil mkString "."
    properties.getNonBlankString(providerProperty)
  }

  def providerPropertyAsURL(provider: String, property: String): String =
    properties.getStringOrURIAsString(PersistencePropertyPrefix :: provider :: property :: Nil mkString ".")

  def getPersistenceURLHeaders(app: String, form: String, formOrData: FormOrData): (String, Map[String, String]) = {

    require(augmentString(app).nonEmpty) // Q: why `augmentString`?
    require(augmentString(form).nonEmpty)

    getPersistenceURLHeadersFromProvider(findProvider(app, form, formOrData).get)
  }

  def getPersistenceURLHeadersFromProvider(provider: String): (String, Map[String, String]) = {

    val propertyPrefix = PersistencePropertyPrefix :: provider :: Nil mkString "."
    val propertyPrefixTokenCount = propertyPrefix.splitTo[List](".").size

    // Build headers map
    val headers = (
      for {
        propertyName ??? properties.propertiesStartsWith(propertyPrefix, matchWildcards = false)
        lowerSuffix  ??? propertyName.splitTo[List](".").drop(propertyPrefixTokenCount).headOption
        if ! StandardProviderProperties(lowerSuffix)
        headerName  = "Orbeon-" + capitalizeSplitHeader(lowerSuffix)
        headerValue = properties.getObject(propertyName).toString
      } yield
        headerName ??? headerValue) toMap

    (providerPropertyAsURL(provider, "uri"), headers)
  }

  def getPersistenceHeadersAsXML(app: String, form: String, formOrData: FormOrData): DocumentInfo = {

    val (_, headers) = getPersistenceURLHeaders(app, form, formOrData)

    // Build headers document
    val headersXML =
      <headers>{
        for {
          (name, value) ??? headers
        } yield
          <header><name>{XMLUtils.escapeXMLMinimal(name)}</name><value>{XMLUtils.escapeXMLMinimal(value)}</value></header>
      }</headers>.toString

    // Convert to TinyTree
    TransformerUtils.stringToTinyTree(XPath.GlobalConfiguration, headersXML, false, false)
  }

  def providerDataFormatVersion(app: String, form: String): String = {

    val provider =
      findProvider(app, form, FormOrData.Data) getOrElse
        (throw new IllegalArgumentException(s"no provider property configuration found for `$app/$form`"))

    val dataFormatVersion = providerPropertyAsString(provider, DataFormatVersionName, DefaultDataFormatVersion)

    require(
      AllowedDataFormatVersions(dataFormatVersion),
      s"`${fullProviderPropertyName(provider, DataFormatVersionName)}` property must be one of ${AllowedDataFormatVersions mkString ", "}"
    )

    dataFormatVersion
  }

  private def fullProviderPropertyName(provider: String, property: String) =
    PersistencePropertyPrefix :: provider :: property :: Nil mkString "."

  private def providerPropertyAsString(provider: String, property: String, default: String) =
    properties.getString(fullProviderPropertyName(provider, property), default)

  // NOTE: We generate .bin, but sample data can contain other extensions
  private val RecognizedAttachmentExtensions = Set("bin", "jpg", "jpeg", "gif", "png", "pdf")
}

trait FormRunnerPersistence {

  import FormRunnerPersistence._
  import org.orbeon.oxf.fr.FormRunner._

  // Check whether a value correspond to an uploaded file
  //
  // For this to be true
  // - the protocol must be file:
  // - the URL must have a valid signature
  //
  // This guarantees that the local file was in fact placed there by the upload control, and not tampered with.
  def isUploadedFileURL(value: String): Boolean =
    value.startsWith("file:/") && XFormsUploadControl.verifyMAC(value)

  //@XPathFunction
  def createFormDataBasePath(app: String, form: String, isDraft: Boolean, document: String): String =
    CRUDBasePath :: app :: form :: (if (isDraft) "draft" else "data") :: document :: "" :: Nil mkString "/"

  //@XPathFunction
  def createFormDefinitionBasePath(app: String, form: String) =
    CRUDBasePath :: app :: form :: FormOrData.Form.entryName :: "" :: Nil mkString "/"

  def createFormMetadataPath(app: String, form: String) =
    FormMetadataBasePath :: app :: form :: Nil mkString "/"

  // Whether the given path is an attachment path (ignoring an optional query string)
  def isAttachmentURLFor(basePath: String, url: String) =
    url.startsWith(basePath) && (splitQuery(url)._1.splitTo[List](".").lastOption exists RecognizedAttachmentExtensions)

  // For a given attachment path, return the filename
  def getAttachmentPathFilenameRemoveQuery(pathQuery: String) = splitQuery(pathQuery)._1.split('/').last

  def providerPropertyAsBoolean(provider: String, property: String, default: Boolean) =
    properties.getBoolean(PersistencePropertyPrefix :: provider :: property :: Nil mkString ".", default)

  //@XPathFunction
  def autosaveSupported(app: String, form: String) =
    providerPropertyAsBoolean(findProvider(app, form, FormOrData.Data).get, "autosave", default = false)

  //@XPathFunction
  def ownerGroupPermissionsSupported(app: String, form: String) =
    providerPropertyAsBoolean(findProvider(app, form, FormOrData.Data).get, "permissions", default = false)

  //@XPathFunction
  def versioningSupported(app: String, form: String) =
    providerPropertyAsBoolean(findProvider(app, form, FormOrData.Data).get, "versioning", default = false)

  def isActiveProvider(provider: String) =
    providerPropertyAsBoolean(provider, "active", default = true)

  // Reads a document forwarding headers. The URL is rewritten, and is expected to be like "/fr/???"
  def readDocument(urlString: String)(implicit logger: IndentedLogger): Option[DocumentInfo] = {

    val request = NetUtils.getExternalContext.getRequest

    val rewrittenURLString =
      URLRewriterUtils.rewriteServiceURL(
        request,
        urlString,
        URLRewriter.REWRITE_MODE_ABSOLUTE
      )

    val url = new URI(rewrittenURLString)

    val headers = Connection.buildConnectionHeadersCapitalizedIfNeeded(
      scheme           = url.getScheme,
      hasCredentials   = false,
      customHeaders    = Map(),
      headersToForward = Connection.headersToForwardFromProperty,
      cookiesToForward = Connection.cookiesToForwardFromProperty,
      Connection.getHeaderFromRequest(request)
    )

    val cxr = Connection(
      method      = GET,
      url         = url,
      credentials = None,
      content     = None,
      headers     = headers,
      loadState   = true,
      logBody     = false
    ).connect(
      saveState = true
    )

    // Libraries are typically not present. In that case, the persistence layer should return a 404 (thus the test
    // on status code),  but the MySQL persistence layer returns a [200 with an empty body][1] (thus a body is
    // required).
    //   [1]: https://github.com/orbeon/orbeon-forms/issues/771
    ConnectionResult.tryWithSuccessConnection(cxr, closeOnSuccess = true) { is ???
      // do process XInclude, so FB's model gets included
      TransformerUtils.readTinyTree(XPath.GlobalConfiguration, is, rewrittenURLString, true, false)
    } toOption
  }

  // Retrieves a form definition from the persistence layer
  def readPublishedForm(appName: String, formName: String)(implicit logger: IndentedLogger): Option[DocumentInfo] =
    readDocument(createFormDefinitionBasePath(appName, formName) + "form.xhtml")

  // Retrieves the metadata for a form from the persistence layer
  def readFormMetadata(appName: String, formName: String)(implicit logger: IndentedLogger): Option[DocumentInfo] =
    readDocument(createFormMetadataPath(appName, formName))

  // Whether the form data is valid as per the error summary
  // We use instance('fr-error-summary-instance')/valid and not valid() because the instance validity may not be
  // reflected with the use of XBL components.
  def dataValid =
    errorSummaryInstance.rootElement \ "valid" === "true"

  // Return the number of failed validations captured by the error summary for the given level
  def countValidationsByLevel(level: ValidationLevel) =
    (errorSummaryInstance.rootElement \ "counts" \@ level.entryName stringValue).toInt

  // Return whether the data is saved
  def isFormDataSaved =
    persistenceInstance.rootElement \ "data-status" === "clean"

  // Return all nodes which refer to data attachments
  //@XPathFunction
  def collectDataAttachmentNodesJava(data: NodeInfo, fromBasePath: String) =
    collectAttachments(data.getDocumentRoot, fromBasePath, fromBasePath, forceAttachments = true)._1.asJava

  def collectAttachments(
    data             : DocumentInfo,
    fromBasePath     : String,
    toBasePath       : String,
    forceAttachments : Boolean
  ): (Seq[NodeInfo], Seq[String], Seq[String]) = (
    for {
      holder        ??? data \\ Node
      if isAttribute(holder) || isElement(holder) && ! hasChildElement(holder)
      beforeURL     = holder.stringValue.trimAllToEmpty
      isUploaded    = isUploadedFileURL(beforeURL)
      if isUploaded ||
        isAttachmentURLFor(fromBasePath, beforeURL) && ! isAttachmentURLFor(toBasePath, beforeURL) ||
        isAttachmentURLFor(toBasePath, beforeURL) && forceAttachments
    } yield {
      // Here we could decide to use a nicer extension for the file. But since initially the filename comes from
      // the client, it cannot be trusted, nor can its mediatype. A first step would be to do content-sniffing to
      // determine a more trusted mediatype. A second step would be to put in an API for virus scanning. For now,
      // we just use .bin as an extension.
      val filename =
        if (isUploaded)
          SecureUtils.randomHexId + ".bin"
        else
          getAttachmentPathFilenameRemoveQuery(beforeURL)

      val afterURL =
        toBasePath + filename

      (holder, beforeURL, afterURL)
    }
  ).unzip3

  def putWithAttachments(
    data              : DocumentInfo,
    toBaseURI         : String,
    fromBasePath      : String,
    toBasePath        : String,
    filename          : String,
    commonQueryString : String,
    forceAttachments  : Boolean,
    username          : Option[String] = None,
    password          : Option[String] = None,
    formVersion       : Option[String] = None
  ): (Seq[String], Seq[String], Int) = {

    // Find all instance nodes containing file URLs we need to upload
    val (uploadHolders, beforeURLs, afterURLs) =
      collectAttachments(data, fromBasePath, toBasePath, forceAttachments)

    def saveAllAttachments(): Unit =
      uploadHolders zip afterURLs foreach { case (holder, resource) ???
        sendThrowOnError("fr-create-update-attachment-submission", Map(
          "holder"       ??? Some(holder),
          "resource"     ??? Some(appendQueryString(toBaseURI + resource, commonQueryString)),
          "username"     ??? username,
          "password"     ??? password,
          "form-version" ??? formVersion)
        )
      }

    def updateAttachmentPaths() =
      uploadHolders zip afterURLs foreach { case (holder, resource) ???
        setvalue(holder, resource)
      }

    def rollbackAttachmentPaths() =
      uploadHolders zip beforeURLs foreach { case (holder, resource) ???
        setvalue(holder, resource)
      }

    def saveXmlData() =
      sendThrowOnError("fr-create-update-submission", Map(
        "holder"       ??? Some(data.rootElement),
        "resource"     ??? Some(appendQueryString(toBaseURI + toBasePath + filename, commonQueryString)),
        "username"     ??? username,
        "password"     ??? password,
        "form-version" ??? formVersion)
      )

    // First process attachments
    saveAllAttachments()

    val versionOpt =
      try {

        // Before saving data, update attachment paths
        updateAttachmentPaths()

        // Save and try to retrieve returned version
        for {
          done     ??? saveXmlData()
          headers  ??? done.headers
          versions ??? headers collectFirst { case (name, values) if name equalsIgnoreCase OrbeonFormDefinitionVersion ??? values }
          version  ??? versions.headOption
        } yield
          version

      } catch {
        case NonFatal(e) ???
          // In our persistence implementation, we do not remove attachments if saving the data fails.
          // However, some custom persistence implementations do. So we don't think we can assume that
          // attachments have been saved. So we rollback attachment paths in the data in this case.
          // This will cause attachments to be saved again even if they actually have already been saved.
          // It is not ideal, but will not lead to data loss. See also:
          //
          // - https://github.com/orbeon/orbeon-forms/issues/606
          // - https://github.com/orbeon/orbeon-forms/issues/3084
          rollbackAttachmentPaths()
          throw e
      }

    (beforeURLs, afterURLs, versionOpt map (_.toInt) getOrElse 1)
  }
}
