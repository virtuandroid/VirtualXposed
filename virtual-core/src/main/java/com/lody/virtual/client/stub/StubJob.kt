package com.lody.virtual.client.stub

import android.app.Service
import android.app.job.IJobCallback
import android.app.job.IJobService
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.lody.virtual.client.core.InvocationStubManager
import com.lody.virtual.client.hook.proxies.am.ActivityManagerStub
import com.lody.virtual.helper.collection.SparseArray
import com.lody.virtual.helper.utils.VLog
import com.lody.virtual.os.VBinder
import com.lody.virtual.os.VUserHandle
import com.lody.virtual.server.job.VJobSchedulerService.Companion.get

/**
 * @author Lody
 * 
 * This service running on the Server process.
 */
// TODO Ensure the service is sandboxed
class StubJob : Service() {
    private val mJobSessions: SparseArray<JobSession?> = SparseArray()
    private var mScheduler: JobScheduler? = null
    private val mService: IJobService = object : IJobService.Stub() {
        @Throws(RemoteException::class)
        override fun startJob(jobParams: JobParameters) {
            val jobId = jobParams.jobId
            val binder = mirror.android.app.job.JobParameters.callback.get(jobParams)
            val callback = IJobCallback.Stub.asInterface(binder)
            val entry = get().findJobByVirtualJobId(jobId)
            if (entry == null) {
                emptyCallback(callback, jobId)
                mScheduler!!.cancel(jobId)
            } else {
                val key = entry.key
                val config = entry.value
                synchronized(mJobSessions) {
                    var session = mJobSessions.get(jobId)
                    if (session != null) {
                        // Job Session has exist.
                        emptyCallback(callback, jobId)
                    } else {
                        session = JobSession(jobId, callback, jobParams)
                        mirror.android.app.job.JobParameters.callback.set(
                            jobParams, session.asBinder()
                        )
                        mirror.android.app.job.JobParameters.jobId.set(jobParams, key.clientJobId)
                        val service = Intent()
                        service.setComponent(ComponentName(key.packageName, config.serviceName!!))
                        service.putExtra("_VA_|_user_id_", VUserHandle.getUserId(key.vuid))
                        var bound = false
                        try {
                            bound = bindService(service, session, 0)
                        } catch (e: Throwable) {
                            VLog.e(TAG, e)
                        }
                        if (bound) {
                            mJobSessions.put(jobId, session)
                        } else {
                            emptyCallback(callback, jobId)
                            mScheduler!!.cancel(jobId)
                            get().cancel(jobId)
                        }
                    }
                }
            }
        }

        @Throws(RemoteException::class)
        override fun stopJob(jobParams: JobParameters) {
            val jobId = jobParams.jobId
            synchronized(mJobSessions) {
                val session = mJobSessions.get(jobId)
                session?.stopSession()
            }
        }
    }

    /**
     * Make JobScheduler happy.
     */
    private fun emptyCallback(callback: IJobCallback, jobId: Int) {
        try {
            callback.acknowledgeStartMessage(jobId, false)
            callback.jobFinished(jobId, false)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    override fun onCreate() {
        super.onCreate()
        InvocationStubManager.getInstance()
            .checkEnv<ActivityManagerStub?>(ActivityManagerStub::class.java)
        mScheduler = getSystemService(JOB_SCHEDULER_SERVICE) as JobScheduler
    }

    override fun onBind(intent: Intent?): IBinder? {
        return mService.asBinder()
    }

    private inner class JobSession(
        private val jobId: Int,
        private val clientCallback: IJobCallback,
        private val jobParams: JobParameters?
    ) : IJobCallback.Stub(), ServiceConnection {
        private var clientJobService: IJobService? = null

        @Throws(RemoteException::class)
        override fun acknowledgeStartMessage(jobId: Int, ongoing: Boolean) {
            clientCallback.acknowledgeStartMessage(jobId, ongoing)
        }

        @Throws(RemoteException::class)
        override fun acknowledgeStopMessage(jobId: Int, reschedule: Boolean) {
            clientCallback.acknowledgeStopMessage(jobId, reschedule)
        }

        @Throws(RemoteException::class)
        override fun jobFinished(jobId: Int, reschedule: Boolean) {
            clientCallback.jobFinished(jobId, reschedule)
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            clientJobService = IJobService.Stub.asInterface(service)
            if (clientJobService == null) {
                emptyCallback(clientCallback, jobId)
                stopSession()
                return
            }
            try {
                clientJobService!!.startJob(jobParams)
            } catch (e: Exception) {
                forceFinishJob()
                e.printStackTrace()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
        }

        fun forceFinishJob() {
            try {
                clientCallback.jobFinished(jobId, false)
            } catch (e: RemoteException) {
                e.printStackTrace()
            } finally {
                stopSession()
            }
        }

        fun stopSession() {
            if (clientJobService != null) {
                try {
                    clientJobService!!.stopJob(jobParams)
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
            }
            mJobSessions.remove(jobId)
            unbindService(this)
        }
    }

    companion object {
        private val TAG: String = StubJob::class.java.getSimpleName()
    }
}
