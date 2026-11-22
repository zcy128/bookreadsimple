package com.example.bookreader

import android.app.Application
import com.example.bookreader.data.auth.UserManager
import com.example.bookreader.data.local.AppDatabase
import com.example.bookreader.data.remote.GoogleDriveManager
import com.example.bookreader.data.repository.BookRepository
import com.example.bookreader.ui.locale.LocaleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BookReaderApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { AppDatabase.getInstance(this) }
    val userManager by lazy { UserManager(this, applicationScope) }
    val driveManager by lazy { GoogleDriveManager(this) }

    val repository by lazy {
        BookRepository(
            context = this,
            database = database,
            driveManager = driveManager,
            userManager = userManager,
            scope = applicationScope
        )
    }

    override fun onCreate() {
        super.onCreate()
        LocaleManager.init(this)
    }
}
