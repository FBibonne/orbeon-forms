/**
 *  Copyright (C) 2007 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.function.xxforms;

import org.orbeon.oxf.xforms.function.XFormsFunction;
import org.orbeon.oxf.xforms.function.Index;
import org.orbeon.oxf.xforms.XFormsUtils;
import org.orbeon.oxf.xforms.XFormsElementContext;
import org.orbeon.oxf.xforms.XFormsModel;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.pipeline.StaticExternalContext;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.saxon.expr.Expression;
import org.orbeon.saxon.expr.StaticContext;
import org.orbeon.saxon.expr.XPathContext;
import org.orbeon.saxon.om.Item;
import org.orbeon.saxon.trans.XPathException;
import org.orbeon.saxon.value.IntegerValue;

/**
 * xxforms:index() function. Behaves like the standard XForms index() function, except the repeat id is optional. When
 * it is not specified, the function returns the id of the closest enclosing repeat.
 */
public class XXFormsIndex extends Index {

    /**
    * preEvaluate: this method suppresses compile-time evaluation by doing nothing
    * (because the value of the expression depends on the runtime context)
    */
    public Expression preEvaluate(StaticContext env) {
        return this;
    }

    public Item evaluateItem(XPathContext xpathContext) throws XPathException {

        final Expression repeatIdExpression = (argument == null || argument.length == 0) ? null : argument[0];
        final String repeatId = (repeatIdExpression == null) ? null : XFormsUtils.namespaceId(getXFormsContainingDocument(), repeatIdExpression.evaluateAsString(xpathContext));

        if (repeatId == null) {
            // Find closest enclosing id
            return findIndexForRepeatId(getXFormsControls().getEnclosingRepeatId());
        } else {
            return super.evaluateItem(xpathContext);
        }
    }
}
