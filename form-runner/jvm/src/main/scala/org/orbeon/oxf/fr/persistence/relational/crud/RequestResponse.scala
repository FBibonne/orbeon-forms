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
package org.orbeon.oxf.fr.persistence.relational.crud

import org.orbeon.oxf.fr.persistence.relational.{Provider, _}
import org.orbeon.oxf.http.Headers
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.util.NetUtils

case class DataPart(isDraft: Boolean, documentId: String)

case class Request(
  provider : Provider,
  app      : String,
  form     : String,
  filename : Option[String],
  version  : Version,
  dataPart : Option[DataPart]
) {
  def forForm       = dataPart.isEmpty
  def forData       = dataPart.isDefined
  def forAttachment = filename.isDefined
}

trait RequestResponse {

  def tableName(request: Request): String =
    Seq(
      Some("orbeon_form"),
      request.forForm       option "_definition",
      request.forData       option "_data",
      request.forAttachment option "_attach"
    ).flatten.mkString

  def httpRequest = NetUtils.getExternalContext.getRequest
  def headerValue(name: String): Option[String] = httpRequest.getFirstHeader(name)

  def requestUsername : Option[String] = headerValue(Headers.OrbeonUsernameLower)
  def requestGroup: Option[String]     = headerValue(Headers.OrbeonGroupLower)
  def requestFlatView                  = headerValue("orbeon-create-flat-view").contains("true")

  val CrudFormPath = "/fr/service/([^/]+)/crud/([^/]+)/([^/]+)/form/([^/]+)".r
  val CrudDataPath = "/fr/service/([^/]+)/crud/([^/]+)/([^/]+)/(data|draft)/([^/]+)/([^/]+)".r

  def request: Request = {

    import Version._

    val version =
      Version(headerValue(OrbeonForDocumentIdLower), headerValue(OrbeonFormDefinitionVersionLower))

    httpRequest.getRequestPath match {
      case CrudFormPath(provider, app, form, filename) ???
        val file = if (filename == "form.xhtml") None else Some(filename)
        Request(Provider.providerFromToken(provider), app, form, file, version, None)
      case CrudDataPath(provider, app, form, dataOrDraft, documentId, filename) ???
        val file = if (filename == "data.xml") None else Some(filename)
        val dataPart = DataPart(dataOrDraft == "draft", documentId)
        Request(Provider.providerFromToken(provider), app, form, file, version, Some(dataPart))
    }
  }

  def httpResponse = NetUtils.getExternalContext.getResponse
}
