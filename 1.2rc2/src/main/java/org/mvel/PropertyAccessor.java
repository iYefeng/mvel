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
package org.mvel;

import static org.mvel.MVEL.eval;
import static org.mvel.DataConversion.canConvert;
import static org.mvel.DataConversion.convert;
import org.mvel.integration.VariableResolverFactory;
import org.mvel.util.ArrayTools;
import org.mvel.util.ParseTools;
import static org.mvel.util.ParseTools.parseParameterList;
import static org.mvel.util.PropertyTools.getFieldOrAccessor;
import static org.mvel.util.PropertyTools.getFieldOrWriteAccessor;
import org.mvel.util.StringAppender;

import java.io.Serializable;
import static java.lang.Character.isJavaIdentifierPart;
import static java.lang.Character.isWhitespace;
import java.lang.reflect.*;
import java.util.*;
import static java.util.Collections.synchronizedMap;

public class PropertyAccessor {
    private int start = 0;
    private int cursor = 0;

    private char[] property;
    private int length;

    private Object thisReference;
    private Object ctx;
    private Object curr;

    private boolean first = true;

    private VariableResolverFactory resolver;

    private static final int DONE = -1;
    private static final int NORM = 0;
    private static final int METH = 1;
    private static final int COL = 2;

    private static final Object[] EMPTYARG = new Object[0];

    private static Map<Class, Map<Integer, Member>> READ_PROPERTY_RESOLVER_CACHE;
    private static Map<Class, Map<Integer, Member>> WRITE_PROPERTY_RESOLVER_CACHE;
    private static Map<Class, Map<Integer, Object[]>> METHOD_RESOLVER_CACHE;

    static {
        configureFactory();
    }

    static void configureFactory() {
        if (MVEL.THREAD_SAFE) {
            READ_PROPERTY_RESOLVER_CACHE = synchronizedMap(new WeakHashMap<Class, Map<Integer, Member>>(10));
            WRITE_PROPERTY_RESOLVER_CACHE = synchronizedMap(new WeakHashMap<Class, Map<Integer, Member>>(10));
            METHOD_RESOLVER_CACHE = synchronizedMap(new WeakHashMap<Class, Map<Integer, Object[]>>(10));
        }
        else {
            READ_PROPERTY_RESOLVER_CACHE = (new WeakHashMap<Class, Map<Integer, Member>>(10));
            WRITE_PROPERTY_RESOLVER_CACHE = (new WeakHashMap<Class, Map<Integer, Member>>(10));
            METHOD_RESOLVER_CACHE = (new WeakHashMap<Class, Map<Integer, Object[]>>(10));
        }
    }

    public PropertyAccessor(char[] property, Object ctx) {
        this.property = property;
        this.length = property.length;
        this.ctx = ctx;
    }

    public PropertyAccessor(char[] property, Object ctx, VariableResolverFactory resolver, Object thisReference) {
        this.property = property;
        this.length = property.length;
        this.ctx = ctx;
        this.resolver = resolver;
        this.thisReference = thisReference;
    }

    public PropertyAccessor(char[] property, Object ctx, Object thisRef, VariableResolverFactory resolver, Object thisReference) {
        this.property = property;
        this.length = property.length;
        this.ctx = ctx;
        this.thisReference = thisRef;
        this.resolver = resolver;
        this.thisReference = thisReference;
    }

    public PropertyAccessor(VariableResolverFactory resolver, Object thisReference) {
        this.resolver = resolver;
        this.thisReference = thisReference;
    }


    public PropertyAccessor(char[] property, int offset, int end, Object ctx, VariableResolverFactory resolver) {
        this.property = property;
        this.cursor = offset;
        this.length = end;
        this.ctx = ctx;
        this.resolver = resolver;
    }

    public PropertyAccessor(String property, Object ctx) {
        this.length = (this.property = property.toCharArray()).length;
        this.ctx = ctx;
    }

    public static Object get(String property, Object ctx) {
        return new PropertyAccessor(property, ctx).get();
    }

    public static Object get(char[] property, Object ctx, VariableResolverFactory resolver, Object thisReference) {
        return new PropertyAccessor(property, ctx, resolver, thisReference).get();
    }

    public static Object get(char[] property, int offset, int end, Object ctx, VariableResolverFactory resolver) {
        return new PropertyAccessor(property, offset, end, ctx, resolver).get();
    }

