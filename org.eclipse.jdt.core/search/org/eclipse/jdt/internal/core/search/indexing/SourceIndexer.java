/*******************************************************************************
 * Copyright (c) 2000, 2022 IBM Corporation and others.
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
package org.eclipse.jdt.internal.core.search.indexing;

import static org.eclipse.jdt.internal.core.JavaModelManager.trace;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.search.SearchDocument;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ISourceElementRequestor;
import org.eclipse.jdt.internal.compiler.SourceElementParser;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FunctionalExpression;
import org.eclipse.jdt.internal.compiler.ast.LambdaExpression;
import org.eclipse.jdt.internal.compiler.ast.ReferenceExpression;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.env.AccessRestriction;
import org.eclipse.jdt.internal.compiler.env.IBinaryType;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.env.ISourceType;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.impl.ITypeRequestor;
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.PackageBinding;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import org.eclipse.jdt.internal.compiler.util.SuffixConstants;
import org.eclipse.jdt.internal.core.DefaultWorkingCopyOwner;
import org.eclipse.jdt.internal.core.JavaModel;
import org.eclipse.jdt.internal.core.JavaModelManager;
import org.eclipse.jdt.internal.core.JavaProject;
import org.eclipse.jdt.internal.core.SourceTypeElementInfo;
import org.eclipse.jdt.internal.core.jdom.CompilationUnit;
import org.eclipse.jdt.internal.core.search.JavaSearchDocument;
import org.eclipse.jdt.internal.core.search.matching.JavaSearchNameEnvironment;
import org.eclipse.jdt.internal.core.search.matching.MethodPattern;
import org.eclipse.jdt.internal.core.search.processing.JobManager;

/**
 * A SourceIndexer indexes java files using a java parser. The following items are indexed:
 * Declarations of:<br>
 * - Classes<br>
 * - Interfaces;<br>
 * - Methods;<br>
 * - Fields;<br>
 * - Lambda expressions;<br>
 * References to:<br>
 * - Methods (with number of arguments); <br>
 * - Fields;<br>
 * - Types;<br>
 * - Constructors.
 */
public class SourceIndexer extends AbstractIndexer implements ITypeRequestor, SuffixConstants {

	private LookupEnvironment lookupEnvironment;
	private CompilerOptions options;
	public ISourceElementRequestor requestor;
	private Parser basicParser;
	private CompilationUnit compilationUnit;
	private CompilationUnitDeclaration cud;
	private org.eclipse.jdt.core.dom.ASTNode dom;
	private static final boolean DEBUG = false;

	public SourceIndexer(SearchDocument document) {
		super(document);
		this.requestor = new SourceIndexerRequestor(this);
	}

	private boolean usedDomBasedIndexing() {
		return Boolean.getBoolean(getClass().getSimpleName() + ".DOM_BASED_INDEXER");  //$NON-NLS-1$
	}

	@Override
	public void indexDocument() {
		if (usedDomBasedIndexing()) {
			indexDocumentFromDOM();
			return;
		}
		// Create a new Parser
		String documentPath = this.document.getPath();
		SourceElementParser parser = this.document.getParser();
		if (parser == null) {
			IPath path = new Path(documentPath);
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(path.segment(0));
			parser = JavaModelManager.getJavaModelManager().indexManager.getSourceElementParser(JavaCore.create(project), this.requestor);
		} else {
			parser.setRequestor(this.requestor);
		}

		// Launch the parser
		char[] source = null;
		char[] name = null;
		try {
			source = this.document.getCharContents();
			name = documentPath.toCharArray();
		} catch(Exception e){
			// ignore
		}
		if (source == null || name == null) return; // could not retrieve document info (e.g. resource was discarded)
		this.compilationUnit = new CompilationUnit(source, name);
		try {
			if (parser.parseCompilationUnit(this.compilationUnit, true, null).hasFunctionalTypes())
				this.document.requireIndexingResolvedDocument();
		} catch (Exception e) {
			if (JobManager.VERBOSE) {
				trace("", e); //$NON-NLS-1$
			}
		}
	}

	@Override
	public void accept(IBinaryType binaryType, PackageBinding packageBinding, AccessRestriction accessRestriction) {
		this.lookupEnvironment.createBinaryTypeFrom(binaryType, packageBinding, accessRestriction);
	}

