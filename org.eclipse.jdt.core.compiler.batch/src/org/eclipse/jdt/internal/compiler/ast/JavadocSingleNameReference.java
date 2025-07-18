/*******************************************************************************
 * Copyright (c) 2000, 2009 IBM Corporation and others.
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
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.ast;

import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.LocalVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodScope;
import org.eclipse.jdt.internal.compiler.lookup.TagBits;

public class JavadocSingleNameReference extends SingleNameReference {

	public int tagSourceStart, tagSourceEnd;

	public JavadocSingleNameReference(char[] source, long pos, int tagStart, int tagEnd) {
		super(source, pos);
		this.tagSourceStart = tagStart;
		this.tagSourceEnd = tagEnd;
		this.bits |= InsideJavadoc;
	}

	public void resolve(ClassScope scope) {
		TypeDeclaration type = scope.referenceContext;
		if (type != null && type.isRecord()) {
			FieldBinding field = type.binding.getField(this.token, false);
			if (field != null && field.isValidBinding()) {
				this.binding = field;
				return;
			}
			if (scope.compilerOptions().reportUnusedParameterIncludeDocCommentReference) {
				try {
					scope.problemReporter().javadocUndeclaredParamTagName(this.token, this.sourceStart, this.sourceEnd, type.modifiers);
				}
				catch (Exception e) {
					scope.problemReporter().javadocUndeclaredParamTagName(this.token, this.sourceStart, this.sourceEnd, -1);
				}
			}
		}
	}

	@Override
	public void resolve(BlockScope scope) {
		resolve(scope, true, scope.compilerOptions().reportUnusedParameterIncludeDocCommentReference);
	}

	/**
	 * Resolve without warnings
	 */
	public void resolve(BlockScope scope, boolean warn, boolean considerParamRefAsUsage) {

		LocalVariableBinding variableBinding = scope.findVariable(this.token);
		if (variableBinding != null && variableBinding.isValidBinding() && ((variableBinding.tagBits & TagBits.IsArgument) != 0)) {
			this.binding = variableBinding;
			if (considerParamRefAsUsage) {
				variableBinding.useFlag = LocalVariableBinding.USED;
			}
			return;
		}
		if (warn) {
			try {
				MethodScope methScope = (MethodScope) scope;
				scope.problemReporter().javadocUndeclaredParamTagName(this.token, this.sourceStart, this.sourceEnd, methScope.referenceMethod().modifiers);
			}
			catch (Exception e) {
				scope.problemReporter().javadocUndeclaredParamTagName(this.token, this.sourceStart, this.sourceEnd, -1);
			}
		}
	}

	/* (non-Javadoc)
	 * Redefine to capture javadoc specific signatures
	 * @see org.eclipse.jdt.internal.compiler.ast.ASTNode#traverse(org.eclipse.jdt.internal.compiler.ASTVisitor, org.eclipse.jdt.internal.compiler.lookup.BlockScope)
	 */
	@Override
	public void traverse(ASTVisitor visitor, BlockScope scope) {
		visitor.visit(this, scope);
		visitor.endVisit(this, scope);
	}
	/* (non-Javadoc)
	 * Redefine to capture javadoc specific signatures
	 * @see org.eclipse.jdt.internal.compiler.ast.ASTNode#traverse(org.eclipse.jdt.internal.compiler.ASTVisitor, org.eclipse.jdt.internal.compiler.lookup.BlockScope)
	 */
	@Override
	public void traverse(ASTVisitor visitor, ClassScope scope) {
		visitor.visit(this, scope);
		visitor.endVisit(this, scope);
	}
}
