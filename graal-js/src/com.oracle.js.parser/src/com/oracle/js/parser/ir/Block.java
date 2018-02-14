/*
 * Copyright (c) 2010, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.js.parser.ir;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.oracle.js.parser.ir.visitor.NodeVisitor;
import com.oracle.js.parser.ir.visitor.TranslatorNodeVisitor;

// @formatter:off
/**
 * IR representation for a list of statements.
 */
public class Block extends Node implements BreakableNode, Terminal, Flags<Block> {
    /** List of statements */
    protected final List<Statement> statements;

    /** Symbol table - keys must be returned in the order they were put in. */
    protected final Map<String, Symbol> symbols;

    private int blockScopedOrRedeclaredSymbols;
    private int declaredNames;

    /** Does the block/function need a new scope? Is this synthetic? */
    protected final int flags;

    /** Flag indicating that this block needs scope */
    public static final int NEEDS_SCOPE = 1 << 0;

    /**
     * Is this block tagged as terminal based on its contents
     * (usually the last statement)
     */
    public static final int IS_TERMINAL = 1 << 2;

    /**
     * Is this block the eager global scope - i.e. the original program. This isn't true for the
     * outermost level of recompiles
     */
    public static final int IS_GLOBAL_SCOPE = 1 << 3;

    /**
     * Is this block a synthetic one introduced by Parser?
     */
    public static final int IS_SYNTHETIC = 1 << 4;

    /**
     * Is this the function body block? May not be the first, if parameter list contains expressions.
     */
    public static final int IS_BODY = 1 << 5;

    /**
     * Is this the parameter initialization block? If present, must be the first block, immediately wrapping the function body block.
     */
    public static final int IS_PARAMETER_BLOCK = 1 << 6;

    /**
     * Marks the variable declaration block for case clauses of a switch statement.
     */
    public static final int IS_SWITCH_BLOCK = 1 << 7;

    /**
     * Marks the variable declaration block for a for-of loop.
     */
    public static final int IS_FOR_OF_BLOCK = 1 << 8;

    /**
     * Is this an expression block (class or do expression) that should return its completion value.
     */
    public static final int IS_EXPRESSION_BLOCK = 1 << 9;

    /**
     * Constructor
     *
     * @param token      The first token of the block
     * @param finish     The index of the last character
     * @param flags      The flags of the block
     * @param statements All statements in the block
     */
    public Block(final long token, final int finish, final int flags, final Statement... statements) {
        super(token, finish);
        assert start <= finish;

        this.statements = Arrays.asList(statements);
        this.symbols    = new LinkedHashMap<>();
        final int len = statements.length;
        final int terminalFlags = len > 0 && statements[len - 1].hasTerminalFlags() ? IS_TERMINAL : 0;
        this.flags = terminalFlags | flags;
    }

    /**
     * Constructor
     *
     * @param token      The first token of the block
     * @param finish     The index of the last character
     * @param flags      The flags of the block
     * @param statements All statements in the block
     */
    public Block(final long token, final int finish, final int flags, final List<Statement> statements) {
        this(token, finish, flags, statements.toArray(new Statement[statements.size()]));
    }

    private Block(final Block block, final int finish, final List<Statement> statements, final int flags, final Map<String, Symbol> symbols) {
        super(block, finish);
        this.statements = statements;
        this.flags      = flags;
        this.symbols    = new LinkedHashMap<>(symbols);

        this.declaredNames = block.declaredNames;
        this.blockScopedOrRedeclaredSymbols = block.blockScopedOrRedeclaredSymbols;
    }

    /**
     * Is this block the outermost eager global scope - i.e. the primordial program?
     * Used for global anchor point for scope depth computation for recompilation code
     * @return true if outermost eager global scope
     */
    public boolean isGlobalScope() {
        return getFlag(IS_GLOBAL_SCOPE);
    }

    /**
     * Assist in IR navigation.
     *
     * @param visitor IR navigating visitor.
     * @return new or same node
     */
    @Override
    public Node accept(final LexicalContext lc, final NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterBlock(this)) {
            return visitor.leaveBlock(setStatements(lc, Node.accept(visitor, statements)));
        }

