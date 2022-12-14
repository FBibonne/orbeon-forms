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
package org.orbeon.oxf.xforms.state

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.{Lock, ReentrantLock}

import org.orbeon.dom.Document
import org.orbeon.oxf.common.OXFException
import org.orbeon.oxf.externalcontext.ExternalContext
import org.orbeon.oxf.http.SessionExpiredException
import org.orbeon.oxf.logging.LifecycleLogger
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.oxf.util.{IndentedLogger, NetUtils}
import org.orbeon.oxf.xforms.{Loggers, XFormsConstants, XFormsContainingDocument, XFormsProperties}
import org.orbeon.oxf.util.CoreUtils._

object XFormsStateManager extends XFormsStateLifecycle {

  import Private._

  private val ReplicationEnabled = false

  def indentedLogger: IndentedLogger = Logger

  // For Java callers
  def instance = XFormsStateManager

  // Information about a document tied to the session.
  case class SessionDocument(uuid: String) {
    val lock = new ReentrantLock
  }

  // Keep public and static for unit tests and submission processor (called from XSLT)
  def removeSessionDocument(uuid: String): Unit = {
    val session = NetUtils.getSession(false)
    if (session ne null) {
      session.removeAttribute(getUUIDSessionKey(uuid), ExternalContext.SessionScope.Application)
    }
  }

  // Public for unit tests
  def getListenerSessionKey(uuid: String): String = XFormsStateManagerListenerStateKeyPrefix + uuid

  def getRequestUUID(request: Document): String = {
    val uuidElement = request.getRootElement.element(XFormsConstants.XXFORMS_UUID_QNAME)
    assert(uuidElement != null)
    uuidElement.getTextTrim.trimAllToNull
  }

  def getDocumentLock(uuid: String): Option[ReentrantLock] =
    getSessionDocument(uuid) map (_.lock)

  def getDocumentLockOrNull(uuid: String) =
    getDocumentLock(uuid).orNull

  /**
    * Return the delay for the session heartbeat event.
    *
    * @return delay in ms, or -1 is not applicable
    */
  def getHeartbeatDelay(containingDocument: XFormsContainingDocument, externalContext: ExternalContext): Long =
    if (containingDocument.getStaticState.isClientStateHandling || ! containingDocument.isSessionHeartbeat) {
      -1L
    } else {
        // 80% of session expiration time, in ms
      externalContext.getRequest.getSession(ForceSessionCreation).getMaxInactiveInterval * 800
    }

  /**
    * Called after the initial response is sent without error.
    *
    * Implementation: cache the document and/or store its initial state.
    */
  def afterInitialResponse(containingDocument: XFormsContainingDocument, template: AnnotatedTemplate): Unit =
    if (! containingDocument.isNoUpdates) {
      containingDocument.setTemplateIfNeeded(template)
      addDocumentToSession(containingDocument.getUUID)
      cacheOrStore(containingDocument, isInitialState = true)
    }

  /**
    * Called when the document is added to the cache.
    *
    * Implementation: set listener to remove the document from the cache when the session expires.
    */
  def onAddedToCache(uuid: String): Unit =
    addCacheSessionListener(uuid)

  /**
    * Called when the document is removed from the cache.
    *
    * Implementation: remove session listener.
    *
    * This is called indirectly when:
    *
    * - the session expires, which calls the session listener above to remove the document from cache
    * - upon takeValid()
    * - nobody else is supposed to call remove() or removeAll() on the cache
    */
  // WARNING: This can be called while another threads owns this document lock
  def onRemovedFromCache(uuid: String): Unit =
    removeCacheSessionListener(uuid)

  /**
    * Called when the document is evicted from cache.
    *
    * Implementation: remove session listener; if server state, store the document state.
    *
    * @param containingDocument containing document
    */
  // WARNING: This could have been called while another threads owns this document lock, but the cache now obtains
  // the lock on the document first and will not evict us if we have the lock. This means that this will be called
  // only if no thread is dealing with this document.
  // Remove session listener for cache
  def onEvictedFromCache(containingDocument: XFormsContainingDocument): Unit = {
    removeCacheSessionListener(containingDocument.getUUID)
    // Store document state
    if (containingDocument.getStaticState.isServerStateHandling)
      storeDocumentState(containingDocument, isInitialState = false)
  }