	@Override
	public void accept(ICompilationUnit unit, AccessRestriction accessRestriction) {
		CompilationResult unitResult = new CompilationResult(unit, 1, 1, this.options.maxProblemsPerUnit);
		CompilationUnitDeclaration parsedUnit = this.basicParser.dietParse(unit, unitResult);
		this.lookupEnvironment.buildTypeBindings(parsedUnit, accessRestriction);
		this.lookupEnvironment.completeTypeBindings(parsedUnit, true);
	}

	@Override
	public void accept(ISourceType[] sourceTypes, PackageBinding packageBinding, AccessRestriction accessRestriction) {
		ISourceType sourceType = sourceTypes[0];
		while (sourceType.getEnclosingType() != null)
			sourceType = sourceType.getEnclosingType();
		SourceTypeElementInfo elementInfo = (SourceTypeElementInfo) sourceType;
		IType type = elementInfo.getHandle();
		ICompilationUnit sourceUnit = (ICompilationUnit) type.getCompilationUnit();
		accept(sourceUnit, accessRestriction);
	}

	public void resolveDocument() {
		if (usedDomBasedIndexing() && this.dom != null && getUnit() instanceof org.eclipse.jdt.internal.core.CompilationUnit unit) {
			resolveDocumentDomImpl(unit);
		} else {
			try {
				IPath path = new Path(this.document.getPath());
				IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(path.segment(0));
				JavaModel model = JavaModelManager.getJavaModelManager().getJavaModel();
				JavaProject javaProject = (JavaProject) model.getJavaProject(project);

				this.options = new CompilerOptions(javaProject.getOptions(true));
				ProblemReporter problemReporter =
						new ProblemReporter(
								DefaultErrorHandlingPolicies.proceedWithAllProblems(),
								this.options,
								new DefaultProblemFactory());

				// Re-parse using normal parser, IndexingParser swallows several nodes, see comment above class.
				this.basicParser = new Parser(problemReporter, false);
				this.basicParser.reportOnlyOneSyntaxError = true;
				this.basicParser.scanner.taskTags = null;
				this.cud = this.basicParser.parse(this.compilationUnit, new CompilationResult(this.compilationUnit, 0, 0, this.options.maxProblemsPerUnit));
				// Use a non model name environment to avoid locks, monitors and such.
				INameEnvironment nameEnvironment = new JavaSearchNameEnvironment(javaProject, JavaModelManager.getJavaModelManager().getWorkingCopies(DefaultWorkingCopyOwner.PRIMARY, true/*add primary WCs*/));
				this.lookupEnvironment = new LookupEnvironment(this, this.options, problemReporter, nameEnvironment);
				reduceParseTree(this.cud);
				this.lookupEnvironment.buildTypeBindings(this.cud, null);
				this.lookupEnvironment.completeTypeBindings();
				this.cud.scope.faultInTypes();
				this.cud.resolve();
			} catch (Exception e) {
				if (JobManager.VERBOSE) {
					trace("", e); //$NON-NLS-1$
				}
			}
		}
	}

	private void resolveDocumentDomImpl(org.eclipse.jdt.internal.core.CompilationUnit unit) {
		String reducedDOM = reduceDOM(this.dom);
		try {
			ASTParser astParser = ASTParser.newParser(AST.getJLSLatest()); // we don't seek exact compilation the more tolerant the better here
			astParser.setSource(unit); // configure projects and so on
			astParser.setUnitName(unit.getElementName());
			astParser.setSource(reducedDOM.toCharArray()); // trimmed contents
			astParser.setStatementsRecovery(true);
			astParser.setResolveBindings(true);
			this.dom = astParser.createAST(null);
		} catch (Exception e) {
			ILog.get().error(e.getMessage(), e);
		}
	}

