/**
 * MVEL 2.0
 * Copyright (C) 2007 The Codehaus
 * Mike Brock, Dhanji Prasanna, John Graham, Mark Proctor
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
 */

package org.mvel2.util;

import org.mvel2.CompileException;
import org.mvel2.DataConversion;
import org.mvel2.ParserContext;
import org.mvel2.compiler.ExecutableStatement;

import static org.mvel2.util.ParseTools.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This is the inline collection sub-parser.  It produces a skeleton model of the collection which is in turn translated
 * into a sequenced AST to produce the collection efficiently at runtime, and passed off to one of the JIT's if
 * configured.
 *
 * @author Christopher Brock
 */
public class CollectionParser {
    private char[] property;

    private int cursor;
    private int start;
    private int end;

    private int type;

    public static final int LIST = 0;
    public static final int ARRAY = 1;
    public static final int MAP = 2;

    private Class colType;
    private ParserContext pCtx;

    private static final Object[] EMPTY_ARRAY = new Object[0];

    public CollectionParser() {
    }

    public CollectionParser(int type) {
        this.type = type;
    }

    public Object parseCollection(char[] property, int start, int offset, boolean subcompile, ParserContext pCtx) {
        this.property = property;
        this.pCtx = pCtx;
        this.end = start + offset;

        while (start < end && isWhitespace(property[start])) {
            start++;
        }
        this.start = this.cursor = start;

        return parseCollection(subcompile);
    }

    public Object parseCollection(char[] property, int start, int offset, boolean subcompile, Class colType, ParserContext pCtx) {
        if (colType != null) this.colType = getBaseComponentType(colType);
        this.property = property;

        this.end = start + offset;

        while (start < end && isWhitespace(property[start])) {
            start++;
        }

        this.start = this.cursor = start;

        this.pCtx = pCtx;

        return parseCollection(subcompile);
    }

    private Object parseCollection(boolean subcompile) {
        if (end - start == 0) {
            if (type == LIST) return new ArrayList();
            else return EMPTY_ARRAY;
        }

        Map<Object, Object> map = null;
        List<Object> list = null;
        String ex;
        int st = start;

        if (type != -1) {
            switch (type) {
                case ARRAY:
                case LIST:
                    list = new ArrayList<Object>();
                    break;
                case MAP:
                    map = new HashMap<Object, Object>();
                    break;
            }
        }

        Object curr = null;
        int newType = -1;


        for (; cursor < end; cursor++) {
            switch (property[cursor]) {
                case '{':
                    if (newType == -1) {
                        newType = ARRAY;
                    }

                case '[':
                    if (cursor > 0 && isIdentifierPart(property[cursor - 1])) continue;

                    if (newType == -1) {
                        newType = LIST;
                    }

                    /**
                     * Sub-parse nested collections.
                     */
                    Object o = new CollectionParser(newType).parseCollection(property, (st = cursor) + 1,
                            (cursor = balancedCapture(property, st, end, property[st])) - st - 1, subcompile, colType, pCtx);

                    if (type == MAP) {
                        map.put(curr, o);
                    }
                    else {
                        list.add(curr = o);
                    }


                    if ((st = ++cursor) < (end - 1) && property[cursor] == ',') {
                        st = cursor + 1;
                    }

                    continue;

                case '(':
                    cursor = balancedCapture(property, cursor, end, '(');

                    break;

                case '\"':
                case '\'':
                    cursor = balancedCapture(property, cursor, end, property[cursor]);

                    break;

                case ',':
                    if (type != MAP) {
                        list.add(ex = new String(property, st, cursor - st));
                    }
                    else {
                        map.put(curr, ex = createStringTrimmed(property, st, cursor - st));
                    }

                    if (subcompile) {
                        subCompile(ex);
                    }

                    st = cursor + 1;

                    break;

                case ':':
                    if (type != MAP) {
                        map = new HashMap<Object, Object>();
                        type = MAP;
                    }
                    curr = createStringTrimmed(property, st, cursor - st);

                    if (subcompile) {
                        subCompile((String) curr);
                    }

                    st = cursor + 1;
                    break;

                case '.':
                    cursor++;
                    while (cursor != end && isWhitespace(property[cursor])) cursor++;
                    if (cursor != end && property[cursor] == '{') {
                        cursor = balancedCapture(property, cursor, '{');
                    }
                    break;
            }
        }

        if (st < end) {
            if (cursor < (end - 1)) cursor++;

            if (type == MAP) {
                map.put(curr, ex = createStringTrimmed(property, st, cursor - st));
            }
            else {
                if (cursor < end) cursor++;
                list.add(ex = createStringTrimmed(property, st, cursor - st));
            }

            if (subcompile) subCompile(ex);
        }

        switch (type) {
            case MAP:
                return map;
            case ARRAY:
                return list.toArray();
            default:
                return list;
        }
    }

    private void subCompile(String ex) {
        if (colType == null) {
            subCompileExpression(ex.toCharArray(), pCtx);
        }
        else {
            Class r = ((ExecutableStatement) subCompileExpression(ex.toCharArray(), pCtx)).getKnownEgressType();
            if (!colType.isAssignableFrom(r) && (isStrongType() || !DataConversion.canConvert(r, colType))) {
                throw new CompileException("expected type: " + colType.getName() + "; but found: " + r.getName());
            }
        }
    }

    private boolean isStrongType() {
        return pCtx != null && pCtx.isStrongTyping();
    }

    public int getCursor() {
        return cursor;
    }
}
