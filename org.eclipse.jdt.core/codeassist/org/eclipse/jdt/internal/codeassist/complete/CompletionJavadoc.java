/*******************************************************************************
 * Copyright (c) 2000, 2013 IBM Corporation and others.
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
package org.eclipse.jdt.internal.codeassist.complete;

import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.Javadoc;
import org.eclipse.jdt.internal.compiler.ast.JavadocSingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.JavadocSingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.RecordComponent;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeParameter;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope;
import org.eclipse.jdt.internal.compiler.lookup.MethodScope;
import org.eclipse.jdt.internal.compiler.lookup.Scope;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.VariableBinding;

/**
 * Node representing a Javadoc comment including code selection.
 */
public class CompletionJavadoc extends Javadoc {

	Expression completionNode;

	public CompletionJavadoc(int sourceStart, int sourceEnd) {
		super(sourceStart, sourceEnd);
	}

	/**
	 * @return Returns the completionNode.
	 */
	public Expression getCompletionNode() {
		return this.completionNode;
	}

	/**
	 * Resolve selected node if not null and throw exception to let clients know
	 * that it has been found.
	 */
	private void internalResolve(Scope scope) {
		if (this.completionNode != null) {
			if (this.completionNode instanceof CompletionOnJavadocTag) {
				((CompletionOnJavadocTag)this.completionNode).filterPossibleTags(scope);
			} else {
				boolean resolve = true;
				if (this.completionNode instanceof CompletionOnJavadocParamNameReference) {
					resolve = ((CompletionOnJavadocParamNameReference)this.completionNode).token != null;
				} else if (this.completionNode instanceof CompletionOnJavadocTypeParamReference) {
					resolve = ((CompletionOnJavadocTypeParamReference)this.completionNode).token != null;
				}
				if (resolve) {
					switch (scope.kind) {
						case Scope.CLASS_SCOPE:
							if (scope.referenceContext() instanceof TypeDeclaration type && type.isRecord()) {
								this.completionNode.resolveType(type.initializerScope);
							}
							else {
								this.completionNode.resolveType((ClassScope)scope);
							}
							break;
						case Scope.METHOD_SCOPE:
							this.completionNode.resolveType((MethodScope) scope);
							break;
					}
				}
				if (this.completionNode instanceof CompletionOnJavadocParamNameReference) {
					CompletionOnJavadocParamNameReference paramNameReference = (CompletionOnJavadocParamNameReference) this.completionNode;
					if (scope.kind == Scope.METHOD_SCOPE) {
						paramNameReference.missingParams = missingParamTags(paramNameReference.binding, (MethodScope)scope);
					} else if (scope.kind == Scope.CLASS_SCOPE) {
						paramNameReference.missingParams = missingRecordComponentNames(paramNameReference.binding, (ClassScope)scope);
					}
					if (paramNameReference.token == null || paramNameReference.token.length == 0) {
						paramNameReference.missingTypeParams = missingTypeParameterTags(paramNameReference.binding, scope);
					}
				} else if (this.completionNode instanceof CompletionOnJavadocTypeParamReference) {
					CompletionOnJavadocTypeParamReference typeParamReference = (CompletionOnJavadocTypeParamReference) this.completionNode;
					typeParamReference.missingParams = missingTypeParameterTags(typeParamReference.resolvedType, scope);
				}
			}
			Binding qualifiedBinding = null;
			CompletionOnJavadocQualifiedTypeReference completionOnJavadocQualifiedTypeReference = null;
			if (this.completionNode instanceof CompletionOnJavadocModuleReference) {
				CompletionOnJavadocModuleReference modRef = (CompletionOnJavadocModuleReference) this.completionNode;
				TypeReference tRef= modRef.getTypeReference();
				if (tRef instanceof CompletionOnJavadocQualifiedTypeReference) {
					completionOnJavadocQualifiedTypeReference = (CompletionOnJavadocQualifiedTypeReference) tRef;
				}
			}
			if (this.completionNode instanceof CompletionOnJavadocQualifiedTypeReference) {
				completionOnJavadocQualifiedTypeReference = (CompletionOnJavadocQualifiedTypeReference) this.completionNode;
			}
			if (completionOnJavadocQualifiedTypeReference != null) {
				if (completionOnJavadocQualifiedTypeReference.packageBinding == null) {
					qualifiedBinding = completionOnJavadocQualifiedTypeReference.resolvedType;
				} else {
					qualifiedBinding = completionOnJavadocQualifiedTypeReference.packageBinding;
				}
			} else if (this.completionNode instanceof CompletionOnJavadocMessageSend) {
				CompletionOnJavadocMessageSend msg = (CompletionOnJavadocMessageSend) this.completionNode;
				if (!msg.receiver.isThis()) qualifiedBinding = msg.receiver.resolvedType;
			} else if (this.completionNode instanceof CompletionOnJavadocAllocationExpression) {
				CompletionOnJavadocAllocationExpression alloc = (CompletionOnJavadocAllocationExpression) this.completionNode;
				qualifiedBinding = alloc.type.resolvedType;
			}
			throw new CompletionNodeFound(this.completionNode, qualifiedBinding, scope);
		}
	}

