/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.runtime.array.dyn;

import static com.oracle.truffle.js.runtime.builtins.JSAbstractArray.arrayGetArray;
import static com.oracle.truffle.js.runtime.builtins.JSAbstractArray.arraySetArray;

import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.js.runtime.JSConfig;
import com.oracle.truffle.js.runtime.array.DynamicArray;
import com.oracle.truffle.js.runtime.array.ScriptArray;

public final class ConstantIntArray extends AbstractConstantArray {
    private static final ConstantIntArray CONSTANT_INT_ARRAY = new ConstantIntArray(INTEGRITY_LEVEL_NONE, createCache());

    public static ConstantIntArray createConstantIntArray() {
        return CONSTANT_INT_ARRAY;
    }

    private ConstantIntArray(int integrityLevel, DynamicArrayCache cache) {
        super(integrityLevel, cache);
    }

    @Override
    public Object getElementInBounds(DynamicObject object, int index, boolean condition) {
        return getElementInt(object, index, condition);
    }

    public static int getElementInt(DynamicObject object, int index, boolean condition) {
        return getArray(object, condition)[index];
    }

    private static int[] getArray(DynamicObject object) {
        return (int[]) arrayGetArray(object);
    }

    public static int[] getArray(DynamicObject object, boolean condition) {
        return (int[]) arrayGetArray(object, condition);
    }

    @Override
    public int lengthInt(DynamicObject object, boolean condition) {
        return getArray(object, condition).length;
    }

    @Override
    public boolean hasElement(DynamicObject object, long index, boolean condition) {
        return index >= 0 && index < getArray(object, condition).length;
    }

    @Override
    public Object[] toArray(DynamicObject object) {
        return ArrayCopy.intToObject(getArray(object));
    }

    @Override
    public ScriptArray deleteElementImpl(DynamicObject object, long index, boolean strict, boolean condition) {
        return createWriteableInt(object, index, HolesIntArray.HOLE_VALUE, condition, ProfileHolder.empty()).deleteElementImpl(object, index, strict, condition);
    }

    @Override
    public ScriptArray setLengthImpl(DynamicObject object, long length, boolean condition, ProfileHolder profile) {
        return createWriteableInt(object, length - 1, HolesIntArray.HOLE_VALUE, condition, ProfileHolder.empty()).setLengthImpl(object, length, condition, profile);
    }

    @Override
    public AbstractIntArray createWriteableInt(DynamicObject object, long index, int value, boolean condition, ProfileHolder profile) {
        int[] copyArray = ArrayCopy.intToInt(getArray(object, condition));
        ZeroBasedIntArray newArray = ZeroBasedIntArray.makeZeroBasedIntArray(object, copyArray.length, copyArray.length, copyArray, integrityLevel);
        if (JSConfig.TraceArrayTransitions) {
            traceArrayTransition(this, newArray, index, value);
        }
        return newArray;
    }

    @Override
    public AbstractWritableArray createWriteableDouble(DynamicObject object, long index, double value, boolean condition, ProfileHolder profile) {
        double[] copyArray = ArrayCopy.intToDouble(getArray(object, condition));
        ZeroBasedDoubleArray newArray = ZeroBasedDoubleArray.makeZeroBasedDoubleArray(object, copyArray.length, copyArray.length, copyArray, integrityLevel);
        if (JSConfig.TraceArrayTransitions) {
            traceArrayTransition(this, newArray, index, value);
        }
        return newArray;
    }

    @Override
    public AbstractWritableArray createWriteableJSObject(DynamicObject object, long index, DynamicObject value, boolean condition, ProfileHolder profile) {
        return createWriteableObject(object, index, value, condition, ProfileHolder.empty());
    }

    @Override
    public AbstractWritableArray createWriteableObject(DynamicObject object, long index, Object value, boolean condition, ProfileHolder profile) {
        Object[] copyArray = ArrayCopy.intToObject(getArray(object, condition));
        ZeroBasedObjectArray newArray = ZeroBasedObjectArray.makeZeroBasedObjectArray(object, copyArray.length, copyArray.length, copyArray, integrityLevel);
        if (JSConfig.TraceArrayTransitions) {
            traceArrayTransition(this, newArray, index, value);
        }
        return newArray;
    }

    @Override
    public ScriptArray removeRangeImpl(DynamicObject object, long start, long end) {
        int[] array = getArray(object);
        if ((array.length - (end - start)) == 0) {
            AbstractConstantEmptyArray.setCapacity(object, 0);
        } else {
            int[] newArray = new int[array.length - (int) (end - start)];
            System.arraycopy(array, 0, newArray, 0, (int) start);
            System.arraycopy(array, (int) end, newArray, (int) start, (int) (array.length - end));
            arraySetArray(object, newArray);
        }
        return this;
    }

    @Override
    public ScriptArray addRangeImpl(DynamicObject object, long offset, int size) {
        int[] array = getArray(object);
        if (array.length == 0) {
            AbstractConstantEmptyArray.setCapacity(object, size);
            return this;
        } else {
            int[] newArray = new int[array.length + size];
            System.arraycopy(array, 0, newArray, 0, (int) offset);
            System.arraycopy(array, (int) offset, newArray, (int) offset + size, (int) (array.length - offset));

            arraySetArray(object, newArray);
            return this;
        }
    }

    @Override
    protected DynamicArray withIntegrityLevel(int newIntegrityLevel) {
        return new ConstantIntArray(newIntegrityLevel, cache);
    }
}
