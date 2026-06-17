package com.vortexdbg.linux.android.dvm.jni

import java.lang.reflect.Field

internal class ProxyField internal constructor(
    private val visitor: ProxyDvmObjectVisitor?,
    private val field: Field
) {

    @Throws(IllegalAccessException::class)
    internal fun get(thisObj: Any?): Any? {
        if (visitor != null) {
            visitor.onProxyVisit(field, thisObj, null)
        }
        var result = field.get(thisObj)
        if (visitor != null) {
            result = visitor.postProxyVisit(field, thisObj, null, result)
        }
        return result
    }

    @Throws(IllegalAccessException::class)
    internal fun getLong(thisObj: Any?): Long {
        if (visitor != null) {
            visitor.onProxyVisit(field, thisObj, null)
        }
        var result = field.getLong(thisObj)
        if (visitor != null) {
            result = visitor.postProxyVisit(field, thisObj, null, result)
        }
        return result
    }

    @Throws(IllegalAccessException::class)
    internal fun getFloat(thisObj: Any?): Float {
        if (visitor != null) {
            visitor.onProxyVisit(field, thisObj, null)
        }
        var result = field.getFloat(thisObj)
        if (visitor != null) {
            result = visitor.postProxyVisit(field, thisObj, null, result)
        }
        return result
    }

    @Throws(IllegalAccessException::class)
    internal fun getBoolean(thisObj: Any?): Boolean {
        if (visitor != null) {
            visitor.onProxyVisit(field, thisObj, null)
        }
        var result = field.getBoolean(thisObj)
        if (visitor != null) {
            result = visitor.postProxyVisit(field, thisObj, null, result)
        }
        return result
    }

    @Throws(IllegalAccessException::class)
    internal fun getByte(thisObj: Any?): Byte {
        if (visitor != null) {
            visitor.onProxyVisit(field, thisObj, null)
        }
        var result = field.getByte(thisObj)
        if (visitor != null) {
            result = visitor.postProxyVisit(field, thisObj, null, result)
        }
        return result
    }

    @Throws(IllegalAccessException::class)
    internal fun getInt(thisObj: Any?): Int {
        if (visitor != null) {
            visitor.onProxyVisit(field, thisObj, null)
        }
        var result = field.getInt(thisObj)
        if (visitor != null) {
            result = visitor.postProxyVisit(field, thisObj, null, result)
        }
        return result
    }

    @Throws(IllegalAccessException::class)
    internal fun setInt(thisObj: Any?, value: Int) {
        if (visitor != null) {
            visitor.onProxyVisit(field, thisObj, arrayOf<Any?>(value))
        }
        field.setInt(thisObj, value)
    }

    @Throws(IllegalAccessException::class)
    internal fun setFloat(thisObj: Any?, value: Float) {
        if (visitor != null) {
            visitor.onProxyVisit(field, thisObj, arrayOf<Any?>(value))
        }
        field.setFloat(thisObj, value)
    }

    @Throws(IllegalAccessException::class)
    internal fun setDouble(thisObj: Any?, value: Double) {
        if (visitor != null) {
            visitor.onProxyVisit(field, thisObj, arrayOf<Any?>(value))
        }
        field.setDouble(thisObj, value)
    }

    @Throws(IllegalAccessException::class)
    internal fun setObject(thisObj: Any?, value: Any?) {
        if (visitor != null) {
            visitor.onProxyVisit(field, thisObj, arrayOf<Any?>(value))
        }
        field.set(thisObj, value)
    }

    @Throws(IllegalAccessException::class)
    internal fun setBoolean(thisObj: Any?, value: Boolean) {
        if (visitor != null) {
            visitor.onProxyVisit(field, thisObj, arrayOf<Any?>(value))
        }
        field.setBoolean(thisObj, value)
    }

    @Throws(IllegalAccessException::class)
    internal fun setLong(thisObj: Any?, value: Long) {
        if (visitor != null) {
            visitor.onProxyVisit(field, thisObj, arrayOf<Any?>(value))
        }
        field.setLong(thisObj, value)
    }

}
