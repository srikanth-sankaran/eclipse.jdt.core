/*******************************************************************************
 * Copyright (c) 2000, 2025 IBM Corporation and others.
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
 *								Bug 360328 - [compiler][null] detect null problems in nested code (local class inside a loop)
 *								Bug 388630 - @NonNull diagnostics at line 0
 *								Bug 392099 - [1.8][compiler][null] Apply null annotation on types for null analysis
 *								Bug 416176 - [1.8][compiler][null] null type annotations cause grief on type variables
 *								Bug 424727 - [compiler][null] NullPointerException in nullAnnotationUnsupportedLocation(ProblemReporter.java:5708)
 *								Bug 457210 - [1.8][compiler][null] Wrong Nullness errors given on full build build but not on incremental build?
 *     Keigo Imai - Contribution for  bug 388903 - Cannot extend inner class as an anonymous class when it extends the outer class
  *    Pierre-Yves B. <pyvesdev@gmail.com> - Contributions for
 *                              Bug 542520 - [JUnit 5] Warning The method xxx from the type X is never used locally is shown when using MethodSource
 *                              Bug 546084 - Using Junit 5s MethodSource leads to ClassCastException
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.ast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.ClassFile;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration.AnalysisMode;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.codegen.CodeStream;
import org.eclipse.jdt.internal.compiler.flow.FlowContext;
import org.eclipse.jdt.internal.compiler.flow.FlowInfo;
import org.eclipse.jdt.internal.compiler.flow.InitializationFlowContext;
import org.eclipse.jdt.internal.compiler.flow.UnconditionalFlowInfo;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.impl.JavaFeature;
import org.eclipse.jdt.internal.compiler.impl.ReferenceContext;
import org.eclipse.jdt.internal.compiler.impl.StringConstant;
import org.eclipse.jdt.internal.compiler.lookup.*;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.AbortCompilation;
import org.eclipse.jdt.internal.compiler.problem.AbortCompilationUnit;
import org.eclipse.jdt.internal.compiler.problem.AbortMethod;
import org.eclipse.jdt.internal.compiler.problem.AbortType;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import org.eclipse.jdt.internal.compiler.problem.ProblemSeverities;
import org.eclipse.jdt.internal.compiler.util.SimpleSetOfCharArray;
import org.eclipse.jdt.internal.compiler.util.Util;

public class TypeDeclaration extends Statement implements ProblemSeverities, ReferenceContext, TypeOrLambda {
	// Type decl kinds
	public static final int CLASS_DECL = 1;
	public static final int INTERFACE_DECL = 2;
	public static final int ENUM_DECL = 3;
	public static final int ANNOTATION_TYPE_DECL = 4;
	public static final int RECORD_DECL = 5;

	public int modifiers = ClassFileConstants.AccDefault;
	public int modifiersSourceStart;
	public int functionalExpressionsCount = 0;
	public Annotation[] annotations;
	public char[] name;
	public TypeReference superclass;
	public TypeReference[] superInterfaces;
	public FieldDeclaration[] fields;
	public AbstractMethodDeclaration[] methods;
	public TypeDeclaration[] memberTypes;
	public SourceTypeBinding binding;
	public ClassScope scope;
	public MethodScope initializerScope;
	public MethodScope staticInitializerScope;
	public boolean ignoreFurtherInvestigation = false;
	public int maxFieldCount;
	public int declarationSourceStart;
	public int declarationSourceEnd;
	public int restrictedIdentifierStart = -1; // used only for record and permits restricted keywords.
	public int bodyStart;
	public int bodyEnd; // doesn't include the trailing comment if any.
	public CompilationResult compilationResult;
	public MethodDeclaration[] missingAbstractMethods;
	public Javadoc javadoc;

	public QualifiedAllocationExpression allocation; // for anonymous only
	public TypeDeclaration enclosingType; // for member types only

	public FieldBinding enumValuesSyntheticfield; 	// for enum
	public int enumConstantsCounter;

	// 1.5 support
	public TypeParameter[] typeParameters;

	// 16 Records support
	public RecordComponent[] recordComponents = NO_RECORD_COMPONENTS;
	public static Set<String> disallowedComponentNames;

	// 17 Sealed Type support
	public TypeReference[] permittedTypes;

	// TEST ONLY: disable one fix here to challenge another related fix (in TypeSystem):
	public static boolean TESTING_GH_2158 = false;

	static {
		disallowedComponentNames = new HashSet<>(9);
		disallowedComponentNames.add("clone"); //$NON-NLS-1$
		disallowedComponentNames.add("finalize"); //$NON-NLS-1$
		disallowedComponentNames.add("getClass"); //$NON-NLS-1$
		disallowedComponentNames.add("hashCode"); //$NON-NLS-1$
		disallowedComponentNames.add("notify");   //$NON-NLS-1$
		disallowedComponentNames.add("notifyAll");//$NON-NLS-1$
		disallowedComponentNames.add("toString"); //$NON-NLS-1$
		disallowedComponentNames.add("wait"); //$NON-NLS-1$
		disallowedComponentNames.add("this"); //$NON-NLS-1$
	}

public TypeDeclaration(CompilationResult compilationResult){
	this.compilationResult = compilationResult;
}

/*
 *	We cause the compilation task to abort to a given extent.
 */
@Override
public void abort(int abortLevel, CategorizedProblem problem) {
	switch (abortLevel) {
		case AbortCompilation :
			throw new AbortCompilation(this.compilationResult, problem);
		case AbortCompilationUnit :
			throw new AbortCompilationUnit(this.compilationResult, problem);
		case AbortMethod :
			throw new AbortMethod(this.compilationResult, problem);
		default :
			throw new AbortType(this.compilationResult, problem);
	}
}

/**
 * This method is responsible for adding a {@code <clinit>} method declaration to the type method collections.
 * Note that this implementation is inserting it in first place (as VAJ or javac), and that this
 * impacts the behavior of the method ConstantPool.resetForClinit(int. int), in so far as
 * the latter will have to reset the constant pool state accordingly (if it was added first, it does
 * not need to preserve some of the method specific cached entries since this will be the first method).
 * inserts the clinit method declaration in the first position.
 *
 * @see org.eclipse.jdt.internal.compiler.codegen.ConstantPool#resetForClinit(int, int)
 */
public final void addClinit() {
	//see comment on needClassInitMethod
	if (needClassInitMethod()) {
		int length;
		AbstractMethodDeclaration[] methodDeclarations;
		if ((methodDeclarations = this.methods) == null) {
			length = 0;
			methodDeclarations = new AbstractMethodDeclaration[1];
		} else {
			length = methodDeclarations.length;
			System.arraycopy(
				methodDeclarations,
				0,
				(methodDeclarations = new AbstractMethodDeclaration[length + 1]),
				1,
				length);
		}
		Clinit clinit = new Clinit(this.compilationResult);
		methodDeclarations[0] = clinit;
		// clinit is added in first location, so as to minimize the use of ldcw (big consumer of constant inits)
		clinit.declarationSourceStart = clinit.sourceStart = this.sourceStart;
		clinit.declarationSourceEnd = clinit.sourceEnd = this.sourceEnd;
		clinit.bodyEnd = this.sourceEnd;
		this.methods = methodDeclarations;
	}
}

/*
 * INTERNAL USE ONLY - Creates a fake method declaration for the corresponding binding.
 * It is used to report errors for missing abstract methods.
 */
public MethodDeclaration addMissingAbstractMethodFor(MethodBinding methodBinding) {
	TypeBinding[] argumentTypes = methodBinding.parameters;
	int argumentsLength = argumentTypes.length;
	//the constructor
	MethodDeclaration methodDeclaration = new MethodDeclaration(this.compilationResult);
	methodDeclaration.selector = methodBinding.selector;
	methodDeclaration.sourceStart = this.sourceStart;
	methodDeclaration.sourceEnd = this.sourceEnd;
	methodDeclaration.modifiers = methodBinding.getAccessFlags() & ~ClassFileConstants.AccAbstract;

	if (argumentsLength > 0) {
		String baseName = "arg";//$NON-NLS-1$
		Argument[] arguments = (methodDeclaration.arguments = new Argument[argumentsLength]);
		for (int i = argumentsLength; --i >= 0;) {
			arguments[i] = new Argument((baseName + i).toCharArray(), 0L, null /*type ref*/, ClassFileConstants.AccDefault);
		}
	}

	//adding the constructor in the methods list
	if (this.missingAbstractMethods == null) {
		this.missingAbstractMethods = new MethodDeclaration[] { methodDeclaration };
	} else {
		MethodDeclaration[] newMethods;
		System.arraycopy(
			this.missingAbstractMethods,
			0,
			newMethods = new MethodDeclaration[this.missingAbstractMethods.length + 1],
			1,
			this.missingAbstractMethods.length);
		newMethods[0] = methodDeclaration;
		this.missingAbstractMethods = newMethods;
	}

	//============BINDING UPDATE==========================
	methodDeclaration.binding = new MethodBinding(
			methodDeclaration.modifiers | ClassFileConstants.AccSynthetic, //methodDeclaration
			methodBinding.selector,
			methodBinding.returnType,
			argumentsLength == 0 ? Binding.NO_PARAMETERS : argumentTypes, //arguments bindings
			methodBinding.thrownExceptions, //exceptions
			this.binding); //declaringClass

	methodDeclaration.scope = new MethodScope(this.scope, methodDeclaration, true);
	methodDeclaration.bindArguments();

/*		if (binding.methods == null) {
			binding.methods = new MethodBinding[] { methodDeclaration.binding };
		} else {
			MethodBinding[] newMethods;
			System.arraycopy(
				binding.methods,
				0,
				newMethods = new MethodBinding[binding.methods.length + 1],
				1,
				binding.methods.length);
			newMethods[0] = methodDeclaration.binding;
			binding.methods = newMethods;
		}*/
	//===================================================

	return methodDeclaration;
}

