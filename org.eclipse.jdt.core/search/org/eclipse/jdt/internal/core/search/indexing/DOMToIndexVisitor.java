/*******************************************************************************
 * Copyright (c) 2023 Red Hat, Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.jdt.internal.core.search.indexing;

import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.internal.core.search.matching.MethodPattern;

/**
 * Visits an AST to feed the index. Similar to {@link SourceIndexerRequestor} but using
 * DOM instead of ECJ parser.
 */
class DOMToIndexVisitor extends ASTVisitor {

	private SourceIndexer sourceIndexer;

	private char[] packageName;
	private List<AbstractTypeDeclaration> enclosingTypes = new LinkedList<>();

	public DOMToIndexVisitor(SourceIndexer sourceIndexer) {
		super(true);
		this.sourceIndexer = sourceIndexer;
	}

	private AbstractTypeDeclaration currentType() {
		return this.enclosingTypes.get(this.enclosingTypes.size() - 1);
	}

	@Override
	public boolean visit(PackageDeclaration packageDeclaration) {
		this.packageName = packageDeclaration.getName().getFullyQualifiedName().toCharArray();
		return false;
	}

	@Override
	public boolean visit(TypeDeclaration type) {
		char[][] enclosing = type.isLocalTypeDeclaration() ? IIndexConstants.ONE_ZERO_CHAR :
				this.enclosingTypes.stream().map(AbstractTypeDeclaration::getName).map(SimpleName::getIdentifier).map(String::toCharArray).toArray(char[][]::new);
		char[][] parameterTypeSignatures = ((List<TypeParameter>)type.typeParameters()).stream()
				.map(TypeParameter::getName)
				.map(Name::toString)
				.map(name -> Signature.createTypeSignature(name, false))
				.map(String::toCharArray)
				.toArray(char[][]::new);
		if (type.isInterface()) {
			this.sourceIndexer.addInterfaceDeclaration(type.getModifiers() | maybeDeprecated(type), this.packageName, simpleName(type.getName()), enclosing, ((List<Type>)type.superInterfaceTypes()).stream().map(this::name).toArray(char[][]::new), parameterTypeSignatures, isSecondary(type));
		} else {
			this.sourceIndexer.addClassDeclaration(type.getModifiers() | maybeDeprecated(type), this.packageName, simpleName(type.getName()), enclosing, type.getSuperclassType() == null ? null : name(type.getSuperclassType()),
				((List<Type>)type.superInterfaceTypes()).stream().map(this::name).toArray(char[][]::new), parameterTypeSignatures, isSecondary(type));
			if (type.bodyDeclarations().stream().noneMatch(member -> member instanceof MethodDeclaration method && method.isConstructor())) {
				this.sourceIndexer.addDefaultConstructorDeclaration(type.getName().getIdentifier().toCharArray(),
						this.packageName, type.getModifiers() | maybeDeprecated(type), 0);
			}
			if (type.getSuperclassType() != null) {
				this.sourceIndexer.addConstructorReference(name(type.getSuperclassType()), 0);
			}
		}
		this.enclosingTypes.add(type);
		// TODO other types
		return true;
	}
	@Override
	public void endVisit(TypeDeclaration type) {
		this.enclosingTypes.remove(type);
	}

	@Override
	public boolean visit(EnumDeclaration type) {
		char[][] enclosing = this.enclosingTypes.stream().map(AbstractTypeDeclaration::getName).map(SimpleName::getIdentifier).map(String::toCharArray).toArray(char[][]::new);
		this.sourceIndexer.addEnumDeclaration(type.getModifiers() | maybeDeprecated(type), this.packageName, type.getName().getIdentifier().toCharArray(), enclosing, Enum.class.getName().toCharArray(), ((List<Type>)type.superInterfaceTypes()).stream().map(this::name).toArray(char[][]::new), isSecondary(type));
		this.enclosingTypes.add(type);
		return true;
	}
	@Override
	public void endVisit(EnumDeclaration type) {
		this.enclosingTypes.remove(type);
	}
	@Override
	public boolean visit(EnumConstantDeclaration enumConstant) {
		this.sourceIndexer.addFieldDeclaration(currentType().getName().getIdentifier().toCharArray(), enumConstant.getName().getIdentifier().toCharArray());
		this.sourceIndexer.addConstructorReference(currentType().getName().getIdentifier().toCharArray(), enumConstant.arguments().size());
		return true;
	}

	@Override
	public boolean visit(AnnotationTypeDeclaration type) {
		char[][] enclosing = this.enclosingTypes.stream().map(AbstractTypeDeclaration::getName).map(SimpleName::getIdentifier).map(String::toCharArray).toArray(char[][]::new);
		this.sourceIndexer.addAnnotationTypeDeclaration(type.getModifiers() | maybeDeprecated(type), this.packageName, type.getName().getIdentifier().toCharArray(), enclosing, isSecondary(type));
		this.enclosingTypes.add(type);
		return true;
	}
	@Override
	public void endVisit(AnnotationTypeDeclaration type) {
		this.enclosingTypes.remove(type);
	}

