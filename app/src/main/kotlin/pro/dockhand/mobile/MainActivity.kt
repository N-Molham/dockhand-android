package pro.dockhand.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import pro.dockhand.mobile.ui.theme.DockhandTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DockhandTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Text(
                        text = "Dockhand",
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }
    }
}