/**
 *	Flow analysis for a local innertype
 */
@Override
public FlowInfo analyseCode(BlockScope currentScope, FlowContext flowContext, FlowInfo flowInfo) {
	if (this.ignoreFurtherInvestigation)
		return flowInfo;
	try {
		if ((flowInfo.tagBits & FlowInfo.UNREACHABLE_OR_DEAD) == 0) {
			this.bits |= ASTNode.IsReachable;
			LocalTypeBinding localType = (LocalTypeBinding) this.binding;
			localType.setConstantPoolName(currentScope.compilationUnitScope().computeConstantPoolName(localType));
		}
		manageEnclosingInstanceAccessIfNecessary(currentScope, flowInfo);
		updateMaxFieldCount(); // propagate down the max field count
		internalAnalyseCode(flowContext, flowInfo);
	} catch (AbortType e) {
		this.ignoreFurtherInvestigation = true;
	}
	return flowInfo;
}

/**
 *	Flow analysis for a member innertype
 */
public void analyseCode(ClassScope enclosingClassScope) {
	if (this.ignoreFurtherInvestigation)
		return;
	try {
		// propagate down the max field count
		updateMaxFieldCount();
		internalAnalyseCode(null, FlowInfo.initial(this.maxFieldCount));
	} catch (AbortType e) {
		this.ignoreFurtherInvestigation = true;
	}
}

/**
 *	Flow analysis for a local member innertype
 */
public void analyseCode(ClassScope currentScope, FlowContext flowContext, FlowInfo flowInfo) {
	if (this.ignoreFurtherInvestigation)
		return;
	try {
		if ((flowInfo.tagBits & FlowInfo.UNREACHABLE_OR_DEAD) == 0) {
			this.bits |= ASTNode.IsReachable;
			LocalTypeBinding localType = (LocalTypeBinding) this.binding;
			localType.setConstantPoolName(currentScope.compilationUnitScope().computeConstantPoolName(localType));
		}
		manageEnclosingInstanceAccessIfNecessary(currentScope, flowInfo);
		updateMaxFieldCount(); // propagate down the max field count
		internalAnalyseCode(flowContext, flowInfo);
	} catch (AbortType e) {
		this.ignoreFurtherInvestigation = true;
	}
}

/**
 *	Flow analysis for a package member type
 */
public void analyseCode(CompilationUnitScope unitScope) {
	if (this.ignoreFurtherInvestigation)
		return;
	try {
		internalAnalyseCode(null, FlowInfo.initial(this.maxFieldCount));
	} catch (AbortType e) {
		this.ignoreFurtherInvestigation = true;
	}
}

/**
 * Check for constructor vs. method with no return type.
 * Answers true if at least one constructor is defined
 */
public boolean checkConstructors(Parser parser) {
	//if a constructor has not the name of the type,
	//convert it into a method with 'null' as its return type
	boolean hasConstructor = false;
	if (this.methods != null) {
		for (int i = this.methods.length; --i >= 0;) {
			AbstractMethodDeclaration am;
			if ((am = this.methods[i]).isConstructor()) {
				if (!CharOperation.equals(am.selector, this.name)) {
					// the constructor was in fact a method with no return type
					// unless an explicit constructor call was supplied
					ConstructorDeclaration c = (ConstructorDeclaration) am;
					if (c.constructorCall == null || c.constructorCall.isImplicitSuper()) { //changed to a method
						MethodDeclaration m = parser.convertToMethodDeclaration(c, this.compilationResult);
						this.methods[i] = m;
					}
				} else {
					switch (kind(this.modifiers)) {
						case TypeDeclaration.INTERFACE_DECL :
							// report the problem and continue the parsing
							parser.problemReporter().interfaceCannotHaveConstructors((ConstructorDeclaration) am);
							break;
						case TypeDeclaration.ANNOTATION_TYPE_DECL :
							// report the problem and continue the parsing
							parser.problemReporter().annotationTypeDeclarationCannotHaveConstructor((ConstructorDeclaration) am);
							break;

					}
					hasConstructor = true;
				}
			}
		}
	}
	return hasConstructor;
}

@Override
public CompilationResult compilationResult() {
	return this.compilationResult;
}

public ConstructorDeclaration createDefaultConstructor(boolean needExplicitConstructorCall, boolean needToInsert) {
	//Add to method'set, the default constuctor that just recall the
	//super constructor with no arguments
	//The arguments' type will be positionned by the TC so just use
	//the default int instead of just null (consistency purpose)

	//the constructor
	ConstructorDeclaration constructor = new ConstructorDeclaration(this.compilationResult);
	constructor.bits |= ASTNode.IsDefaultConstructor;
	constructor.selector = this.name;
	constructor.modifiers = this.modifiers & ExtraCompilerModifiers.AccVisibilityMASK;

	//if you change this setting, please update the
	//SourceIndexer2.buildTypeDeclaration(TypeDeclaration,char[]) method
	constructor.declarationSourceStart = constructor.sourceStart = this.sourceStart;
	constructor.declarationSourceEnd =
		constructor.sourceEnd = constructor.bodyEnd = this.sourceEnd;

	//the super call inside the constructor
	if (needExplicitConstructorCall) {
		constructor.constructorCall = SuperReference.implicitSuperConstructorCall();
		constructor.constructorCall.sourceStart = this.sourceStart;
		constructor.constructorCall.sourceEnd = this.sourceEnd;
	}

	//adding the constructor in the methods list: rank is not critical since bindings will be sorted
	if (needToInsert) {
		if (this.methods == null) {
			this.methods = new AbstractMethodDeclaration[] { constructor };
		} else {
			AbstractMethodDeclaration[] newMethods;
			System.arraycopy(
				this.methods,
				0,
				newMethods = new AbstractMethodDeclaration[this.methods.length + 1],
				1,
				this.methods.length);
			newMethods[0] = constructor;
			this.methods = newMethods;
		}
	}
	return constructor;
}

// anonymous type constructor creation: rank is important since bindings already got sorted
public MethodBinding createDefaultConstructorWithBinding(MethodBinding inheritedConstructorBinding, boolean eraseThrownExceptions) {
	//Add to method'set, the default constuctor that just recall the
	//super constructor with the same arguments
	String baseName = "$anonymous"; //$NON-NLS-1$
	TypeBinding[] argumentTypes = inheritedConstructorBinding.parameters;
	int argumentsLength = argumentTypes.length;
	//the constructor
	ConstructorDeclaration constructor = new ConstructorDeclaration(this.compilationResult);
	constructor.selector = new char[] { 'x' }; //no maining
	constructor.sourceStart = this.sourceStart;
	constructor.sourceEnd = this.sourceEnd;
	int newModifiers = this.modifiers & ExtraCompilerModifiers.AccVisibilityMASK;
	if (inheritedConstructorBinding.isVarargs()) {
		newModifiers |= ClassFileConstants.AccVarargs;
	}
	constructor.modifiers = newModifiers;
	constructor.bits |= ASTNode.IsDefaultConstructor;

	if (argumentsLength > 0) {
		Argument[] arguments = (constructor.arguments = new Argument[argumentsLength]);
		for (int i = argumentsLength; --i >= 0;) {
			arguments[i] = new Argument((baseName + i).toCharArray(), 0L, null /*type ref*/, ClassFileConstants.AccDefault);
		}
	}
	//the super call inside the constructor
	constructor.constructorCall = SuperReference.implicitSuperConstructorCall();
	constructor.constructorCall.sourceStart = this.sourceStart;
	constructor.constructorCall.sourceEnd = this.sourceEnd;

	if (argumentsLength > 0) {
		Expression[] args1;
		args1 = constructor.constructorCall.arguments = new Expression[argumentsLength];
		for (int i = argumentsLength; --i >= 0;) {
			args1[i] = new SingleNameReference((baseName + i).toCharArray(), 0L);
		}
	}

	//adding the constructor in the methods list
	if (this.methods == null) {
		this.methods = new AbstractMethodDeclaration[] { constructor };
	} else {
		AbstractMethodDeclaration[] newMethods;
		System.arraycopy(this.methods, 0, newMethods = new AbstractMethodDeclaration[this.methods.length + 1], 1, this.methods.length);
		newMethods[0] = constructor;
		this.methods = newMethods;
	}

	//============BINDING UPDATE==========================
	// https://bugs.eclipse.org/bugs/show_bug.cgi?id=277643, align with javac on JLS 15.12.2.6
	ReferenceBinding[] thrownExceptions = eraseThrownExceptions
			? this.scope.environment().convertToRawTypes(inheritedConstructorBinding.thrownExceptions, true, true)
			: inheritedConstructorBinding.thrownExceptions;

	SourceTypeBinding sourceType = this.binding;
	constructor.binding = new MethodBinding(
			constructor.modifiers, //methodDeclaration
			argumentsLength == 0 ? Binding.NO_PARAMETERS : argumentTypes, //arguments bindings
			thrownExceptions, //exceptions
			sourceType); //declaringClass
	constructor.binding.tagBits |= (inheritedConstructorBinding.tagBits & TagBits.HasMissingType);
	constructor.binding.modifiers |= ExtraCompilerModifiers.AccIsDefaultConstructor;
	if (inheritedConstructorBinding.parameterFlowBits != null // this implies that annotation based null/resource analysis is enabled
			&& argumentsLength > 0)
	{
		// copy flowbits from inherited constructor to the new constructor:
		int len = inheritedConstructorBinding.parameterFlowBits.length;
		System.arraycopy(inheritedConstructorBinding.parameterFlowBits, 0,
				constructor.binding.parameterFlowBits = new byte[len], 0, len);
	}
	// TODO(stephan): do argument types already carry sufficient info about type annotations?

	constructor.scope = new MethodScope(this.scope, constructor, true);
	constructor.bindArguments();
	constructor.constructorCall.resolve(constructor.scope);

	MethodBinding[] methodBindings = sourceType.methods(); // trigger sorting
	int length;
	System.arraycopy(methodBindings, 0, methodBindings = new MethodBinding[(length = methodBindings.length) + 1], 1, length);
	methodBindings[0] = constructor.binding;
	if (++length > 1)
		ReferenceBinding.sortMethods(methodBindings, 0, length);	// need to resort, since could be valid methods ahead (140643) - DOM needs eager sorting
	sourceType.setMethods(methodBindings);
	//===================================================

	return constructor.binding;
}