    public static Object get(String property, Object ctx, VariableResolverFactory resolver, Object thisReference) {
        return new PropertyAccessor(property.toCharArray(), ctx, resolver, thisReference).get();
    }

    public static void set(Object ctx, String property, Object value) {
        new PropertyAccessor(property, ctx).set(value);
    }

    public static void set(Object ctx, VariableResolverFactory resolver, String property, Object value) {
        new PropertyAccessor(property.toCharArray(), ctx, resolver, null).set(value);
    }

    private Object get() {
        curr = ctx;

        try {
            while (cursor < length) {
                switch (nextToken()) {
                    case NORM:
                        curr = getBeanProperty(curr, capture());
                        break;
                    case METH:
                        curr = getMethod(curr, capture());
                        break;
                    case COL:
                        curr = getCollectionProperty(curr, capture());
                        break;
                    case DONE:
                }

                first = false;
            }

            return curr;
        }
        catch (InvocationTargetException e) {
            throw new PropertyAccessException("could not access property", e);
        }
        catch (IllegalAccessException e) {
            throw new PropertyAccessException("could not access property", e);
        }
        catch (IndexOutOfBoundsException e) {
            throw new PropertyAccessException("array or collections index out of bounds (property: " + new String(property) + ")", e);
        }
        catch (PropertyAccessException e) {
            throw new PropertyAccessException("failed to access property: <<" + new String(property) + ">> in: " + (ctx != null ? ctx.getClass() : null), e);
        }
        catch (CompileException e) {
            throw e;
        }
        catch (NullPointerException e) {
            throw new PropertyAccessException("null pointer exception in property: " + new String(property), e);
        }
        catch (Exception e) {
            throw new PropertyAccessException("unknown exception in expression: " + new String(property), e);
        }
    }

    private void set(Object value) {
        curr = ctx;

        try {
            int oLength = length;
            length = ArrayTools.findLast('.', property);
            curr = get();

            if (curr == null)
                throw new PropertyAccessException("cannot bind to null context: " + new String(property));

            length = oLength;

            if (nextToken() == COL) {

                int start = ++cursor;

                whiteSpaceSkip();

                if (cursor == length)
                    throw new PropertyAccessException("unterminated '['");

                if (!scanTo(']'))
                    throw new PropertyAccessException("unterminated '['");

                String ex = new String(property, start, cursor - start);

                if (ctx instanceof Map) {
                    //noinspection unchecked
                    ((Map) ctx).put(eval(ex, this.ctx, this.resolver), value);
                }
                else if (ctx instanceof List) {
                    //noinspection unchecked
                    ((List) ctx).set(eval(ex, this.ctx, this.resolver, Integer.class), value);
                }
                else if (ctx instanceof Object[]) {
                    ((Object[]) ctx)[eval(ex, this.ctx, this.resolver, Integer.class)] = convert(value, ctx.getClass().getComponentType());
                }

                else {
                    throw new PropertyAccessException("cannot bind to collection property: " + new String(property) + ": not a recognized collection type: " + ctx.getClass());
                }

                return;
            }

            String tk = capture();

            Member member = checkWriteCache(curr.getClass(), tk == null ? 0 : tk.hashCode());
            if (member == null) {
                addWriteCache(curr.getClass(), tk == null ? 0 : tk.hashCode(), (member = getFieldOrWriteAccessor(curr.getClass(), tk)));
            }

            if (member instanceof Field) {
                Field fld = (Field) member;

                if (value != null && !fld.getType().isAssignableFrom(value.getClass())) {
                    if (!canConvert(fld.getType(), value.getClass())) {
                        throw new ConversionException("cannot convert type: "
                                + value.getClass() + ": to " + fld.getType());
                    }

                    fld.set(curr, convert(value, fld.getType()));
                }
                else {
                    fld.set(curr, value);
                }
            }
            else if (member != null) {
                Method meth = (Method) member;

                if (value != null && !meth.getParameterTypes()[0].isAssignableFrom(value.getClass())) {
                    if (!canConvert(meth.getParameterTypes()[0], value.getClass())) {
                        throw new ConversionException("cannot convert type: "
                                + value.getClass() + ": to " + meth.getParameterTypes()[0]);
                    }
                    meth.invoke(curr, convert(value, meth.getParameterTypes()[0]));
                }
                else {
                    meth.invoke(curr, value);
                }
            }
            else if (curr instanceof Map) {
                //noinspection unchecked
                ((Map) curr).put(eval(tk, this.ctx, this.resolver), value);
            }
            else {
                throw new PropertyAccessException("could not access property (" + tk + ") in: " + ctx.getClass().getName());
            }
        }
        catch (InvocationTargetException e) {
            throw new PropertyAccessException("could not access property", e);
        }
        catch (IllegalAccessException e) {
            throw new PropertyAccessException("could not access property", e);
        }

    }


//    private String captureNext() {
//        nextToken();
//        return capture();
//    }

