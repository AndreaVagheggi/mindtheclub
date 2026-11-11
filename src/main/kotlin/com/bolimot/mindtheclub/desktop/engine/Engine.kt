package com.bolimot.mindtheclub.desktop.engine

import com.bolimot.mindtheclub.crypto.KeyManager
import com.bolimot.mindtheclub.database.database.DatabaseProvider
import com.bolimot.mindtheclub.desktop.backend.Backend
import com.bolimot.mindtheclub.desktop.push.NtfySubscriber
import com.bolimot.mindtheclub.desktop.push.WebPushKeys
import com.bolimot.mindtheclub.desktop.storage.Prefs
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.App
import com.bolimot.mindtheclub.tools.MySelf
import com.bolimot.mindtheclub.works.InboxRecoveryWorker
import com.bolimot.mindtheclub.works.PendingRetryWorker
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Starts and stops MindTheClub on this PC: what Application.onCreate and the first token sync
 * are on the phone (start/App.kt, start/initApplication.kt).
 *
 * Starting means, in this order:
 *   1. the identity opens from the Windows vault;
 *   2. its public key is checked against the published one, so a key text that differs from
 *      the phone's can never be published (see IdentityKeys.publicKey);
 *   3. the database opens and the WebRTC library loads;
 *   4. a push channel opens and is registered for the identity, with proof of possession of
 *      the key. From that moment signals for the identity come here, and the phone that held
 *      it deactivates itself at its next start (one identity, one installation).
 *
 * Nothing is registered if any earlier step fails.
 */
object Engine {

    private const val TAG = "Engine"

    // The App's own file and key names (functions/InstallationIdentity.kt), plus "claimed".
    private const val INSTALLATION_PREFS = "InstallationIdentityPrefs"
    private const val KEY_MOVED = "identityMoved"
    private const val KEY_CLAIMED = "claimed"

    class StartFailed(message: String) : Exception(message)

    @Volatile private var subscriber: NtfySubscriber? = null

    /** Called when the push channel drops. The program must be restarted, for now. */
    @Volatile var onPushLost: ((reason: String) -> Unit)? = null