/**
 * Find the matching parse node, answers null if nothing found
 */
public FieldDeclaration declarationOf(FieldBinding fieldBinding) {
	if (fieldBinding != null && this.fields != null) {
		for (FieldDeclaration field : this.fields) {
			FieldDeclaration fieldDecl;
			if ((fieldDecl = field).binding == fieldBinding)
				return fieldDecl;
		}
	}
	return null;
}

/**
 * Find the matching parse node, answers null if nothing found
 */
public TypeDeclaration declarationOf(MemberTypeBinding memberTypeBinding) {
	if (memberTypeBinding != null && this.memberTypes != null) {
		for (TypeDeclaration memberType : this.memberTypes) {
			TypeDeclaration memberTypeDecl;
			if (TypeBinding.equalsEquals((memberTypeDecl = memberType).binding, memberTypeBinding))
				return memberTypeDecl;
		}
	}
	return null;
}

/**
 * Find the matching parse node, answers null if nothing found
 */
public AbstractMethodDeclaration declarationOf(MethodBinding methodBinding) {
	if (methodBinding != null && this.methods != null) {
		for (AbstractMethodDeclaration method : this.methods) {
			AbstractMethodDeclaration methodDecl;

			if ((methodDecl = method).binding == methodBinding)
				return methodDecl;
		}
	}
	return null;
}

/**
 * Find the matching parse node, answers null if nothing found
 */
public RecordComponent declarationOf(RecordComponentBinding recordComponentBinding) {
	for (RecordComponent component : this.recordComponents) {
		if (component.binding == recordComponentBinding)
			return component;
	}
	return null;
}

/**
 * Finds the matching type amoung this type's member types.
 * Returns null if no type with this name is found.
 * The type name is a compound name relative to this type
 * e.g. if this type is X and we're looking for Y.X.A.B
 *     then a type name would be {X, A, B}
 */
public TypeDeclaration declarationOfType(char[][] typeName) {
	int typeNameLength = typeName.length;
	if (typeNameLength < 1 || !CharOperation.equals(typeName[0], this.name)) {
		return null;
	}
	if (typeNameLength == 1) {
		return this;
	}
	char[][] subTypeName = new char[typeNameLength - 1][];
	System.arraycopy(typeName, 1, subTypeName, 0, typeNameLength - 1);
	for (TypeDeclaration memberType : this.memberTypes) {
		TypeDeclaration typeDecl = memberType.declarationOfType(subTypeName);
		if (typeDecl != null) {
			return typeDecl;
		}
	}
	return null;
}

@Override
public CompilationUnitDeclaration getCompilationUnitDeclaration() {
	if (this.scope != null) {
		return this.scope.compilationUnitScope().referenceContext;
	}
	return null;
}

/**
 * Generic bytecode generation for type
 */
public void generateCode(ClassFile enclosingClassFile) {
	if ((this.bits & ASTNode.HasBeenGenerated) != 0)
		return;
	this.bits |= ASTNode.HasBeenGenerated;
	if (this.ignoreFurtherInvestigation) {
		if (this.binding == null)
			return;
		ClassFile.createProblemType(
			this,
			this.scope.referenceCompilationUnit().compilationResult);
		return;
	}
	try {
		// create the result for a compiled type
		ClassFile classFile = ClassFile.getNewInstance(this.binding);
		if (this.compilationResult.usesPreview)
			classFile.targetJDK |= ClassFileConstants.MINOR_VERSION_PREVIEW;
		classFile.initialize(this.binding, enclosingClassFile, false);
		if (this.binding.isMemberType()) {
			classFile.recordInnerClasses(this.binding);
		} else if (this.binding.isLocalType()) {
			enclosingClassFile.recordInnerClasses(this.binding);
			classFile.recordInnerClasses(this.binding);
		}

		TypeVariableBinding[] typeVariables = this.binding.typeVariables();
		for (TypeVariableBinding typeVariableBinding : typeVariables) {
			if ((typeVariableBinding.tagBits & TagBits.ContainsNestedTypeReferences) != 0) {
				Util.recordNestedType(classFile, typeVariableBinding);
			}
		}

		// generate all fields
		classFile.addFieldInfos();

		if (this.memberTypes != null) {
			for (TypeDeclaration memberType : this.memberTypes) {
				classFile.recordInnerClasses(memberType.binding);
				memberType.generateCode(this.scope, classFile);
			}
		}
		// generate all methods
		classFile.setForMethodInfos();
		if (this.methods != null) {
			for (AbstractMethodDeclaration method : this.methods) {
				method.generateCode(this.scope, classFile);
			}
		}
		// generate all synthetic and abstract methods
		classFile.addSpecialMethods(this);

		if (this.ignoreFurtherInvestigation) { // trigger problem type generation for code gen errors
			throw new AbortType(this.scope.referenceCompilationUnit().compilationResult, null);
		}

		// finalize the compiled type result
		classFile.addAttributes();
		this.scope.referenceCompilationUnit().compilationResult.record(
			this.binding.constantPoolName(),
			classFile);
	} catch (AbortType e) {
		if (this.binding == null)
			return;
		ClassFile.createProblemType(
			this,
			this.scope.referenceCompilationUnit().compilationResult);
	}
}

/**
 * Bytecode generation for a local inner type (API as a normal statement code gen)
 */
@Override
public void generateCode(BlockScope blockScope, CodeStream codeStream) {
	if ((this.bits & ASTNode.IsReachable) == 0) {
		return;
	}
	if ((this.bits & ASTNode.HasBeenGenerated) != 0) return;
	int pc = codeStream.position;
	if (this.binding != null) {
		SyntheticArgumentBinding[] enclosingInstances = ((NestedTypeBinding) this.binding).syntheticEnclosingInstances();
		for (int i = 0, slotSize = 0, count = enclosingInstances == null ? 0 : enclosingInstances.length; i < count; i++){
			SyntheticArgumentBinding enclosingInstance = enclosingInstances[i];
			enclosingInstance.resolvedPosition = ++slotSize; // shift by 1 to leave room for aload0==this
			if (slotSize > 0xFF) { // no more than 255 words of arguments
				blockScope.problemReporter().noMoreAvailableSpaceForArgument(enclosingInstance, blockScope.referenceType());
			}
		}
	}
	generateCode(codeStream.classFile);
	codeStream.recordPositionsFrom(pc, this.sourceStart);
}

/**
 * Bytecode generation for a member inner type
 */
public void generateCode(ClassScope classScope, ClassFile enclosingClassFile) {
	if ((this.bits & ASTNode.HasBeenGenerated) != 0) return;
	if (this.binding != null) {
		SyntheticArgumentBinding[] enclosingInstances = ((NestedTypeBinding) this.binding).syntheticEnclosingInstances();
		for (int i = 0, slotSize = 0, count = enclosingInstances == null ? 0 : enclosingInstances.length; i < count; i++){
			SyntheticArgumentBinding enclosingInstance = enclosingInstances[i];
			enclosingInstance.resolvedPosition = ++slotSize; // shift by 1 to leave room for aload0==this
			if (slotSize > 0xFF) { // no more than 255 words of arguments
				classScope.problemReporter().noMoreAvailableSpaceForArgument(enclosingInstance, classScope.referenceType());
			}
		}
	}
	generateCode(enclosingClassFile);
}

/**
 * Bytecode generation for a package member
 */
public void generateCode(CompilationUnitScope unitScope) {
	generateCode((ClassFile) null);
}

@Override
public boolean hasErrors() {
	return this.ignoreFurtherInvestigation;
}

/**
 *	Common flow analysis for all types
 */
