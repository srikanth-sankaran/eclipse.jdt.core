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
 *******************************************************************************/
package org.eclipse.jdt.core.tests.rewrite.describing;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import junit.framework.Test;
import junit.framework.TestSuite;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jdt.core.tests.model.AbstractJavaModelTests;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

/**
 * Tests for ASTRewrite. Subclasses must have 2 constructors that forward to
 * constructors with the same signature as this class's constructors.
 *
 * Test methods can end with:
 * <ul>
 * <li>"_since_<i>n</i>", where <i>n</i> is an AST.JLS* constant value:
 *   test will run for all AST levels >= <i>n</i>
 * </li>
 * <li>"_only_<i>a</i>_<i>b</i>...", where <i>a</i>, <i>b</i>, ... are AST.JLS* constant values:
 *   test will run for all specified AST levels
 * </li>
 * </ul>
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ASTRewritingTest extends AbstractJavaModelTests {

	private static final String ONLY_AST_STRING = "_only";
	private static final String SINCE_AST_STRING = "_since";
	private static final String STRING_ = "_";

	protected int apiLevel;

	protected IJavaProject project1;
	protected IPackageFragmentRoot sourceFolder;

	/** @deprecated using deprecated code */
	public String getName() {
		String name = super.getName() + " - JLS" + this.apiLevel;
		return name;
	}

	public ASTRewritingTest(String name) {
		super(name.substring(0, name.indexOf(" - JLS")));
		name.indexOf(" - JLS");
		this.apiLevel = Integer.parseInt(name.substring(name.indexOf(" - JLS") + 6));
	}

	/**
	 * Creates an instance of a test at a particular AST level. All sub tests of ASTRewritingTest must have a constructor
	 * with the specified parameters.
	 *
	 * @param name name of the test method
	 * @param apiLevel The JLS level
	 */
	public ASTRewritingTest(String name, int apiLevel) {
		super(name);
		this.apiLevel = apiLevel;
	}

	public static Test suite() {
		TestSuite suite= new TestSuite(ASTRewritingTest.class.getName());


		  suite.addTest(ASTRewritingExpressionsTest.suite());
		  suite.addTest(ASTRewritingInsertBoundTest.suite());
		  suite.addTest(ASTRewritingMethodDeclTest.suite());
		  suite.addTest(ASTRewritingMoveCodeTest.suite());
		  suite.addTest(ASTRewritingStatementsTest.suite());
		  suite.addTest(ASTRewritingSwitchExpressionsTest.suite());
		  suite.addTest(ASTRewritingSwitchPatternTest.suite());

		  suite.addTest(ASTRewritingTrackingTest.suite());
		  suite.addTest(ASTRewritingJavadocTest.suite());
		  suite.addTest(ASTRewritingTypeAnnotationsTest.suite());
		  suite.addTest(ASTRewritingTypeDeclTest.suite());
		  suite.addTest(ASTRewritingGroupNodeTest.suite());
		  suite.addTest(ASTRewritingRevertTest.suite());
		  suite.addTest(LineCommentOffsetsTest.suite());
		  suite.addTest(ASTRewritingWithStatementsRecoveryTest.suite());
		  suite.addTest(ASTRewritePropertyTest.suite());
		  suite.addTest(ASTRewritingPackageDeclTest.suite());
		  suite.addTest(ASTRewritingLambdaExpressionTest.suite());
		  suite.addTest(ASTRewritingReferenceExpressionTest.suite());
		  suite.addTest(ASTRewritingRecordDeclarationTest.suite());
		  suite.addTest(ASTRewritingRecordPatternTest.suite());
		  suite.addTest(ASTRewritingInstanceOfPatternExpressionTest.suite());
		  suite.addTest(SourceModifierTest.suite());
		  suite.addTest(ImportRewriteTest.suite());
		  suite.addTest(ImportRewrite18Test.suite());
		  suite.addTest(ImportRewrite_RecordTest.suite());
		  suite.addTest(ASTRewritingSuperAfterStatementsTest.suite());
		  suite.addTest(ASTRewritingEitherOrMultiPatternNodeTest.suite());

		return suite;
	}

	/**
	 * Creates a test suite according to the rules in {@link ASTRewritingTest}.
	 *
	 * @param testClass subclass of ASTRewritingTest
	 * @return test suite that runs all tests with all supported AST levels
	 */
	protected static TestSuite createSuite(Class testClass) {
		return createSuite(testClass, -1);
	}

	/**
	 * Creates a test suite according to the rules in {@link ASTRewritingTest}.
	 *
	 * @param testClass subclass of ASTRewritingTest
	 * @param classSince smallest supported AST level for this test class, or -1 to support all levels
	 * @return test suite that runs all tests with all supported AST levels
	 */
	protected static TestSuite createSuite(Class testClass, int classSince) {
		TestSuite suite = new TestSuite(testClass.getName());
		try {
			Method[] methods = testClass.getMethods();
			Constructor cons = testClass.getConstructor(new Class[]{String.class, int.class});
			for (int i = 0, max = methods.length; i < max; i++) {
				String name = methods[i].getName();
				if (name.startsWith("test")) { //$NON-NLS-1$

					int index = name.indexOf(ONLY_AST_STRING);
					if (index != -1) {
						String suffix = name.substring(index + ONLY_AST_STRING.length() + 1);
						String[] levels = suffix.split(STRING_);
						for (int l= 0; l < levels.length; l++) {
                            int level = Integer.valueOf(levels[l]);
                            if (AST.isSupportedVersion(level)) {
                                suite.addTest((Test) cons.newInstance(name, level));
                            }
						}

					} else {
						int since = -1;
						index = name.indexOf(SINCE_AST_STRING);
						if (index != -1) {
							String suffix = name.substring(index + SINCE_AST_STRING.length() + 1);
							since = Integer.parseInt(suffix);
						}
						for (int level :AST.getAllSupportedVersions()) {
							if (level >= since && level >= classSince) {
								suite.addTest((Test) cons.newInstance(new Object[]{name, Integer.valueOf(level)}));
							}
						}
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace(); // In the unlikely case, can't do much
		}
		return suite;
	}

	@Override
	protected void setUp() throws Exception {
		super.setUp();

		IJavaProject proj= createProject("P", CompilerOptions.getFirstSupportedJavaVersion());

		this.project1 = proj;
		this.sourceFolder = getPackageFragmentRoot("P", "src");
	}

	@SuppressWarnings("deprecation")
	protected void setUpProjectAbove14() throws Exception {
		if (this.apiLevel == AST_INTERNAL_JLS14 ) {
			this.project1.setOption(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_14);
			this.project1.setOption(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_14);
			this.project1.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_14);
		}
		setUpProjectAbove15();
	}

	@SuppressWarnings("deprecation")
	protected void setUpProjectAbove15() throws Exception {
		if (this.apiLevel == AST_INTERNAL_JLS15 ) {
			this.project1.setOption(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_15);
			this.project1.setOption(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_15);
			this.project1.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_15);
		}
		setUpProjectAbove16();
	}

	@SuppressWarnings("deprecation")
	protected void setUpProjectAbove16() throws Exception {
		if (this.apiLevel == AST_INTERNAL_JLS16 ) {
			this.project1.setOption(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_16);
			this.project1.setOption(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_16);
			this.project1.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_16);
		}
		setUpProjectAbove17();
	}

	@SuppressWarnings("deprecation")
	protected void setUpProjectAbove17() throws Exception {
		if (this.apiLevel == AST_INTERNAL_JLS17 ) {
			this.project1.setOption(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_17);
			this.project1.setOption(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_17);
			this.project1.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_17);
		}
		setUpProjectAbove18();
	}

	@SuppressWarnings("deprecation")
	protected void setUpProjectAbove18() throws Exception {
		if (this.apiLevel == AST_INTERNAL_JLS18 ) {
			this.project1.setOption(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_18);
			this.project1.setOption(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_18);
			this.project1.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_18);
		}
		setUpProjectAbove19();
	}
	@SuppressWarnings("deprecation")
	protected void setUpProjectAbove19() throws Exception {
		if (this.apiLevel == AST_INTERNAL_JLS19 ) {
			this.project1.setOption(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_19);
			this.project1.setOption(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_19);
			this.project1.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_19);
		}
		setUpProjectAbove20();
	}
	protected void setUpProjectAbove20() throws Exception {
		if (this.apiLevel == AST_INTERNAL_JLS20) {
			this.project1.setOption(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_20);
			this.project1.setOption(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_20);
			this.project1.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_20);
		}
		setUpProjectAbove21();
	}
	protected void setUpProjectAbove21() throws Exception {
		if (this.apiLevel == AST_INTERNAL_JLS21) {
			this.project1.setOption(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_21);
			this.project1.setOption(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_21);
			this.project1.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_21);
		}
		setUpProjectAbove22();
	}
	protected void setUpProjectAbove22() throws Exception {
		if (this.apiLevel == AST_INTERNAL_JLS22) {
			this.project1.setOption(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_22);
			this.project1.setOption(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_22);
			this.project1.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_22);
		}
		setUpProjectAbove23();
	}
	protected void setUpProjectAbove23() throws Exception {
		if (this.apiLevel == AST_INTERNAL_JLS23) {
			this.project1.setOption(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_23);
			this.project1.setOption(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_23);
			this.project1.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_23);
		}
		setUpProjectAbove24();
	}
	protected void setUpProjectAbove24() throws Exception {
		if (this.apiLevel == AST_INTERNAL_JLS24) {
			this.project1.setOption(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_24);
			this.project1.setOption(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_24);
			this.project1.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_24);
		}
	}

	protected IJavaProject createProject(String projectName, String complianceVersion) throws CoreException {
		IJavaProject proj = createJavaProject(projectName, new String[] {"src"}, "bin");
		proj.setOption(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR, JavaCore.SPACE);
		proj.setOption(DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE, "4");
		proj.setOption(DefaultCodeFormatterConstants.FORMATTER_INDENT_SWITCHSTATEMENTS_COMPARE_TO_CASES, DefaultCodeFormatterConstants.TRUE);
		proj.setOption(DefaultCodeFormatterConstants.FORMATTER_INDENT_SWITCHSTATEMENTS_COMPARE_TO_SWITCH, DefaultCodeFormatterConstants.TRUE);
		proj.setOption(JavaCore.COMPILER_COMPLIANCE, complianceVersion);
		proj.setOption(JavaCore.COMPILER_PB_ASSERT_IDENTIFIER, JavaCore.ERROR);
		proj.setOption(JavaCore.COMPILER_SOURCE, complianceVersion);
		proj.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, complianceVersion);
		return proj;
	}
	@Override
	protected void tearDown() throws Exception {
		deleteProject("P");
		super.tearDown();
	}

	protected CompilationUnit createAST(ICompilationUnit cu) {
		return createAST(this.apiLevel, cu, false, false);
	}
	protected CompilationUnit createAST(ICompilationUnit cu, boolean statementsRecovery) {
		return createAST(this.apiLevel, cu, false, statementsRecovery);
	}
	protected CompilationUnit createAST(ICompilationUnit cu, boolean resolveBindings, boolean statementsRecovery) {
		return createAST(this.apiLevel, cu, resolveBindings, statementsRecovery);
	}


	protected CompilationUnit createAST(int JLSLevel, ICompilationUnit cu) {
		ASTParser parser= ASTParser.newParser(JLSLevel);
		parser.setSource(cu, JLSLevel);
		parser.setResolveBindings(false);
		parser.setStatementsRecovery(false);
		return (CompilationUnit) parser.createAST(null);
	}

	protected CompilationUnit createAST(int JLSLevel, ICompilationUnit cu, boolean resolveBindings, boolean statementsRecovery) {
		ASTParser parser= ASTParser.newParser(JLSLevel);
		parser.setSource(cu);
		parser.setResolveBindings(resolveBindings);
		parser.setStatementsRecovery(statementsRecovery);
		return (CompilationUnit) parser.createAST(null);
	}

	protected String evaluateRewrite(ICompilationUnit cu, ASTRewrite rewrite) throws Exception {
		Document document1= new Document(cu.getSource());
		TextEdit res= rewrite.rewriteAST(document1, cu.getJavaProject().getOptions(true));
		res.apply(document1);
		String content1= document1.get();

		Document document2= new Document(cu.getSource());
		TextEdit res2= rewrite.rewriteAST();
		res2.apply(document2);
		String content2= document2.get();

		assertEquals(content1, content2);

		return content1;
	}


	public static void assertEqualString(String actual, String expected) {
		StringAsserts.assertEqualString(actual, expected);
	}

	public static TypeDeclaration findTypeDeclaration(CompilationUnit astRoot, String simpleTypeName) {
		return (TypeDeclaration) findAbstractTypeDeclaration(astRoot, simpleTypeName);
	}

	public static EnumDeclaration findEnumDeclaration(CompilationUnit astRoot, String simpleTypeName) {
		return (EnumDeclaration) findAbstractTypeDeclaration(astRoot, simpleTypeName);
	}

	public static AbstractTypeDeclaration findAbstractTypeDeclaration(CompilationUnit astRoot, String simpleTypeName) {
		List types= astRoot.types();
		for (int i= 0; i < types.size(); i++) {
			AbstractTypeDeclaration elem= (AbstractTypeDeclaration) types.get(i);
			if (simpleTypeName.equals(elem.getName().getIdentifier())) {
				return elem;
			}
		}
		return null;
	}

	public static MethodDeclaration findMethodDeclaration(TypeDeclaration typeDecl, String methodName) {
		MethodDeclaration[] methods= typeDecl.getMethods();
		for (int i= 0; i < methods.length; i++) {
			if (methodName.equals(methods[i].getName().getIdentifier())) {
				return methods[i];
			}
		}
		return null;
	}

	public static MethodDeclaration findMethodDeclaration(RecordDeclaration typeDecl, String methodName) {
		MethodDeclaration[] methods= typeDecl.getMethods();
		for (int i= 0; i < methods.length; i++) {
			if (methodName.equals(methods[i].getName().getIdentifier())) {
				return methods[i];
			}
		}
		return null;
	}

	protected static SingleVariableDeclaration createNewParam(AST ast, String name) {
		SingleVariableDeclaration newParam= ast.newSingleVariableDeclaration();
		newParam.setType(ast.newPrimitiveType(PrimitiveType.FLOAT));
		newParam.setName(ast.newSimpleName(name));
		return newParam;
	}

	protected static FieldDeclaration createNewField(AST ast, String name) {
		VariableDeclarationFragment frag= ast.newVariableDeclarationFragment();
		frag.setName(ast.newSimpleName(name));
		FieldDeclaration newFieldDecl= ast.newFieldDeclaration(frag);
		newFieldDecl.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PRIVATE_KEYWORD));
		newFieldDecl.setType(ast.newPrimitiveType(PrimitiveType.DOUBLE));
		return newFieldDecl;
	}

	protected static MethodDeclaration createNewMethod(AST ast, String name, boolean isAbstract) {
		MethodDeclaration decl= ast.newMethodDeclaration();
		decl.setName(ast.newSimpleName(name));
		decl.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PRIVATE_KEYWORD));
		if (isAbstract) {
			decl.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.ABSTRACT_KEYWORD));
		}
		decl.setReturnType2(ast.newPrimitiveType(PrimitiveType.VOID));
		SingleVariableDeclaration param= ast.newSingleVariableDeclaration();
		param.setName(ast.newSimpleName("str"));
		param.setType(ast.newSimpleType(ast.newSimpleName("String")));
		decl.parameters().add(param);
		decl.setBody(isAbstract ? null : ast.newBlock());
		return decl;
	}

	public static ImplicitTypeDeclaration findImplicitDeclaration(CompilationUnit astRoot, String simpleTypeName) {
		List types= astRoot.types();
		for (int i= 0; i < types.size(); i++) {
			ImplicitTypeDeclaration elem= (ImplicitTypeDeclaration) types.get(i);
			if (simpleTypeName.equals(elem.getName().getIdentifier())) {
				return elem;
			}
		}
		return null;
	}

}