	private boolean isSecondary(AbstractTypeDeclaration type) {
		return type.getParent() instanceof CompilationUnit &&
			!Objects.equals(type.getName().getIdentifier() + ".java", Path.of(this.sourceIndexer.document.getPath()).getFileName().toString()); //$NON-NLS-1$
	}

	@Override
	public boolean visit(RecordDeclaration recordDecl) {
		this.enclosingTypes.add(recordDecl);
		// copied processing of TypeDeclaration
		this.sourceIndexer.addClassDeclaration(recordDecl.getModifiers() | maybeDeprecated(recordDecl), this.packageName, recordDecl.getName().getIdentifier().toCharArray(), null, null,
				((List<Type>)recordDecl.superInterfaceTypes()).stream().map(this::name).toArray(char[][]::new), null, false);
		return true;
	}
	@Override
	public void endVisit(RecordDeclaration type) {
		this.enclosingTypes.remove(type);
	}


	@Override
	public boolean visit(MethodDeclaration method) {
		char[] methodName = method.getName().getIdentifier().toCharArray();
		char[][] parameterTypes = ((List<VariableDeclaration>)method.parameters()).stream()
			.filter(SingleVariableDeclaration.class::isInstance)
			.map(SingleVariableDeclaration.class::cast)
			.map(SingleVariableDeclaration::getType)
			.map(this::name)
			.toArray(char[][]::new);
		char[] returnType = name(method.getReturnType2());
		char[][] exceptionTypes = ((List<Type>)method.thrownExceptionTypes()).stream()
			.map(this::name)
			.toArray(char[][]::new);
		char[][] parameterNames = ((List<VariableDeclaration>)method.parameters()).stream()
				.map(VariableDeclaration::getName)
				.map(SimpleName::getIdentifier)
				.map(String::toCharArray)
				.toArray(char[][]::new);
		if (!method.isConstructor()) {
			this.sourceIndexer.addMethodDeclaration(methodName, parameterTypes, returnType, exceptionTypes);
			if (!this.enclosingTypes.isEmpty()) {
				this.sourceIndexer.addMethodDeclaration(this.enclosingTypes.get(this.enclosingTypes.size() - 1).getName().getIdentifier().toCharArray(),
					null /* TODO: fully qualified name of enclosing type? */,
					methodName,
					parameterTypes.length,
					null,
					parameterTypes,
					parameterNames,
					returnType,
					method.getModifiers() | maybeDeprecated(method),
					this.packageName,
					0 /* TODO What to put here? */,
					exceptionTypes,
					0 /* TODO ExtraFlags.IsLocalType ? */);
			}
		} else {
			this.sourceIndexer.addConstructorDeclaration(method.getName().getFullyQualifiedName().toCharArray(),
					method.parameters().size(),
					null, parameterTypes, parameterNames, method.getModifiers() | maybeDeprecated(method), this.packageName, currentType().getModifiers(), exceptionTypes, 0);
		}
		return true;
	}

	@Override
	public boolean visit(ImportDeclaration node) {
		if (node.isStatic() && !node.isOnDemand()) {
			this.sourceIndexer.addMethodReference(simpleName(node.getName()), 0);
		} else if (Modifier.isModule(node.getModifiers())) {
			this.sourceIndexer.addModuleReference(node.getName().getFullyQualifiedName().toCharArray());
		} if (!node.isOnDemand()) {
			this.sourceIndexer.addTypeReference(node.getName().getFullyQualifiedName().toCharArray());
		}
		return true;
	}

	@Override
	public boolean visit(FieldDeclaration field) {
		char[] typeName = name(field.getType());
		for (VariableDeclarationFragment fragment: (List<VariableDeclarationFragment>)field.fragments()) {
			this.sourceIndexer.addFieldDeclaration(typeName, fragment.getName().getIdentifier().toCharArray());
		}
		return true;
	}

	@Override
	public boolean visit(MethodInvocation methodInvocation) {
		this.sourceIndexer.addMethodReference(methodInvocation.getName().getIdentifier().toCharArray(), methodInvocation.arguments().size());
		return true;
	}

