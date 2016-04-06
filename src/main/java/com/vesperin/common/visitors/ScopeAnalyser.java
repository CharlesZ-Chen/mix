package com.vesperin.common.visitors;

import com.google.common.collect.Sets;
import com.vesperin.common.spi.BindingRequest;
import com.vesperin.common.locations.Location;
import com.vesperin.common.locations.Locations;
import com.vesperin.common.requests.BindingRequestBySignature;
import com.vesperin.common.requests.BindingRequestByValue;
import com.vesperin.common.utils.Jdt;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.SourceRange;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Evaluates all fields, methods and types available (declared) at a given
 * offset in a compilation unit.
 *
 * @author Huascar Sanchez
 */
public class ScopeAnalyser {

  private final Set<ITypeBinding> typeBindingsVisited;
  private final CompilationUnit   root;

  /**
   * Construct a ScopeAnalyser object.
   *
   * @param root the compilation unit to go through scope analysis.
   */
  public ScopeAnalyser(CompilationUnit root) {
    Objects.requireNonNull(root, "CompilationUnit is null");

    this.typeBindingsVisited  = new HashSet<>();
    this.root                 = root;
  }

  /**
   * Returns the bindings of fields in a {@link BodyDeclaration} object.
   *
   * @param node the node to check
   * @param flags the scope flags
   * @return the list of object bindings.
   */
  public static List<IBinding> getFieldDeclarations(ASTNode node, int flags){
    return getVariableDeclarations(node, flags, true);
  }

  /**
   * Returns the bindings of local variables in a {@link Block} object.
   *
   * @param node the node to check
   * @param flags the scope flags
   * @return the list of local bindings.
   */
  public static List<IBinding> getVariableDeclarations(ASTNode node, int flags){
    return getVariableDeclarations(node, flags, false);
  }

  /**
   * Returns the bindings of variable declarations (including fields, non local fields, parameters).
   */
  public static List<IBinding> getVariableDeclarations(ASTNode node, int flags, boolean focusOnFields) {

    ASTNode declaration = Jdt.findParentStatement(node);

    if (declaration == null && !focusOnFields) {
      declaration = Jdt.getChildren(node).stream()
        .filter(s -> s instanceof Block)
        .findFirst().orElse(null);
    }

    while (declaration instanceof Statement && declaration.getNodeType() != ASTNode.BLOCK) {
      declaration = declaration.getParent();
    }

    if (declaration instanceof Block) {
      final BindingRequestBySignature request = new BindingRequestBySignature();
      final DeclarationsAfterVisitor visitor = new DeclarationsAfterVisitor(
        node.getStartPosition(),
        flags,
        request
      );

      declaration.accept(visitor);

      return request.getRequestedBindings();
    }

    return new ArrayList<>();
  }

  private void clearVisitedBindings() {
    typeBindingsVisited.clear();
  }


  /**
   * Collects all bindings available in a type and in its hierarchy.
   *
   * @param binding the type binding.
   * @param flags the flags that specify the elements to report.
   * @param request the binding request strategy.
   * @return return true if the request has reported the
   *    binding as found and no further results are required.
   */
  private boolean collectsInheritedElements(ITypeBinding binding, int flags, BindingRequest request) {
    if (!typeBindingsVisited.add(binding)) {
      return false;
    }

    if (!collectVariableBindings(binding, flags, request)) {
      if (!collectMethodBindings(binding, flags, request)) {
        if (!collectTypeBindings(binding, flags, request)) {
          if (!collectInheritedBindings(binding, flags, request)) {
            if(!collectInterfaceBindings(binding, flags, request)){
              return false;
            }
          }
        }
      }
    }

    return true;
  }

  private boolean collectInterfaceBindings(ITypeBinding binding, int flags, BindingRequest request) {
    final ITypeBinding[] interfaces = binding.getInterfaces();
    // includes looking for methods:  abstract and then unimplemented methods
    for (ITypeBinding eachInterface : interfaces) {
      if (collectsInheritedElements(eachInterface, flags, request))  {
        return true;
      }
    }

    return false;
  }

  private boolean collectInheritedBindings(ITypeBinding binding, int flags, BindingRequest request) {
    final ITypeBinding superClass = binding.getSuperclass();
    if (superClass != null) {
      if (collectsInheritedElements(superClass, flags, request)) {
        return true;
      }
    } else if (binding.isArray()) {
      final AST           rootAST       = root.getAST();
      final ITypeBinding  wellKnownType = rootAST.resolveWellKnownType("java.lang.Object");
      if (collectsInheritedElements(wellKnownType, flags, request)) {
        return true;
      }
    }

    return false;
  }