private void internalAnalyseCode(FlowContext flowContext, FlowInfo flowInfo) {
	if (CharOperation.equals(this.name, TypeConstants.YIELD)) {
		this.scope.problemReporter().validateRestrictedKeywords(this.name, this);
	}

	if (!this.binding.isUsed() && this.binding.isOrEnclosedByPrivateType()) {
		if (!this.scope.referenceCompilationUnit().compilationResult.hasSyntaxError) {
			this.scope.problemReporter().unusedPrivateType(this);
		}
	}

	// https://bugs.eclipse.org/bugs/show_bug.cgi?id=385780
	if (this.typeParameters != null &&
			!this.scope.referenceCompilationUnit().compilationResult.hasSyntaxError) {
		for (TypeParameter typeParameter : this.typeParameters) {
			if ((typeParameter.binding.modifiers & ExtraCompilerModifiers.AccLocallyUsed) == 0) {
				this.scope.problemReporter().unusedTypeParameter(typeParameter);
			}
		}
	}

	boolean useOwningAnnotations = this.scope.compilerOptions().isAnnotationBasedResourceAnalysisEnabled;
	boolean isCloseable = this.binding.hasTypeBit(TypeIds.BitAutoCloseable|TypeIds.BitCloseable);
	FieldDeclaration fieldNeedingClose = null;

	// for local classes we use the flowContext as our parent, but never use an initialization context for this purpose
	// see Bug 360328 - [compiler][null] detect null problems in nested code (local class inside a loop)
	FlowContext parentContext = (flowContext instanceof InitializationFlowContext) ? null : flowContext;
	InitializationFlowContext initializerContext = new InitializationFlowContext(parentContext, this, flowInfo, flowContext, this.initializerScope);
	// no static initializer in local classes, thus no need to set parent:
	InitializationFlowContext staticInitializerContext = new InitializationFlowContext(null, this, flowInfo, flowContext, this.staticInitializerScope);
	FlowInfo nonStaticFieldInfo = flowInfo.unconditionalFieldLessCopy();	// discards info about fields of inclosing classes
	FlowInfo staticFieldInfo = flowInfo.unconditionalFieldLessCopy();

	if (JavaFeature.FLEXIBLE_CONSTRUCTOR_BODIES.isSupported(this.scope.compilerOptions())) {
		if (this.methods != null) {
			// collect field initializations happening in constructor prologues
			FlowInfo prologueInfo = null;
			for (int i=0; i<this.methods.length; i++) {
				AbstractMethodDeclaration method = this.methods[i];
				if (method.isConstructor()) {
					FlowInfo ctorInfo = flowInfo.copy();
					ConstructorDeclaration constructor = (ConstructorDeclaration) method;
					constructor.analyseCode(this.scope, initializerContext, ctorInfo, ctorInfo.reachMode(), AnalysisMode.PROLOGUE);
					ctorInfo = constructor.getPrologueInfo();
					if (prologueInfo == null)
						prologueInfo = ctorInfo.copy();
					else
						prologueInfo = prologueInfo.mergeDefiniteInitsWith(ctorInfo.unconditionalInits()); // will only evaluate field inits below
				}
			}
			if (prologueInfo != null) {
				// field initializers should see inits from ctor prologues:
				for (FieldBinding field : this.binding.fields()) {
					if (prologueInfo.isDefinitelyAssigned(field)) {
						nonStaticFieldInfo.markAsDefinitelyAssigned(field);
					} else if (prologueInfo.isPotentiallyAssigned(field)) {
						// mimic missing method markAsPotentiallyAssigned(field):
						UnconditionalFlowInfo assigned = FlowInfo.initial(this.maxFieldCount);
						assigned.markAsDefinitelyAssigned(field);
						nonStaticFieldInfo.addPotentialInitializationsFrom(assigned);
					}
				}
			}
		}
	}

	if (this.fields != null) {
		for (FieldDeclaration field : this.fields) {
			if (field.isStatic()) {
				if ((staticFieldInfo.tagBits & FlowInfo.UNREACHABLE_OR_DEAD) != 0)
					field.bits &= ~ASTNode.IsReachable;

				/*if (field.isField()){
					staticInitializerContext.handledExceptions = NoExceptions; // no exception is allowed jls8.3.2
				} else {*/
				staticInitializerContext.handledExceptions = Binding.ANY_EXCEPTION; // tolerate them all, and record them
				/*}*/
				staticFieldInfo = field.analyseCode(this.staticInitializerScope, staticInitializerContext, staticFieldInfo);
				// in case the initializer is not reachable, use a reinitialized flowInfo and enter a fake reachable
				// branch, since the previous initializer already got the blame.
				if (staticFieldInfo == FlowInfo.DEAD_END) {
					this.staticInitializerScope.problemReporter().initializerMustCompleteNormally(field);
					staticFieldInfo = FlowInfo.initial(this.maxFieldCount).setReachMode(FlowInfo.UNREACHABLE_OR_DEAD);
				}
			} else {
				if ((nonStaticFieldInfo.tagBits & FlowInfo.UNREACHABLE_OR_DEAD) != 0)
					field.bits &= ~ASTNode.IsReachable;

				/*if (field.isField()){
					initializerContext.handledExceptions = NoExceptions; // no exception is allowed jls8.3.2
				} else {*/
					initializerContext.handledExceptions = Binding.ANY_EXCEPTION; // tolerate them all, and record them
				/*}*/
				nonStaticFieldInfo = field.analyseCode(this.initializerScope, initializerContext, nonStaticFieldInfo);
				// in case the initializer is not reachable, use a reinitialized flowInfo and enter a fake reachable
				// branch, since the previous initializer already got the blame.
				if (nonStaticFieldInfo == FlowInfo.DEAD_END) {
					this.initializerScope.problemReporter().initializerMustCompleteNormally(field);
					nonStaticFieldInfo = FlowInfo.initial(this.maxFieldCount).setReachMode(FlowInfo.UNREACHABLE_OR_DEAD);
				}
				if (fieldNeedingClose == null && useOwningAnnotations && isCloseable && (field.binding.tagBits & TagBits.AnnotationOwning) != 0) {
					fieldNeedingClose = field;
				}
			}
		}
	}
	if (this.memberTypes != null) {
		for (TypeDeclaration memberType : this.memberTypes) {
			if (flowContext != null){ // local type
				memberType.analyseCode(this.scope, flowContext, nonStaticFieldInfo.copy().setReachMode(flowInfo.reachMode())); // reset reach mode in case initializers did abrupt completely
			} else {
				memberType.analyseCode(this.scope);
			}
		}
	}
	if (this.scope.compilerOptions().complianceLevel >= ClassFileConstants.JDK9) {
		// synthesize <clinit> if one is not present. Required to initialize
		// synthetic final fields as modifying final fields outside of a <clinit>
		// is disallowed in Java 9
		if (this.methods == null || !this.methods[0].isClinit()) {
			Clinit clinit = new Clinit(this.compilationResult);
			clinit.declarationSourceStart = clinit.sourceStart = this.sourceStart;
			clinit.declarationSourceEnd = clinit.sourceEnd = this.sourceEnd;
			clinit.bodyEnd = this.sourceEnd;
			int length = this.methods == null ? 0 : this.methods.length;
			AbstractMethodDeclaration[] methodDeclarations = new AbstractMethodDeclaration[length + 1];
			methodDeclarations[0] = clinit;
			if (this.methods != null)
				System.arraycopy(this.methods, 0, methodDeclarations, 1, length);
		}
	}
	if (this.methods != null) {
		UnconditionalFlowInfo outerInfo = flowInfo.unconditionalFieldLessCopy();
		FlowInfo constructorInfo = nonStaticFieldInfo.unconditionalInits().discardNonFieldInitializations().addInitializationsFrom(outerInfo);
		SimpleSetOfCharArray jUnitMethodSourceValues = getJUnitMethodSourceValues();
		for (AbstractMethodDeclaration method : this.methods) {
			if (method.ignoreFurtherInvestigation)
				continue;
			if (method.isInitializationMethod()) {
				// pass down the appropriate initializerContext:
				if (method.isStatic()) { // <clinit>
					((Clinit)method).analyseCode(
						this.scope,
						staticInitializerContext,
						staticFieldInfo.unconditionalInits().discardNonFieldInitializations().addInitializationsFrom(outerInfo));
				} else { // constructor
					((ConstructorDeclaration)method).analyseCode(this.scope, initializerContext, constructorInfo.copy(), flowInfo.reachMode());
				}
			} else { // regular method
				// JUnit 5 only accepts methods without arguments for method sources
				if (method.arguments == null && jUnitMethodSourceValues.includes(method.selector) && method.binding != null) {
					method.binding.modifiers |= ExtraCompilerModifiers.AccLocallyUsed;
				}
				// pass down the parentContext (NOT an initializer context, see above):
				((MethodDeclaration)method).analyseCode(this.scope, parentContext, flowInfo.copy());
				if (fieldNeedingClose != null && CharOperation.equals(TypeConstants.CLOSE, method.selector) && method.arguments == null) {
					fieldNeedingClose = null;
				}
			}
		}
	}
	if (fieldNeedingClose != null) {
		this.scope.problemReporter().missingImplementationOfClose(fieldNeedingClose);
	}
	// enable enum support ?
	if (this.binding.isEnum() && !this.binding.isAnonymousType()) {
		this.enumValuesSyntheticfield = this.binding.addSyntheticFieldForEnumValues();
	}
}

private SimpleSetOfCharArray getJUnitMethodSourceValues() {
	SimpleSetOfCharArray junitMethodSourceValues = new SimpleSetOfCharArray();
	for (AbstractMethodDeclaration methodDeclaration : this.methods) {
		if (methodDeclaration.annotations != null) {
			for (Annotation annotation : methodDeclaration.annotations) {
				if (annotation.resolvedType != null && annotation.resolvedType.id == TypeIds.T_OrgJunitJupiterParamsProviderMethodSource) {
					addJUnitMethodSourceValues(junitMethodSourceValues, annotation, methodDeclaration.selector);
				}
			}
		}
	}
	return junitMethodSourceValues;
}

private void addJUnitMethodSourceValues(SimpleSetOfCharArray junitMethodSourceValues, Annotation annotation, char[] methodName) {
	for (MemberValuePair memberValuePair : annotation.memberValuePairs()) {
		if (CharOperation.equals(memberValuePair.name, TypeConstants.VALUE)) {
			Expression value = memberValuePair.value;
			if (value instanceof ArrayInitializer) { // e.g. @MethodSource({ "someMethod" })
				ArrayInitializer arrayInitializer = (ArrayInitializer) value;
				for (Expression arrayValue : arrayInitializer.expressions) {
					junitMethodSourceValues.add(getValueAsChars(arrayValue));
				}
			} else {
				junitMethodSourceValues.add(getValueAsChars(value));
			}
			return;
		}
	}
	// value member not specified (i.e. marker annotation): JUnit 5 defaults to the test method's name
	junitMethodSourceValues.add(methodName);
}

private char[] getValueAsChars(Expression value) {
	if (value instanceof StringLiteral) { // e.g. "someMethod"
		return ((StringLiteral) value).source();
	} else if (value.constant instanceof StringConstant) { // e.g. SOME_CONSTANT + "value"
		return ((StringConstant) value.constant).stringValue().toCharArray();
	}
	return CharOperation.NO_CHAR;
}

public final static int kind(int flags) {
	switch (flags & (ClassFileConstants.AccInterface|ClassFileConstants.AccAnnotation|ClassFileConstants.AccEnum|ExtraCompilerModifiers.AccRecord)) {
		case ClassFileConstants.AccInterface :
			return TypeDeclaration.INTERFACE_DECL;
		case ClassFileConstants.AccInterface|ClassFileConstants.AccAnnotation :
			return TypeDeclaration.ANNOTATION_TYPE_DECL;
		case ClassFileConstants.AccEnum :
			return TypeDeclaration.ENUM_DECL;
		case ExtraCompilerModifiers.AccRecord :
			return TypeDeclaration.RECORD_DECL;
		default :
			return TypeDeclaration.CLASS_DECL;
	}
}

