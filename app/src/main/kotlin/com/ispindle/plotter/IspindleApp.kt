package com.ispindle.plotter

import android.app.Application
import com.ispindle.plotter.data.AppDatabase
import com.ispindle.plotter.data.Repository
import com.ispindle.plotter.network.IspindleHttpServer

class IspindleApp : Application() {

    lateinit var repository: Repository
        private set
    lateinit var httpServer: IspindleHttpServer
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        repository = Repository(db.deviceDao(), db.readingDao(), db.calibrationDao())
        httpServer = IspindleHttpServer(repository)
    }

    override fun onTerminate() {
        httpServer.shutdown()
        super.onTerminate()
    }
}
