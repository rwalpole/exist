
/* eXist Open Source Native XML Database
 * Copyright (C) 2001-03,  Wolfgang M. Meier (wolfgang@exist-db.org)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Library General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 * $Id$
 */

package org.exist.xquery;

import org.exist.dom.NodeSet;
import org.exist.xquery.util.ExpressionDumper;
import org.exist.xquery.value.BooleanValue;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.Type;

/**
 * Boolean operator "and".
 * 
 * @author Wolfgang <wolfgang@exist-db.org>
 */
public class OpAnd extends LogicalOp {

	public OpAnd(XQueryContext context) {
		super(context);
	}

	public Sequence eval(Sequence contextSequence, Item contextItem) throws XPathException {
        if (context.getProfiler().isEnabled()) {
            context.getProfiler().start(this);       
            context.getProfiler().message(this, Profiler.DEPENDENCIES, "DEPENDENCIES", Dependency.getDependenciesName(this.getDependencies()));
            if (contextSequence != null)
                context.getProfiler().message(this, Profiler.START_SEQUENCES, "CONTEXT SEQUENCE", contextSequence);
            if (contextItem != null)
                context.getProfiler().message(this, Profiler.START_SEQUENCES, "CONTEXT ITEM", contextItem.toSequence());
        }
        
		if (getLength() == 0)
			return Sequence.EMPTY_SEQUENCE;

		if (contextItem != null)
			contextSequence = contextItem.toSequence();
        
		Expression left = getLeft();
		Expression right = getRight();
		if(optimize) {
			NodeSet rl = left.eval(contextSequence, null).toNodeSet();
			rl = rl.getContextNodes(inPredicate);
			// TODO: optimize and return false if rl.getLength() == 0 ?
			NodeSet rr = right.eval(contextSequence, null).toNodeSet();
			rr = rr.getContextNodes(inPredicate);
			return rr.intersection(rl);
		} else {
			boolean ls = left.eval(contextSequence).effectiveBooleanValue();
			// immediately return false if the left operand is false
			if (!ls)
				return BooleanValue.FALSE;
			boolean rs = right.eval(contextSequence).effectiveBooleanValue();
			return ls && rs ? BooleanValue.TRUE : BooleanValue.FALSE;
		}
	}

	/* (non-Javadoc)
     * @see org.exist.xquery.PathExpr#dump(org.exist.xquery.util.ExpressionDumper)
     */
    public void dump(ExpressionDumper dumper) {
        if (getLength() == 0)
            return;
        getExpression(0).dump(dumper);
        for (int i = 1; i < getLength(); i++) {
            dumper.display(") and (");
            getExpression(i).dump(dumper);
        }
    }
    
    public String toString() {        
    	if (getLength() == 0)
            return "";
    	StringBuffer result = new StringBuffer();
    	result.append(getExpression(0).toString());
        for (int i = 1; i < getLength(); i++) {
        	result.append(") and (");
        	result.append(getExpression(i).toString());
        }
        return result.toString();
    }    
}
