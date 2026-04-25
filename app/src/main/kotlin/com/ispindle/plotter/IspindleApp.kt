package com.ispindle.plotter

import android.app.Application
import com.ispindle.plotter.data.AppDatabase
import com.ispindle.plotter.data.PendingCalibration
import com.ispindle.plotter.data.Repository
import com.ispindle.plotter.network.IspindleHttpServer
import java.util.concurrent.atomic.AtomicReference

class IspindleApp : Application() {

    lateinit var repository: Repository
        private set
    lateinit var httpServer: IspindleHttpServer
        private set

    /**
     * Hand-off slot between the Configure pair flow (which reads the
     * polynomial from the iSpindle's AP-mode portal) and the next ingest
     * call (which sees the device join home WiFi and POST). Atomic because
     * ingest happens on Ktor's worker pool while the writer is on a
     * coroutine launched from MainActivity.
     */
    val pendingCalibration = AtomicReference<PendingCalibration?>(null)

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        repository = Repository(
            deviceDao = db.deviceDao(),
            readingDao = db.readingDao(),
            calibrationDao = db.calibrationDao(),
            pendingCalibration = pendingCalibration
        )
        httpServer = IspindleHttpServer(repository)
    }

    override fun onTerminate() {
        httpServer.shutdown()
        super.onTerminate()
    }
}
