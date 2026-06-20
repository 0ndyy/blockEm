package ondy.example.blockem

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ondy.example.blockem.ui.theme.BlockEmTheme

class BlockActivity : ComponentActivity() {
    private var targetPackage: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Grab the package name of the app that triggered the block
        targetPackage = intent.getStringExtra("PACKAGE_NAME") ?: ""

        setContent {
            BlockEmTheme {
                BackHandler { goHome() }

                BlockScreenUI(onReturnClicked = {
                    if (targetPackage.isNotEmpty()) {
                        // Tell the BlockerService to reopen the app and press the Back button
                        val serviceIntent = Intent(this, BlockerService::class.java).apply {
                            action = "ACTION_RETURN_APP"
                            putExtra("PACKAGE_NAME", targetPackage)
                        }
                        startService(serviceIntent)
                    } else {
                        goHome()
                    }
                    finish()
                })
            }
        }
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }
}

@Composable
fun BlockScreenUI(onReturnClicked: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "Limit Reached",
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "You've hit your daily scroll limit. Time to do something else.",
                color = Color.LightGray,
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = onReturnClicked,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(0.6f).height(50.dp)
            ) {
                Text("Return", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}