	@Override
	public StringBuilder print(int indent, StringBuilder output) {
		printIndent(indent, output).append("/**\n"); //$NON-NLS-1$
		boolean nodePrinted = false;
		if (this.paramReferences != null) {
			for (JavadocSingleNameReference ref : this.paramReferences) {
				printIndent(indent, output).append(" * @param "); //$NON-NLS-1$
				ref.print(indent, output).append('\n');
				if (!nodePrinted && this.completionNode != null) {
					nodePrinted =  this.completionNode == ref;
				}
			}
		}
		if (this.paramTypeParameters != null) {
			for (JavadocSingleTypeReference paramTypeParameter : this.paramTypeParameters) {
				printIndent(indent, output).append(" * @param <"); //$NON-NLS-1$
				paramTypeParameter.print(indent, output).append(">\n"); //$NON-NLS-1$
				if (!nodePrinted && this.completionNode != null) {
					nodePrinted =  this.completionNode == paramTypeParameter;
				}
			}
		}
		if (this.returnStatement != null) {
			printIndent(indent, output).append(" * @"); //$NON-NLS-1$
			this.returnStatement.print(indent, output).append('\n');
		}
		if (this.exceptionReferences != null) {
			for (TypeReference ref : this.exceptionReferences) {
				printIndent(indent, output).append(" * @throws "); //$NON-NLS-1$
				ref.print(indent, output).append('\n');
				if (!nodePrinted && this.completionNode != null) {
					nodePrinted =  this.completionNode == ref;
				}
			}
		}
		if (this.seeReferences != null) {
			for (Expression ref : this.seeReferences) {
				printIndent(indent, output).append(" * @see "); //$NON-NLS-1$
				ref.print(indent, output).append('\n');
				if (!nodePrinted && this.completionNode != null) {
					nodePrinted =  this.completionNode == ref;
				}
			}
		}
		if (!nodePrinted && this.completionNode != null) {
			printIndent(indent, output).append(" * "); //$NON-NLS-1$
			this.completionNode.print(indent, output).append('\n');
		}
		printIndent(indent, output).append(" */\n"); //$NON-NLS-1$
		return output;
	}

	/**
	 * Resolve completion node if not null and throw exception to let clients know
	 * that it has been found.
	 */
	@Override
	public void resolve(ClassScope scope) {
		super.resolve(scope);
		internalResolve(scope);
	}

	/**
	 * Resolve completion node if not null and throw exception to let clients know
	 * that it has been found.
	 */
	@Override
	public void resolve(CompilationUnitScope scope) {
		internalResolve(scope);
	}

	/**
	 * Resolve completion node if not null and throw exception to let clients know
	 * that it has been found.
	 */
	@Override
	public void resolve(MethodScope scope) {
		super.resolve(scope);
		internalResolve(scope);
	}

