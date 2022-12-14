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
package org.orbeon.oxf.xforms.submission

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import javax.xml.transform.stream.StreamResult

import org.orbeon.dom.Document
import org.orbeon.dom.io.DocumentSource
import org.orbeon.dom.saxon.DocumentWrapper
import org.orbeon.oxf.externalcontext.URLRewriter
import org.orbeon.oxf.json.Converter
import org.orbeon.oxf.util.{Connection, ContentTypes, XPath}
import org.orbeon.oxf.xforms.XFormsUtils
import org.orbeon.oxf.xforms.model.InstanceData
import org.orbeon.oxf.xml.{TransformerUtils, XMLConstants}

case class SerializationParameters(
  messageBody            : Array[Byte],
  queryString            : String,
  actualRequestMediatype : String
)

object SerializationParameters {

  def apply(
    submission               : XFormsModelSubmission,
    p                        : SubmissionParameters,
    p2                       : SecondPassParameters,
    requestedSerialization   : String,
    documentToSubmit         : Document,
    overriddenSerializedData : String
  ): SerializationParameters = {

    // Actual request mediatype: the one specified by @mediatype, or the default mediatype for the serialization otherwise
    def actualRequestMediatype(default: String) =
      p.mediatypeOpt getOrElse default

    if (p.serialize) {
      requestedSerialization match {
        case _ if (overriddenSerializedData ne null) && overriddenSerializedData != "" ???
          // Form author set data to serialize
          if (Connection.requiresRequestBody(p.httpMethod)) {
            SerializationParameters(
              messageBody            = overriddenSerializedData.getBytes("UTF-8"),
              queryString            = null,
              actualRequestMediatype = actualRequestMediatype("application/xml")
            )
          } else {
            SerializationParameters(
              messageBody            = null,
              queryString            = URLEncoder.encode(overriddenSerializedData, "UTF-8"),
              actualRequestMediatype = actualRequestMediatype(null)
            )
          }
        case serialization @ "application/x-www-form-urlencoded" ???
          if (Connection.requiresRequestBody(p.httpMethod)) {
            SerializationParameters(
              messageBody            = XFormsSubmissionUtils.createWwwFormUrlEncoded(documentToSubmit, p2.separator).getBytes("UTF-8"),
              queryString            = null,
              actualRequestMediatype = actualRequestMediatype(serialization)
            )
          } else {
            SerializationParameters(
              messageBody            = null,
              queryString            = XFormsSubmissionUtils.createWwwFormUrlEncoded(documentToSubmit, p2.separator),
              actualRequestMediatype = actualRequestMediatype(null)
            )
          }
        case serialization @ "application/xml" ???
          try {
            val identity = TransformerUtils.getIdentityTransformer
            TransformerUtils.applyOutputProperties(
              identity,
              "xml",
              p2.versionOpt.orNull,
              null,
              null,
              p2.encoding,
              p2.omitXmlDeclaration,
              p2.standaloneOpt map java.lang.Boolean.valueOf orNull,
              p2.indent,
              4
            )

            // TODO: use cdata-section-elements

            val os = new ByteArrayOutputStream
            identity.transform(new DocumentSource(documentToSubmit), new StreamResult(os))

            SerializationParameters(
                messageBody            = os.toByteArray,
                queryString            = null,
                actualRequestMediatype = actualRequestMediatype(serialization)
              )
          } catch {
            case e: Exception ???
              throw new XFormsSubmissionException(
                submission,
                e,
                "xf:submission: exception while serializing instance to XML.",
                "serializing instance"
              )
          }
        case serialization @ "application/json" ???

          val result = Converter.xmlToJsonString(
            root   = new DocumentWrapper(documentToSubmit, null, XPath.GlobalConfiguration),
            strict = true
          )

          SerializationParameters(
              messageBody            = result.getBytes(p2.encoding),
              queryString            = null,
              actualRequestMediatype = actualRequestMediatype(serialization)
            )

        case serialization @ "multipart/related" ???
          // TODO
          throw new XFormsSubmissionException(
            submission,
            s"xf:submission: submission serialization not yet implemented: $serialization",
            "serializing instance"
          )
        case "multipart/form-data" ???
          // Build multipart/form-data body
          val multipartFormData = XFormsSubmissionUtils.createMultipartFormData(documentToSubmit)
          val os = new ByteArrayOutputStream
          multipartFormData.writeTo(os)

          // The mediatype also contains the boundary
          SerializationParameters(
            messageBody            = os.toByteArray,
            queryString            = null,
            actualRequestMediatype = actualRequestMediatype(multipartFormData.getContentType.getValue)
          )
        case serialization @ "application/octet-stream" ???
          // Binary serialization
          val nodeType = InstanceData.getType(documentToSubmit.getRootElement)
          if (XMLConstants.XS_BASE64BINARY_QNAME == nodeType) {
            // TODO
            throw new XFormsSubmissionException(
              submission,
              "xf:submission: binary serialization with base64Binary type is not yet implemented.",
              "serializing instance"
            )
          } else {
            // Default to anyURI
            // TODO: PERFORMANCE: Must pass InputStream all the way to the submission instead of storing into byte[] in memory!

            // NOTE: We support a relative path, in which case the path is resolved as a service URL
            val resolvedURI =
              XFormsUtils.resolveServiceURL(
                submission.containingDocument,
                submission.getSubmissionElement,
                documentToSubmit.getRootElement.getStringValue,
                URLRewriter.REWRITE_MODE_ABSOLUTE
              )

            try {
              SerializationParameters(
                messageBody            = SubmissionUtils.readByteArray(submission.getModel, resolvedURI),
                queryString            = null,
                actualRequestMediatype = actualRequestMediatype(serialization)
              )
            } catch {
              case e: Exception ???
                throw new XFormsSubmissionException(
                  submission,
                  "xf:submission: binary serialization with anyURI type failed reading URL.",
                  "serializing instance"
                )
            }
          }
        case serialization @ ("text/html" | "application/xhtml+xml") ???
          // HTML or XHTML serialization
          try {
            val identity = TransformerUtils.getIdentityTransformer
            TransformerUtils.applyOutputProperties(
              identity,
              if (serialization == "text/html") "html" else "xhtml",
              p2.versionOpt.orNull,
              null,
              null,
              p2.encoding,
              p2.omitXmlDeclaration,
              p2.standaloneOpt map java.lang.Boolean.valueOf orNull,
              p2.indent,
              4
            )

            // TODO: use cdata-section-elements

            val os = new ByteArrayOutputStream
            identity.transform(new DocumentSource(documentToSubmit), new StreamResult(os))

            SerializationParameters(
              messageBody            = os.toByteArray,
              queryString            = null,
              actualRequestMediatype = actualRequestMediatype(serialization)
            )
          } catch {
            case e: Exception ???
              throw new XFormsSubmissionException(
                submission,
                e,
                "xf:submission: exception while serializing instance to HTML or XHTML.",
                "serializing instance"
              )
          }
        case serialization if ContentTypes.isTextOrJSONContentType(serialization) ???
          // Text serialization
          try {
            val identity = TransformerUtils.getIdentityTransformer
            TransformerUtils.applyOutputProperties(
              identity,
              "text",
              null,
              null,
              null,
              p2.encoding,
              true,
              false,
              false,
              0
            )
            val os = new ByteArrayOutputStream
            identity.transform(new DocumentSource(documentToSubmit), new StreamResult(os))

            SerializationParameters(
              messageBody            = os.toByteArray,
              queryString            = null,
              actualRequestMediatype = actualRequestMediatype(serialization)
            )
          } catch {
            case e: Exception ???
              throw new XFormsSubmissionException(
                submission,
                e,
                "xf:submission: exception while serializing instance to text.",
                "serializing instance"
              )
          }
        case serialization ???
          throw new XFormsSubmissionException(
            submission,
            s"xf:submission: invalid submission serialization requested: $serialization",
            "serializing instance"
          )
      }
    } else {
      SerializationParameters(
        queryString            = null,
        messageBody            = null,
        actualRequestMediatype = null
      )
    }
  }
}