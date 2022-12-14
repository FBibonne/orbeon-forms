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
package org.orbeon.oxf.xforms.processor

import java.io._
import java.net.{URI, URLEncoder}

import org.orbeon.exception.OrbeonFormatter
import org.orbeon.oxf.externalcontext.ExternalContext.SessionScope
import org.orbeon.oxf.externalcontext.ExternalContext.SessionScope.Application
import org.orbeon.oxf.externalcontext.{ExternalContext, URLRewriter}
import org.orbeon.oxf.http.HttpMethod.GET
import org.orbeon.oxf.http.StatusCode
import org.orbeon.oxf.pipeline.api.PipelineContext
import org.orbeon.oxf.processor.{ProcessorImpl, ResourceServer}
import org.orbeon.oxf.util.IOUtils._
import org.orbeon.oxf.util.PathUtils._
import org.orbeon.oxf.util._
import org.orbeon.oxf.xforms.{AssetPath, Caches, Loggers, XFormsProperties}

import scala.util.Try
import scala.util.control.NonFatal

/**
  * Serve XForms engine JavaScript and CSS resources by combining them.
  *
  * NOTE: Should rename to XFormsAssetServer?
  */
class XFormsResourceServer extends ProcessorImpl with Logging {

  import org.orbeon.oxf.xforms.processor.XFormsResourceServer._

  override def start(pipelineContext: PipelineContext): Unit = {

    implicit val externalContext = NetUtils.getExternalContext
    val requestPath = externalContext.getRequest.getRequestPath

    if (requestPath.startsWith(DynamicResourcesPath))
      serveDynamicResource(requestPath)
    else
      serveCSSOrJavaScript(requestPath)
  }

  private def serveDynamicResource(requestPath: String)(implicit externalContext: ExternalContext): Unit = {

    val response = externalContext.getResponse

    findDynamicResource(requestPath) match {
      case Some(resource) ???

        val digestFromPath = filename(requestPath)

        // Found URL, stream it out

        // Set caching headers

        // NOTE: Algorithm is that XFOutputControl currently passes either -1 or the last modified of the
        // resource if "fast" to obtain last modified ("oxf:" or "file:"). Would be nice to do better: pass
        // whether resource is cacheable or not; here, when dereferencing the resource, we get the last
        // modified (Last-Modified header from HTTP even) and store it. Then we can handle conditional get.
        // This is some work though. Might have to proxy conditional GET as well. So for now we don't
        // handle conditional GET and produce a non-now last modified only in a few cases.

        response.setResourceCaching(resource.lastModified, 0)

        if (resource.size >= 0)
          response.setContentLength(resource.size.asInstanceOf[Int]) // Q: Why does this API (and Servlet counterpart) take an int?

        // TODO: for Safari, try forcing application/octet-stream
        // NOTE: IE 6/7 don't display a download box when detecting an HTML document (known IE bug)
        response.setContentType(resource.contentType)

        // File name visible by the user
        val rawFilename = resource.filenameOpt getOrElse digestFromPath

        def addExtensionIfNeeded(filename: String) =
          findExtension(filename) match {
            case Some(_) ???
              filename
            case None    ???
              Mediatypes.findExtensionForMediatype(resource.mediaType) map
              (filename + "." +)                                       getOrElse
              filename
          }

        val contentFilename = addExtensionIfNeeded(rawFilename)

        // Handle as attachment
        // TODO: filename should be encoded somehow, as 1) spaces don't work and 2) non-ISO-8859-1 won't work
        response.setHeader("Content-Disposition", "attachment; filename=" + URLEncoder.encode(contentFilename, "UTF-8"))

        // Copy stream out
        try {
          val cxr =
            Connection(
              method      = GET,
              url         = resource.uri,
              credentials = None,
              content     = None,
              headers     = resource.headers,
              loadState   = true,
              logBody     = false
            ).connect(
              saveState = true
            )

          // TODO: handle 404, etc. and set response parameters *after* we know that we have a successful response code.

          useAndClose(cxr.content.inputStream) { is ???
            useAndClose(response.getOutputStream) { os ???
              copyStream(is, os)
            }
          }
        } catch {
          case NonFatal(t) ??? warn("exception copying stream", Seq("throwable" ??? OrbeonFormatter.format(t)))
        }

      case None ???
        response.setStatus(StatusCode.NotFound)
    }
  }

