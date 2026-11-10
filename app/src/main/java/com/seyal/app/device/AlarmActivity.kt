package com.seyal.app.device

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.seyal.app.R
import com.seyal.app.agent.Scheduler

/** Seyal's own alarm screen, so an alarm can ring on an exact date (the clock app can't). */
class AlarmActivity : ComponentActivity() {
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        val label = intent.getStringExtra("text") ?: "Alarm"
        val id = intent.getStringExtra("id") ?: ""
        startSound()
        setContent {
            Column(
                Modifier.fillMaxSize().background(Color(0xFF1C1B18)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Seyal alarm", color = Color(0xFF9FE1CB), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                Text(label, color = Color.White, fontSize = 34.sp, fontFamily = FontFamily.Serif, lineHeight = 40.sp)
                Spacer(Modifier.height(48.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { snooze(id, label) }, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(28.dp)) {
                        Text("Snooze 10 min", color = Color.White)
                    }
                    Button(
                        onClick = { stopAndClose(id) }, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E6B5E)),
                    ) { Text("Dismiss", color = Color.White) }
                }
            }
        }
    }

    private fun startSound() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setDataSource(this@AlarmActivity, uri)
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                isLooping = true
                prepare(); start()
            }
        }
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
        }
    }

    private fun stopSound() {
        runCatching { player?.stop(); player?.release() }
        player = null
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.cancel()
        }
    }

    private fun snooze(id: String, label: String) {
        Scheduler.setAlarm(this, System.currentTimeMillis() + 10 * 60_000L, label)
        stopAndClose(id)
    }

    private fun stopAndClose(id: String) {
        stopSound()
        NotificationManagerCompat.from(this).cancel(("alarm$id").hashCode())
        finish()
    }

    override fun onDestroy() {
        stopSound()
        super.onDestroy()
    }

    companion object {
        /** Full-screen notification; Android opens the activity on the lock screen when allowed. */
        fun ring(ctx: Context, id: String, label: String) {
            val full = Intent(ctx, AlarmActivity::class.java).putExtra("id", id).putExtra("text", label)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(ctx, id.hashCode(), full, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, Notify.CH_ALARM)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(label)
                .setContentText("Seyal alarm · tap to open")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(("alarm$id").hashCode(), n)
            runCatching { ctx.startActivity(full) }
        }
    }
}
