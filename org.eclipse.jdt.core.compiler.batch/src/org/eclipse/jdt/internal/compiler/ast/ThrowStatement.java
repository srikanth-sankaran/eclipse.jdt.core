/*******************************************************************************
 * Copyright (c) 2000, 2014 IBM Corporation and others.
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
 *     Stephan Herrmann - Contributions for
 *								bug 359334 - Analysis for resource leak warnings does not consider exceptions as method exit points
 *								bug 368546 - [compiler][resource] Avoid remaining false positives found when compiling the Eclipse SDK
 *								bug 345305 - [compiler][null] Compiler misidentifies a case of "variable can only be null"
 *								Bug 429430 - [1.8] Lambdas and method reference infer wrong exception type with generics (RuntimeException instead of IOException)
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.ast;

import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.codegen.CodeStream;
import org.eclipse.jdt.internal.compiler.flow.FlowContext;
import org.eclipse.jdt.internal.compiler.flow.FlowInfo;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;

public class ThrowStatement extends Statement {

	public Expression exception;
	public TypeBinding exceptionType;

public ThrowStatement(Expression exception, int sourceStart, int sourceEnd) {
	this.exception = exception;
	this.sourceStart = sourceStart;
	this.sourceEnd = sourceEnd;
}

@Override
public FlowInfo analyseCode(BlockScope currentScope, FlowContext flowContext, FlowInfo flowInfo) {
	this.exception.analyseCode(currentScope, flowContext, flowInfo);
	this.exception.checkNPE(currentScope, flowContext, flowInfo);
	// need to check that exception thrown is actually caught somewhere
	flowContext.checkExceptionHandlers(this.exceptionType, this, flowInfo, currentScope);
	currentScope.checkUnclosedCloseables(flowInfo, flowContext, this, currentScope);
	flowContext.recordAbruptExit();
	return FlowInfo.DEAD_END;
}

/**
 * Throw code generation
 *
 * @param currentScope org.eclipse.jdt.internal.compiler.lookup.BlockScope
 * @param codeStream org.eclipse.jdt.internal.compiler.codegen.CodeStream
 */
@Override
public void generateCode(BlockScope currentScope, CodeStream codeStream) {
	if ((this.bits & ASTNode.IsReachable) == 0)
		return;
	int pc = codeStream.position;
	this.exception.generateCode(currentScope, codeStream, true);
	codeStream.athrow();
	codeStream.recordPositionsFrom(pc, this.sourceStart);
}

@Override
public StringBuilder printStatement(int indent, StringBuilder output) {
	printIndent(indent, output).append("throw "); //$NON-NLS-1$
	this.exception.printExpression(0, output);
	return output.append(';');
}

@Override
public void resolve(BlockScope scope) {
	this.exceptionType = this.exception.resolveType(scope);
	if (this.exceptionType != null && this.exceptionType.isValidBinding()) {
		if (this.exceptionType != TypeBinding.NULL && this.exceptionType.findSuperTypeOriginatingFrom(TypeIds.T_JavaLangThrowable, true) == null) {
			scope.problemReporter().cannotThrowType(this.exception, this.exceptionType);
		}
		this.exception.computeConversion(scope, this.exceptionType, this.exceptionType);
	}
}

@Override
public void traverse(ASTVisitor visitor, BlockScope blockScope) {
	if (visitor.visit(this, blockScope))
		this.exception.traverse(visitor, blockScope);
	visitor.endVisit(this, blockScope);
}

@Override
public boolean doesNotCompleteNormally() {
	return true;
}
}