  /**
    * Return the locked document lock. Must be called before beforeUpdate().
    *
    * @param uuid incoming UUID
    * @return the document lock, already locked
    */
  def acquireDocumentLock(uuid: String, timeout: Long): Option[Lock] = {
    assert(uuid ne null)
    // Check that the session is associated with the requested UUID. This enforces the rule that an incoming request
    // for a given UUID must belong to the same session that created the document. If the session expires, the
    // key goes away as well, and the key won't be present. If we don't do this check, the XForms server might
    // handle requests for a given UUID within a separate session, therefore providing access to other sessions,
    // which is not desirable. Further, we now have a lock stored in the session.
    val lock =
      XFormsStateManager.getDocumentLock(uuid) getOrElse
        (throw SessionExpiredException("Unknown form document requested."))

    // Lock document for at most the max retry delay plus an increment
    try {
      lock.tryLock(timeout, TimeUnit.MILLISECONDS) option lock
    } catch {
      case e: InterruptedException ???
        throw new OXFException(e)
    }
  }

  // Release the given document lock. Must be called after afterUpdate() in a finally block.
  def releaseDocumentLock(lock: Lock): Unit =
    lock.unlock()

  /**
    * Called before an incoming update.
    *
    * If found in cache, document is removed from cache.
    *
    * @return document, either from cache or from state information
    */
  def beforeUpdate(parameters: RequestParameters): XFormsContainingDocument =
    findOrRestoreDocument(parameters, isInitialState = false, disableUpdates = false)

  /**
    * Called after an update.
    *
    * @param keepDocument whether to keep the document around
    */
  def afterUpdate(containingDocument: XFormsContainingDocument, keepDocument: Boolean): Unit = {
    if (keepDocument) {
      // Re-add document to the cache
      Logger.logDebug(LogType, "Keeping document in cache.")
      cacheOrStore(containingDocument, isInitialState = false)
    } else {
      // Don't re-add document to the cache
      Logger.logDebug(LogType, "Not keeping document in cache following error.")
      // Remove all information about this document from the session
      val uuid = containingDocument.getUUID
      removeCacheSessionListener(uuid)
      XFormsStateManager.removeSessionDocument(uuid)
    }
  }

  /**
    * Find or restore a document based on an incoming request.
    *
    * Implementation: try cache first, then restore from store if not found.
    *
    * If found in cache, document is removed from cache.
    *
    * @param isInitialState whether to return the initial state, otherwise return the current state
    * @param disableUpdates whether to disable updates (for recreating initial document upon browser back)
    * @return document, either from cache or from state information
    */
  def findOrRestoreDocument(
    parameters     : RequestParameters,
    isInitialState : Boolean,
    disableUpdates : Boolean
  ): XFormsContainingDocument =
    if (! isInitialState) {
      // Try cache first unless the initial state is requested
      if (XFormsProperties.isCacheDocument) {
        // Try to find the document in cache using the UUID
        // NOTE: If the document has cache.document="false", then it simply won't be found in the cache, but
        // we can't know that the property is set to false before trying.

        def newerSequenceNumberInStore(cachedDocument: XFormsContainingDocument) =
          ReplicationEnabled && (EhcacheStateStore.findSequence(parameters.uuid) exists (_ > cachedDocument.getSequence))

        XFormsDocumentCache.take(parameters.uuid) match {
          case Some(cachedDocument) if newerSequenceNumberInStore(cachedDocument)  ???
            Logger.logDebug(LogType, "Document cache enabled. Document from cache has out of date sequence number. Retrieving state from store.")
            XFormsDocumentCache.remove(parameters.uuid)
            createDocumentFromStore(parameters, isInitialState, disableUpdates)
          case Some(cachedDocument) ???
            // Found in cache
            Logger.logDebug(LogType, "Document cache enabled. Returning document from cache.")
            cachedDocument
          case None ???
            Logger.logDebug(LogType, "Document cache enabled. Document not found in cache. Retrieving state from store.")
            createDocumentFromStore(parameters, isInitialState, disableUpdates)
        }
      } else {
        Logger.logDebug(LogType, "Document cache disabled. Retrieving state from store.")
        createDocumentFromStore(parameters, isInitialState, disableUpdates)
      }
    } else {
      Logger.logDebug(LogType, "Initial document state requested. Retrieving state from store.")
      createDocumentFromStore(parameters, isInitialState, disableUpdates)
    }

