/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.lib;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;

import org.apache.hadoop.hive.ql.parse.SemanticException;

/**
 * base class for operator graph walker this class takes list of starting ops
 * and walks them one by one. it maintains list of walked operators
 * (dispatchedList) and a list of operators that are discovered but not yet
 * dispatched
 */
public class DefaultGraphWalker implements SemanticGraphWalker {

  /**
   * opStack keeps the nodes that have been visited, but have not been
   * dispatched yet
   */
  protected final Stack<Node> opStack;
  /**
   * opQueue keeps the nodes in the order that the were dispatched.
   * Then it is used to go through the processed nodes and store
   * the results that the dispatcher has produced (if any)
   */
  protected final Queue<Node> opQueue;
  /**
   * toWalk stores the starting nodes for the graph that needs to be
   * traversed
   */
  protected final List<Node> toWalk = new ArrayList<Node>();
  protected final IdentityHashMap<Node, Object> retMap = new  IdentityHashMap<Node, Object>();
  protected final SemanticDispatcher dispatcher;

  /**
   * Constructor.
   *
   * @param disp
   *          dispatcher to call for each op encountered
   */
  public DefaultGraphWalker(SemanticDispatcher disp) {
    dispatcher = disp;
    opStack = new Stack<Node>();
    opQueue = new LinkedList<Node>();
  }

  /**
   * @return the doneList
   */
  protected Set<Node> getDispatchedList() {
    return retMap.keySet();
  }

  /**
   * Dispatch the current operator.
   *
   * @param nd
   *          node being walked
   * @param ndStack
   *          stack of nodes encountered
   * @throws SemanticException
   */
  public void dispatch(Node nd, Stack<Node> ndStack) throws SemanticException {
    dispatchAndReturn(nd, ndStack);
  }

  /**
   * Returns dispatch result
   */
  public <T> T dispatchAndReturn(Node nd, Stack<Node> ndStack) throws SemanticException {
    Object[] nodeOutputs = null;
    if (nd.getChildren() != null) {
      nodeOutputs = new Object[nd.getChildren().size()];
      int i = 0;
      for (Node child : nd.getChildren()) {
        nodeOutputs[i++] = retMap.get(child);
      }
    }

    Object retVal = dispatcher.dispatch(nd, ndStack, nodeOutputs);
    retMap.put(nd, retVal);
    return (T) retVal;
  }

  /**
   * starting point for walking.
   *
   * @throws SemanticException
   */
  public void startWalking(Collection<Node> startNodes,
      HashMap<Node, Object> nodeOutput) throws SemanticException {
    toWalk.addAll(startNodes);
    while (toWalk.size() > 0) {
      Node nd = toWalk.remove(0);
      walk(nd);
      // Some walkers extending DefaultGraphWalker e.g. ForwardWalker
      // do not use opQueue and rely uniquely in the toWalk structure,
      // thus we store the results produced by the dispatcher here
      // TODO: rewriting the logic of those walkers to use opQueue
      if (nodeOutput != null && getDispatchedList().contains(nd)) {
        nodeOutput.put(nd, retMap.get(nd));
      }
    }

    // Store the results produced by the dispatcher
    while (!opQueue.isEmpty()) {
      Node node = opQueue.poll();
      if (nodeOutput != null && getDispatchedList().contains(node)) {
        nodeOutput.put(node, retMap.get(node));
      }
    }
  }

  /**
   * walk the current operator and its descendants.
   *
   * @param nd
   *          current operator in the graph
   * @throws SemanticException
   */
  protected void walk(Node nd) throws SemanticException {
    // Push the node in the stack
    opStack.push(nd);

    // While there are still nodes to dispatch...
    while (!opStack.empty()) {
      Node node = opStack.peek();

      if (node.getChildren() == null ||
              getDispatchedList().containsAll(node.getChildren())) {
        // Dispatch current node
        if (!getDispatchedList().contains(node)) {
          dispatch(node, opStack);
          opQueue.add(node);
        }
        opStack.pop();
        continue;
      }

      // Add a single child and restart the loop
      for (Node childNode : node.getChildren()) {
        // skip if already dispatched
        if (getDispatchedList().contains(childNode)) {
          continue;
        }

        boolean hasChildren = childNode.getChildren() != null;
        opStack.push(childNode);
        if (hasChildren) {
          // exit loop to process child's children
          break;
        } else { // no children - safe to dispatch in-line
          dispatch(childNode, opStack);
          opQueue.add(childNode);
          opStack.pop();
        }
      }
    } // end while
  }

}
