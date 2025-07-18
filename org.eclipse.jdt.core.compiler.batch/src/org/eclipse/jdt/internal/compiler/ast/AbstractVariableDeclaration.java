/*******************************************************************************
 * Copyright (c) 2000, 2023 IBM Corporation and others.
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
 *     Stephan Herrmann - Contribution for
 *								Bug 400874 - [1.8][compiler] Inference infrastructure should evolve to meet JLS8 18.x (Part G of JSR335 spec)
 *								Bug 427438 - [1.8][compiler] NPE at org.eclipse.jdt.internal.compiler.ast.ConditionalExpression.generateCode(ConditionalExpression.java:280)
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.ast;

import java.util.List;
import org.eclipse.jdt.internal.compiler.codegen.AnnotationContext;
import org.eclipse.jdt.internal.compiler.flow.FlowContext;
import org.eclipse.jdt.internal.compiler.flow.FlowInfo;
import org.eclipse.jdt.internal.compiler.impl.JavaFeature;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.InferenceContext18;
import org.eclipse.jdt.internal.compiler.lookup.InvocationSite;
import org.eclipse.jdt.internal.compiler.lookup.LocalVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.Scope;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;

public abstract class AbstractVariableDeclaration extends Statement implements InvocationSite {
	public int declarationEnd;
	/**
	 * For local declarations (outside of for statement initialization) and field declarations,
	 * the declarationSourceEnd covers multiple locals if any.
	 * For local declarations inside for statement initialization, this is not the case.
	 */
	public int declarationSourceEnd;
	public int declarationSourceStart;
	public int hiddenVariableDepth; // used to diagnose hiding scenarii
	public Expression initialization;
	public int modifiers;
	public int modifiersSourceStart;
	public Annotation[] annotations;

	public char[] name;

	public TypeReference type;

	@Override
	public FlowInfo analyseCode(BlockScope currentScope, FlowContext flowContext, FlowInfo flowInfo) {
		return flowInfo;
	}

	public static final int FIELD = 1;
	public static final int INITIALIZER = 2;
	public static final int ENUM_CONSTANT = 3;
	public static final int LOCAL_VARIABLE = 4;
	public static final int PARAMETER = 5;
	public static final int TYPE_PARAMETER = 6;
	public static final int RECORD_COMPONENT = 7; // record


	/**
	 * @see org.eclipse.jdt.internal.compiler.lookup.InvocationSite#genericTypeArguments()
	 */
	@Override
	public TypeBinding[] genericTypeArguments() {
		return null;
	}

	/**
	 * Returns the constant kind of this variable declaration
	 */
	public abstract int getKind();

	@Override
	public InferenceContext18 freshInferenceContext(Scope scope) {
		return null;
	}

	public boolean isArgument() {
		return false;
	}

	@Override
	public boolean isSuperAccess() {
		return false;
	}

	@Override
	public boolean isTypeAccess() {
		return false;
	}

	public boolean isVarArgs() {
		return false;
	}

	@Override
	public StringBuilder printStatement(int indent, StringBuilder output) {
		printAsExpression(indent, output);
		switch(getKind()) {
			case ENUM_CONSTANT:
				return output.append(',');
			default:
				return output.append(';');
		}
	}

	public StringBuilder printAsExpression(int indent, StringBuilder output) {
		printIndent(indent, output);
		printModifiers(this.modifiers, output);
		if (this.annotations != null) {
			printAnnotations(this.annotations, output);
			output.append(' ');
		}

		if (this.type != null) {
			this.type.print(0, output).append(' ');
		}
		output.append(this.name);
		switch(getKind()) {
			case ENUM_CONSTANT:
				if (this.initialization != null) {
					this.initialization.printExpression(indent, output);
				}
				break;
			default:
				if (this.initialization != null) {
					output.append(" = "); //$NON-NLS-1$
					this.initialization.printExpression(indent, output);
				}
		}
		return output;
	}

	@Override
	public void resolve(BlockScope scope) {
		// do nothing by default (redefined for local variables)
	}

	@Override
	public void setActualReceiverType(ReferenceBinding receiverType) {
		// do nothing by default
	}

	@Override
	public void setDepth(int depth) {

		this.hiddenVariableDepth = depth;
	}

	@Override
	public void setFieldIndex(int depth) {
		// do nothing by default
	}

	/**
	 * Returns true if this variable is an unnamed variable (_) and false otherwise.
	 *
	 * @param scope used to determine source level
	 */
	public boolean isUnnamed(Scope scope) {
		return this.name.length == 1 && this.name[0] == '_' && JavaFeature.UNNAMMED_PATTERNS_AND_VARS.isSupported(scope.compilerOptions().sourceLevel, scope.compilerOptions().enablePreviewFeatures);
	}

	public boolean isVarTyped(Scope scope) {
		return this.type != null && this.type.isTypeNameVar(scope);
	}

	public void getAllAnnotationContexts(int targetType, List<AnnotationContext> allAnnotationContexts) {
		// do nothing
	}

	public void getAllAnnotationContexts(int targetType, int parameterIndex, List<AnnotationContext> allAnnotationContexts) {
		// do nothing
	}

	public void getAllAnnotationContexts(int targetType, LocalVariableBinding localVariable, List<AnnotationContext> allTypeAnnotationContexts) {
		// do nothing
	}

	public abstract Binding getBinding();

	public abstract void setBinding(Binding binding);

}
