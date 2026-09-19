package com.example.ui.easteregg

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.R
import com.example.data.AppPreferences
import com.example.databinding.ActivityEasterEggBinding

/**
 * Pantalla del Easter Egg de TagLock.
 * Contiene el mini-juego interactivo "Padlock Runner" (estilo Chrome Dino con un candado y obstáculos de distracción)
 * junto con la biografía del creador (Marbol077), acceso al repositorio de GitHub y créditos.
 */
class EasterEggActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEasterEggBinding
    private lateinit var appPreferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityEasterEggBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.easterEggRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        appPreferences = AppPreferences.getInstance(this)

        setupToolbar()
        setupGame()
        setupCreatorLinks()
    }

    private fun setupToolbar() {
        binding.toolbarEasterEgg.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupGame() {
        val savedHighScore = appPreferences.getRunnerHighScore()
        binding.padlockRunnerView.setHighScore(savedHighScore)

        updateScoreUi(0, savedHighScore)

        binding.padlockRunnerView.onScoreChanged = { currentScore, highScore ->
            updateScoreUi(currentScore, highScore)
        }

        binding.padlockRunnerView.onGameOver = { finalScore ->
            val best = appPreferences.getRunnerHighScore()
            if (finalScore > best) {
                appPreferences.setRunnerHighScore(finalScore)
                updateScoreUi(finalScore, finalScore)
            }
        }

        binding.btnRestartRunner.setOnClickListener {
            binding.padlockRunnerView.resetGame(startImmediately = true)
        }
    }

    private fun updateScoreUi(score: Int, highScore: Int) {
        binding.tvRunnerScore.text = getString(R.string.runner_score_label, score)
        binding.tvRunnerHighScore.text = getString(R.string.runner_high_score_label, highScore)
    }

    private fun setupCreatorLinks() {
        val githubUrl = "https://github.com/arafay1707/TagLock"
        val kofiUrl = "https://Ko-fi.com/Marbol077"

        binding.btnOpenGithub.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, githubUrl, Toast.LENGTH_LONG).show()
            }
        }

        binding.btnCopyGithubUrl.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("TagLock GitHub", githubUrl)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.creator_copied_toast), Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenKofi.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(kofiUrl))
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, kofiUrl, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, EasterEggActivity::class.java)
            context.startActivity(intent)
        }
    }
}
