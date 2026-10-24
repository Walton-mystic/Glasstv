package se.glasstv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme

class MainActivity : ComponentActivity() {
    private val vm: MainVm by viewModels()
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFile(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashLog()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App(vm) } }
    }

    /** Sparar orsaken om appen kraschar, så att den kan visas nästa gång appen startar. */
    private fun installCrashLog() {
        val file = java.io.File(filesDir, "crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { file.writeText(android.util.Log.getStackTraceString(e).take(6000)) }
            prev?.uncaughtException(t, e)
        }
    }

    override fun onResume() {
        super.onResume()
        // Kopplas om varje gång så att ViewModel aldrig håller kvar en gammal aktivitet.
        vm.pickFile = {
            try { picker.launch(arrayOf("*/*")) } catch (e: Exception) { vm.status = "Ingen filväljare finns på TV:n – använd QR-koden" }
        }
    }

    override fun onDestroy() { vm.pickFile = {}; super.onDestroy() }
    override fun onStart() { super.onStart(); vm.onStart() }
    override fun onStop() { super.onStop(); vm.onStop() }
}