public boolean isRecord() {
	return (this.modifiers & ExtraCompilerModifiers.AccRecord) != 0;
}
public boolean isImplicitType() {
	return false;
}
/*
 * Access emulation for a local type
 * force to emulation of access to direct enclosing instance.
 * By using the initializer scope, we actually only request an argument emulation, the
 * field is not added until actually used. However we will force allocations to be qualified
 * with an enclosing instance.
 * 15.9.2
 */
public void manageEnclosingInstanceAccessIfNecessary(BlockScope currentScope, FlowInfo flowInfo) {
	if ((flowInfo.tagBits & FlowInfo.UNREACHABLE_OR_DEAD) != 0) return;
	NestedTypeBinding nestedType = (NestedTypeBinding) this.binding;

	MethodScope methodScope = currentScope.methodScope();
	if (!methodScope.isStatic) {
		boolean earlySeen = false;
		Scope outerScope = currentScope.parent;
		if (!methodScope.isConstructorCall) {
			nestedType.addSyntheticArgumentAndField(nestedType.enclosingType());
			outerScope = outerScope.enclosingInstanceScope();
			earlySeen = methodScope.isInsideEarlyConstructionContext(nestedType.enclosingType(), false);
		}
		addSyntheticArgumentsBeyondEarlyConstructionContext(earlySeen, outerScope);
	}
	// add superclass enclosing instance arg for anonymous types (if necessary)
	if (nestedType.isAnonymousType()) {
		ReferenceBinding superclassBinding = (ReferenceBinding)nestedType.superclass.erasure();
		if (superclassBinding.enclosingType() != null && !superclassBinding.isStatic()) {
			if (!superclassBinding.isLocalType()
					|| ((NestedTypeBinding)superclassBinding).getSyntheticField(superclassBinding.enclosingType(), true) != null
					|| superclassBinding.isMemberType()){
				nestedType.addSyntheticArgument(superclassBinding.enclosingType());
			}
		}
		// From 1.5 on, provide access to enclosing instance synthetic constructor argument when declared inside constructor call
		// only for direct anonymous type
		//public class X {
		//	void foo() {}
		//	class M {
		//		M(Object o) {}
		//		M() { this(new Object() { void baz() { foo(); }}); } // access to #foo() indirects through constructor synthetic arg: val$this$0
		//	}
		//}
		if (!methodScope.isStatic && methodScope.isConstructorCall) {
			ReferenceBinding enclosing = nestedType.enclosingType();
			if (enclosing.isNestedType()) {
				NestedTypeBinding nestedEnclosing = (NestedTypeBinding)enclosing;
//					if (nestedEnclosing.findSuperTypeErasingTo(nestedEnclosing.enclosingType()) == null) { // only if not inheriting
					SyntheticArgumentBinding syntheticEnclosingInstanceArgument = nestedEnclosing.getSyntheticArgument(nestedEnclosing.enclosingType(), true, false);
					if (syntheticEnclosingInstanceArgument != null) {
						nestedType.addSyntheticArgumentAndField(syntheticEnclosingInstanceArgument);
					}
				}
//				}
		}
	}
}


/**
 * Access emulation for a local member type
 * force to emulation of access to direct enclosing instance.
 * By using the initializer scope, we actually only request an argument emulation, the
 * field is not added until actually used. However we will force allocations to be qualified
 * with an enclosing instance.
 *
 * Local member cannot be static.
 */
public void manageEnclosingInstanceAccessIfNecessary(ClassScope currentScope, FlowInfo flowInfo) {
	if ((flowInfo.tagBits & FlowInfo.UNREACHABLE_OR_DEAD) == 0) {
		NestedTypeBinding nestedType = (NestedTypeBinding) this.binding;
		nestedType.addSyntheticArgumentAndField(this.binding.enclosingType());
		boolean earlySeen = this.scope.insideEarlyConstructionContext;
		addSyntheticArgumentsBeyondEarlyConstructionContext(earlySeen, currentScope);
	}
}

@Override
public void ensureSyntheticOuterAccess(SourceTypeBinding targetEnclosing) {
	((NestedTypeBinding) this.binding).addSyntheticArgumentAndField(targetEnclosing);
}

/**
 * A {@code <clinit>} will be requested as soon as static fields or assertions are present. It will be eliminated during
 * classfile creation if no bytecode was actually produced based on some optimizations/compiler settings.
 */
public final boolean needClassInitMethod() {
	// always need a <clinit> when assertions are present
	if ((this.bits & ASTNode.ContainsAssertion) != 0)
		return true;

	switch (kind(this.modifiers)) {
		case TypeDeclaration.INTERFACE_DECL:
		case TypeDeclaration.ANNOTATION_TYPE_DECL:
			return this.fields != null; // fields are implicitly statics
		case TypeDeclaration.ENUM_DECL:
			return true; // even if no enum constants, need to set $VALUES array
	}
	if (this.fields != null) {
		for (int i = this.fields.length; --i >= 0;) {
			FieldDeclaration field = this.fields[i];
			//need to test the modifier directly while there is no binding yet
			if ((field.modifiers & ClassFileConstants.AccStatic) != 0)
				return true; // TODO (philippe) shouldn't it check whether field is initializer or has some initial value ?
		}
	}
	return false;
}

public void parseMethods(Parser parser, CompilationUnitDeclaration unit) {
	//connect method bodies
	if (unit.ignoreMethodBodies)
		return;

	//members
	if (this.memberTypes != null) {
		int length = this.memberTypes.length;
		for (int i = 0; i < length; i++) {
			TypeDeclaration typeDeclaration = this.memberTypes[i];
			typeDeclaration.parseMethods(parser, unit);
			this.bits |= (typeDeclaration.bits & ASTNode.HasSyntaxErrors);
		}
	}

	//methods
	if (this.methods != null) {
		int length = this.methods.length;
		for (int i = 0; i < length; i++) {
			AbstractMethodDeclaration abstractMethodDeclaration = this.methods[i];
			abstractMethodDeclaration.parseStatements(parser, unit);
			this.bits |= (abstractMethodDeclaration.bits & ASTNode.HasSyntaxErrors);
		}
	}

	//initializers
	if (this.fields != null) {
		int length = this.fields.length;
		for (int i = 0; i < length; i++) {
			final FieldDeclaration fieldDeclaration = this.fields[i];
			switch(fieldDeclaration.getKind()) {
				case AbstractVariableDeclaration.INITIALIZER:
					((Initializer) fieldDeclaration).parseStatements(parser, this, unit);
					this.bits |= (fieldDeclaration.bits & ASTNode.HasSyntaxErrors);
					break;
			}
		}
	}
}

@Override
public StringBuilder print(int indent, StringBuilder output) {
	if (this.javadoc != null) {
		this.javadoc.print(indent, output);
	}
	if ((this.bits & ASTNode.IsAnonymousType) == 0) {
		printIndent(indent, output);
		printHeader(0, output);
	}
	return printBody(indent, output);
}

public StringBuilder printBody(int indent, StringBuilder output) {
	output.append(" {"); //$NON-NLS-1$
	if (this.memberTypes != null) {
		for (TypeDeclaration memberType : this.memberTypes) {
			if (memberType != null) {
				output.append('\n');
				memberType.print(indent + 1, output);
			}
		}
	}
	if (this.fields != null) {
		for (FieldDeclaration field : this.fields) {
			if (field != null) {
				output.append('\n');
				field.print(indent + 1, output);
			}
		}
	}
	if (this.methods != null) {
		for (AbstractMethodDeclaration method : this.methods) {
			if (method != null) {
				output.append('\n');
				method.print(indent + 1, output);
			}
		}
	}
	output.append('\n');
	return printIndent(indent, output).append('}');
}

public StringBuilder printHeader(int indent, StringBuilder output) {
	printModifiers(this.modifiers, output);
	if (this.annotations != null) {
		printAnnotations(this.annotations, output);
		output.append(' ');
	}

	switch (kind(this.modifiers)) {
		case TypeDeclaration.CLASS_DECL :
			output.append("class "); //$NON-NLS-1$
			break;
		case TypeDeclaration.INTERFACE_DECL :
			output.append("interface "); //$NON-NLS-1$
			break;
		case TypeDeclaration.ENUM_DECL :
			output.append("enum "); //$NON-NLS-1$
			break;
		case TypeDeclaration.ANNOTATION_TYPE_DECL :
			output.append("@interface "); //$NON-NLS-1$
			break;
		case TypeDeclaration.RECORD_DECL :
			output.append("record "); //$NON-NLS-1$
			break;
	}
	output.append(this.name);
	if (this.isRecord()) {
		output.append('(');
		for (int i = 0, length = this.recordComponents.length; i < length; i++) {
			if (i > 0) output.append(", "); //$NON-NLS-1$
			output.append(this.recordComponents[i].type.getTypeName()[0]);
			output.append(' ');
			output.append(this.recordComponents[i].name);
		}
		output.append(')');
	}
	if (this.typeParameters != null) {
		output.append("<");//$NON-NLS-1$
		for (int i = 0; i < this.typeParameters.length; i++) {
			if (i > 0) output.append( ", "); //$NON-NLS-1$
			this.typeParameters[i].print(0, output);
		}
		output.append(">");//$NON-NLS-1$
	}

	if (!this.isRecord() && this.superclass != null) {
		output.append(" extends ");  //$NON-NLS-1$
		this.superclass.print(0, output);
	}
	if (this.superInterfaces != null && this.superInterfaces.length > 0) {
		switch (kind(this.modifiers)) {
			case TypeDeclaration.CLASS_DECL :
			case TypeDeclaration.ENUM_DECL :
			case TypeDeclaration.RECORD_DECL :
				output.append(" implements "); //$NON-NLS-1$
				break;
			case TypeDeclaration.INTERFACE_DECL :
			case TypeDeclaration.ANNOTATION_TYPE_DECL :
				output.append(" extends "); //$NON-NLS-1$
				break;
		}
		for (int i = 0; i < this.superInterfaces.length; i++) {
			if (i > 0) output.append( ", "); //$NON-NLS-1$
			this.superInterfaces[i].print(0, output);
		}
	}
	if (this.permittedTypes != null && this.permittedTypes.length > 0) {
		output.append(" permits "); //$NON-NLS-1$
		for (int i = 0; i < this.permittedTypes.length; i++) {
			if (i > 0) output.append( ", "); //$NON-NLS-1$
			this.permittedTypes[i].print(0, output);
		}
	}
	return output;
}

