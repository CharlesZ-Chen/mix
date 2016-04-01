package com.vesperin.common.locators;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.vesperin.common.Context;
import com.vesperin.common.locations.Location;
import com.vesperin.common.locations.Locations;
import com.vesperin.common.utils.Jdt;
import com.vesperin.common.visitors.StatementsSelectionVisitor;
import org.eclipse.jdt.core.dom.ASTNode;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Huascar Sanchez
 */
abstract class AbstractProgramUnit implements ProgramUnit {
  private final String name;

  /**
   * Construct a new {@code AbstractProgramUnit}.
   *
   * @param name the name of the program unit.
   */
  AbstractProgramUnit(String name){
    Preconditions.checkArgument(!Strings.isNullOrEmpty(name), "Invalid name");
    this.name = name;
  }

  @Override public String getName() {
    return name;
  }


  protected static <T extends ASTNode> boolean contains(List<Location> nodes, T node){
    for(Location each : nodes){
      final ProgramUnitLocation pul = (ProgramUnitLocation)each;
      if(pul.getNode().equals(node) || pul.getNode() == node ) return true;
    }

    return false;
  }

  protected static <T extends ASTNode> T parent(Class<T> klass, ASTNode node){
    return Jdt.parent(klass, node);
  }


  protected List<Location> findLocations(Context parsedContext){
    Preconditions.checkNotNull(parsedContext);

    final List<Location> locations = new ArrayList<>();
    final List<Location> instances = Locations.locateWord(parsedContext.getSource(), getName());

    for(Location each : instances){

      addLocations(parsedContext, locations, each);
    }


    return locations;
  }

  protected void addLocations(Context parsedContext, List<Location> locations, Location each) {
    final StatementsSelectionVisitor statements = new StatementsSelectionVisitor(
      each,
      true
    );

    parsedContext.accept(statements);
    statements.checkIfSelectionCoversValidStatements();


    if(statements.isSelectionCoveringValidStatements()){
      // Note: once formatted, it is hard to locate a method. This mean that statements
      // getSelectedNodes is empty, and the only non null node is the statements.lastCoveringNode,
      // which can be A BLOCK if method is the selection. Therefore, I should get the parent of
      // this block to get the method or class to remove.

      for(ASTNode eachNode : statements.getSelectedNodes()){
        // ignore instance creation, parameter passing,... just give me its declaration
        addDeclaration(locations, each, eachNode);
      }
    }
  }


  protected abstract void addDeclaration(List<Location> namedLocations, Location each, ASTNode eachNode);

  @Override public String toString() {
    return "ProgramUnit(" + getName() + ")";
  }
}
