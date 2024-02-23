/*******************************************************************************
 * Copyright (c) 2022, 2023 IBM Corporation and others.
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
import org.eclipse.jdt.internal.compiler.codegen.BranchLabel;
import org.eclipse.jdt.internal.compiler.codegen.CodeStream;
import org.eclipse.jdt.internal.compiler.flow.FlowContext;
import org.eclipse.jdt.internal.compiler.flow.FlowInfo;
import org.eclipse.jdt.internal.compiler.impl.Constant;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.LocalVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;

public class GuardedPattern extends Pattern {

	public Pattern primaryPattern;
	public Expression condition;
	public int whenSourceStart = -1;

	public GuardedPattern(Pattern primaryPattern, Expression condition) {
		this.primaryPattern = primaryPattern;
		this.condition = condition;
		this.sourceStart = primaryPattern.sourceStart;
		this.sourceEnd = condition.sourceEnd;
	}

	@Override
	public LocalVariableBinding[] bindingsWhenTrue() {
		return LocalVariableBinding.merge(this.primaryPattern.bindingsWhenTrue(),
											this.condition.bindingsWhenTrue());
	}

	@Override
	public FlowInfo analyseCode(BlockScope currentScope, FlowContext flowContext, FlowInfo flowInfo) {
		flowInfo = this.primaryPattern.analyseCode(currentScope, flowContext, flowInfo);
		currentScope.methodScope().recordInitializationStates(flowInfo);
		FlowInfo mergedFlow = this.condition.analyseCode(currentScope, flowContext, flowInfo);
		mergedFlow = mergedFlow.safeInitsWhenTrue();
		currentScope.methodScope().recordInitializationStates(mergedFlow);
		return mergedFlow;
	}

	@Override
	public void generateCode(BlockScope currentScope, CodeStream codeStream, BranchLabel patternMatchLabel, BranchLabel matchFailLabel) {
		BranchLabel guardCheckLabel = new BranchLabel(codeStream);
		this.primaryPattern.generateCode(currentScope, codeStream, guardCheckLabel, matchFailLabel);
		guardCheckLabel.place();
		this.condition.generateOptimizedBoolean(currentScope, codeStream, null, matchFailLabel, true);
	}

	@Override
	public boolean matchFailurePossible() {
		return !isEffectivelyUnguarded() || this.primaryPattern.matchFailurePossible();
	}

	@Override
	public boolean isUnconditional(TypeBinding t) {
		return isEffectivelyUnguarded() && this.primaryPattern.isUnconditional(t);
	}

	@Override
	public boolean isEffectivelyUnguarded() {
		Constant cst = this.condition.optimizedBooleanConstant();
		return cst != null && cst != Constant.NotAConstant && cst.booleanValue() == true;
	}

	@Override
	public void setIsEitherOrPattern() {
		this.primaryPattern.setIsEitherOrPattern();
	}

	@Override
	public boolean coversType(TypeBinding type) {
		return isEffectivelyUnguarded() && this.primaryPattern.coversType(type);
	}

	@Override
	public boolean dominates(Pattern p) {
		return isEffectivelyUnguarded() && this.primaryPattern.dominates(p);
	}

	@Override
	public Pattern[] getAlternatives() {
		return this.primaryPattern.getAlternatives();
	}

	@Override
	public TypeBinding resolveType(BlockScope scope) {
		if (this.resolvedType != null || this.primaryPattern == null)
			return this.resolvedType;
		this.resolvedType = this.primaryPattern.resolveType(scope);

		this.condition.resolveTypeExpectingWithBindings(this.primaryPattern.bindingsWhenTrue(), scope, TypeBinding.BOOLEAN);
		Constant cst = this.condition.optimizedBooleanConstant();
		if (cst.typeID() == TypeIds.T_boolean && cst.booleanValue() == false) {
			scope.problemReporter().falseLiteralInGuard(this.condition);
		}

		if (!isEffectivelyUnguarded())
			this.primaryPattern.setIsEffectivelyGuarded();

		this.condition.traverse(new ASTVisitor() {
			@Override
			public boolean visit(
					SingleNameReference ref,
					BlockScope skope) {
				LocalVariableBinding local = ref.localVariableBinding();
				if (local != null) {
					ref.bits |= ASTNode.IsUsedInPatternGuard;
				}
				return false;
			}
			@Override
			public boolean visit(
					QualifiedNameReference ref,
					BlockScope skope) {
				if ((ref.bits & ASTNode.RestrictiveFlagMASK) == Binding.LOCAL) {
					ref.bits |= ASTNode.IsUsedInPatternGuard;
				}
				return false;
			}
		}, scope);
		return this.resolvedType = this.primaryPattern.resolvedType;
	}

	@Override
	public StringBuilder printExpression(int indent, StringBuilder output) {
		this.primaryPattern.print(indent, output).append(" when "); //$NON-NLS-1$
		return this.condition.print(indent, output);
	}

	@Override
	public void traverse(ASTVisitor visitor, BlockScope scope) {
		if (visitor.visit(this, scope)) {
			this.primaryPattern.traverse(visitor, scope);
			this.condition.traverse(visitor, scope);
		}
		visitor.endVisit(this, scope);
	}

	@Override
	protected boolean isApplicable(TypeBinding other, BlockScope scope) {
		return this.primaryPattern.isApplicable(other, scope);
	}
}