  private static boolean collectTypeBindings(ITypeBinding binding, int flags, BindingRequest request) {
    if (Scopes.isTypesFlagAvailable(flags)) {
      final ITypeBinding[] typeBindings = binding.getDeclaredTypes();
      for (ITypeBinding eachTypeBinding : typeBindings) {
        if (request.accept(eachTypeBinding))
          return true;
      }
    }
    return false;
  }

  private static boolean collectMethodBindings(ITypeBinding binding, int flags, BindingRequest request) {
    if (Scopes.isMethodsFlagAvailable(flags)) {
      final IMethodBinding[] methodBindings = binding.getDeclaredMethods();
      for (IMethodBinding eachMethodBindings : methodBindings) {
        if (!eachMethodBindings.isSynthetic() && !eachMethodBindings.isConstructor()) {
          if (request.accept(eachMethodBindings))
            return true;
        }
      }
    }
    return false;
  }

  private static boolean collectVariableBindings(ITypeBinding binding, int flags, BindingRequest request) {
    if (Scopes.isVariablesFlagAvailable(flags)) {
      final IVariableBinding[] variableBindings = binding.getDeclaredFields();
      for (IVariableBinding eachVariableBinding : variableBindings) {
        if (request.accept(eachVariableBinding))
          return true;
      }
    }

    return false;
  }


  /**
   * Collects all elements available in a type: its hierarchy and its outer scopes.
   * @param binding The type binding
   * @param flags Flags defining the elements to report
   * @param request the request to which all results are reported
   * @return return <code>true</code> if the request has reported the binding as found and no further results are required
   */
  private boolean collectTypeDeclarations(ITypeBinding binding, int flags, BindingRequest request) {
    if (Scopes.isTypesFlagAvailable(flags) && !binding.isAnonymous()) {
      if (request.accept(binding)) {
        return true;
      }

      final ITypeBinding[] typeParameters = binding.getTypeParameters();
      for (ITypeBinding typeParameter : typeParameters) {
        if (request.accept(typeParameter)) {
          return true;
        }
      }
    }

    collectsInheritedElements(binding, flags, request); // add inherited

    if (binding.isLocal()) {
      collectOuterDeclarationsForLocalType(binding, flags, request);
    } else {
      final ITypeBinding declaringClass = binding.getDeclaringClass();
      if (declaringClass != null) {
        if (collectTypeDeclarations(declaringClass, flags, request))  { // Recursively add inherited
          return true;
        }
      } else if (Scopes.isTypesFlagAvailable(flags)) {
        if (root.findDeclaringNode(binding) != null) {
          List<TypeDeclaration> types = Jdt.typeSafeList(TypeDeclaration.class, root.types());
          for (TypeDeclaration type : types) {
            if (request.accept(type.resolveBinding())) {
              return true;
            }
          }
        }
      }
    }

    return false;
  }

  private boolean collectOuterDeclarationsForLocalType(ITypeBinding localBinding, int flags, BindingRequest bindingRequest) {
    final ASTNode node  = root.findDeclaringNode(localBinding);

    if (node == null) {
      return false;
    }

    if (node instanceof AbstractTypeDeclaration
        || node instanceof AnonymousClassDeclaration) {

      if (collectLocalDeclarations(node.getParent(), flags, bindingRequest)) {
        return true;
      }

      final ITypeBinding parentTypeBinding  = getBindingOfParentType(node.getParent());
      if (parentTypeBinding != null) {
        if (collectTypeDeclarations(parentTypeBinding, flags, bindingRequest)) {
          return true;
        }
      }

    }
    return false;
  }

  private static ITypeBinding getBinding(Expression node) {
    if (node != null) {
      return node.resolveTypeBinding();
    }

    return null;
  }