  // Return the static state string to send to the client in the HTML page.
  def getClientEncodedStaticState(containingDocument: XFormsContainingDocument): String =
    if (containingDocument.getStaticState.isServerStateHandling) {
      // No state to return
      null
    } else {
      // Return full encoded state
      containingDocument.getStaticState.encodedState
    }

  // Return the dynamic state string to send to the client in the HTML page.
  def getClientEncodedDynamicState(containingDocument: XFormsContainingDocument): Option[String] =
    containingDocument.getStaticState.isClientStateHandling option
      DynamicState.encodeDocumentToString(containingDocument, XFormsProperties.isGZIPState, isForceEncryption = true)

  /**
    * Called before sending an update response.
    *
    * Implementation: update the document's change sequence.
    *
    * @param ignoreSequence     whether to ignore the sequence number
    */
  def beforeUpdateResponse(containingDocument: XFormsContainingDocument, ignoreSequence: Boolean): Unit = {
    if (containingDocument.isDirtySinceLastRequest) {
      Logger.logDebug(LogType, "Document is dirty. Generating new dynamic state.")
    } else {
      // The document is not dirty: no real encoding takes place here
      Logger.logDebug(LogType, "Document is not dirty. Keep existing dynamic state.")
    }
    // Tell the document to update its state
    if (! ignoreSequence)
      containingDocument.updateChangeSequence()
  }

  /**
    * Called after sending a successful update response.
    *
    * Implementation: cache the document and/or store its current state.
    */
  def afterUpdateResponse(containingDocument: XFormsContainingDocument): Unit =
    // Notify document that we are done sending the response
    containingDocument.afterUpdateResponse()

  private object Private {

    // Ideally we wouldn't want to force session creation, but it's hard to implement the more elaborate expiration
    // strategy without session.
    val ForceSessionCreation = true

    val LogType = "state manager"
    val Logger = Loggers.getIndentedLogger("state")

    val XFormsStateManagerUuidKeyPrefix          = "oxf.xforms.state.manager.uuid-key."
    val XFormsStateManagerListenerStateKeyPrefix = "oxf.xforms.state.manager.session-listeners-key."

    def addDocumentToSession(uuid: String): Unit = {
      val session = NetUtils.getSession(ForceSessionCreation)
      session.setAttribute(getUUIDSessionKey(uuid), SessionDocument(uuid), ExternalContext.SessionScope.Application)
    }

    def getSessionDocument(uuid: String): Option[SessionDocument] =
      Option(NetUtils.getSession(false)) flatMap { session ???
        session.getAttribute(getUUIDSessionKey(uuid), ExternalContext.SessionScope.Application)
      } collect {
        case value: SessionDocument ??? value
      }

    def getUUIDSessionKey(uuid: String) =
      XFormsStateManagerUuidKeyPrefix + uuid

    // Tricky: if onRemove() is called upon session expiration, there might not be an ExternalContext. But it's fine,
    // because the session goes away -> all of its attributes go away so we don't have to remove them below.
    def removeCacheSessionListener(uuid: String): Unit = {
      val session = NetUtils.getSession(ForceSessionCreation)
      if (session ne null) {
        val listenerSessionKey = XFormsStateManager.getListenerSessionKey(uuid)

        session.getAttribute(listenerSessionKey) match {
          case Some(listener: ExternalContext.SessionListener) ???
            session.removeListener(listener)
            // Forget, in session, mapping (UUID -> session listener)
            session.removeAttribute(listenerSessionKey)
          case _ ???
        }
      }
    }

