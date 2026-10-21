package com.blushdesk.app

import android.app.Application
import com.blushdesk.app.di.AppContainer
import com.blushdesk.app.notifications.ReminderWorker

class FergBentablesApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // The inventory reminders' background job; scheduling again on later starts changes nothing.
        ReminderWorker.schedule(this)
    }
}
