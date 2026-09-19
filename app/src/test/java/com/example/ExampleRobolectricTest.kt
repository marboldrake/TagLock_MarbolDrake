package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.MockAppBlockerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("TagLock", appName)
  }

  @Test
  fun `test mock app blocker controller operations`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val controller = MockAppBlockerController.getInstance(context)

    // Inicialmente desbloqueado o reiniciar estado
    controller.unlock()
    assertFalse(controller.isLocked())

    // Probar bloqueo
    controller.lock()
    assertTrue(controller.isLocked())

    // Probar desbloqueo
    controller.unlock()
    assertFalse(controller.isLocked())

    // Probar agregar y remover paquete bloqueado
    val testPkg = "com.test.app"
    controller.addBlockedPackage(testPkg)
    assertTrue(controller.getBlockedPackages().contains(testPkg))

    controller.removeBlockedPackage(testPkg)
    assertFalse(controller.getBlockedPackages().contains(testPkg))
  }

  @Test
  fun `test nfc security manager key pairing and authorization`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val nfcSec = com.example.data.NfcSecurityManager.getInstance(context)

    // Clave secreta
    val defaultKey = nfcSec.getSecretKey()
    assertTrue(defaultKey.isNotEmpty())

    nfcSec.setSecretKey("MY_CUSTOM_SECRET_KEY")
    assertEquals("MY_CUSTOM_SECRET_KEY", nfcSec.getSecretKey())

    // Sin tag vinculado
    nfcSec.setPairedTagId(null)
    assertFalse(nfcSec.hasPairedTag())

    // Vincular tag
    val myTagId = "04:A1:B2:C3:D4"
    nfcSec.setPairedTagId(myTagId)
    assertTrue(nfcSec.hasPairedTag())
    assertEquals(myTagId, nfcSec.getPairedTagId())

    // Tag autorizado
    assertTrue(nfcSec.isTagAuthorized(myTagId, "MY_CUSTOM_SECRET_KEY"))
    assertTrue(nfcSec.isTagAuthorized("04:a1:b2:c3:d4")) // case insensitive

    // Tag no autorizado
    assertFalse(nfcSec.isTagAuthorized("04:99:88:77:66"))
    assertFalse(nfcSec.isTagAuthorized(myTagId, "WRONG_SECRET"))
  }

  @Test
  fun `test session duration and package blocking checks`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val controller = com.example.data.MockAppBlockerController.getInstance(context)

    controller.unlock()
    assertFalse(controller.isLocked())

    val blockedApp = "com.blocked.social"
    controller.addBlockedPackage(blockedApp)

    // No debe reportar bloqueado si controller.isLocked() es false
    assertFalse(controller.isPackageBlocked(blockedApp))

    // Bloquear con 25 minutos
    controller.lockWithDuration(25)
    assertTrue(controller.isLocked())
    assertTrue(controller.isPackageBlocked(blockedApp))
    assertFalse(controller.isPackageBlocked("com.other.unblocked"))

    val remaining = controller.getSessionRemainingMillis()
    assertTrue(remaining > 24 * 60 * 1000L)
    assertTrue(remaining <= 25 * 60 * 1000L)

    controller.unlock()
    assertFalse(controller.isLocked())
    assertFalse(controller.isPackageBlocked(blockedApp))
  }

  @Test
  fun `test app preferences for onboarding theme and language`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appPrefs = com.example.data.AppPreferences.getInstance(context)

    // Test setup completed
    appPrefs.setSetupCompleted(false)
    assertFalse(appPrefs.isSetupCompleted())
    appPrefs.setSetupCompleted(true)
    assertTrue(appPrefs.isSetupCompleted())

    // Test theme preferences
    appPrefs.setThemeMode(com.example.data.AppPreferences.THEME_LIGHT)
    assertEquals(com.example.data.AppPreferences.THEME_LIGHT, appPrefs.getThemeMode())

    appPrefs.setThemeMode(com.example.data.AppPreferences.THEME_DARK)
    assertEquals(com.example.data.AppPreferences.THEME_DARK, appPrefs.getThemeMode())

    appPrefs.setThemeMode(com.example.data.AppPreferences.THEME_SYSTEM)
    assertEquals(com.example.data.AppPreferences.THEME_SYSTEM, appPrefs.getThemeMode())

    // Test language preferences
    appPrefs.setLanguageCode(com.example.data.AppPreferences.LANG_ES)
    assertEquals(com.example.data.AppPreferences.LANG_ES, appPrefs.getLanguageCode())

    appPrefs.setLanguageCode(com.example.data.AppPreferences.LANG_EN)
    assertEquals(com.example.data.AppPreferences.LANG_EN, appPrefs.getLanguageCode())
  }
}