  private def serveCSSOrJavaScript(requestPath: String)(implicit externalContext: ExternalContext): Unit = {

    val filenameFromPath = filename(requestPath)

    val isCSS = filenameFromPath endsWith ".css"
    val isJS  = filenameFromPath endsWith ".js"

    val response = externalContext.getResponse

    // Eliminate funny requests
    if (! isCSS && ! isJS && ! filenameFromPath.startsWith("orbeon-")) {
      response.setStatus(StatusCode.NotFound)
      return
    }

    val resources = {
      // New hash-based mechanism
      val resourcesHash = filenameFromPath.substring("orbeon-".length, filenameFromPath.lastIndexOf("."))
      val cacheElement = Caches.resourcesCache.get(resourcesHash)
      if (cacheElement ne null) {
        // Mapping found
        val resourcesStrings = cacheElement.getObjectValue.asInstanceOf[Array[String]].toList
        resourcesStrings map (r ??? AssetPath(r, hasMin = false))
      } else {
        // Not found, either because the hash is invalid, or because the cache lost the mapping
        response.setStatus(StatusCode.NotFound)
        return
      }
    }

    val isMinimal = false

    // Get last modified date
    val combinedLastModified = XFormsResourceRewriter.computeCombinedLastModified(resources, isMinimal)

    // Set Last-Modified, required for caching and conditional get
    if (URLRewriterUtils.isResourcesVersioned)
      // Use expiration far in the future
      response.setResourceCaching(combinedLastModified, System.currentTimeMillis + ResourceServer.ONE_YEAR_IN_MILLISECONDS)
    else
      // Use standard expiration policy
      response.setResourceCaching(combinedLastModified, 0)

    // Check If-Modified-Since and don't return content if condition is met
    if (! response.checkIfModifiedSince(externalContext.getRequest, combinedLastModified)) {
      response.setStatus(StatusCode.NotModified)
      return
    }

    response.setContentType(if (isCSS) "text/css; charset=UTF-8" else "application/x-javascript")

    // Namespace to use, must be None if empty
    def namespaceOpt = {
      def nsFromParameters = Option(externalContext.getRequest.getParameterMap.get(NamespaceParameter)) map (_(0).asInstanceOf[String])
      def nsFromContainer  = Some(response.getNamespacePrefix)

      nsFromParameters orElse nsFromContainer filter (_.nonEmpty)
    }

    def debugParameters = Seq("request path" ??? requestPath)

    if (XFormsProperties.isCacheCombinedResources) {

      // Caching requested
      XFormsResourceRewriter.cacheAssets(
        resources,
        requestPath,
        namespaceOpt,
        combinedLastModified,
        isCSS,
        isMinimal
      ) match {
        case Some(resourceFile) ???
          // Caching could take place, send out cached result
          debug("serving from cache ", debugParameters)
          useAndClose(response.getOutputStream) { os ???
            copyStream(new FileInputStream(resourceFile), os)
          }
        case None ???
          // Was unable to cache, just serve
          debug("caching requested but not possible, serving directly", debugParameters)
          XFormsResourceRewriter.generateAndClose(resources, namespaceOpt, response.getOutputStream, isCSS, isMinimal)
      }
    } else {
      // Should not cache, just serve
      debug("caching not requested, serving directly", debugParameters)
      XFormsResourceRewriter.generateAndClose(resources, namespaceOpt, response.getOutputStream, isCSS, isMinimal)
    }
  }
}

object XFormsResourceServer {

  val DynamicResourcesSessionKey = "orbeon.resources.dynamic."
  val DynamicResourcesPath       = "/xforms-server/dynamic/"
  val NamespaceParameter         = "ns"

  implicit def indentedLogger: IndentedLogger = Loggers.getIndentedLogger("resources")

  // Transform an URI accessible from the server into a URI accessible from the client.
  // The mapping expires with the session.
  def proxyURI(
    uri              : String,
    filename         : Option[String],
    contentType      : Option[String],
    lastModified     : Long,
    customHeaders    : Map[String, List[String]],
    headersToForward : Set[String],
    getHeader        : String ??? Option[List[String]])(implicit
    logger           : IndentedLogger
  ): String = {

    // Get session
    val externalContext = NetUtils.getExternalContext
    val session = externalContext.getRequest.getSession(true)

    require(session ne null, "proxyURI requires a session")

    // The resource URI may already be absolute, or may be relative to the server base. Make sure we work with
    // an absolute URI.
    val serviceURI = new URI(
      URLRewriterUtils.rewriteServiceURL(
        NetUtils.getExternalContext.getRequest,
        uri,
        URLRewriter.REWRITE_MODE_ABSOLUTE
      )
    )

    val outgoingHeaders =
      Connection.buildConnectionHeadersCapitalizedIfNeeded(
        scheme           = serviceURI.getScheme,
        hasCredentials   = false,
        customHeaders    = customHeaders,
        headersToForward = headersToForward,
        cookiesToForward = Connection.cookiesToForwardFromProperty,
        getHeader        = getHeader)(
        logger           = logger
      )

    val resource =
      DynamicResource(serviceURI, filename, contentType, lastModified, outgoingHeaders)

    // Store mapping into session
    session.setAttribute(DynamicResourcesSessionKey + resource.digest, resource, SessionScope.Application)

    DynamicResourcesPath + resource.digest
  }