@Override
public StringBuilder printStatement(int tab, StringBuilder output) {
	return print(tab, output);
}

public AbstractVariableDeclaration [] protoFieldDeclarations() {
	return Stream.concat(Stream.of(this.recordComponents), Stream.of(this.fields == null ? ASTNode.NO_FIELD_DECLARATIONS : this.fields)).toArray(AbstractVariableDeclaration[]::new);
}

/*
 * Keep track of number of lambda/method reference expressions in this type declaration.
 * Return the 0 based "ordinal" in the TypeDeclaration.
 */
public int record(FunctionalExpression expression) {
	return this.functionalExpressionsCount++;
}

public void resolve() {
	SourceTypeBinding sourceType = this.binding;
	if (sourceType == null) {
		this.ignoreFurtherInvestigation = true;
		return;
	}
	try {
		if (CharOperation.equals(this.name, TypeConstants.VAR)) {
			if (this.scope.compilerOptions().sourceLevel < ClassFileConstants.JDK10) {
				this.scope.problemReporter().varIsReservedTypeNameInFuture(this);
			} else {
				this.scope.problemReporter().varIsReservedTypeName(this);
			}
		}
		this.scope.problemReporter().validateRestrictedKeywords(this.name, this);
		// resolve annotations and check @Deprecated annotation
		long annotationTagBits = sourceType.getAnnotationTagBits();
		if ((annotationTagBits & TagBits.AnnotationDeprecated) == 0
				&& (sourceType.modifiers & ClassFileConstants.AccDeprecated) != 0) {
			this.scope.problemReporter().missingDeprecatedAnnotationForType(this);
		}
		if ((annotationTagBits & TagBits.AnnotationFunctionalInterface) != 0) {
			if (this.binding.isSealed()) {
				this.scope.problemReporter().functionalInterfaceMayNotBeSealed(this);
			} else if (!this.binding.isFunctionalInterface(this.scope)) {
				this.scope.problemReporter().notAFunctionalInterface(this);
			}
		}

		if (!sourceType.isRecord() && (this.bits & ASTNode.UndocumentedEmptyBlock) != 0)
			this.scope.problemReporter().undocumentedEmptyBlock(this.bodyStart - 1, this.bodyEnd);

		boolean needSerialVersion =
						this.scope.compilerOptions().getSeverity(CompilerOptions.MissingSerialVersion) != ProblemSeverities.Ignore
						&& sourceType.isClass()
						&& !sourceType.isAnonymousType()
						&& !sourceType.isRecord()
						&& sourceType.findSuperTypeOriginatingFrom(TypeIds.T_JavaIoExternalizable, false /*Externalizable is not a class*/) == null
						&& sourceType.findSuperTypeOriginatingFrom(TypeIds.T_JavaIoSerializable, false /*Serializable is not a class*/) != null;

		if (needSerialVersion) {
			// if Object writeReplace() throws java.io.ObjectStreamException is present, then no serialVersionUID is needed
			// see https://bugs.eclipse.org/bugs/show_bug.cgi?id=101476
			CompilationUnitScope compilationUnitScope = this.scope.compilationUnitScope();
			MethodBinding methodBinding = sourceType.getExactMethod(TypeConstants.WRITEREPLACE, Binding.NO_TYPES, compilationUnitScope);
			ReferenceBinding[] throwsExceptions;
			needSerialVersion =
				methodBinding == null
					|| !methodBinding.isValidBinding()
					|| methodBinding.returnType.id != TypeIds.T_JavaLangObject
					|| (throwsExceptions = methodBinding.thrownExceptions).length != 1
					|| throwsExceptions[0].id != TypeIds.T_JavaIoObjectStreamException;
			if (needSerialVersion) {
				// check the presence of an implementation of the methods
				// private void writeObject(java.io.ObjectOutputStream out) throws IOException
				// private void readObject(java.io.ObjectInputStream out) throws IOException
				boolean hasWriteObjectMethod = false;
				boolean hasReadObjectMethod = false;
				TypeBinding argumentTypeBinding = this.scope.getType(TypeConstants.JAVA_IO_OBJECTOUTPUTSTREAM, 3);
				if (argumentTypeBinding.isValidBinding()) {
					methodBinding = sourceType.getExactMethod(TypeConstants.WRITEOBJECT, new TypeBinding[] { argumentTypeBinding }, compilationUnitScope);
					hasWriteObjectMethod = methodBinding != null
							&& methodBinding.isValidBinding()
							&& methodBinding.modifiers == ClassFileConstants.AccPrivate
							&& methodBinding.returnType == TypeBinding.VOID
							&& (throwsExceptions = methodBinding.thrownExceptions).length == 1
							&& throwsExceptions[0].id == TypeIds.T_JavaIoException;
				}
				argumentTypeBinding = this.scope.getType(TypeConstants.JAVA_IO_OBJECTINPUTSTREAM, 3);
				if (argumentTypeBinding.isValidBinding()) {
					methodBinding = sourceType.getExactMethod(TypeConstants.READOBJECT, new TypeBinding[] { argumentTypeBinding }, compilationUnitScope);
					hasReadObjectMethod = methodBinding != null
							&& methodBinding.isValidBinding()
							&& methodBinding.modifiers == ClassFileConstants.AccPrivate
							&& methodBinding.returnType == TypeBinding.VOID
							&& (throwsExceptions = methodBinding.thrownExceptions).length == 1
							&& throwsExceptions[0].id == TypeIds.T_JavaIoException;
				}
				needSerialVersion = !hasWriteObjectMethod || !hasReadObjectMethod;
			}
		}
		// generics (and non static generic members) cannot extend Throwable
		if (sourceType.findSuperTypeOriginatingFrom(TypeIds.T_JavaLangThrowable, true) != null) {
			ReferenceBinding current = sourceType;
			checkEnclosedInGeneric : do {
				if (current.isGenericType()) {
					this.scope.problemReporter().genericTypeCannotExtendThrowable(this);
					break checkEnclosedInGeneric;
				}
				if (current.isStatic()) break checkEnclosedInGeneric;
				if (current.isLocalType()) {
					NestedTypeBinding nestedType = (NestedTypeBinding) current.erasure();
					if (nestedType.scope.methodScope().isStatic) break checkEnclosedInGeneric;
				}
			} while ((current = current.enclosingType()) != null);
		}
		// this.maxFieldCount might already be set
		int localMaxFieldCount = 0;
		int lastVisibleFieldID = -1;
		boolean hasEnumConstants = false;
		FieldDeclaration[] enumConstantsWithoutBody = null;

		if (this.memberTypes != null) {
			for (TypeDeclaration memberType : this.memberTypes) {
				memberType.resolve(this.scope);
			}
		}

		for (RecordComponent rc : this.recordComponents) {
			localMaxFieldCount++;
			rc.resolve(this.initializerScope);
		}

		if (this.fields != null) {
			for (int i = 0, count = this.fields.length; i < count; i++) {
				FieldDeclaration field = this.fields[i];
				switch(field.getKind()) {
					case AbstractVariableDeclaration.ENUM_CONSTANT:
						hasEnumConstants = true;
						if (!(field.initialization instanceof QualifiedAllocationExpression)) {
							if (enumConstantsWithoutBody == null)
								enumConstantsWithoutBody = new FieldDeclaration[count];
							enumConstantsWithoutBody[i] = field;
						}
						//$FALL-THROUGH$
					case AbstractVariableDeclaration.FIELD:
						FieldBinding fieldBinding = field.binding;
						if (fieldBinding == null) {
							// still discover secondary errors
							if (field.initialization != null) field.initialization.resolve(field.isStatic() ? this.staticInitializerScope : this.initializerScope);
							this.ignoreFurtherInvestigation = true;
							continue;
						}
						if (needSerialVersion
								&& ((fieldBinding.modifiers & (ClassFileConstants.AccStatic | ClassFileConstants.AccFinal)) == (ClassFileConstants.AccStatic | ClassFileConstants.AccFinal))
								&& CharOperation.equals(TypeConstants.SERIALVERSIONUID, fieldBinding.name)
								&& TypeBinding.equalsEquals(TypeBinding.LONG, fieldBinding.type)) {
							needSerialVersion = false;
						}
						localMaxFieldCount++;
						lastVisibleFieldID = field.binding.id;
						break;

					case AbstractVariableDeclaration.INITIALIZER:
						 ((Initializer) field).lastVisibleFieldID = lastVisibleFieldID + 1;
						break;
				}
				if (this.isRecord()) {
					field.javadoc = this.javadoc;
				}
				field.resolve(field.isStatic() ? this.staticInitializerScope : this.initializerScope);
			}
		}
		if (this.maxFieldCount < localMaxFieldCount) {
			this.maxFieldCount = localMaxFieldCount;
		}
		if (needSerialVersion) {
			//check that the current type doesn't extend javax.rmi.CORBA.Stub
			TypeBinding javaxRmiCorbaStub = this.scope.getType(TypeConstants.JAVAX_RMI_CORBA_STUB, 4);
			if (javaxRmiCorbaStub.isValidBinding()) {
				ReferenceBinding superclassBinding = this.binding.superclass;
				loop: while (superclassBinding != null) {
					if (TypeBinding.equalsEquals(superclassBinding, javaxRmiCorbaStub)) {
						needSerialVersion = false;
						break loop;
					}
					superclassBinding = superclassBinding.superclass();
				}
			}
			if (needSerialVersion) {
				this.scope.problemReporter().missingSerialVersion(this);
			}
		}

		// check extends/implements for annotation type
		switch(kind(this.modifiers)) {
			case TypeDeclaration.ANNOTATION_TYPE_DECL :
				if (this.superclass != null) {
					this.scope.problemReporter().annotationTypeDeclarationCannotHaveSuperclass(this);
				}
				if (this.superInterfaces != null) {
					this.scope.problemReporter().annotationTypeDeclarationCannotHaveSuperinterfaces(this);
				}
				break;
			case TypeDeclaration.ENUM_DECL :
				// check enum abstract methods
				if (this.binding.isAbstract()) {
					if (!hasEnumConstants) {
						for (final AbstractMethodDeclaration methodDeclaration : this.methods) {
							if (methodDeclaration.isAbstract() && methodDeclaration.binding != null)
								this.scope.problemReporter().enumAbstractMethodMustBeImplemented(methodDeclaration);
						}
					} else if (enumConstantsWithoutBody != null) {
						for (final AbstractMethodDeclaration methodDeclaration : this.methods) {
							if (methodDeclaration.isAbstract() && methodDeclaration.binding != null) {
								for (FieldDeclaration decl : enumConstantsWithoutBody)
									if (decl != null)
										this.scope.problemReporter().enumConstantMustImplementAbstractMethod(methodDeclaration, decl);
							}
						}
					}
				}
				break;
		}

		int missingAbstractMethodslength = this.missingAbstractMethods == null ? 0 : this.missingAbstractMethods.length;
		int methodsLength = this.methods == null ? 0 : this.methods.length;
		if ((methodsLength + missingAbstractMethodslength) > 0xFFFF) {
			this.scope.problemReporter().tooManyMethods(this);
		}
		if (this.methods != null) {
			for (AbstractMethodDeclaration method : this.methods) {
				method.resolve(this.scope);
			}
		}
		// Resolve javadoc
		if (this.javadoc != null) {
			if (this.scope != null && (this.name != TypeConstants.PACKAGE_INFO_NAME)) {
				// if the type is package-info, the javadoc was resolved as part of the compilation unit javadoc
				this.javadoc.resolve(this.scope);
			}
		} else if (!sourceType.isLocalType()) {
			// Set javadoc visibility
			int visibility = sourceType.modifiers & ExtraCompilerModifiers.AccVisibilityMASK;
			ProblemReporter reporter = this.scope.problemReporter();
			try {
				int severity = reporter.computeSeverity(IProblem.JavadocMissing);
				if (severity != ProblemSeverities.Ignore) {
					if (this.enclosingType != null) {
						visibility = Util.computeOuterMostVisibility(this.enclosingType, visibility);
					}
					int javadocModifiers = (this.binding.modifiers & ~ExtraCompilerModifiers.AccVisibilityMASK) | visibility;
					reporter.javadocMissing(this.sourceStart, this.sourceEnd, severity, javadocModifiers);
				}
			} finally {
				reporter.close();
			}
		}
		updateNestRelations();
		FieldDeclaration[] fieldsDecls = this.fields;
		if (fieldsDecls != null) {
			for (FieldDeclaration fieldDeclaration : fieldsDecls)
				fieldDeclaration.resolveJavadoc(this.initializerScope);
		}
		AbstractMethodDeclaration[] methodDecls = this.methods;
		if (methodDecls != null) {
			for (AbstractMethodDeclaration methodDeclaration : methodDecls)
				methodDeclaration.resolveJavadoc();
		}
	} catch (AbortType e) {
		this.ignoreFurtherInvestigation = true;
		return;
	}
}

