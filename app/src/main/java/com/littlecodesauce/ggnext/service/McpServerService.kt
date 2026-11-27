package com.littlecodesauce.ggnext.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.littlecodesauce.ggnext.MainActivity
import com.littlecodesauce.ggnext.mcp.McpHttpServer

/**
 * 忽略电池优化权限辅助工具（用于保障 MCP 本地服务在切后台/锁屏时稳定保活）
 */
object BatteryOptimizationHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}

/**
 * MCP 服务独立前台保活 Service，避免应用退至后台后被系统冻结本地 HTTP 监听端口。
 */
class McpServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            McpHttpServer.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val port = intent?.getIntExtra(EXTRA_PORT, 27080) ?: 27080
        val allowLan = intent?.getBooleanExtra(EXTRA_ALLOW_LAN, false) ?: false
        val authEnabled = intent?.getBooleanExtra(EXTRA_AUTH_ENABLED, false) ?: false
        val authToken = intent?.getStringExtra(EXTRA_AUTH_TOKEN).orEmpty()

        val (ok, _) = McpHttpServer.start(applicationContext, port, allowLan, authEnabled, authToken)
        if (!ok) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        McpHttpServer.stop()
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GGNext MCP Service", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, McpServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val st = McpHttpServer.state.value
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("GGNext MCP")
            .setContentText(st.mcpEndpointUrl)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
        runCatching { startForeground(NOTIFY_ID, n) }
    }

    companion object {
        private const val CHANNEL_ID = "ggnext_mcp_server"
        private const val NOTIFY_ID = 2049
        private const val ACTION_STOP = "com.littlecodesauce.ggnext.MCP_STOP"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_ALLOW_LAN = "allow_lan"
        private const val EXTRA_AUTH_ENABLED = "auth_enabled"
        private const val EXTRA_AUTH_TOKEN = "auth_token"

        fun start(
            context: Context,
            port: Int,
            allowLan: Boolean,
            authEnabled: Boolean,
            authToken: String
        ) {
            val intent = Intent(context, McpServerService::class.java).apply {
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_ALLOW_LAN, allowLan)
                putExtra(EXTRA_AUTH_ENABLED, authEnabled)
                putExtra(EXTRA_AUTH_TOKEN, authToken)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            McpHttpServer.stop()
            runCatching { context.stopService(Intent(context, McpServerService::class.java)) }
        }
    }
}
