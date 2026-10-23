package com.lody.virtual.server.job

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.os.PersistableBundle
import android.os.RemoteException
import android.text.TextUtils
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.stub.VASettings
import com.lody.virtual.helper.utils.Singleton
import com.lody.virtual.os.VBinder
import com.lody.virtual.os.VEnvironment
import com.lody.virtual.server.IJobScheduler
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max

/**
 * @author Lody
 */
class VJobSchedulerService private constructor() : IJobScheduler.Stub() {
    private val mJobStore: MutableMap<JobId, JobConfig> = HashMap()
    private var mGlobalJobId = 0

    private val mScheduler = VirtualCore.get().context
        .getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    private val mJobProxyComponent: ComponentName =
        ComponentName(VirtualCore.get().hostPkg, VASettings.STUB_JOB)

    init {
        readJobs()
    }

    data class JobId(
        @JvmField val vuid: Int,
        @JvmField val packageName: String,
        /**
         * The id given by User.
         */
        @JvmField val clientJobId: Int
    ) : Parcelable {
        internal constructor(p: Parcel) : this(
            p.readInt(),
            p.readString()!!,
            p.readInt(),
        )

        override fun describeContents(): Int {
            return 0
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeInt(this.vuid)
            dest.writeString(this.packageName)
            dest.writeInt(this.clientJobId)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<JobId?> = object : Parcelable.Creator<JobId?> {
                override fun createFromParcel(source: Parcel): JobId {
                    return JobId(source)
                }

                override fun newArray(size: Int): Array<JobId?> {
                    return arrayOfNulls(size)
                }
            }
        }
    }

    data class JobConfig(
        /**
         * The id given by VA.
         */
        val virtualJobId: Int,
        var serviceName: String?,
        var extras: PersistableBundle?
    ) : Parcelable {
        internal constructor(p: Parcel) : this(
            p.readInt(),
            p.readString(),
            p.readParcelable<PersistableBundle?>(PersistableBundle::class.java.getClassLoader())
        )

        override fun describeContents(): Int {
            return 0
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeInt(this.virtualJobId)
            dest.writeString(this.serviceName)
            dest.writeParcelable(this.extras, flags)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<JobConfig?> = object : Parcelable.Creator<JobConfig?> {
                override fun createFromParcel(source: Parcel): JobConfig {
                    return JobConfig(source)
                }

                override fun newArray(size: Int): Array<JobConfig?> {
                    return arrayOfNulls(size)
                }
            }
        }
    }


    @Throws(RemoteException::class)
    override fun schedule(job: JobInfo): Int {
        val vuid = VBinder.getCallingUid()
        val id = job.id
        val service = job.service
        if (service.packageName != VBinder.getCallingPackageName()) {
            throw SecurityException("Cannot start jobs in other packages!")
        }

        val jobId = JobId(vuid, service.packageName, id)
        var config = mJobStore[jobId]
        if (config == null) {
            config = JobConfig(mGlobalJobId++, service.className, job.extras)
            mJobStore[jobId] = config
        } else {
            config.serviceName = service.className
            config.extras = job.extras
        }
        saveJobs()
        mirror.android.app.job.JobInfo.jobId.set(job, config.virtualJobId)
        mirror.android.app.job.JobInfo.service.set(job, mJobProxyComponent)
        return mScheduler.schedule(job)
    }

    private fun saveJobs() {
        val jobFile = VEnvironment.getJobConfigFile()
        val p = Parcel.obtain()
        try {
            p.writeInt(JOB_FILE_VERSION)
            p.writeInt(mJobStore.size)
            for ((key, value) in mJobStore) {
                key.writeToParcel(p, 0)
                value.writeToParcel(p, 0)
            }
            val fos = FileOutputStream(jobFile)
            fos.write(p.marshall())
            fos.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            p.recycle()
        }
    }