    private int nextToken() {
        switch (property[start = cursor]) {
            case'[':
                return COL;
            case'.':
                cursor = ++start;
        }

        //noinspection StatementWithEmptyBody
        while (++cursor < length && isJavaIdentifierPart(property[cursor])) ;


        if (cursor < length) {
            while (isWhitespace(property[cursor])) cursor++;
            switch (property[cursor]) {
                case'[':
                    return COL;
                case'(':
                    return METH;
                default:
                    return 0;
            }
        }
        return 0;
    }

    private String capture() {
        return new String(property, start, trimLeft(cursor) - start);
    }

    protected int trimLeft(int pos) {
        while (pos > 0 && isWhitespace(property[pos - 1])) pos--;
        return pos;
    }


    public static void clearPropertyResolverCache() {
        READ_PROPERTY_RESOLVER_CACHE.clear();
        WRITE_PROPERTY_RESOLVER_CACHE.clear();
        METHOD_RESOLVER_CACHE.clear();
    }

    public static void reportCacheSizes() {
        System.out.println("read property cache: " + READ_PROPERTY_RESOLVER_CACHE.size());
        for (Class cls : READ_PROPERTY_RESOLVER_CACHE.keySet()) {
            System.out.println(" [" + cls.getName() + "]: " + READ_PROPERTY_RESOLVER_CACHE.get(cls).size() + " entries.");
        }
        System.out.println("write property cache: " + WRITE_PROPERTY_RESOLVER_CACHE.size());
        for (Class cls : WRITE_PROPERTY_RESOLVER_CACHE.keySet()) {
            System.out.println(" [" + cls.getName() + "]: " + WRITE_PROPERTY_RESOLVER_CACHE.get(cls).size() + " entries.");
        }
        System.out.println("method cache: " + METHOD_RESOLVER_CACHE.size());
        for (Class cls : METHOD_RESOLVER_CACHE.keySet()) {
            System.out.println(" [" + cls.getName() + "]: " + METHOD_RESOLVER_CACHE.get(cls).size() + " entries.");
        }
    }

    private static void addReadCache(Class cls, Integer property, Member member) {
        if (!READ_PROPERTY_RESOLVER_CACHE.containsKey(cls)) {
            READ_PROPERTY_RESOLVER_CACHE.put(cls, new WeakHashMap<Integer, Member>());
        }
        READ_PROPERTY_RESOLVER_CACHE.get(cls).put(property, member);
    }

    private static Member checkReadCache(Class cls, Integer property) {
        if (READ_PROPERTY_RESOLVER_CACHE.containsKey(cls)) {
            return READ_PROPERTY_RESOLVER_CACHE.get(cls).get(property);
        }
        return null;
    }

    private static void addWriteCache(Class cls, Integer property, Member member) {
        if (!WRITE_PROPERTY_RESOLVER_CACHE.containsKey(cls)) {
            WRITE_PROPERTY_RESOLVER_CACHE.put(cls, new WeakHashMap<Integer, Member>());
        }
        WRITE_PROPERTY_RESOLVER_CACHE.get(cls).put(property, member);
    }

    private static Member checkWriteCache(Class cls, Integer property) {
        if (WRITE_PROPERTY_RESOLVER_CACHE.containsKey(cls)) {
            return WRITE_PROPERTY_RESOLVER_CACHE.get(cls).get(property);
        }
        return null;
    }


    private static void addMethodCache(Class cls, Integer property, Method member) {
        if (!METHOD_RESOLVER_CACHE.containsKey(cls)) {
            METHOD_RESOLVER_CACHE.put(cls, new WeakHashMap<Integer, Object[]>());
        }
        METHOD_RESOLVER_CACHE.get(cls).put(property, new Object[]{member, member.getParameterTypes()});
    }

    private static Object[] checkMethodCache(Class cls, Integer property) {
        if (METHOD_RESOLVER_CACHE.containsKey(cls)) {
            return METHOD_RESOLVER_CACHE.get(cls).get(property);
        }
        return null;
    }


