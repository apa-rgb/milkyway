package pl.apargb.milkyway

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

internal val operatorNumbers = (1..10).map { "%02d".format(java.util.Locale.ROOT, it) }
internal fun operatorEmail(number: String, project: String): String {
    require(number in operatorNumbers) { "Wybierz konto od 01 do 10." }
    require(project.matches(Regex("[a-z][a-z0-9-]{4,62}"))) { "Nieprawidłowy projekt Firebase." }
    return "konto$number@$project.accounts.invalid"
}

internal data class CloudSessionState(val configured: Boolean = false, val checking: Boolean = false,
    val uid: String? = null, val number: String? = null, val laboratory: Boolean = false, val connected: Boolean = false,
    val ready: Boolean = false, val revision: Long = 0, val error: String? = null, val displayName: String? = null)

/** One authenticated session. Never mix private device data into a shared plant automatically. */
internal class CloudSession private constructor(private val context: Context) {
    private val configured = listOf(BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_API_KEY,
        BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_DATABASE_URL, BuildConfig.FIREBASE_PLANT_ID).all { it.isNotBlank() }
    private val mutableState = MutableStateFlow(CloudSessionState(configured = configured, checking = configured))
    val state = mutableState.asStateFlow()
    private val cache = ConcurrentHashMap<SharedDomain, Map<String, Any?>>()
    private val listeners = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()
    private var listenerGeneration = 0L
    private var loginError: String? = null
    private val profile = OperatorProfile(context)
    private var pendingLoginName: Pair<String, String>? = null
    private val app: FirebaseApp? = if (configured) FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setApiKey(BuildConfig.FIREBASE_API_KEY)
        .setApplicationId(BuildConfig.FIREBASE_APP_ID).setDatabaseUrl(BuildConfig.FIREBASE_DATABASE_URL).build(), "milkyway") else null
    private val auth = app?.let(FirebaseAuth::getInstance)
    private val database = app?.let(FirebaseDatabase::getInstance)?.apply { setPersistenceEnabled(true) }

    init {
        auth?.addAuthStateListener { firebase ->
            detach()
            val user = firebase.currentUser
            mutableState.value = CloudSessionState(configured = configured, checking = user != null, error = loginError)
            user?.let(::verifyUser)
        }
    }

    private fun verifyUser(user: FirebaseUser) {
        val generation = listenerGeneration
        user.getIdToken(false).addOnCompleteListener { task ->
            if (auth?.currentUser?.uid != user.uid || generation != listenerGeneration) return@addOnCompleteListener
            if (!task.isSuccessful) {
                mutableState.value = mutableState.value.copy(uid = user.uid, checking = false, ready = false,
                    error = "Nie udało się potwierdzić sesji. Połącz się z internetem i spróbuj ponownie.")
                return@addOnCompleteListener
            }
            val claims = task.result?.claims.orEmpty()
            val number = claims["account"] as? String
            val plant = claims["plant"] as? String
            val role = claims["role"] as? String
            if (number !in operatorNumbers || plant != BuildConfig.FIREBASE_PLANT_ID || role !in listOf("operator", "laboratory")) {
                loginError = "To konto nie ma dostępu do tego zakładu."
                auth?.signOut()
                mutableState.value = mutableState.value.copy(checking = false, error = "To konto nie ma dostępu do tego zakładu.")
            } else {
                pendingLoginName?.takeIf { it.first == user.email }?.let { profile.save(user.uid, it.second) }
                pendingLoginName = null
                mutableState.value = mutableState.value.copy(uid = user.uid, number = number, laboratory = role == "laboratory",
                    displayName = profile.name(user.uid).takeIf { it.isNotBlank() })
                attach(user.uid)
            }
        }
    }

    fun signIn(number: String, password: String, name: String) {
        val auth = auth ?: return
        if (mutableState.value.checking) return
        if (password.isBlank()) { mutableState.value = mutableState.value.copy(error = "Wpisz hasło."); return }
        loginError = null
        val email = operatorEmail(number, BuildConfig.FIREBASE_PROJECT_ID)
        pendingLoginName = email to normalizeOperatorName(name)
        mutableState.value = mutableState.value.copy(checking = true, error = null)
        auth.signInWithEmailAndPassword(email, password)
            .addOnFailureListener {
                pendingLoginName = null
                mutableState.value = mutableState.value.copy(checking = false, error = "Nie udało się zalogować. Sprawdź hasło i połączenie.")
            }
    }

    fun signOut() { loginError = null; pendingLoginName = null; auth?.signOut() }
    fun retry() {
        auth?.currentUser?.let { user ->
            detach()
            mutableState.value = mutableState.value.copy(checking = true, ready = false, error = null)
            verifyUser(user)
        }
    }

    private fun domainReference(domain: SharedDomain) = database!!.getReference("plants/${BuildConfig.FIREBASE_PLANT_ID}/domains/${domain.path}")
    private fun detach() {
        listenerGeneration++
        listeners.forEach { (ref, listener) -> ref.removeEventListener(listener) }
        listeners.clear(); cache.clear()
    }

    private fun attach(uid: String) {
        val generation = listenerGeneration
        mutableState.value = mutableState.value.copy(ready = false, error = null)
        val connection = database!!.getReference(".info/connected")
        listen(connection, object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (state.value.uid == uid && generation == listenerGeneration) mutableState.value = mutableState.value.copy(connected = snapshot.getValue(Boolean::class.java) == true)
            }
            override fun onCancelled(error: DatabaseError) { if (generation == listenerGeneration) fail(uid) }
        })
        SharedDomain.entries.forEach { domain ->
            listen(domainReference(domain), object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (state.value.uid != uid || generation != listenerGeneration) return
                    @Suppress("UNCHECKED_CAST")
                    val value = snapshot.value as? Map<String, Any?> ?: emptyMap()
                    cache[domain] = value
                    mutableState.value = mutableState.value.copy(ready = cache.size == SharedDomain.entries.size,
                        checking = cache.size != SharedDomain.entries.size, revision = state.value.revision + 1, error = null)
                }
                override fun onCancelled(error: DatabaseError) { if (generation == listenerGeneration) fail(uid) }
            })
        }
    }

    private fun fail(uid: String) {
        if (state.value.uid == uid) {
            detach()
            mutableState.value = mutableState.value.copy(checking = false, ready = false, connected = false,
                error = "Nie udało się pobrać wspólnych danych. Sprawdź połączenie i dostęp do zakładu.")
        }
    }
    private fun listen(ref: DatabaseReference, listener: ValueEventListener) { listeners += ref to listener; ref.addValueEventListener(listener) }

    fun <T> access(domain: SharedDomain, mutation: Boolean, local: () -> T, operation: (android.database.sqlite.SQLiteOpenHelper) -> T): T {
        if (!configured) return local()
        val session = state.value
        require(session.uid != null && session.ready) { "Najpierw zaloguj się i pobierz wspólne dane." }
        if (!mutation) return domain.database(context).useDatabase { helper ->
            SharedRows.restore(helper, domain, cache.getValue(domain)); operation(helper)
        }
        require(session.connected) { "Brak internetu. Wpis nie został zapisany; ponów zapis po połączeniu." }
        val result = AtomicReference<T>()
        val failure = AtomicReference<Exception>()
        val done = CountDownLatch(1)
        val actor = mapOf("uid" to session.uid, "account" to session.number)
        domainReference(domain).runTransaction(object : Transaction.Handler {
            override fun doTransaction(current: MutableData): Transaction.Result {
                if (state.value.uid != session.uid) { failure.set(IllegalArgumentException("Sesja wygasła. Zaloguj się ponownie.")); return Transaction.abort() }
                return try {
                    @Suppress("UNCHECKED_CAST")
                    val previous = current.value as? Map<String, Any?> ?: emptyMap()
                    domain.database(context).useDatabase { helper ->
                        SharedRows.restore(helper, domain, previous)
                        result.set(operation(helper))
                        val next = SharedRows.capture(helper, domain, previous, actor, System.currentTimeMillis())
                        if (domain == SharedDomain.INVENTORY) SharedRows.validateLaboratoryChange(previous, next, session.laboratory)
                        current.value = next
                    }
                    failure.set(null)
                    Transaction.success(current)
                } catch (error: Exception) { failure.set(error); Transaction.abort() }
            }
            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null) failure.set(IllegalArgumentException("Nie udało się zapisać wspólnych danych. Spróbuj ponownie."))
                if (!committed && failure.get() == null) failure.set(IllegalArgumentException("Wpis nie został zapisany. Spróbuj ponownie."))
                if (committed && state.value.uid == session.uid) {
                    @Suppress("UNCHECKED_CAST")
                    val value = snapshot?.value as? Map<String, Any?>
                    if (value != null && ((cache[domain]?.get("revision") as? Number)?.toLong() ?: 0) <= ((value["revision"] as? Number)?.toLong() ?: 0)) {
                        cache[domain] = value
                        mutableState.value = mutableState.value.copy(revision = state.value.revision + 1)
                    }
                }
                done.countDown()
            }
        }, false) // Never display speculative transactions as committed production.
        done.await() // IO worker; completion comes only after server acknowledgement, including retries.
        failure.get()?.let { throw it }
        return result.get()
    }

    companion object {
        @Volatile private var instance: CloudSession? = null
        fun get(context: Context): CloudSession = instance ?: synchronized(this) {
            instance ?: CloudSession(context.applicationContext).also { instance = it }
        }
    }
}

internal object CloudAccess {
    fun <T> inventory(context: Context, local: InventoryRepository, mutation: Boolean = false, operation: (InventoryRepository) -> T): T =
        CloudSession.get(context).access(SharedDomain.INVENTORY, mutation, { operation(local) }) { helper ->
            operation(InventoryRepository(context, helper as InventoryDatabase))
        }
    fun <T> notes(context: Context, local: WorkNotesRepository, mutation: Boolean = false, operation: (WorkNotesRepository) -> T): T =
        CloudSession.get(context).access(SharedDomain.NOTES, mutation, { operation(local) }) { helper ->
            operation(WorkNotesRepository(context, helper as WorkNotesDatabase))
        }
    fun <T> production(context: Context, local: ProductionQueueRepository, mutation: Boolean = false, operation: (ProductionQueueRepository) -> T): T =
        CloudSession.get(context).access(SharedDomain.PRODUCTION, mutation, { operation(local) }) { helper ->
            operation(ProductionQueueRepository(context, helper as ProductionQueueDatabase))
        }
}
