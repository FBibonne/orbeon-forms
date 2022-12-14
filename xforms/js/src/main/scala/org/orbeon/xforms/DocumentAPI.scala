/**
  * Copyright (C) 2017 Orbeon, Inc.
  *
  * This program is free software; you can redistribute it and/or modify it under the terms of the
  * GNU Lesser General Public License as published by the Free Software Foundation; either version
  *  2.1 of the License, or (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  * See the GNU Lesser General Public License for more details.
  *
  * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
  */
package org.orbeon.xforms

import org.orbeon.oxf.util.StringUtils._
import org.scalajs.dom
import org.scalajs.dom.html

import scala.scalajs.js
import scala.scalajs.js.JSStringOps._
import scala.scalajs.js.annotation.{JSExportAll, JSExportTopLevel}
import scala.scalajs.js.{URIUtils, |}

@JSExportTopLevel("ORBEON.xforms.Document")
@JSExportAll
object DocumentAPI {

  import Private._

  // Dispatch an event
  // NOTE: This doesn't support all parameters.
  // Which should be deprecated, this or the other `dispatchEvent()`?
  def dispatchEvent(
    targetId     : String,
    eventName    : String,
    form         : js.UndefOr[html.Element] = js.undefined,
    bubbles      : js.UndefOr[Boolean]      = js.undefined,
    cancelable   : js.UndefOr[Boolean]      = js.undefined,
    incremental  : js.UndefOr[Boolean]      = js.undefined,
    ignoreErrors : js.UndefOr[Boolean]      = js.undefined
  ): Unit = {

    val eventObject  = new js.Object
    val eventDynamic = eventObject.asInstanceOf[js.Dynamic]

    eventDynamic.targetId  = targetId
    eventDynamic.eventName = eventName

    form         foreach (eventDynamic.form         = _)
    bubbles      foreach (eventDynamic.bubbles      = _)
    form         foreach (eventDynamic.form         = _)
    cancelable   foreach (eventDynamic.cancelable   = _)
    incremental  foreach (eventDynamic.incremental  = _)
    ignoreErrors foreach (eventDynamic.ignoreErrors = _)

    dispatchEvent(eventObject)
  }

  // Dispatch an event defined by an object
  // NOTE: Use the first XForms form on the page when no form is provided.
  // TODO: Can we type `eventObject`?
  def dispatchEvent(eventObject: js.Object): Unit = {

    val eventDynamic = eventObject.asInstanceOf[js.Dynamic]

    val (resolvedForm, adjustedTargetId) =
      adjustIdNamespace(
        eventDynamic.form.asInstanceOf[html.Element],
        eventDynamic.targetId.asInstanceOf[String]
      )

    eventDynamic.form     = resolvedForm
    eventDynamic.targetId = adjustedTargetId

    AjaxServer.fireEvents(
      js.Array(new AjaxServer.Event(eventDynamic)),
      incremental = eventDynamic.incremental.asInstanceOf[js.UndefOr[Boolean]].getOrElse(false)
    )
  }

  // Return the value of an XForms control
  def getValue(
    controlIdOrElem : String | html.Element,
    formElem        : js.UndefOr[html.Element] = js.undefined
  ): js.UndefOr[String] =
      Controls.getCurrentValue(findControlOrThrow(controlIdOrElem, formElem))

  // Set the value of an XForms control
  def setValue(
    controlIdOrElem : String | html.Element,
    newValue        : String | Double | Boolean,
    form            : js.UndefOr[html.Element] = js.undefined
  ): Unit = {

    val newStringValue = newValue.toString

    val control = findControlOrThrow(controlIdOrElem, form)

    require(
      ! $(control).is(".xforms-output, .xforms-upload"),
      s"Cannot set the value of an output or upload control for id `${control.id}`"
    )

    // Directly change the value in the UI without waiting for an Ajax response
    Controls.setCurrentValue(control, newStringValue)

    // And also fire server event
    val event = new AjaxServer.Event(
      new js.Object {
        val targetId  = control.id
        val eventName = "xxforms-value"
        val value     = newStringValue
      }
    )

    AjaxServer.fireEvents(js.Array(event), incremental = false)
  }

  def focus(
    controlIdOrElem : String | html.Element,
    form            : js.UndefOr[html.Element] = js.undefined
  ): Unit = {

    val control = findControlOrThrow(controlIdOrElem, form)

    Controls.setFocus(control.id)
    dispatchEvent(targetId = control.id, eventName = "xforms-focus")
  }

  // Whether the document is being reloaded
  def isReloading(): Boolean = Globals.isReloading

  // Return the current index of the repeat (equivalent to `index($repeatId)`)
  def getRepeatIndex(repeatId: String): String = Globals.repeatIndexes(repeatId)

  // Get a value stored in the hidden client-state input field
  def getFromClientState(formId: String, key: String): String =
    clientStateToMap(formId).get(key).orNull

  // Store a value into the hidden client-state input field
  def storeInClientState(formId: String, key: String, value: String | Double | Boolean): Unit =
    mapToClientState(formId, clientStateToMap(formId) + (key ??? value.toString))

  private object Private {

    def adjustIdNamespace(
      formElem : js.UndefOr[html.Element],
      targetId : String
    ): (html.Element, String) = {

      val form   = formElem getOrElse $(dom.document.forms).filter(".xforms-form")(0)
      val ns     = Globals.ns(form.id)

      // For backward compatibility, handle the case where the id is already prefixed.
      // This is not great as we don't know for sure whether the control starts with a namespace, e.g. `o0`,
      // `o1`, etc. It might be safer to disable the short namespaces feature because of this.
      form ??? (
        if (targetId.startsWith(ns))
          targetId
        else
          ns + targetId
      )
    }

    def findControlOrThrow(
      controlIdOrElem : String | html.Element,
      formElem        : js.UndefOr[html.Element]
    ): html.Element = {

      val (resolvedControlId, resolvedControlOpt) =
        (controlIdOrElem: Any) match {
          case givenControlId: String ???
            givenControlId ??? Option(dom.document.getElementById(adjustIdNamespace(formElem, givenControlId)._2))
          case givenElement: html.Element ???
            givenElement.id ??? Some(givenElement)
        }

      resolvedControlOpt match {
        case Some(resolvedControl: html.Element) if Controls.isInRepeatTemplate(resolvedControl) ???
          throw new IllegalArgumentException(s"Control is within a repeat template for id `$resolvedControlId`")
        case Some(resolvedControl: html.Element) ???
          resolvedControl
        case _ ???
          throw new IllegalArgumentException(s"Cannot find control for id `$resolvedControlId`")
      }
    }

    def clientStateToMap(formId: String): Map[String, String] =
      Globals.formClientState(formId).value.trimAllToOpt match {
        case Some(clientState) ???
          // Careful: JavaScript's `split` is different from Scala's `split`!
          clientState.jsSplit("&").to[List].sliding(2, 2) map {
            case List(key, value) ???
              key ??? URIUtils.decodeURIComponent(value)
          } toMap
        case None ???
          Map.empty
      }

    def mapToClientState(formId: String, map: Map[String, String]): Unit = {

      val stateString =
        map.iterator flatMap {
          case (key, value) ???
            Iterator(key, URIUtils.encodeURIComponent(value))
        } mkString "&"

      Globals.formClientState(formId).value = stateString
    }
  }
}