    private Object getBeanProperty(Object ctx, String property)
            throws IllegalAccessException, InvocationTargetException {


        if (first && resolver != null && resolver.isResolveable(property)) {
            return resolver.getVariableResolver(property).getValue();
        }


        Class cls;
        Member member = checkReadCache(cls = (ctx instanceof Class ? ((Class) ctx) : ctx.getClass()), property.hashCode());

        if (member == null) {
            addReadCache(cls, property.hashCode(), member = getFieldOrAccessor(cls, property));
        }

        if (member instanceof Field) {
            return ((Field) member).get(ctx);
        }
        else if (member != null) {
            try {
                return ((Method) member).invoke(ctx, EMPTYARG);
            }
            catch (IllegalAccessException e) {
                synchronized (member) {
                    try {
                        ((Method) member).setAccessible(true);
                        return ((Method) member).invoke(ctx, EMPTYARG);
                    }
                    finally {
                        ((Method) member).setAccessible(false);
                    }
                }
            }

        }
        else if (ctx instanceof Map && ((Map) ctx).containsKey(property)) {
            return ((Map) ctx).get(property);
        }
        else if ("this".equals(property)) {
            return this.thisReference;
        }
        else if (ctx instanceof Class) {
            Class c = (Class) ctx;
            for (Method m : c.getMethods()) {
                if (property.equals(m.getName())) {
                    return m;
                }
            }


        }
        throw new PropertyAccessException("could not access property (" + property + ")");
    }

    private void whiteSpaceSkip() {
        if (cursor < length)
            //noinspection StatementWithEmptyBody
            while (isWhitespace(property[cursor]) && ++cursor < length) ;
    }

    private boolean scanTo(char c) {
        for (; cursor < length; cursor++) {
            if (property[cursor] == c) {
                return true;
            }
        }
        return false;
    }

//    private int containsStringLiteralTermination() {
//        int pos = cursor;
//        for (pos--; pos > 0; pos--) {
//            if (property[pos] == '\'' || property[pos] == '"') return pos;
//            else if (!isWhitespace(property[pos])) return pos;
//        }
//        return -1;
//    }


    /**
     * Handle accessing a property embedded in a collections, map, or array
     *
     * @param ctx  -
     * @param prop -
     * @return -
     * @throws Exception -
     */
    private Object getCollectionProperty(Object ctx, String prop) throws Exception {
        if (prop.length() > 0) ctx = getBeanProperty(ctx, prop);

        int start = ++cursor;

        whiteSpaceSkip();

        if (cursor == length)
            throw new PropertyAccessException("unterminated '['");

        Object item;

        if (!scanTo(']'))
            throw new PropertyAccessException("unterminated '['");

        String ex = new String(property, start, cursor - start);

        item = eval(ex, ctx, resolver);

        ++cursor;

        if (ctx instanceof Map) {
            return ((Map) ctx).get(item);
        }
        else if (ctx instanceof List) {
            return ((List) ctx).get((Integer) item);
        }
        else if (ctx instanceof Collection) {
            int count = (Integer) item;
            if (count > ((Collection) ctx).size())
                throw new PropertyAccessException("index [" + count + "] out of bounds on collections");

            Iterator iter = ((Collection) ctx).iterator();
            for (int i = 0; i < count; i++) iter.next();
            return iter.next();
        }
        else if (ctx instanceof Object[]) {
            return ((Object[]) ctx)[(Integer) item];
        }
        else if (ctx instanceof CharSequence) {
            return ((CharSequence) ctx).charAt((Integer) item);
        }
        else {
            throw new PropertyAccessException("illegal use of []: unknown type: " + (ctx == null ? null : ctx.getClass().getName()));
        }
    }

    private static final Map<String, Serializable[]> SUBEXPRESSION_CACHE = new WeakHashMap<String, Serializable[]>();