	@Override
	public boolean visit(ExpressionMethodReference methodInvocation) {
		int argsCount = 0;
		if (this.sourceIndexer.document.shouldIndexResolvedDocument()) {
			IMethodBinding binding = methodInvocation.resolveMethodBinding();
			if (binding != null) {
				argsCount = binding.getParameterTypes().length;
			}
		}
		this.sourceIndexer.addMethodReference(methodInvocation.getName().getIdentifier().toCharArray(), argsCount);
		return true;
	}
	@Override
	public boolean visit(TypeMethodReference methodInvocation) {
		int argsCount = 0;
		if (this.sourceIndexer.document.shouldIndexResolvedDocument()) {
			IMethodBinding binding = methodInvocation.resolveMethodBinding();
			if (binding != null) {
				argsCount = binding.getParameterTypes().length;
			}
		}
		this.sourceIndexer.addMethodReference(methodInvocation.getName().getIdentifier().toCharArray(), argsCount);
		return true;
	}
	@Override
	public boolean visit(SuperMethodInvocation methodInvocation) {
		this.sourceIndexer.addMethodReference(methodInvocation.getName().getIdentifier().toCharArray(), methodInvocation.arguments().size());
		return true;
	}
	@Override
	public boolean visit(SuperMethodReference methodInvocation) {
		int argsCount = 0;
		if (this.sourceIndexer.document.shouldIndexResolvedDocument()) {
			IMethodBinding binding = methodInvocation.resolveMethodBinding();
			if (binding != null) {
				argsCount = binding.getParameterTypes().length;
			}
		}
		this.sourceIndexer.addMethodReference(methodInvocation.getName().getIdentifier().toCharArray(), argsCount);
		return true;
	}
	@Override
	public boolean visit(ClassInstanceCreation methodInvocation) {
		this.sourceIndexer.addConstructorReference(name(methodInvocation.getType()), methodInvocation.arguments().size());
		if (methodInvocation.getAnonymousClassDeclaration() != null) {
			this.sourceIndexer.addClassDeclaration(0, this.packageName, new char[0], IIndexConstants.ONE_ZERO_CHAR, name(methodInvocation.getType()), null, null, false);
			this.sourceIndexer.addTypeReference(name(methodInvocation.getType()));
		}
		return true;
	}
	@Override
	public boolean visit(CreationReference methodInvocation) {
		int argsCount = 0;
		if (this.sourceIndexer.document.shouldIndexResolvedDocument()) {
			IMethodBinding binding = methodInvocation.resolveMethodBinding();
			if (binding != null) {
				argsCount = binding.getParameterTypes().length;
			}
		}
		this.sourceIndexer.addConstructorReference(name(methodInvocation.getType()), argsCount);
		return true;
	}

	@Override
	public boolean visit(SuperConstructorInvocation node) {
		char[] superClassName = Object.class.getName().toCharArray();
		if (currentType() instanceof TypeDeclaration decl && decl.getSuperclassType() != null) {
			superClassName = name(decl.getSuperclassType());
		}
		this.sourceIndexer.addConstructorReference(superClassName, node.arguments().size());
		return true;
	}

	private char[] name(Type type) {
		if (type == null) {
			return null;
		}
		if (type instanceof PrimitiveType primitive) {
			return primitive.toString().toCharArray();
		}
		if (type instanceof SimpleType simpleType) {
			return simpleName(simpleType.getName());
		}
		if (type instanceof ParameterizedType parameterized) {
//			String res = new String(name(parameterized.getType()));
//			res += '<';
//			res += ((List<Type>)parameterized.typeArguments()).stream()
//				.map(this::name)
//				.map(String::new)
//				.collect(Collectors.joining(",")); //$NON-NLS-1$
//			res += '>';
//			return res.toCharArray();
			return name(parameterized.getType());
		}
//		if (type instanceof ArrayType arrayType) {
//			char[] res = name(arrayType.getElementType());
//			res = Arrays.copyOf(res, res.length + 2 * arrayType.getDimensions());
//			for (int i = 0; i < arrayType.getDimensions(); i++) {
//				res[res.length - 1 - 2 * i] = ']';
//				res[res.length - 1 - 2 * i - 1] = '[';
//			}
//			return res;
//		}
//		if (type instanceof QualifiedType qualifiedType) {
//			return simpleName(qualifiedType.getName());
//		}
		return type.toString().toCharArray();
	}

	@Override
	public boolean visit(NormalAnnotation annotation) {
		this.sourceIndexer.addAnnotationTypeReference(simpleName(annotation.getTypeName()));
		return true;
	}
	@Override
	public boolean visit(MarkerAnnotation annotation) {
		this.sourceIndexer.addAnnotationTypeReference(simpleName(annotation.getTypeName()));
		return true;
	}
	@Override
	public boolean visit(SingleMemberAnnotation annotation) {
		this.sourceIndexer.addAnnotationTypeReference(simpleName(annotation.getTypeName()));
		return true;
	}

