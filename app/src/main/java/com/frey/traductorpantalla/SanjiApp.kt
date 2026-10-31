package com.frey.traductorpantalla

import android.app.Application

/** Carga al arrancar lo que comparten todos los modos (la lista de personajes). */
class SanjiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Glossary.load(this)
    }
}