    def cacheOrStore(containingDocument: XFormsContainingDocument, isInitialState: Boolean): Unit = {

      if (containingDocument.getStaticState.isCacheDocument) {
        // Cache the document
        Logger.logDebug(LogType, "Document cache enabled. Putting document in cache.")
        XFormsDocumentCache.put(containingDocument)
        if ((isInitialState || ReplicationEnabled) && containingDocument.getStaticState.isServerStateHandling) {
          // Also store document state (used by browser soft reload, browser back and <xf:reset>)
          Logger.logDebug(LogType, "Storing initial document state.")
          storeDocumentState(containingDocument, isInitialState)
        }
      } else if (containingDocument.getStaticState.isServerStateHandling) {
        // Directly store the document state
        Logger.logDebug(LogType, "Document cache disabled. Storing initial document state.")
        storeDocumentState(containingDocument, isInitialState)
      }

      LifecycleLogger.eventAssumingRequest(
        "xforms",
        "after cacheOrStore",
        List(
          "document cache current size" ??? XFormsDocumentCache.getCurrentSize.toString,
          "document cache max size"     ??? XFormsDocumentCache.getMaxSize.toString
        )
      )
    }

    def createDocumentFromStore(
      parameters     : RequestParameters,
      isInitialState : Boolean,
      disableUpdates : Boolean
    ): XFormsContainingDocument = {

      val isServerState = parameters.encodedClientStaticStateOpt.isEmpty

      val xformsState =
        parameters.encodedClientDynamicStateOpt match {
          case None ???

            assert(isServerState)

            // State must be found by UUID in the store
            val externalContext = NetUtils.getExternalContext

            if (Logger.isDebugEnabled)
              Logger.logDebug(
                LogType,
                "Getting document state from store.",
                "current cache size", XFormsDocumentCache.getCurrentSize.toString,
                "current store size", EhcacheStateStore.getCurrentSize.toString,
                "max store size", EhcacheStateStore.getMaxSize.toString
              )

            val session = externalContext.getRequest.getSession(ForceSessionCreation)
            EhcacheStateStore.findState(session, parameters.uuid, isInitialState) getOrElse {
              // 2014-11-12: This means that 1. We had a valid incoming session and 2. we obtained a lock on the
              // document, yet we didn't find it. This means that somehow state was not placed into or expired from
              // the state store.
              throw SessionExpiredException("Unable to retrieve XForms engine state. Unable to process incoming request.")
            }
          case Some(encodedClientDynamicState) ???
            // State comes directly with request

            assert(! isServerState)

            XFormsState(None, parameters.encodedClientStaticStateOpt, Some(DynamicState(encodedClientDynamicState)))
        }

      // Create document
      new XFormsContainingDocument(xformsState, disableUpdates, ! isServerState) ensuring { document ???
        (isServerState && document.getStaticState.isServerStateHandling) ||
          document.getStaticState.isClientStateHandling
      }
    }

    def addCacheSessionListener(uuid: String): Unit = {
      val session = NetUtils.getSession(ForceSessionCreation)
      val listenerSessionKey = XFormsStateManager.getListenerSessionKey(uuid)

      if (session.getAttribute(listenerSessionKey, ExternalContext.SessionScope.Application).isEmpty) {
        // Remove from cache when session expires
        val listener = new ExternalContext.SessionListener {
          def sessionDestroyed(): Unit = {
            Logger.logDebug(LogType, "Removing document from cache following session expiration.")
            // NOTE: This will call onRemoved() on the document, and onRemovedFromCache() on XFormsStateManager
            XFormsDocumentCache.remove(uuid)
          }
        }
        // Add listener
        try {
          session.addListener(listener)
        } catch {
          case e: IllegalStateException ???
            Logger.logInfo(LogType, s"Unable to add session listener: ${e.getMessage}")
            XFormsDocumentCache.remove(uuid) // remove immediately
            throw e
        }
        // Remember, in session, mapping (UUID -> session listener)
        session.setAttribute(listenerSessionKey, listener)
      }
    }

    def storeDocumentState(containingDocument: XFormsContainingDocument, isInitialState: Boolean): Unit = {
      require(containingDocument.getStaticState.isServerStateHandling)
      EhcacheStateStore.storeDocumentState(
        containingDocument,
        NetUtils.getExternalContext.getRequest.getSession(ForceSessionCreation),
        isInitialState
      )
    }
  }
}