    /**
     * Find an appropriate method, execute it, and return it's response.
     *
     * @param ctx  -
     * @param name -
     * @return -
     * @throws Exception -
     */
    @SuppressWarnings({"unchecked"})
    private Object getMethod(Object ctx, String name) throws Exception {
        if (first && resolver != null && resolver.isResolveable(name)) {
            Method m = (Method) resolver.getVariableResolver(name).getValue();
            ctx = m.getDeclaringClass();
            name = m.getName();
            first = false;
        }
        int st = cursor;

 //       int depth = 1;

        cursor = ParseTools.balancedCapture(property, cursor, '(');


//        while (cursor++ < length - 1 && depth != 0) {
//            switch (property[cursor]) {
//                case'(':
//                    depth++;
//                    continue;
//                case')':
//                    depth--;
//
//            }
//        }
//        cursor--;

        String tk = (cursor - st) > 1 ? new String(property, st + 1, cursor - st - 1) : "";

        cursor++;

        Object[] args;
        Serializable[] es;

        if (tk.length() == 0) {
            args = ParseTools.EMPTY_OBJ_ARR;
            es = null;
        }
        else {
            if (SUBEXPRESSION_CACHE.containsKey(tk)) {
                es = SUBEXPRESSION_CACHE.get(tk);
                args = new Object[es.length];
                for (int i = 0; i < es.length; i++) {
                    args[i] = MVEL.executeExpression(es[i], thisReference, resolver);
                }

            }
            else {
                String[] subtokens = parseParameterList(tk.toCharArray(), 0, -1);

                es = new Serializable[subtokens.length];
                args = new Object[subtokens.length];
                for (int i = 0; i < subtokens.length; i++) {
                    es[i] = ParseTools.subCompileExpression(subtokens[i]);
                    args[i] = MVEL.executeExpression(es[i], thisReference, resolver);

                    if (es[i] instanceof CompiledExpression)
                        ((CompiledExpression) es[i]).setKnownEgressType(args[i] != null ? args[i].getClass() : null);
                }

                SUBEXPRESSION_CACHE.put(tk, es);
            }

        }

        /**
         * If the target object is an instance of java.lang.Class itself then do not
         * adjust the Class scope target.
         */
        Class cls = ctx instanceof Class ? (Class) ctx : ctx.getClass();

        //    Integer signature = ;

        /**
         * Check to see if we have already cached this method;
         */
        Object[] cache = checkMethodCache(cls, createSignature(name, tk));

        Method m;
        Class[] parameterTypes;

        if (cache != null) {
            m = (Method) cache[0];
            parameterTypes = (Class[]) cache[1];
        }
        else {
            m = null;
            parameterTypes = null;
        }

        /**
         * If we have not cached the method then we need to go ahead and try to resolve it.
         */
        if (m == null) {
            /**
             * Try to find an instance method from the class target.
             */

            if ((m = ParseTools.getBestCandidate(args, name, cls.getMethods())) != null) {
                addMethodCache(cls, createSignature(name, tk), m);
                parameterTypes = m.getParameterTypes();
            }

            if (m == null) {
                /**
                 * If we didn't find anything, maybe we're looking for the actual java.lang.Class methods.
                 */
                if ((m = ParseTools.getBestCandidate(args, name, cls.getClass().getDeclaredMethods())) != null) {
                    addMethodCache(cls, createSignature(name, tk), m);
                    parameterTypes = m.getParameterTypes();
                }
            }
        }

        if (m == null) {
            StringAppender errorBuild = new StringAppender();
            for (int i = 0; i < args.length; i++) {
                errorBuild.append(args[i] != null ? args[i].getClass().getName() : null);
                if (i < args.length - 1) errorBuild.append(", ");
            }

            if ("size".equals(name) && args.length == 0 && cls.isArray()) {
                return Array.getLength(ctx);
            }

            throw new PropertyAccessException("unable to resolve method: " + cls.getName() + "." + name + "(" + errorBuild.toString() + ") [arglength=" + args.length + "]");
        }
        else {
            if (es != null) {
                ExecutableStatement cExpr;
                for (int i = 0; i < es.length; i++) {
                    cExpr = (ExecutableStatement) es[i];
                    if (cExpr.getKnownIngressType() == null) {
                        cExpr.setKnownIngressType(parameterTypes[i]);
                        cExpr.computeTypeConversionRule();
                    }
                    if (!cExpr.isConvertableIngressEgress()) {
                        args[i] = convert(args[i], parameterTypes[i]);
                    }
                }
            }
            else {
                /**
                 * Coerce any types if required.
                 */
                for (int i = 0; i < args.length; i++)
                    args[i] = convert(args[i], parameterTypes[i]);
            }

            /**
             * Invoke the target method and return the response.
             */
            return m.invoke(ctx, args);
        }
    }

    private static int createSignature(String name, String args) {
        return name.hashCode() + args.hashCode();
    }

}