/**
 * Resolve a local type declaration
 */
@Override
public void resolve(BlockScope blockScope) {

	// need to build its scope first and proceed with binding's creation
	if ((this.bits & ASTNode.IsAnonymousType) == 0) {
		// check collision scenarii
		Binding existing = blockScope.getType(this.name);
		if (existing instanceof ReferenceBinding
				&& existing != this.binding
				&& existing.isValidBinding()) {
			ReferenceBinding existingType = (ReferenceBinding) existing;
			if (existingType instanceof TypeVariableBinding) {
				blockScope.problemReporter().typeHiding(this, (TypeVariableBinding) existingType);
				// https://bugs.eclipse.org/bugs/show_bug.cgi?id=312989, check for collision with enclosing type.
				Scope outerScope = blockScope.parent;
checkOuterScope:while (outerScope != null) {
					Binding existing2 = outerScope.getType(this.name);
					if (existing2 instanceof TypeVariableBinding && existing2.isValidBinding()) {
						TypeVariableBinding tvb = (TypeVariableBinding) existingType;
						Binding declaringElement = tvb.declaringElement;
						if (declaringElement instanceof ReferenceBinding
								&& CharOperation.equals(((ReferenceBinding) declaringElement).sourceName(), this.name)) {
							blockScope.problemReporter().typeCollidesWithEnclosingType(this);
							break checkOuterScope;
						}
					} else if (existing2 instanceof ReferenceBinding
							&& existing2.isValidBinding()
							&& outerScope.isDefinedInType((ReferenceBinding) existing2)) {
							blockScope.problemReporter().typeCollidesWithEnclosingType(this);
							break checkOuterScope;
					} else if (existing2 == null) {
						break checkOuterScope;
					}
					outerScope = outerScope.parent;
				}
			} else if (existingType instanceof LocalTypeBinding
						&& ((LocalTypeBinding) existingType).scope.methodScope() == blockScope.methodScope()) {
					// dup in same method
					blockScope.problemReporter().duplicateNestedType(this);
			} else if (existingType instanceof LocalTypeBinding && blockScope.isLambdaSubscope()
					&& blockScope.enclosingLambdaScope().enclosingMethodScope() == ((LocalTypeBinding) existingType).scope.methodScope()) {
				blockScope.problemReporter().duplicateNestedType(this);
			} else if (blockScope.isDefinedInType(existingType)) {
				//	collision with enclosing type
				blockScope.problemReporter().typeCollidesWithEnclosingType(this);
			} else if (blockScope.isDefinedInSameUnit(existingType)){ // only consider hiding inside same unit
				// hiding sibling
				blockScope.problemReporter().typeHiding(this, existingType);
			}
		}
		blockScope.addLocalType(this);
	}

	if (this.binding != null) {
		// remember local types binding for innerclass emulation propagation
		blockScope.referenceCompilationUnit().record((LocalTypeBinding)this.binding);

		// binding is not set if the receiver could not be created
		resolve();
		updateMaxFieldCount();
	}
}

/**
 * Resolve a member type declaration (can be a local member)
 */
public void resolve(ClassScope upperScope) {
	// member scopes are already created
	// request the construction of a binding if local member type

	if (this.binding != null && this.binding instanceof LocalTypeBinding) {
		// remember local types binding for innerclass emulation propagation
		upperScope.referenceCompilationUnit().record((LocalTypeBinding)this.binding);
	}
	resolve();
	updateMaxFieldCount();
}

/**
 * Resolve a top level type declaration
 */
public void resolve(CompilationUnitScope upperScope) {
	// top level : scope are already created
	resolve();
	updateMaxFieldCount();
}

@Override
public void tagAsHavingErrors() {
	this.ignoreFurtherInvestigation = true;
}

/**
 *	Iteration for a package member type
 */
public void traverse(ASTVisitor visitor, CompilationUnitScope unitScope) {
	try {
		if (visitor.visit(this, unitScope)) {
			if (this.javadoc != null) {
				this.javadoc.traverse(visitor, this.scope);
			}
			if (this.annotations != null) {
				int annotationsLength = this.annotations.length;
				for (int i = 0; i < annotationsLength; i++)
					this.annotations[i].traverse(visitor, this.staticInitializerScope);
			}
			if (this.superclass != null)
				this.superclass.traverse(visitor, this.scope);
			if (this.superInterfaces != null) {
				int length = this.superInterfaces.length;
				for (int i = 0; i < length; i++)
					this.superInterfaces[i].traverse(visitor, this.scope);
			}
			if (this.permittedTypes != null) {
				int length = this.permittedTypes.length;
				for (int i = 0; i < length; i++)
					this.permittedTypes[i].traverse(visitor, this.scope);
			}
			if (this.typeParameters != null) {
				int length = this.typeParameters.length;
				for (int i = 0; i < length; i++) {
					this.typeParameters[i].traverse(visitor, this.scope);
				}
			}

			for (RecordComponent component : this.recordComponents)
				component.traverse(visitor, this.initializerScope);

			if (this.memberTypes != null) {
				int length = this.memberTypes.length;
				for (int i = 0; i < length; i++)
					this.memberTypes[i].traverse(visitor, this.scope);
			}
			if (this.fields != null) {
				int length = this.fields.length;
				for (int i = 0; i < length; i++) {
					FieldDeclaration field;
					if ((field = this.fields[i]).isStatic()) {
						field.traverse(visitor, this.staticInitializerScope);
					} else {
						field.traverse(visitor, this.initializerScope);
					}
				}
			}
			if (this.methods != null) {
				int length = this.methods.length;
				for (int i = 0; i < length; i++)
					this.methods[i].traverse(visitor, this.scope);
			}
		}
		visitor.endVisit(this, unitScope);
	} catch (AbortType e) {
		// silent abort
	}
}

/**
 *	Iteration for a local inner type
 */
