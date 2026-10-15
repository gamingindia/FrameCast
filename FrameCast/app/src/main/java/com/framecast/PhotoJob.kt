package com.framecast

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

/**
 * Sends new camera photos to the frame. A content-trigger job fires when MediaStore gets a new image;
 * photos newer than the last one sent are uploaded. If the frame is unreachable (its WiFi sleeps most of
 * the time), a retry job runs a few minutes later.
 */
class PhotoJob : JobService() {

    companion object {
        const val ID_TRIGGER = 41
        const val ID_RETRY = 42

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            val info = JobInfo.Builder(ID_TRIGGER, ComponentName(ctx, PhotoJob::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                    )
                )
                .setTriggerContentUpdateDelay(2000)
                .setTriggerContentMaxDelay(10000)
                .build()
            js.schedule(info)
        }

        fun scheduleRetry(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            val info = JobInfo.Builder(ID_RETRY, ComponentName(ctx, PhotoJob::class.java))
                .setMinimumLatency(3 * 60_000L)
                .setOverrideDeadline(12 * 60_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .build()
            js.schedule(info)
        }

        fun cancel(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            js.cancel(ID_TRIGGER)
            js.cancel(ID_RETRY)
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        if (params.jobId == ID_TRIGGER) schedule(app)        // re-arm the trigger
        Thread {
            val done = PhotoSync.run(app)
            if (!done) scheduleRetry(app)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}

object PhotoSync {
    /** @return false if the frame could not be reached and the run should be retried later */
    fun run(ctx: Context): Boolean {
        val p = FrameApi.prefs(ctx)
        if (!p.getBoolean("auto", false)) return true
        var last = p.getLong("last_added", 0L)
        if (last == 0L) {
            p.edit().putLong("last_added", System.currentTimeMillis() / 1000).apply()
            return true
        }
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val ids = ArrayList<Long>()
        val dates = ArrayList<Long>()
        try {
            ctx.contentResolver.query(
                base,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
                "${MediaStore.Images.Media.DATE_ADDED} > ? AND ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?",
                arrayOf(last.toString(), "Camera"),
                "${MediaStore.Images.Media.DATE_ADDED} ASC"
            )?.use { c ->
                while (c.moveToNext() && ids.size < 10) {
                    ids.add(c.getLong(0))
                    dates.add(c.getLong(1))
                }
            }
        } catch (e: SecurityException) {
            return true      // permission was revoked, nothing to do
        }
        for (i in ids.indices) {
            val r = PhotoSender.sendOne(ctx, ContentUris.withAppendedId(base, ids[i]))
            if (r.ok || !r.net) {                      // sent, or a photo the frame can't take: move on
                last = dates[i]
                p.edit().putLong("last_added", last).apply()
            } else {
                return false                           // frame unreachable: try again later
            }
        }
        return true
    }
}
