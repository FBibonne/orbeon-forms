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
package org.orbeon.oxf.test

import org.junit.After
import org.orbeon.dom
import org.orbeon.oxf.pipeline.InitUtils
import org.orbeon.oxf.processor.ProcessorUtils
import org.orbeon.oxf.xforms.state.AnnotatedTemplate
import org.orbeon.oxf.xforms.{XFormsContainingDocument, XFormsStaticStateImpl}

abstract class DocumentTestBase extends ResourceManagerTestBase with XFormsSupport with XMLSupport {

  // FIXME: change to avoid global document
  private var _document: XFormsContainingDocument = _
  def document = _document

  def setupDocument(documentURL: String): XFormsContainingDocument =
    setupDocument(ProcessorUtils.createDocumentFromURL(documentURL, null))

  def setupDocument(xhtml: dom.Document): XFormsContainingDocument = {
    ResourceManagerTestBase.staticSetup()

    val (template, staticState) = XFormsStaticStateImpl.createFromDocument(xhtml)
    val doc = new XFormsContainingDocument(staticState, null, null, true)

    doc.setTemplateIfNeeded(AnnotatedTemplate(template))
    doc.afterInitialResponse()
    doc.beforeExternalEvents(null)

    _document = doc

    doc
  }

  def setupDocument(doc: XFormsContainingDocument): Unit = {

    doc.afterInitialResponse()
    doc.beforeExternalEvents(null)

    _document = doc
  }

  @After def disposeDocument(): Unit = {
    if (_document ne null) {
      val doc = _document
      _document = null
      disposeDocument(doc)
    }
  }

  def disposeDocument(doc: XFormsContainingDocument): Unit = {
    doc.afterExternalEvents()
    doc.afterUpdateResponse()
  }

  def withXFormsDocument[T](xhtml: dom.Document)(thunk: XFormsContainingDocument ??? T): T = {
    InitUtils.withPipelineContext { pipelineContext ???
      ResourceManagerTestBase.setExternalContext(pipelineContext)
      val doc = setupDocument(xhtml)
      try {
        thunk(doc)
      } finally {
        disposeDocument(doc)
      }
    }
  }
}