  private static ITypeBinding getQualifier(SimpleName selector) {
    final ASTNode parent  = selector.getParent();

    switch (parent.getNodeType()) {
      case ASTNode.METHOD_INVOCATION:
        return getTypeBinding(
            selector,
            (MethodInvocation) parent
        );
      case ASTNode.QUALIFIED_NAME:
        return getTypeBinding(
            selector,
            (QualifiedName) parent
        );
      case ASTNode.FIELD_ACCESS:
        return getTypeBinding(
            selector,
            (FieldAccess) parent
        );
      case ASTNode.SUPER_FIELD_ACCESS: {
        final ITypeBinding bindingOfParentType  = getBindingOfParentType(parent);
        final ITypeBinding nonNullBinding       = Objects.requireNonNull(bindingOfParentType);

        return nonNullBinding.getSuperclass();
      }
      case ASTNode.SUPER_METHOD_INVOCATION: {
        return getTypeBinding(
            selector,
            parent
        );
      }
      default:
        if (parent instanceof Type) {
          ASTNode normalizedNode  = getNormalizedNode(parent);
          if (normalizedNode.getLocationInParent() == ClassInstanceCreation.TYPE_PROPERTY) {
            ClassInstanceCreation creation  = (ClassInstanceCreation) normalizedNode.getParent();
            return getBinding(creation.getExpression());
          }
        }
        return null;
    }
  }

  private static ITypeBinding getTypeBinding(SimpleName selector, ASTNode parent) {
    final SuperMethodInvocation superMethodInvocation = (SuperMethodInvocation) parent;

    if (selector == superMethodInvocation.getName()) {
      final ITypeBinding bindingOfParentType  = getBindingOfParentType(parent);
      final ITypeBinding nonNullBinding       = Objects.requireNonNull(bindingOfParentType);

      return nonNullBinding.getSuperclass();
    }

    return null;
  }

  private static ITypeBinding getTypeBinding(SimpleName selector, FieldAccess parent) {
    if (selector == parent.getName()) {
      return getBinding(parent.getExpression());
    }

    return null;
  }

  private static ITypeBinding getTypeBinding(SimpleName selector, QualifiedName parent) {
    if (selector == parent.getName()) {
      return getBinding(parent.getQualifier());
    }

    return null;
  }

  private static ITypeBinding getTypeBinding(SimpleName selector, MethodInvocation parent) {
    if (selector == parent.getName()) {
      return getBinding(parent.getExpression());
    }

    return null;
  }

  public boolean isElementDeclaredInScope(IBinding declaration, SimpleName selector, int flags) {
    try {
      // special case for switch on enum
      if (selector.getLocationInParent() == SwitchCase.EXPRESSION_PROPERTY) {
        final SwitchStatement switchStatement = ((SwitchStatement) selector.getParent().getParent());
        final ITypeBinding binding  = switchStatement.getExpression().resolveTypeBinding();
        if (binding != null && binding.isEnum()) {
          return hasEnumConstants(declaration, binding.getTypeDeclaration());
        }
      }

      final ITypeBinding parentTypeBinding = getBindingOfParentTypeContext(selector);
      if (parentTypeBinding != null) {
        final ITypeBinding          binding = getQualifier(selector);
        final BindingRequestByValue request = new BindingRequestByValue(
            declaration,
            parentTypeBinding,
            flags
        );

        if (binding == null) {
          collectLocalDeclarations(selector, flags, request);

          if (request.isBindingFound()){
            return request.isVisible();
          }

          collectTypeDeclarations(parentTypeBinding, flags, request);

          if (request.isBindingFound()){
            return request.isVisible();
          }
        } else {
          collectsInheritedElements(binding, flags, request);

          if (request.isBindingFound()){
            return request.isVisible();
          }
        }
      }
      return false;
    } finally {
      clearVisitedBindings();
    }
  }

  private boolean collectLocalDeclarations(ASTNode node, int flags, BindingRequest request) {
    return collectLocalDeclarations(node, node.getStartPosition(), flags, request);
  }


  private boolean collectLocalDeclarations(ASTNode node, int offset, int flags, BindingRequest request) {
    if (Scopes.isVariablesFlagAvailable(flags) || Scopes.isTypesFlagAvailable(flags)) {
      final BodyDeclaration declaration = findParentBodyDeclaration(node);
      if (declaration instanceof MethodDeclaration || declaration instanceof Initializer) {
        final ScopeVisitor visitor  = new ScopeVisitor(offset, flags, request);
        declaration.accept(visitor);
        return visitor.isBreakStatement();
      }
    }
    return false;
  }


  public static BodyDeclaration findParentBodyDeclaration(ASTNode node) {
    while ((node != null) && (!(node instanceof BodyDeclaration))) {
      node  = node.getParent();
    }

    return (BodyDeclaration) node;
  }


