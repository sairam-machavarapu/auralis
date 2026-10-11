package com.auralis.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.auralis.core.database.*
import com.auralis.service.scanner.FixtureProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Explicit adb-only benchmark setup; this activity and its provider do not ship in release. */
class ScannerBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val db = AuralisDatabase.get(applicationContext)
                val uri = "content://$packageName.scanfixtures/tree/root"
                if (intent.getBooleanExtra("cleanup", false)) {
                    WorkManager.getInstance(applicationContext).cancelUniqueWork("auralis-library-scan").result.get()
                    db.withTransaction {
                        db.folders().find(uri)?.let { folder ->
                            db.openHelper.writableDatabase.execSQL("DELETE FROM tracks WHERE folder_id = ?", arrayOf(folder.id))
                            db.folders().delete(folder.id)
                        }
                    }
                } else {
                    FixtureProvider.count = 20_000; FixtureProvider.broken = true
                    db.folders().insert(LibraryFolderEntity(uri = uri, displayName = "Scanner benchmark (20,000 test songs)", enabled = false))
                }
            }
            startActivity(Intent(this@ScannerBenchmarkActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }
    }
}

class ScannerBenchmarkReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AuralisDatabase.get(context)
                db.folders().find("content://${context.packageName}.scanfixtures/tree/root")?.let { db.folders().setEnabled(it.id, true) }
            } finally { pending.finish() }
        }
    }
}