	/*
	 * Look for missing record component @param tags
	 */
	private char[][] missingRecordComponentNames(Binding paramNameRefBinding, ClassScope scope) {
		TypeDeclaration type = scope.referenceContext;
		if (type == null || !type.isRecord()) {
			return null;
		}
		// Verify if there's some possible param tag
		RecordComponent[] components = type.recordComponents;
		int componentSize = components == null ? 0 : components.length;
		if (componentSize == 0) return null;
		int paramTagsSize = this.paramReferences == null ? 0 : this.paramReferences.length;

		char[][] missingComponents = new char[componentSize][];
		if (paramTagsSize == 0) {
			for (int i = 0; i < componentSize; i++) {
				missingComponents[i] = components[i].name;
			}
			return missingComponents;
		}

		missingComponents = new char[componentSize][];
		int size = 0;
		for (int i = 0; i < componentSize; i++) {
			RecordComponent arg = components[i];
			boolean found = false;
			int paramNameRefCount = 0;
			for (int j = 0; j < paramTagsSize && !found; j++) {
				JavadocSingleNameReference param = this.paramReferences[j];
				VariableBinding field = type.binding.getField(arg.name, false);
				if (field == param.binding) {
					if (param.binding == paramNameRefBinding) { // do not count first occurrence of component name reference
						paramNameRefCount++;
						found = paramNameRefCount > 1;
					} else {
						found = true;
					}
				}
			}
			if (!found) {
				missingComponents[size++] = arg.name;
			}
		}
		if (size > 0) {
			if (size != componentSize) {
				System.arraycopy(missingComponents, 0, missingComponents = new char[size][], 0, size);
			}
			return missingComponents;
		}
		return null;
	}
	/*
	 * Look for missing method @param tags
	 */
	private char[][] missingParamTags(Binding paramNameRefBinding, MethodScope methScope) {

		// Verify if there's some possible param tag
		AbstractMethodDeclaration md = methScope.referenceMethod();
		int paramTagsSize = this.paramReferences == null ? 0 : this.paramReferences.length;
		if (md == null) return null;
		int argumentsSize = md.arguments == null ? 0 : md.arguments.length;
		if (argumentsSize == 0) return null;

		// Store all method arguments if there's no @param in javadoc
		if (paramTagsSize == 0) {
			char[][] missingParams = new char[argumentsSize][];
			for (int i = 0; i < argumentsSize; i++) {
				missingParams[i] = md.arguments[i].name;
			}
			return missingParams;
		}

		// Look for missing arguments
		char[][] missingParams = new char[argumentsSize][];
		int size = 0;
		for (int i = 0; i < argumentsSize; i++) {
			Argument arg = md.arguments[i];
			boolean found = false;
			int paramNameRefCount = 0;
			for (int j = 0; j < paramTagsSize && !found; j++) {
				JavadocSingleNameReference param = this.paramReferences[j];
				if (arg.binding == param.binding) {
					if (param.binding == paramNameRefBinding) { // do not count first occurence of param name reference
						paramNameRefCount++;
						found = paramNameRefCount > 1;
					} else {
						found = true;
					}
				}
			}
			if (!found) {
				missingParams[size++] = arg.name;
			}
		}
		if (size > 0) {
			if (size != argumentsSize) {
				System.arraycopy(missingParams, 0, missingParams = new char[size][], 0, size);
			}
			return missingParams;
		}
		return null;
	}

	/*
	 * Look for missing type parameters @param tags
	 */
	private char[][] missingTypeParameterTags(Binding paramNameRefBinding, Scope scope) {
		int paramTypeParamLength = this.paramTypeParameters == null ? 0 : this.paramTypeParameters.length;

		// Verify if there's any type parameter to tag
		TypeParameter[] parameters =  null;
		TypeVariableBinding[] typeVariables = null;
		switch (scope.kind) {
			case Scope.METHOD_SCOPE:
				AbstractMethodDeclaration methodDeclaration = ((MethodScope)scope).referenceMethod();
				if (methodDeclaration == null) return null;
				parameters = methodDeclaration.typeParameters();
				typeVariables = methodDeclaration.binding.typeVariables;
				break;
			case Scope.CLASS_SCOPE:
				TypeDeclaration typeDeclaration = ((ClassScope) scope).referenceContext;
				parameters = typeDeclaration.typeParameters;
				typeVariables = typeDeclaration.binding.typeVariables;
				break;
		}
		if (typeVariables == null || typeVariables.length == 0) return null;

		// Store all type parameters if there's no @param in javadoc
		if (parameters != null) {
			int typeParametersLength = parameters.length;
			if (paramTypeParamLength == 0) {
				char[][] missingParams = new char[typeParametersLength][];
				for (int i = 0; i < typeParametersLength; i++) {
					missingParams[i] = parameters[i].name;
				}
				return missingParams;
			}

			// Look for missing type parameter
			char[][] missingParams = new char[typeParametersLength][];
			int size = 0;
			for (int i = 0; i < typeParametersLength; i++) {
				TypeParameter parameter = parameters[i];
				boolean found = false;
				int paramNameRefCount = 0;
				for (int j = 0; j < paramTypeParamLength && !found; j++) {
					if (TypeBinding.equalsEquals(parameter.binding, this.paramTypeParameters[j].resolvedType)) {
						if (parameter.binding == paramNameRefBinding) { // do not count first occurence of param nmae reference
							paramNameRefCount++;
							found = paramNameRefCount > 1;
						} else {
							found = true;
						}
					}
				}
				if (!found) {
					missingParams[size++] = parameter.name;
				}
			}
			if (size > 0) {
				if (size != typeParametersLength) {
					System.arraycopy(missingParams, 0, missingParams = new char[size][], 0, size);
				}
				return missingParams;
			}
		}
		return null;
	}
}