    private fun readJobs() {
        val jobFile = VEnvironment.getJobConfigFile()
        if (!jobFile.exists()) {
            return
        }
        val p = Parcel.obtain()
        try {
            val fis = FileInputStream(jobFile)
            val bytes = ByteArray(jobFile.length().toInt())
            val len = fis.read(bytes)
            fis.close()
            if (len != bytes.size) {
                throw IOException("Unable to read job config.")
            }
            p.unmarshall(bytes, 0, bytes.size)
            p.setDataPosition(0)
            val version = p.readInt()
            if (version != JOB_FILE_VERSION) {
                throw IOException("Bad version of job file: $version")
            }
            if (mJobStore.isNotEmpty()) {
                mJobStore.clear()
            }
            val count = p.readInt()
            repeat(count) {
                val jobId = JobId(p)
                val config = JobConfig(p)
                mJobStore[jobId] = config
                mGlobalJobId = max(mGlobalJobId, config.virtualJobId)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            p.recycle()
        }
    }

    @Throws(RemoteException::class)
    override fun cancel(jobId: Int) {
        val vuid = VBinder.getCallingUid()
        synchronized(mJobStore) {
            var changed = false
            val iterator = mJobStore.entries.iterator()

            while (iterator.hasNext()) {
                val entry = iterator.next()
                val job = entry.key
                val config = entry.value
                if (job.vuid == vuid && job.clientJobId == jobId) {
                    changed = true
                    mScheduler.cancel(config.virtualJobId)
                    iterator.remove()
                    break
                }
            }
            if (changed) {
                saveJobs()
            }
        }
    }

    @Throws(RemoteException::class)
    override fun cancelAll() {
        val vuid = VBinder.getCallingUid()
        synchronized(mJobStore) {
            var changed = false
            val iterator = mJobStore.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val job = entry.key
                if (job.vuid == vuid) {
                    val config = entry.value
                    mScheduler.cancel(config.virtualJobId)
                    changed = true
                    iterator.remove()
                    break
                }
            }
            if (changed) {
                saveJobs()
            }
        }
    }

    @Throws(RemoteException::class)
    override fun getAllPendingJobs(): List<JobInfo?> {
        val vuid = VBinder.getCallingUid()
        val jobs = mScheduler.allPendingJobs
        synchronized(mJobStore) {
            val iterator: MutableIterator<JobInfo> = jobs.listIterator()
            while (iterator.hasNext()) {
                val job = iterator.next()
                if (VASettings.STUB_JOB != job.service.className) {
                    // Schedule by Host, invisible in VA.
                    iterator.remove()
                    continue
                }
                val jobEntry = findJobByVirtualJobId(job.id)
                if (jobEntry == null) {
                    iterator.remove()
                    continue
                }
                val jobId = jobEntry.key
                val config = jobEntry.value
                if (jobId.vuid != vuid) {
                    iterator.remove()
                    continue
                }
                mirror.android.app.job.JobInfo.jobId.set(job, jobId.clientJobId)
                mirror.android.app.job.JobInfo.service.set(
                    job,
                    ComponentName(jobId.packageName, config.serviceName!!)
                )
            }
        }
        return jobs
    }

    @Throws(RemoteException::class)
    override fun enqueue(job: JobInfo?, work: JobWorkItem?): Int {
        return 0
    }

    @Throws(RemoteException::class)
    override fun getPendingJob(i: Int): JobInfo? {
        return null
    }


    fun findJobByVirtualJobId(
        virtualJobId: Int,
    ): MutableMap.MutableEntry<JobId, JobConfig>? {
        synchronized(mJobStore) {
            return mJobStore.entries.firstOrNull { it.value.virtualJobId == virtualJobId }
        }
    }

    companion object {
        private const val JOB_FILE_VERSION = 1
        private val gDefault: Singleton<VJobSchedulerService> =
            object : Singleton<VJobSchedulerService>() {
                override fun create(): VJobSchedulerService {
                    return VJobSchedulerService()
                }
            }

        @JvmStatic
        fun get(): VJobSchedulerService {
            return gDefault.get()
        }
    }
}