	@Override
	public boolean visit(SimpleType type) {
		this.sourceIndexer.addTypeReference(name(type));
		return true;
	}
	@Override
	public boolean visit(QualifiedType type) {
		this.sourceIndexer.addTypeReference(name(type));
		return true;
	}
	@Override
	public boolean visit(SimpleName name) {
		this.sourceIndexer.addNameReference(name.getIdentifier().toCharArray());
		return true;
	}
	// TODO (cf SourceIndexer and SourceIndexerRequestor)
	// * Lambda: addIndexEntry/addClassDeclaration
	// * FieldReference

	@Override
	public boolean visit(MethodRef methodRef) {
		this.sourceIndexer.addMethodReference(methodRef.getName().getIdentifier().toCharArray(), methodRef.parameters().size());
		this.sourceIndexer.addConstructorReference(methodRef.getName().getIdentifier().toCharArray(), methodRef.parameters().size());
		return true;
	}
	@Override
	public boolean visit(MemberRef memberRef) {
		this.sourceIndexer.addFieldReference(memberRef.getName().getIdentifier().toCharArray());
		this.sourceIndexer.addTypeReference(memberRef.getName().getIdentifier().toCharArray());
		return true;
	}

	@Override
	public boolean visit(LambdaExpression node) {
		var binding = node.resolveMethodBinding();
		if (binding != null) {
			this.sourceIndexer.addIndexEntry(IIndexConstants.METHOD_DECL, MethodPattern.createIndexKey(binding.getName().toCharArray(), binding.getParameterTypes().length));

			this.sourceIndexer.addClassDeclaration(0,  // most entries are blank, that is fine, since lambda type/method cannot be searched.
					CharOperation.NO_CHAR,
					IIndexConstants.ONE_ZERO,
					IIndexConstants.ONE_ZERO_CHAR,
					CharOperation.NO_CHAR,
					new char[][] { binding.getDeclaringClass().getQualifiedName().toCharArray() },
					CharOperation.NO_CHAR_CHAR,
					true);
		}
		return true;
	}

	private static char[] simpleName(Name name) {
		if (name instanceof SimpleName simple) {
			return simple.getIdentifier().toCharArray();
		}
		if (name instanceof QualifiedName qualified) {
			return simpleName(qualified.getName());
		}
		return null;
	}

	@Override
	public boolean visit(ModuleDeclaration node) {
		this.sourceIndexer.addModuleDeclaration(node.getName().getFullyQualifiedName().toCharArray());
		return true;
	}

	@Override
	public boolean visit(RequiresDirective node) {
		this.sourceIndexer.addModuleReference(node.getName().getFullyQualifiedName().toCharArray());
		return true;
	}
	@Override
	public boolean visit(ExportsDirective node) {
		this.sourceIndexer.addModuleExportedPackages(node.getName().getFullyQualifiedName().toCharArray());
		for (Name moduleName : (List<Name>)node.modules()) {
			this.sourceIndexer.addModuleReference(moduleName.getFullyQualifiedName().toCharArray());
		}
		return true;
	}
	@Override
	public boolean visit(ProvidesDirective node) {
		this.sourceIndexer.addTypeReference(node.getName().getFullyQualifiedName().toCharArray());
		for (var n : (List<Name>)node.implementations()) {
			this.sourceIndexer.addTypeReference(n.getFullyQualifiedName().toCharArray());
		}
		return true;
	}
	@Override
	public boolean visit(UsesDirective node) {
		this.sourceIndexer.addTypeReference(node.getName().toString().toCharArray());
		return true;
	}
	@Override
	public boolean visit(OpensDirective node) {
		this.sourceIndexer.addModuleExportedPackages(node.getName().getFullyQualifiedName().toCharArray());
		for (Name moduleName : (List<Name>)node.modules()) {
			this.sourceIndexer.addModuleReference(moduleName.getFullyQualifiedName().toCharArray());
		}
		return true;
	}

	private int maybeDeprecated(BodyDeclaration declaration) {
		return hasDeprecated(declaration.modifiers()) || hasDeprecated(declaration.getJavadoc()) ? org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants.AccDeprecated : 0;
	}
	private boolean hasDeprecated(List<?> modifiers) {
		if (modifiers == null) {
			return false;
		}
		return modifiers.stream()
				.filter(Annotation.class::isInstance)
				.map(Annotation.class::cast)
				.map(Annotation::getTypeName)
				.map(Name::getFullyQualifiedName)
				.anyMatch(Deprecated.class.getSimpleName()::equals);
	}
	private boolean hasDeprecated(Javadoc javadoc) {
		if (javadoc == null) {
			return false;
		}
		return ((List<?>)javadoc.tags()).stream()
				.filter(TagElement.class::isInstance)
				.map(TagElement.class::cast)
				.map(TagElement::getTagName)
				.anyMatch(TagElement.TAG_DEPRECATED::equals);
	}
}