    fun start() {
        if (!WebPushKeys.selfTest()) throw StartFailed("La decifratura Web Push non funziona su questo PC.")

        val identity = KeyManager.identity()
            ?: throw StartFailed("Su questo PC non c'e' un'identita': lancia prima prova4.bat e carica un backup.")
        val userId = MySelf.userId()
            ?: throw StartFailed("Il profilo salvato non ha un userId: ricarica il backup con prova4.bat.")

        // ONE IDENTITY, ONE INSTALLATION (C:\mtc\docs\arch\identity.md section 4). This PC may
        // have handed the identity back, or another device may have taken it: in both cases it
        // must stay quiet instead of fighting for delivery, exactly as a phone deactivates
        // itself (start/initApplication.kt, syncFirebaseTokenInBackground).
        val installation = Prefs.of(INSTALLATION_PREFS)
        if (installation.getBoolean(KEY_MOVED) == true) {
            throw StartFailed(
                "L'identita' non e' piu' su questo PC: e' stata restituita al telefono, o presa da un altro " +
                        "dispositivo. Per usarla di nuovo qui carica un backup recente con prova4.bat (NUOVO)."
            )
        }

        val document = try {
            Backend.userDocument(userId)
        } catch (e: Exception) {
            throw StartFailed("Server non raggiungibile (${e.message}). Riprova con la rete attiva.")
        }
        if (document?.get("publicKey") != identity.publicKey) {
            throw StartFailed("La chiave su questo PC non e' quella pubblicata per l'identita': ricarica un backup recente.")
        }

        // Only a PC that has ALREADY held the identity can lose it. A PC that has just loaded a
        // backup has not claimed it yet, and claiming it below is the whole point of loading.
        val remoteInstallation = document?.get("installationId")?.takeIf { it.isNotEmpty() }
        if (installation.getBoolean(KEY_CLAIMED) == true &&
            remoteInstallation != null && remoteInstallation != installationId()
        ) {
            installation.putBoolean(KEY_MOVED, true)
            debugLine(TAG, "Identity owned by installation $remoteInstallation, not mine. Deactivating this PC.")
            throw StartFailed(
                "Un altro dispositivo ha preso questa identita' (di solito il telefono, dopo un restore). " +
                        "Questo PC si e' disattivato. Per usarla di nuovo qui carica un backup recente con prova4.bat (NUOVO)."
            )
        }

        try {
            DatabaseProvider.provideDatabase()
            // The words for "sent" and "delivered" are the phone's, fixed when its data were loaded.
            runBlocking { DesktopStrings.load() }
        } catch (e: Throwable) {
            throw StartFailed("Il database non si apre: ${e.javaClass.simpleName}: ${e.message}")
        }
        try {
            DataLink.start()
        } catch (e: Throwable) {
            throw StartFailed("La libreria WebRTC non parte su questo PC: ${e.javaClass.simpleName}: ${e.message}")
        }

        // The push channel. Subscribed BEFORE it is registered, so nothing is missed.
        val pushKeys = WebPushKeys.generate()
        val channel = NtfySubscriber(
            onMessage = { body -> onPushBody(body, pushKeys) },
            onClosed = { reason ->
                debugLine(TAG, "Push channel lost: $reason")
                try {
                    onPushLost?.invoke(reason)
                } catch (e: Exception) {
                    // Display only.
                }
            }
        )
        if (!channel.connect()) throw StartFailed("Non riesco ad aprire il canale push (ntfy.sh non raggiungibile).")

        try {
            val sealedChallenge = (Backend.call("getPushChallenge", buildJsonObject { put("userId", userId) })["challenge"]
                    as? JsonPrimitive)?.contentOrNull
                ?: throw StartFailed("Il server non conosce questa identita'.")
            Backend.call("updateMyPushEndpoint", buildJsonObject {
                put("userId", userId)
                put("proof", identity.decrypt(sealedChallenge))
                put("endpoint", channel.endpoint)
                put("p256dh", pushKeys.p256dh)
                put("auth", pushKeys.auth)
                put("publicKey", identity.publicKey)
                put("installationId", installationId())
                // The trial clock belongs to the identity; the server only ever moves it earlier.
                Prefs.of("desktop_carried").getLong("trialStartedAt")?.let { put("trialStartedAt", it) }
            })
        } catch (e: StartFailed) {
            throw e
        } catch (e: Backend.CallableException) {
            throw StartFailed("Registrazione rifiutata dal server: ${e.status} (${e.message})")
        } catch (e: Exception) {
            throw StartFailed("Registrazione non riuscita: ${e.message}")
        }

        subscriber = channel
        installation.putBoolean(KEY_CLAIMED, true)
        debugLine(TAG, "Started, signals for $userId come to this PC")

        // Only now, with the identity registered here, the recovery layer starts, as in the
        // App's MainActivity: the two periodic workers, and one pass over the outgoing ladder.
        val context = App.context()
        PendingRetryWorker.schedule(context)
        InboxRecoveryWorker.schedule(context)

        // What a previous run left unsent and nothing is tracking yet is announced first, so
        // that the pass below finds it on the ladder like everything else.
        val resumed = Outgoing.resumeUnconfirmed()
        if (resumed > 0) debugLine(TAG, "Resumed $resumed unconfirmed message(s)")
        App.instance!!.applicationScope.launch {
            try {
                PendingRetryWorker.retryAllNow(context)
            } catch (e: Exception) {
                debugLine(TAG, "Start pass over pending messages failed: ${e.message}")
            }
        }
    }

    fun stop() {
        DataLink.closeAll()
        DatabaseProvider.close()
    }

    /** True when this PC gave the identity back or lost it, and must not start the engine. */
    fun isDeactivated(): Boolean = Prefs.of(INSTALLATION_PREFS).getBoolean(KEY_MOVED) == true

    /**
     * This PC gives the identity up: it stops and will not start again until a backup is
     * loaded. Called once the backup for the phone has been written and read back.
     *
     * The push registration is deliberately LEFT in place. Withdrawing it would make the
     * relay answer "not-found" for this identity until the phone has restored, and a sender
     * that gets "not-found" drops its whole pending queue for the peer (SendFcmWorker,
     * removeAllForPeer). Left as it is, a signal sent in the gap fails as a transient error
     * and the sender retries; the phone's restore then takes the address over.
     */
    fun handBack() {
        stop()
        Prefs.of(INSTALLATION_PREFS).putBoolean(KEY_MOVED, true)
        debugLine(TAG, "Identity handed back, this PC is deactivated")
    }

    /**
     * This installation's id, generated once and kept, as on the phone
     * (functions/InstallationIdentity.kt). It is what the identity's previous holder compares
     * with its own to know the identity moved. Kept in its own file and never handed over.
     */
    private fun installationId(): String {
        val prefs = Prefs.of(INSTALLATION_PREFS)
        prefs.getString("installationId")?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "")
        prefs.putString("installationId", id)
        return id
    }

    /** One push message: open the Web Push layer and hand the routing map to SignalHandler. */
    private fun onPushBody(body: ByteArray, pushKeys: WebPushKeys) {
        val raw = try {
            val map = HashMap<String, String>()
            for ((key, value) in Json.parseToJsonElement(String(pushKeys.open(body), Charsets.UTF_8)).jsonObject) {
                val text = (value as? JsonPrimitive)?.contentOrNull ?: continue
                map[key] = text
            }
            map
        } catch (e: Exception) {
            debugLine(TAG, "Push message not readable: ${e.message}")
            return
        }
        SignalHandler.onPush(raw)
    }
}
