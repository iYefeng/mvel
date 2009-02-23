/**
 * MVEL (The MVFLEX Expression Language)
 *
 * Copyright (C) 2007 Christopher Brock, MVFLEX/Valhalla Project and the Codehaus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.mvel.ast;

import org.mvel.integration.VariableResolverFactory;
import static org.mvel.util.ParseTools.doOperations;
import org.mvel.debug.DebugTools;
import static org.mvel.debug.DebugTools.getOperatorName;
import org.mvel.Operator;
import static org.mvel.Operator.PTABLE;

public class BinaryOperation extends ASTNode {
    private int operation;
    private ASTNode left;
    private ASTNode right;

    public BinaryOperation(int operation, ASTNode left, ASTNode right) {
        assert operation != -1;
        this.operation = operation;
        this.left = left;
        this.right = right;
    }

    public Object getReducedValueAccelerated(Object ctx, Object thisValue, VariableResolverFactory factory) {
        return doOperations(left.getReducedValueAccelerated(ctx, thisValue, factory), operation, right.getReducedValueAccelerated(ctx, thisValue, factory));
    }

    public Object getReducedValue(Object ctx, Object thisValue, VariableResolverFactory factory) {
        throw new RuntimeException("unsupported AST operation");
    }


    public int getOperation() {
        return operation;
    }

    public void setOperation(int operation) {
        assert operation != -1;
        this.operation = operation;
    }

    public ASTNode getLeft() {
        return left;
    }

    public void setLeft(ASTNode left) {
        this.left = left;
    }

    public ASTNode getRight() {
        return right;
    }

    public ASTNode getRightMost() {
        BinaryOperation n = this;
        while (n.right != null && n.right instanceof BinaryOperation) {
            n = (BinaryOperation) n.right;
        }
        return n.right;
    }

    public BinaryOperation getRightBinary() {
        return right != null && right instanceof BinaryOperation ? (BinaryOperation) right : null;
    }

    public void setRight(ASTNode right) {
        this.right = right;
    }

    public void setRightMost(ASTNode right) {
        BinaryOperation n = this;
        while (n.right != null && n.right instanceof BinaryOperation) {
            n = (BinaryOperation) n.right;
        }
        n.right = right;
    }

    public int getPrecedence() {
        return PTABLE[operation];
    }

    public boolean isGreaterPrecedence(BinaryOperation o) {
        return o.getPrecedence() > PTABLE[operation];
    }

    public String toString() {
        return "(" + left.toString() + " [" + getOperatorName(operation) + "] " + right.toString() + ")";
    }
}