  private static boolean hasEnumConstants(IBinding declaration, ITypeBinding binding) {
    final IVariableBinding[] declaredFields   = binding.getDeclaredFields();

    for (IVariableBinding variableBinding : declaredFields) {
      if (variableBinding == declaration) {
        return true;
      }
    }

    return false;
  }



  public IBinding[] getDeclarationsInScope(SimpleName selector, int flags) {
    try {
      // this is a special case for switch on enum
      if (selector.getLocationInParent() == SwitchCase.EXPRESSION_PROPERTY) {
        final SwitchStatement switchStatement = ((SwitchStatement) selector.getParent().getParent());
        final ITypeBinding binding  = switchStatement.getExpression().resolveTypeBinding();
        if (binding != null && binding.isEnum()) {
          return getEnumConstants(binding);
        }
      }

      ITypeBinding parentTypeBinding  = getBindingOfParentType(selector);
      if (parentTypeBinding != null) {
        final ITypeBinding              binding = getQualifier(selector);
        final BindingRequestBySignature request = new BindingRequestBySignature(parentTypeBinding, flags);

        if (binding == null) {
          collectLocalDeclarations(selector, flags, request);
          collectTypeDeclarations(parentTypeBinding, flags, request);
        } else {
          collectsInheritedElements(binding, flags, request);
        }

        final List<IBinding> result = request.getRequestedBindings();

        return result.toArray(new IBinding[result.size()]);
      }
      return null;
    } finally {
      clearVisitedBindings();
    }
  }


  public IBinding[] getDeclarationsInScope(int offset, int endOffset, int flags) {

    final ASTNode node = selectNodeWithinRange(root,
      new SourceRange(offset, Math.abs(endOffset - offset))
    );

    if (node == null) { return Scopes.EMPTY_BINDINGS; }

    if (node instanceof SimpleName) {
      return getDeclarationsInScope((SimpleName) node, flags);
    }

    try {
      final ITypeBinding              binding = getBindingOfParentType(node);
      final BindingRequestBySignature request = new BindingRequestBySignature(binding, flags);

      collectLocalDeclarations(node, offset, flags, request);

      if (binding != null) {
        collectTypeDeclarations(binding, flags, request);
      }

      final List<IBinding> result = request.getRequestedBindings();
      return result.toArray(new IBinding[result.size()]);
    } finally {
      clearVisitedBindings();
    }
  }

  private IVariableBinding[] getEnumConstants(ITypeBinding binding) {
    final IVariableBinding[] declaredFields = binding.getDeclaredFields();
    final List<IVariableBinding> result = new ArrayList<>(declaredFields.length);

    for (IVariableBinding eachVariableBinding : declaredFields) {
      if (eachVariableBinding.isEnumConstant()) {
        result.add(eachVariableBinding);
      }
    }

    return result.toArray(new IVariableBinding[result.size()]);
  }


  public static ASTNode getNormalizedNode(ASTNode node) {
    ASTNode current= node;
    // normalize name
    if (QualifiedName.NAME_PROPERTY.equals(current.getLocationInParent())) {
      current = current.getParent();
    }

    // normalize type
    if (QualifiedType.NAME_PROPERTY.equals(current.getLocationInParent()) ||
        SimpleType.NAME_PROPERTY.equals(current.getLocationInParent())) {
      current = current.getParent();
    }
    // normalize parameterized types
    if (ParameterizedType.TYPE_PROPERTY.equals(current.getLocationInParent())) {
      current = current.getParent();
    }
    return current;
  }


  /**
   * Gets the type binding of the node's type context or null if the node is an annotation,
   * type parameter or super type declaration of a top level type.
   *
   * The result of this method is equal to the result of {@link #getBindingOfParentType(ASTNode)}
   * for nodes in the type's body.
   *
   * @param node ASTNode object
   * @return the type binding of the node's parent type context
   */
  public static ITypeBinding getBindingOfParentTypeContext(ASTNode node) {
    StructuralPropertyDescriptor lastLocation = null;

    while (node != null) {
      if (node instanceof AbstractTypeDeclaration) {
        final AbstractTypeDeclaration declaration = (AbstractTypeDeclaration) node;
        if (lastLocation == declaration.getBodyDeclarationsProperty()) {
          return declaration.resolveBinding();
        } else if (declaration instanceof EnumDeclaration
          && lastLocation == EnumDeclaration.ENUM_CONSTANTS_PROPERTY) {

          return declaration.resolveBinding();
        }
      } else if (node instanceof AnonymousClassDeclaration) {
        return ((AnonymousClassDeclaration) node).resolveBinding();
      }

      lastLocation    = node.getLocationInParent();
      node            = node.getParent();
    }
    return null;
  }

