package com.holdgrip

import android.content.Context

// عدد انگلیسی را به ارقام فارسی تبدیل می‌کند
fun Int.toFa(): String =
    toString().map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")

// ارقام فارسی را به انگلیسی برمی‌گرداند تا بشود پارس کرد
fun String.toEnDigits(): String =
    map { if (it in '۰'..'۹') '0' + (it - '۰') else it }.joinToString("")

// همه مختصات به صورت نسبت بین ۰ و ۱ از عرض و ارتفاع صفحه ذخیره می‌شوند
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("holdgrip", Context.MODE_PRIVATE)

    var ax: Float
        get() = sp.getFloat("ax", 0.5f)
        set(v) { sp.edit().putFloat("ax", v).apply() }

    var ay: Float
        get() = sp.getFloat("ay", 0.70f)
        set(v) { sp.edit().putFloat("ay", v).apply() }

    var bx: Float
        get() = sp.getFloat("bx", 0.5f)
        set(v) { sp.edit().putFloat("bx", v).apply() }

    var by: Float
        get() = sp.getFloat("by", 0.35f)
        set(v) { sp.edit().putFloat("by", v).apply() }

    var btnX: Float
        get() = sp.getFloat("btnX", 0.85f)
        set(v) { sp.edit().putFloat("btnX", v).apply() }

    var btnY: Float
        get() = sp.getFloat("btnY", 0.55f)
        set(v) { sp.edit().putFloat("btnY", v).apply() }

    var swipeMs: Int
        get() = sp.getInt("swipeMs", 120)
        set(v) { sp.edit().putInt("swipeMs", v).apply() }

    var showButton: Boolean
        get() = sp.getBoolean("showButton", true)
        set(v) { sp.edit().putBoolean("showButton", v).apply() }

    var locked: Boolean
        get() = sp.getBoolean("locked", true)
        set(v) { sp.edit().putBoolean("locked", v).apply() }
}