        return this;
    }

    @Override
    public <R> R accept(LexicalContext lc, TranslatorNodeVisitor<? extends LexicalContext, R> visitor) {
        return visitor.enterBlock(this);
    }

    /**
     * Get a copy of the list for all the symbols defined in this block
     * @return symbol iterator
     */
    public List<Symbol> getSymbols() {
        return Collections.unmodifiableList(new ArrayList<>(symbols.values()));
    }

    /**
     * Retrieves an existing symbol defined in the current block.
     * @param name the name of the symbol
     * @return an existing symbol with the specified name defined in the current block, or null if this block doesn't
     * define a symbol with this name.T
     */
    public Symbol getExistingSymbol(final String name) {
        return symbols.get(name);
    }

    /**
     * Test if this block represents a <tt>catch</tt> block in a <tt>try</tt> statement.
     * This is used by the Splitter as catch blocks are not be subject to splitting.
     *
     * @return true if this block represents a catch block in a try statement.
     */
    public boolean isCatchBlock() {
        return getLastStatement() instanceof CatchNode;
    }

    @Override
    public void toString(final StringBuilder sb, final boolean printType) {
        for (final Node statement : statements) {
            statement.toString(sb, printType);
            sb.append(';');
        }
    }

    @Override
    public int getFlags() {
        return flags;
    }

    /**
     * Is this a terminal block, i.e. does it end control flow like ending with a throw or return?
     *
     * @return true if this node statement is terminal
     */
    @Override
    public boolean isTerminal() {
        return getFlag(IS_TERMINAL);
    }

    /**
     * Get the list of statements in this block
     *
     * @return a list of statements
     */
    public List<Statement> getStatements() {
        return Collections.unmodifiableList(statements);
    }

    /**
     * Returns the number of statements in the block.
     * @return the number of statements in the block.
     */
    public int getStatementCount() {
        return statements.size();
    }

    /**
     * Returns the line number of the first statement in the block.
     * @return the line number of the first statement in the block, or -1 if the block has no statements.
     */
    public int getFirstStatementLineNumber() {
        if (statements == null || statements.isEmpty()) {
            return -1;
        }
        return statements.get(0).getLineNumber();
    }


    /**
     * Returns the first statement in the block.
     * @return the first statement in the block, or null if the block has no statements.
     */
    public Statement getFirstStatement() {
        return statements.isEmpty() ? null : statements.get(0);
    }

    /**
     * Returns the last statement in the block.
     * @return the last statement in the block, or null if the block has no statements.
     */
    public Statement getLastStatement() {
        return statements.isEmpty() ? null : statements.get(statements.size() - 1);
    }

    /**
     * Reset the statement list for this block
     *
     * @param lc lexical context
     * @param statements new statement list
     * @return new block if statements changed, identity of statements == block.statements
     */
    public Block setStatements(final LexicalContext lc, final List<Statement> statements) {
        if (this.statements == statements) {
            return this;
        }
        int lastFinish = 0;
        if (!statements.isEmpty()) {
            lastFinish = statements.get(statements.size() - 1).getFinish();
        }
        return Node.replaceInLexicalContext(lc, this, new Block(this, Math.max(finish, lastFinish), statements, flags, symbols));
    }

    /**
     * Add or overwrite an existing symbol in the block
     *
     * @param lc     get lexical context
     * @param symbol symbol
     */
    public void putSymbol(final LexicalContext lc, final Symbol symbol) {
        symbols.put(symbol.getName(), symbol);
        if ((symbol.isBlockScoped() || symbol.isVarRedeclaredHere()) && !symbol.isImportBinding()) {
            blockScopedOrRedeclaredSymbols++;
        }
        if ((symbol.isBlockScoped() || (symbol.isVar() && symbol.isVarDeclaredHere())) && !symbol.isImportBinding()) {
            declaredNames++;
        }
    }

    /**
     * Check whether scope is necessary for this Block
     *
     * @return true if this function needs a scope
     */
    public boolean needsScope() {
        return (flags & NEEDS_SCOPE) == NEEDS_SCOPE;
    }

    /**
     * Check whether this block is synthetic or not.
     *
     * @return true if this is a synthetic block
     */
    public boolean isSynthetic() {
        return (flags & IS_SYNTHETIC) == IS_SYNTHETIC;
    }

    @Override
    public Block setFlags(final LexicalContext lc, final int flags) {
        if (this.flags == flags) {
            return this;
        }
        return Node.replaceInLexicalContext(lc, this, new Block(this, finish, statements, flags, symbols));
    }

    @Override
    public Block setFlag(final LexicalContext lc, final int flag) {
        return setFlags(lc, flags | flag);
    }

    @Override
    public boolean getFlag(final int flag) {
        return (flags & flag) == flag;
    }

    @Override
    public boolean isBreakableWithoutLabel() {
        return false;
    }

    @Override
    public Node accept(final NodeVisitor<? extends LexicalContext> visitor) {
        return BreakableNode.super.accept(visitor);
    }

    @Override
    public <R> R accept(TranslatorNodeVisitor<? extends LexicalContext, R> visitor) {
        return BreakableNode.super.accept(visitor);
    }

    public Map<String, Symbol> getSymbolMap() {
        return symbols;
    }

    public boolean hasBlockScopedOrRedeclaredSymbols() {
        return blockScopedOrRedeclaredSymbols != 0;
    }

    public boolean hasDeclarations() {
        return declaredNames != 0;
    }

    public boolean isFunctionBody() {
        return getFlag(IS_BODY);
    }

    public boolean isParameterBlock() {
        return getFlag(IS_PARAMETER_BLOCK);
    }

    public boolean isSwitchBlock() {
        return getFlag(IS_SWITCH_BLOCK);
    }

    public boolean isForOfBlock() {
        return getFlag(IS_FOR_OF_BLOCK);
    }

    public boolean isExpressionBlock() {
        return getFlag(IS_EXPRESSION_BLOCK);
    }
}
