package aff.importer.tool

import android.app.Application
import aff.importer.tool.data.CrashLogManager

class ArcaeaImporterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogManager.init(this)
    }
}