	private String reduceDOM(org.eclipse.jdt.core.dom.ASTNode domParam) {
		domParam.accept(new ASTVisitor(false) {
			private boolean requiresBinding = false;
			@Override
			public boolean visit(org.eclipse.jdt.core.dom.TypeDeclaration node) {
				node.setJavadoc(null);
				return super.visit(node);
			}
			@Override
			public boolean visit(RecordDeclaration node) {
				node.setJavadoc(null);
				return super.visit(node);
			}
			@Override
			public boolean visit(AnnotationTypeDeclaration node) {
				node.setJavadoc(null);
				return super.visit(node);
			}
			@Override
			public boolean visit(FieldDeclaration node) {
				node.setJavadoc(null);
				return super.visit(node);
			}
			@Override
			public boolean visit(MethodDeclaration node) {
				node.setJavadoc(null);
				if (node.getParent() instanceof AbstractTypeDeclaration type &&
					type.getParent() instanceof org.eclipse.jdt.core.dom.CompilationUnit) {
					// reset
					this.requiresBinding = false;
				}
				return super.visit(node);
			}
			@Override
			public void endVisit(MethodDeclaration node) {
				if (!this.requiresBinding &&
					node.getParent() instanceof AbstractTypeDeclaration type &&
					type.getParent() instanceof org.eclipse.jdt.core.dom.CompilationUnit &&
					node.getBody() != null) {
					node.getBody().statements().clear();
				}
			}
			@Override
			public boolean visit(ExpressionMethodReference methodRef) {
				this.requiresBinding = true;
				return false;
			}
			@Override
			public boolean visit(CreationReference methodRef) {
				this.requiresBinding = true;
				return false;
			}
			@Override
			public boolean visit(SuperMethodReference methodRef) {
				this.requiresBinding = true;
				return false;
			}
			@Override
			public boolean visit(TypeMethodReference methodRef) {
				this.requiresBinding = true;
				return false;
			}
			@Override
			public boolean visit(org.eclipse.jdt.core.dom.LambdaExpression methodRef) {
				this.requiresBinding = true;
				return false;
			}
		});
		return domParam.toString();
	}
	/**
	 * Called prior to the unit being resolved. Reduce the parse tree where possible.
	 */
	private void reduceParseTree(CompilationUnitDeclaration unit) {
		// remove statements from methods that have no functional interface types.
		TypeDeclaration[] types = unit.types;
		for (int i = 0, l = types == null ? 0 : types.length; i < l; i++)
			purgeMethodStatements(types[i]);
	}

	private void purgeMethodStatements(TypeDeclaration type) {
		AbstractMethodDeclaration[] methods = type.methods;
		for (int j = 0, length = methods == null ? 0 : methods.length; j < length; j++) {
			AbstractMethodDeclaration method = methods[j];
			/*
			 * In case the method defines a local type, skip purging the method body.
			 * We don't know if the local type defines a method that uses a method reference or a lambda.
			 * See:
			 *   https://github.com/eclipse-jdt/eclipse.jdt.core/issues/432
			 *   https://bugs.eclipse.org/bugs/show_bug.cgi?id=566435
			 */
			if (method != null && (method.bits & (ASTNode.HasFunctionalInterfaceTypes | ASTNode.HasLocalType)) == 0) {
				method.statements = null;
				method.javadoc = null;
			}
		}

		TypeDeclaration[] memberTypes = type.memberTypes;
		if (memberTypes != null)
			for (TypeDeclaration memberType : memberTypes)
				purgeMethodStatements(memberType);
	}

