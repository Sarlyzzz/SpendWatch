package com.spendwatch.app

import android.app.Application
import com.spendwatch.app.data.AppDatabase
import com.spendwatch.app.data.TransactionRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class SpendWatchApplication : Application() {
    val database by lazy { AppDatabase.create(this) }
    val repository by lazy { TransactionRepository(database) }

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