  // For Java callers
  // 2015-09-21: Only used by FileSerializer.
  def jProxyURI(uri: String, contentType: String) =
    proxyURI(uri, None, Option(contentType), -1, Map(), Set(), _ ??? None)(null)

  // Try to remove a dynamic resource
  //
  // - do nothing if the session or resource are not found
  // - if `removeFile == true` and the resource maps to a file, try to remove the file
  // - remove the mapping from the session
  def tryToRemoveDynamicResource(
    requestPath     : String,
    removeFile      : Boolean
  ): Unit = {

    implicit val externalContext = NetUtils.getExternalContext

    findDynamicResource(requestPath) foreach { resource ???
      externalContext.getRequest.sessionOpt foreach { session ???

        if (removeFile)
          Try(new File(resource.uri)) foreach { file ???
            file.delete()
          }

        session.removeAttribute(DynamicResourcesSessionKey + resource.digest, SessionScope.Application)
      }
    }
  }

  private def findDynamicResource(
    requestPath     : String)(implicit
    externalContext : ExternalContext
  ): Option[DynamicResource] =
    externalContext.getRequest.sessionOpt flatMap { session ???
      val digestFromPath = filename(requestPath)
      val lookupKey      = DynamicResourcesSessionKey + digestFromPath

      session.getAttribute(lookupKey, SessionScope.Application) map (_.asInstanceOf[DynamicResource])
    }

  // For unit tests only (called from XSLT)
  def testGetResources(key: String)  =
    Option(Caches.resourcesCache.get(key)) map (_.getObjectValue.asInstanceOf[Array[String]]) orNull

  // Information about the resource, stored into the session
  case class DynamicResource(
    digest       : String,
    uri          : URI,
    filenameOpt  : Option[String],
    contentType  : String,
    mediaType    : String,
    size         : Long,
    lastModified : Long,
    headers      : Map[String, List[String]]
  )

  object DynamicResource {
    def apply(
      uri            : URI,
      filenameOpt    : Option[String],
      contentTypeOpt : Option[String],
      lastModified   : Long,
      headers        : Map[String, List[String]]
    ): DynamicResource = {

      // Create a digest, so that for a given URI we always get the same key
      //
      // 2015-09-02: Also digest header name/values, as they matter for example if a resource includes a
      // version number in a header. Headers will include headers explicitly set on `xf:output` with `xf:header`,
      // as well as `Accept`, `User-Agent`, and `Orbeon-Token`.
      // One question is what to do with `Orbeon-Token`. We could exclude it from the digest just in case, for
      // security reasons, but 1) `digest()` should be safe and 2) after a restart, if the session is restored,
      // the token will have changed anyway, so it's better if the digest does not include it as things won't
      // work anyway. On the other hand, unit tests fail if `Orbeon-Token` keeps changing. Not sure what's the best
      // here, but for now filtering out. In addition, that's what we used to do before.

      // Just digest a key produced with `toString`, since we know that tuples, `List` and `Map` produce
      // a reasonable output with `toString`.
      val key    = (uri, headers filterNot (_._1.equalsIgnoreCase("Orbeon-Token"))).toString
      val digest = SecureUtils.digestString(key, "hex")

      val mediatypeOpt        = contentTypeOpt flatMap ContentTypes.getContentTypeMediaType
      val incompleteMediatype = mediatypeOpt exists (_.endsWith("/*"))

      val contentType =
        contentTypeOpt filterNot (_ ??? incompleteMediatype) getOrElse "application/octet-stream"

      DynamicResource(
        digest       = digest,
        uri          = uri,
        filenameOpt  = filenameOpt,
        contentType  = contentType,
        mediaType    = ContentTypes.getContentTypeMediaType(contentType) getOrElse (throw new IllegalStateException),
        size         = -1,
        lastModified = lastModified,
        headers      = headers
      )
    }
  }

  private def filename(requestPath: String) =
    requestPath.substring(requestPath.lastIndexOf('/') + 1)
}