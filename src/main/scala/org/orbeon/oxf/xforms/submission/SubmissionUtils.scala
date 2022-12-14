/**
 * Copyright (C) 2012 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.submission

import java.io.InputStream
import java.net.URI

import org.orbeon.oxf.common.OXFException
import org.orbeon.oxf.externalcontext.ExternalContext
import org.orbeon.oxf.http
import org.orbeon.oxf.http.HttpMethod.GET
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.oxf.util._
import org.orbeon.oxf.xforms.model.{XFormsInstance, XFormsModel}
import org.orbeon.oxf.xforms.{XFormsContainingDocument, XFormsUtils}
import org.orbeon.oxf.xml.{SaxonUtils, TransformerUtils}
import org.orbeon.saxon.om.{DocumentInfo, NodeInfo}

// The plan is to move stuff from XFormsSubmissionUtils to here as needed
object SubmissionUtils {

  // Result of `resolveAttributeValueTemplates` can be `None` if, e.g. you have an AVT like `resource="{()}"`!
  def stringAvtTrimmedOpt(
    value              : String)(implicit
    refContext         : RefContext,
    containingDocument : XFormsContainingDocument
  ): Option[String] =
    Option(
      XFormsUtils.resolveAttributeValueTemplates(
        containingDocument,
        refContext.xpathContext,
        refContext.refNodeInfo,
        value
      )
    ) flatMap (_.trimAllToOpt)

  def booleanAvtOpt(
    value              : String)(implicit
    refContext         : RefContext,
    containingDocument : XFormsContainingDocument
  ): Option[Boolean] =
    stringAvtTrimmedOpt(value) map (_.toBoolean)

  def dataNodeHash(node: NodeInfo): String =
    SecureUtils.hmacString(SaxonUtils.buildNodePath(node) mkString ("/", "/", ""), "hex")

  def readByteArray(model: XFormsModel, resolvedURL: String): Array[Byte] =
    processGETConnection(model, resolvedURL) { is ???
      NetUtils.inputStreamToByteArray(is)
    }

  def readTinyTree(model: XFormsModel, resolvedURL: String, handleXInclude: Boolean): DocumentInfo =
    processGETConnection(model, resolvedURL) { is ???
      TransformerUtils.readTinyTree(
        XPath.GlobalConfiguration,
        is,
        resolvedURL,
        handleXInclude,
        true
      )
    }

  def processGETConnection[T](model: XFormsModel, resolvedURL: String)(body: InputStream ??? T): T =
    ConnectionResult.withSuccessConnection(openGETConnection(model, resolvedURL), closeOnSuccess = true)(body)

  def openGETConnection(model: XFormsModel, resolvedURL: String): ConnectionResult = {

    implicit val _logger = model.indentedLogger
    val url = new URI(resolvedURL)

    Connection(
      method          = GET,
      url             = url,
      credentials     = None,
      content         = None,
      headers         = Connection.buildConnectionHeadersCapitalizedIfNeeded(
        scheme           = url.getScheme,
        hasCredentials   = false,
        customHeaders    = Map(),
        headersToForward = Connection.headersToForwardFromProperty,
        cookiesToForward = Connection.cookiesToForwardFromProperty,
        getHeader        = model.containingDocument.headersGetter
      ) mapValues (_.toList),
      loadState       = true,
      logBody         = BaseSubmission.isLogBody
    ).connect(
      saveState = true
    )
  }

  def evaluateHeaders(submission: XFormsModelSubmission, forwardClientHeaders: Boolean): Map[String, List[String]] = {
    try {
      val headersToForward =
        clientHeadersToForward(submission.containingDocument.getRequestHeaders, forwardClientHeaders)

      SubmissionHeaders.evaluateHeaders(
        submission.container,
        submission.getModel.getContextStack,
        submission.getEffectiveId,
        submission.getSubmissionElement,
        headersToForward
      )

    } catch {
      case e: OXFException ??? throw new XFormsSubmissionException(submission, e, e.getMessage, "processing <header> elements")
    }
  }

  def clientHeadersToForward(allHeaders: Map[String, List[String]], forwardClientHeaders: Boolean): Map[String, List[String]] = {
    if (forwardClientHeaders) {
      // Forwarding the user agent and accept headers makes sense when dealing with resources that
      // typically would come from the client browser, including:
      //
      // - submission with replace="all"
      // - dynamic resources loaded by xf:output
      //
      // Also useful when the target URL renders XForms in noscript mode, where some browser sniffing takes
      // place for handling the <button> vs. <submit> element.
      val toForward =
        for {
          name   ??? List("user-agent", "accept")
          values ??? allHeaders.get(name)
        } yield
          name ??? values

      // Give priority to explicit headers
      toForward.toMap
    } else
      Map.empty[String, List[String]]
  }

  def forwardResponseHeaders(cxr: ConnectionResult, response: ExternalContext.Response): Unit =
    for {
      (headerName, headerValues) ??? http.Headers.proxyHeaders(cxr.headers, request = false)
      headerValue                ??? headerValues
    } locally {
      response.addHeader(headerName, headerValue)
    }

  import org.orbeon.oxf.util.CollectionUtils._

  // Whether there is at least one relevant upload control with pending upload bound to any node of the given instance
  def hasBoundRelevantPendingUploadControls(
    doc         : XFormsContainingDocument,
    instanceOpt : Option[XFormsInstance]
  ): Boolean =
    instanceOpt match {
      case Some(instance) if doc.countPendingUploads > 0 ???

        val boundRelevantPendingUploadControlsIt =
          for {
            uploadControl ??? doc.getControls.getCurrentControlTree.getUploadControls.iterator
            if uploadControl.isRelevant && doc.isUploadPendingFor(uploadControl)
            node          ??? collectByErasedType[NodeInfo](uploadControl.getBoundItem)
            if instance eq instance.model.getInstanceForNode(node)
          } yield
            uploadControl

        boundRelevantPendingUploadControlsIt.nonEmpty
      case _ ???
        false
    }

}
