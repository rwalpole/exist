/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2011 The eXist Project
 *  http://exist-db.org
 *  
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *  
 *  $Id$
 */
package org.exist.interpreter;

import org.exist.dom.QName;
import org.exist.xquery.value.SequenceType;

public interface FunctionSignature {

	public QName getName();

	public int getArgumentCount();

	public FunctionId getFunctionId();

	public SequenceType getReturnType();

	public void setReturnType(SequenceType type);

	public SequenceType[] getArgumentTypes();

	public void setArgumentTypes(SequenceType[] types);

	public String getDescription();

	public boolean isOverloaded();

	public boolean isDeprecated();

	public String getDeprecated();

	public void setDeprecated(String message);

}