  /**
   * Gets the type binding of the node's parent type declaration.
   *
   * @param foundNode ASTNode object
   * @return the type binding of the node's parent type declaration
   */
  public static ITypeBinding getBindingOfParentType(ASTNode foundNode) {
    ASTNode node = foundNode;
    while (node != null) {
      if (node instanceof AbstractTypeDeclaration) {
        return ((AbstractTypeDeclaration) node).resolveBinding();
      } else if (node instanceof AnonymousClassDeclaration) {
        return ((AnonymousClassDeclaration) node).resolveBinding();
      } else if (node instanceof CompilationUnit){
        node = (TypeDeclaration) ((CompilationUnit) node).types().get(0);
        continue;
      }

      node  = node.getParent();
    }

    return null;
  }

  /**
   * Gets the used variable names (in particular non-static fields, static fields)
   * within some given scope (starting at offset). This scope includes any super class
   * extended by the class currently being inspected.
   *
   * @param offset the starting position.
   * @param endOffset the ending position
   * @return a collection of used names; empty if none found.
   */
  public Collection<String> getUsedFieldNames(int offset, int endOffset) {
    final Set<String> result  = new HashSet<>();

    final IBinding[] bindingsBefore = getDeclarationsInScope(offset, endOffset, Scopes.VARIABLES);

    for (IBinding eachBindingBefore : bindingsBefore) {
      result.add(eachBindingBefore.getName());
    }

    final IBinding[] bindingsAfter  = getUsedFieldDeclarations(offset, endOffset, Scopes.VARIABLES);

    for (IBinding eachBindingAfter : bindingsAfter) {
      result.add(eachBindingAfter.getName());
    }

    final List<ImportDeclaration> imports = Jdt.typeSafeList(ImportDeclaration.class, root.imports());

    final List<String> filtered = imports.stream()
      .filter(eachImportDeclaration -> eachImportDeclaration.isStatic()
        && !eachImportDeclaration.isOnDemand())
      .map(eachImportDeclaration -> Jdt.getSimpleNameIdentifier(eachImportDeclaration.getName()))
      .collect(Collectors.toList());

    result.addAll(filtered);

    return result;
  }


  /**
   * Similar to {@link ScopeAnalyser#getUsedFieldNames(int, int)}, we collect the bindings of
   * non static fields, and static fields within some given scope. Local variable and parameters
   * are not collected.
   *
   * @param startOffset the starting position
   * @param endOffset the ending position
   * @param flags the indicators of the target nodes to be looked at.
   * @return an array of bindings
   */
  public IBinding[] getUsedFieldDeclarations(int startOffset, int endOffset, int flags) {
    try {

      final ASTNode node = selectNodeWithinRange(root,
        new SourceRange(startOffset, Math.abs(endOffset - startOffset))
      );

      if (node == null) { return Scopes.EMPTY_BINDINGS; }

      final List<IBinding> bindings = getFieldDeclarations(node, flags);
      return bindings.toArray(new IBinding[bindings.size()]);
    } finally {
      clearVisitedBindings();
    }
  }


   private static ASTNode selectNodeWithinRange(CompilationUnit root, ISourceRange range){
     final Location selection = Locations.createLocation(Jdt.from(root), range);

     final StatementsSelectionVisitor selector = new StatementsSelectionVisitor(selection);
     root.accept(selector);

     return selector.getFirstSelectedNode();
  }


  public Set<IBinding> getUsedDeclarationsInScope(int offset, int endOffset){
    final IBinding[] methods = getDeclarationsInScope(offset, endOffset, Scopes.METHODS);
    final IBinding[] fields  = getDeclarationsInScope(offset, endOffset, Scopes.VARIABLES);
    final IBinding[] types   = getDeclarationsInScope(offset, endOffset, Scopes.TYPES);

    return Sets.union(
      Sets.newHashSet(types),
      Sets.union(Sets.newHashSet(methods), Sets.newHashSet(fields))
    );
  }


}
