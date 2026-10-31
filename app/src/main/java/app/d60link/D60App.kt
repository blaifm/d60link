package app.d60link

import android.app.Application
import android.content.Context

class D60App : Application() {
    val controller: Controller by lazy { Controller(this) }
}

val Context.controller: Controller get() = (applicationContext as D60App).controller