	@Override
	public void indexResolvedDocument() {
		if (usedDomBasedIndexing() && this.dom != null) {
			// just re-run indexing, but with the resolved document (and its bindings)
			this.dom.accept(new DOMToIndexVisitor(this));
			this.dom = null;
			return;
		}

		try {
			if (DEBUG) {
				trace(new String(this.cud.compilationResult.fileName) + ':');
			}
			for (int i = 0, length = this.cud.functionalExpressionsCount; i < length; i++) {
				FunctionalExpression expression = this.cud.functionalExpressions[i];
				if (expression instanceof LambdaExpression) {
					LambdaExpression lambdaExpression = (LambdaExpression) expression;
					if (lambdaExpression.binding != null && lambdaExpression.binding.isValidBinding()) {
						final char[] superinterface = lambdaExpression.resolvedType.sourceName();
						if (DEBUG) {
							trace('\t' + new String(superinterface) + '.' +
									new String(lambdaExpression.descriptor.selector) + "-> {}"); //$NON-NLS-1$
						}
						SourceIndexer.this.addIndexEntry(IIndexConstants.METHOD_DECL, MethodPattern.createIndexKey(lambdaExpression.descriptor.selector, lambdaExpression.descriptor.parameters.length));

						addClassDeclaration(0,  // most entries are blank, that is fine, since lambda type/method cannot be searched.
								CharOperation.NO_CHAR, // package name
								ONE_ZERO,
								ONE_ZERO_CHAR, // enclosing types.
								CharOperation.NO_CHAR, // super class
								new char[][] { superinterface },
								CharOperation.NO_CHAR_CHAR,
								true); // not primary.

					} else {
						if (DEBUG) {
							trace("\tnull/bad binding in lambda"); //$NON-NLS-1$
						}
					}
				} else {
					ReferenceExpression referenceExpression = (ReferenceExpression) expression;
					if (referenceExpression.isArrayConstructorReference())
						continue;
					MethodBinding binding = referenceExpression.getMethodBinding();
					if (binding != null && binding.isValidBinding()) {
						if (DEBUG) {
							trace('\t' + new String(referenceExpression.resolvedType.sourceName()) + "::"  //$NON-NLS-1$
									+ new String(referenceExpression.descriptor.selector) + " == " + new String(binding.declaringClass.sourceName()) + '.' + //$NON-NLS-1$
									new String(binding.selector));
						}
						if (referenceExpression.isMethodReference())
							SourceIndexer.this.addMethodReference(binding.selector, binding.parameters.length);
						else
							SourceIndexer.this.addConstructorReference(binding.declaringClass.sourceName(), binding.parameters.length);
					} else {
						if (DEBUG) {
							trace("\tnull/bad binding in reference expression"); //$NON-NLS-1$
						}
					}
				}
			}
		} catch (Exception e) {
			if (JobManager.VERBOSE) {
				trace("", e); //$NON-NLS-1$
			}
		}
	}

	private org.eclipse.jdt.core.ICompilationUnit getUnit() {
		if (this.document instanceof JavaSearchDocument javaSearchDoc) {
			IFile file = javaSearchDoc.getFile();
			try {
				if (JavaProject.hasJavaNature(file.getProject())) {
					IJavaProject javaProject = JavaCore.create(file.getProject());
					// Do NOT call javaProject.getElement(pathToJavaFile) as it can loop inside index
					// when there are multiple package root/source folders, and then cause deadlock
					// so we go finer grain by picking the right fragment first (so index call shouldn't happen)
					IPackageFragment fragment = javaProject.findPackageFragment(file.getFullPath().removeLastSegments(1));
					if (fragment != null) {
						return fragment.getCompilationUnit(file.getName());
					}
				}
			} catch (Exception ex) {
				ILog.get().error("Failed to index document from DOM for " + this.document.getPath(), ex); //$NON-NLS-1$
			}
		}
		return null;
	}

	/**
	 * @return whether the operation was successful
	 */
	boolean indexDocumentFromDOM() {
		var unit = getUnit();
		ASTParser astParser = ASTParser.newParser(AST.getJLSLatest()); // we don't seek exact compilation the more tolerant the better here
		if (unit != null) {
			astParser.setSource(unit);
		} else {
			astParser.setSource(this.document.getCharContents());
		}
		astParser.setStatementsRecovery(true);
		astParser.setResolveBindings(this.document.shouldIndexResolvedDocument());
		org.eclipse.jdt.core.dom.ASTNode domLocal = astParser.createAST(null);
		if (domLocal != null) {
			domLocal.accept(new DOMToIndexVisitor(this));
			domLocal.accept(
					new ASTVisitor() {
						@Override
						public boolean preVisit2(org.eclipse.jdt.core.dom.ASTNode node) {
							if (SourceIndexer.this.document.shouldIndexResolvedDocument()) {
								return false; // interrupt
							}
							if (node instanceof MethodReference || node instanceof org.eclipse.jdt.core.dom.LambdaExpression) {
								SourceIndexer.this.document.requireIndexingResolvedDocument();
								return false;
							}
							return true;
						}
					});
			if (this.document.shouldIndexResolvedDocument()) {
				this.dom = domLocal;
			}
			return true;
		}
		return false;
	}

}
