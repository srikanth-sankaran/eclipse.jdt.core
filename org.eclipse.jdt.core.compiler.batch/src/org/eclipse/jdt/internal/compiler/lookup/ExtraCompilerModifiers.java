/*******************************************************************************
 * Copyright (c) 2000, 2020 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Stephan Herrmann <stephan@cs.tu-berlin.de> - Contributions for
 *								bug 328281 - visibility leaks not detected when analyzing unused field in private class
 *								bug 382353 - [1.8][compiler] Implementation property modifiers should be accepted on default methods.
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.lookup;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;

// TODO (philippe) these should be moved to tagbits
public interface ExtraCompilerModifiers { // modifier constant
	/**
	 * Bits that are depending upon ClassFileConstants (relying that classfiles only use the 16 lower bits).
	 * <p>
	 * Does <b>not</b> include {@link ClassFileConstants#AccDeprecated} and
	 * {@link ClassFileConstants#AccAnnotationDefault}!
	 * </p>
	 */
	final int AccJustFlag = 0xFFFF;// 16 lower bits

	final int AccDefaultMethod = ASTNode.Bit17;
	final int AccCompactConstructor = ASTNode.Bit24;
	// bit18 - use by ClassFileConstants.AccAnnotationDefault
	final int AccRestrictedAccess = ASTNode.Bit19;
	final int AccFromClassFile = ASTNode.Bit20;
	final int AccDefaultAbstract = ASTNode.Bit20;
	// bit21 - use by ClassFileConstants.AccDeprecated
	final int AccDeprecatedImplicitly = ASTNode.Bit22; // record whether deprecated itself or contained by a deprecated type
	final int AccAlternateModifierProblem = ASTNode.Bit23;
	final int AccSemicolonBody = ASTNode.Bit25;
	final int AccRecord = ASTNode.Bit25;
	final int AccUnresolved = ASTNode.Bit26;
	final int AccBlankFinal = ASTNode.Bit27; // for blank final variables
	final int AccIsDefaultConstructor = ASTNode.Bit27; // for default constructor
	final int AccNonSealed = ASTNode.Bit27; // for class/interface
	final int AccLocallyUsed = ASTNode.Bit28; // used to diagnose unused (a) private/local members or (b) members of private classes
											  // generally set when actual usage has been detected
											  // or, (b) when member of a private class is exposed via a non-private subclass
											  //     see https://bugs.eclipse.org/bugs/show_bug.cgi?id=328281
	final int AccVisibilityMASK = ClassFileConstants.AccPublic | ClassFileConstants.AccProtected | ClassFileConstants.AccPrivate;

	final int AccSealed = ASTNode.Bit29; // used for class/interface to set sealed
	final int AccOverriding = ASTNode.Bit29; // record fact a method overrides another one
	final int AccImplementing = ASTNode.Bit30; // record fact a method implements another one (it is concrete and overrides an abstract one)
	final int AccImplicitlyDeclared = ASTNode.Bit30; // used for implicitly declared classes
	final int AccGenericSignature = ASTNode.Bit31; // record fact a type/method/field involves generics in its signature (and need special signature attr)
	final int AccOutOfFlowScope = ASTNode.Bit29; // used to signal pattern binding variables not being live.
}