@Override
public void traverse(ASTVisitor visitor, BlockScope blockScope) {
	try {
		if (visitor.visit(this, blockScope)) {
			if (this.javadoc != null) {
				this.javadoc.traverse(visitor, this.scope);
			}
			if (this.annotations != null) {
				int annotationsLength = this.annotations.length;
				for (int i = 0; i < annotationsLength; i++)
					this.annotations[i].traverse(visitor, this.staticInitializerScope);
			}
			if (this.superclass != null)
				this.superclass.traverse(visitor, this.scope);
			if (this.superInterfaces != null) {
				int length = this.superInterfaces.length;
				for (int i = 0; i < length; i++)
					this.superInterfaces[i].traverse(visitor, this.scope);
			}
			if (this.permittedTypes != null) {
				int length = this.permittedTypes.length;
				for (int i = 0; i < length; i++)
					this.permittedTypes[i].traverse(visitor, this.scope);
			}
			if (this.typeParameters != null) {
				int length = this.typeParameters.length;
				for (int i = 0; i < length; i++) {
					this.typeParameters[i].traverse(visitor, this.scope);
				}
			}

			for (RecordComponent component : this.recordComponents)
				component.traverse(visitor, this.initializerScope);

			if (this.memberTypes != null) {
				int length = this.memberTypes.length;
				for (int i = 0; i < length; i++)
					this.memberTypes[i].traverse(visitor, this.scope);
			}
			if (this.fields != null) {
				int length = this.fields.length;
				for (int i = 0; i < length; i++) {
					FieldDeclaration field = this.fields[i];
					if (field.isStatic() && !field.isFinal()) {
						// local type cannot have static fields that are not final, https://bugs.eclipse.org/bugs/show_bug.cgi?id=244544
					} else {
						field.traverse(visitor, this.initializerScope);
					}
				}
			}
			if (this.methods != null) {
				int length = this.methods.length;
				for (int i = 0; i < length; i++)
					this.methods[i].traverse(visitor, this.scope);
			}
		}
		visitor.endVisit(this, blockScope);
	} catch (AbortType e) {
		// silent abort
	}
}

/**
 *	Iteration for a member innertype
 */
public void traverse(ASTVisitor visitor, ClassScope classScope) {
	try {
		if (visitor.visit(this, classScope)) {
			if (this.javadoc != null) {
				this.javadoc.traverse(visitor, this.scope);
			}
			if (this.annotations != null) {
				int annotationsLength = this.annotations.length;
				for (int i = 0; i < annotationsLength; i++)
					this.annotations[i].traverse(visitor, this.staticInitializerScope);
			}
			if (this.superclass != null)
				this.superclass.traverse(visitor, this.scope);
			if (this.superInterfaces != null) {
				int length = this.superInterfaces.length;
				for (int i = 0; i < length; i++)
					this.superInterfaces[i].traverse(visitor, this.scope);
			}
			if (this.permittedTypes != null) {
				int length = this.permittedTypes.length;
				for (int i = 0; i < length; i++)
					this.permittedTypes[i].traverse(visitor, this.scope);
			}
			if (this.typeParameters != null) {
				int length = this.typeParameters.length;
				for (int i = 0; i < length; i++) {
					this.typeParameters[i].traverse(visitor, this.scope);
				}
			}

			for (RecordComponent component : this.recordComponents)
				component.traverse(visitor, this.initializerScope);

			if (this.memberTypes != null) {
				int length = this.memberTypes.length;
				for (int i = 0; i < length; i++)
					this.memberTypes[i].traverse(visitor, this.scope);
			}
			if (this.fields != null) {
				int length = this.fields.length;
				for (int i = 0; i < length; i++) {
					FieldDeclaration field;
					if ((field = this.fields[i]).isStatic()) {
						field.traverse(visitor, this.staticInitializerScope);
					} else {
						field.traverse(visitor, this.initializerScope);
					}
				}
			}
			if (this.methods != null) {
				int length = this.methods.length;
				for (int i = 0; i < length; i++)
					this.methods[i].traverse(visitor, this.scope);
			}
		}
		visitor.endVisit(this, classScope);
	} catch (AbortType e) {
		// silent abort
	}
}

/**
 * MaxFieldCount's computation is necessary so as to reserve space for
 * the flow info field portions. It corresponds to the maximum amount of
 * fields this class or one of its innertypes have.
 *
 * During name resolution, types are traversed, and the max field count is recorded
 * on the outermost type. It is then propagated down during the flow analysis.
 *
 * This method is doing either up/down propagation.
 */
void updateMaxFieldCount() {
	if (this.binding == null)
		return; // error scenario
	TypeDeclaration outerMostType = this.scope.outerMostClassScope().referenceType();
	if (this.maxFieldCount > outerMostType.maxFieldCount) {
		outerMostType.maxFieldCount = this.maxFieldCount; // up
	} else {
		this.maxFieldCount = outerMostType.maxFieldCount; // down
	}
}

private final void updateNestRelations() {

	ClassScope outerMostClassScope = this.scope;
	Scope skope = this.scope;
	boolean concreteType = true;
	while (skope != null && skope.kind != Scope.COMPILATION_UNIT_SCOPE) {
		switch (skope.kind) {
			case Scope.METHOD_SCOPE :
				ReferenceContext context = ((MethodScope) skope).referenceContext;
				if (context instanceof LambdaExpression lambdaExpression) {
					if (lambdaExpression != lambdaExpression.original) // transient unreal universe.
						concreteType = false;
				}
				break;
			case Scope.CLASS_SCOPE:
				outerMostClassScope = (ClassScope) skope;
				break;
		}
		skope = skope.parent;
	}

	SourceTypeBinding nestHost =  outerMostClassScope != null ? outerMostClassScope.referenceContext.binding : null;
	if (nestHost != null && !this.binding.equals(nestHost)) {
		// https://bugs.eclipse.org/bugs/show_bug.cgi?id=572190 && https://github.com/eclipse-jdt/eclipse.jdt.core/issues/4204:
		// The nest host is valid even for a transient type, but a transient type from a lambda copy is not a member of the nest host (its non-transient original is/will be)
		this.binding.setNestHost(nestHost);
		if (concreteType)
			nestHost.addNestMember(this.binding);
	}
}

public boolean isPackageInfo() {
	return CharOperation.equals(this.name,  TypeConstants.PACKAGE_INFO_NAME);
}
/**
 * Returns whether the type is a secondary one or not.
 */
public boolean isSecondary() {
	return (this.bits & ASTNode.IsSecondaryType) != 0;
}
public void updateSupertypesWithAnnotations(Map<ReferenceBinding,ReferenceBinding> outerUpdates) {
	if (this.binding == null)
		return;
	this.binding.getAnnotationTagBits();
	if (this.binding instanceof MemberTypeBinding) {
		((MemberTypeBinding) this.binding).updateDeprecationFromEnclosing();
	}
	Map<ReferenceBinding,ReferenceBinding> updates = new HashMap<>();
	if (this.typeParameters != null) {
		for (TypeParameter typeParameter : this.typeParameters) {
			typeParameter.updateWithAnnotations(this.scope); // TODO: need to integrate with outerUpdates/updates?
		}
	}
	if (this.superclass != null) {
		this.binding.superclass = updateWithAnnotations(this.superclass, this.binding.superclass, outerUpdates, updates);
	}
	if (this.superInterfaces != null) {
		ReferenceBinding[] superIfcBindings = this.binding.superInterfaces;
		boolean areBindingsConsistent = superIfcBindings != null && superIfcBindings.length == this.superInterfaces.length;
		for (int i = 0; i < this.superInterfaces.length; i++) {
			ReferenceBinding previous = areBindingsConsistent ? superIfcBindings[i] : null;
			ReferenceBinding updated = updateWithAnnotations(this.superInterfaces[i], previous, outerUpdates, updates);
			if (areBindingsConsistent)
				superIfcBindings[i] = updated;
		}
	}
	if (this.memberTypes != null) {
		for (TypeDeclaration memberTypesDecl : this.memberTypes) {
			memberTypesDecl.updateSupertypesWithAnnotations(updates);
		}
	}
	if (this.scope.compilerOptions().isAnnotationBasedResourceAnalysisEnabled) {
		this.binding.detectWrapperResource(); // needs field an methods built
	}
}

protected ReferenceBinding updateWithAnnotations(TypeReference typeRef, ReferenceBinding previousType,
		Map<ReferenceBinding, ReferenceBinding> outerUpdates, Map<ReferenceBinding, ReferenceBinding> updates)
{
	if (!TESTING_GH_2158
			&& previousType instanceof ParameterizedTypeBinding previousPTB
			&& previousPTB.original() instanceof SourceTypeBinding previousOriginal
			&& previousOriginal.supertypeAnnotationsUpdated) {
		// re-initialized parameterized type with updated annotations from the original:
		typeRef.resolvedType = this.scope.environment().createParameterizedType(previousOriginal,		// <- has been updated
				previousPTB.arguments, previousType.enclosingType(), previousType.getAnnotations());	// <- no changes here
	}

	typeRef.updateWithAnnotations(this.scope, 0);
	ReferenceBinding updatedType = (ReferenceBinding) typeRef.resolvedType;
	if (updatedType instanceof ParameterizedTypeBinding) {
		ParameterizedTypeBinding ptb = (ParameterizedTypeBinding) updatedType;
		if (updatedType.enclosingType() != null && outerUpdates.containsKey(ptb.enclosingType())) {
			updatedType = this.scope.environment().createParameterizedType(ptb.genericType(), ptb.typeArguments(), outerUpdates.get(ptb.enclosingType()));
		}
	}
	if (updatedType == null || !updatedType.isValidBinding())
		return previousType;
	if (previousType != null) {
		if (previousType.id == TypeIds.T_JavaLangObject && ((this.binding.tagBits & TagBits.HierarchyHasProblems) != 0))
			return previousType; // keep this cycle breaker
		if (previousType != updatedType) { //$IDENTITY-COMPARISON$
			updates.put(previousType, updatedType);
			this.binding.supertypeAnnotationsUpdated = true;
		}
	}
	return updatedType;
}
}
