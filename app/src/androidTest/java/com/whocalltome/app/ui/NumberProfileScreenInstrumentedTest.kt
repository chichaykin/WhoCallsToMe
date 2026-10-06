package com.whocalltome.app.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whocalltome.app.data.db.AppDatabase
import com.whocalltome.app.data.db.NumberEntryEntity
import com.whocalltome.app.data.db.CallRecordEntity
import com.whocalltome.app.data.db.UserOverrideEntity
import com.whocalltome.app.data.model.*
import com.whocalltome.app.data.phone.PhoneContactLookup
import com.whocalltome.app.data.remote.LookupProviderCatalog
import com.whocalltome.app.data.repository.AppCallerIdentityRepository
import com.whocalltome.app.ui.theme.WhoCallToMeTheme
import com.whocalltome.app.data.settings.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.time.LocalDateTime
import java.time.ZoneId

/** All repository data and provider responses are synthetic; never calls real providers. */
@RunWith(AndroidJUnit4::class)
class NumberProfileScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val first = "+12025550101"
    private val second = "+12025550102"
    private lateinit var database: AppDatabase
    private lateinit var viewModel: AppViewModel
    private val store = ViewModelStore()
    private val contacts = ConcurrentHashMap<String, ContactLookupResult>()
    private val provider = FakeProvider()
    private val selectedNumber = mutableStateOf(first)
    private val permission = mutableStateOf(PermissionUiStatus.GRANTED)
    private var permissionRequests = 0
    private var backCount = 0

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        val repository = AppCallerIdentityRepository(database.dao(), object : PhoneContactLookup {
            override fun findContact(phoneNumber: String) = contacts[phoneNumber] ?: ContactLookupResult.NotFound
        }, object : LookupProviderCatalog {
            override fun provider(id: String) = provider.takeIf { it.id == id }
            override fun configuredProviders() = listOf(provider)
            override fun automaticProviders() = listOf(provider)
        })
        compose.runOnUiThread {
            viewModel = AppViewModel(application, repository)
            store.put("profile", viewModel)
        }
    }

    @After
    fun tearDown() {
        provider.gate?.complete(Unit)
        compose.runOnUiThread { store.clear() }
        database.close()
    }

    @Test
    fun previewShowsThreeCallsAndFullHistoryOpensSeparately() {
        contacts[first] = ContactLookupResult.Found("Synthetic contact", null)
        seedHistory()
        showCard()
        waitForLocal(first)
        waitForHistory(first, 3)
        compose.onNodeWithText("Последние звонки").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history-call-11").performScrollTo().assertIsDisplayed().assert(hasClickAction().not())
        compose.onNodeWithText("1:05", substring = true).assertExists()
        compose.onNodeWithTag("history-call-12").performScrollTo().assert(hasClickAction().not())
        compose.onNodeWithText("0 сек", substring = true).assertExists()
        compose.onNodeWithTag("history-call-13").performScrollTo().assert(hasClickAction().not())
        compose.onNodeWithText("Длительность неизвестна", substring = true).assertExists()
        runBlocking { database.dao().insertCallRecords(listOf(CallRecordEntity(id = 14, e164 = first, direction = "BLOCKED", eventAt = 1))) }
        waitForHistory(first, 4)
        compose.onNodeWithTag("history-open").performScrollTo().performClick()
        compose.onNodeWithText("История звонков").assertIsDisplayed()
        compose.onNodeWithTag("history-call-14").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Последние звонки").assertExists()
        assertEquals(0, provider.calls.get())
    }

    @Test
    fun previewRestoresAndResetsForAnotherNumber() {
        contacts[first] = ContactLookupResult.Found("Synthetic contact", null)
        contacts[second] = ContactLookupResult.Found("Other synthetic contact", null)
        seedHistory()
        val restoration = StateRestorationTester(compose)
        showCard(restoration = restoration)
        waitForHistory(first, 3)
        compose.onNodeWithTag("history-call-11").performScrollTo().assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("history-call-11").performScrollTo().assertExists()
        compose.runOnIdle { selectedNumber.value = second }
        waitForLocal(second)
        waitForHistory(second, 1)
        compose.onNodeWithText("Последние звонки").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history-call-99").assertExists()
        compose.onNodeWithTag("history-call-11").assertDoesNotExist()
    }

    @Test
    fun emptyHistoryDoesNotPretendToContainCalls() {
        contacts[first] = ContactLookupResult.Found("Synthetic contact", null)
        showCard()
        waitForHistory(first, 0)
        compose.onNodeWithText("Последние звонки").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("В сохранённом журнале нет звонков с этим номером").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun malformedImportedRecordShowsErrorWithoutLosingDataOrShowingPreviousCard() {
        contacts[first] = ContactLookupResult.Found("Synthetic contact", null)
        seedHistory()
        runBlocking { database.dao().upsertNumberEntry(NumberEntryEntity("+invalid", personalName = "Preserved synthetic entry")) }
        showCard()
        waitForLocal(first)
        waitForHistory(first, 3)
        compose.runOnIdle { selectedNumber.value = "+invalid" }
        compose.waitUntil(5_000) { viewModel.numberProfileState.value.isInvalidNumber }
        compose.onNodeWithText("Некорректный номер. Проверьте номер в сохранённой записи").assertIsDisplayed()
        compose.onNodeWithText("Загружаем данные…").assertDoesNotExist()
        compose.onNodeWithText("Synthetic contact").assertDoesNotExist()
        compose.onNodeWithText("Последние звонки").assertDoesNotExist()
        compose.onNodeWithText("Проверка номера").assertDoesNotExist()
        assertEquals("Preserved synthetic entry", runBlocking { database.dao().getNumberEntry("+invalid")?.personalName })
        assertEquals(0, provider.calls.get())
    }

    @Test
    fun longHistoryIsLazyAndLargeTextKeepsDateAndDurationVisible() {
        contacts[first] = ContactLookupResult.Found("Synthetic contact", null)
        val time = LocalDateTime.of(2026, 1, 1, 4, 13).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runBlocking { database.dao().insertCallRecords((0 until 600).map {
            CallRecordEntity(id = 2000L + it, e164 = first, direction = "INCOMING", eventAt = time + it * 1000, durationSeconds = 65)
        }) }
        showCard(fontScale = 1.6f)
        waitForHistory(first, 600)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(4)
        compose.onNodeWithTag("history-open").performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(601)
        compose.onNodeWithTag("history-call-2000")
            .assertIsDisplayed().assert(hasClickAction().not())
            .assertTextContains("Входящий")
            .assertTextContains("1:05")
    }

    private fun seedHistory() {
        val time = LocalDateTime.of(2026, 1, 1, 4, 13).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runBlocking { database.dao().insertCallRecords(listOf(
            CallRecordEntity(id = 11, e164 = first, direction = "INCOMING", eventAt = time, durationSeconds = 65, systemCallId = 101),
            CallRecordEntity(id = 12, e164 = first, direction = "MISSED", eventAt = time - 60_000, systemCallId = 102),
            CallRecordEntity(id = 13, e164 = first, direction = "OUTGOING", eventAt = time - 120_000),
            CallRecordEntity(id = 99, e164 = second, direction = "INCOMING", eventAt = time),
        )) }
    }

    private fun waitForHistory(number: String, count: Int) {
        compose.waitUntil(5_000) {
            viewModel.numberCallHistory.value.let { it.e164 == number && !it.isLoading && it.error == null && it.calls.size == count }
        }
        compose.waitForIdle()
    }

    @Test
    fun knownContactShowsContactActionAndHidesPersonalFormIncludingNamelessContact() {
        contacts[first] = ContactLookupResult.Found("Synthetic contact", "content://contacts/lookup/test/1")
        showCard()
        waitForLocal(first)
        compose.onNodeWithText("Synthetic contact").assertExists()
        compose.onNodeWithText("Из контактов").assertExists()
        compose.onNodeWithText("Открыть контакт").performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        assertEquals(0, provider.calls.get())

        contacts[first] = ContactLookupResult.Found(null, null)
        compose.runOnIdle { viewModel.refreshNumberProfile() }
        compose.waitUntil(5_000) { viewModel.numberProfileState.value.identity?.displayName == null }
        compose.onNodeWithText("Контакт без имени").assertExists()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        assertEquals(0, provider.calls.get())
    }

    @Test
    fun asynchronousLookupAndStateRestorationPreserveDraftAndOnlySaveExplicitPersonalName() {
        provider.gate = CompletableDeferred()
        provider.name = "External synthetic name"
        val restoration = StateRestorationTester(compose)
        showCard(restoration = restoration)
        waitForLocal(first)
        compose.waitUntil(5_000) { viewModel.numberProfileState.value.isChecking }
        compose.onNodeWithText("Задать имя").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Draft synthetic name")
        compose.onNodeWithText("Личный").performClick()

        provider.gate!!.complete(Unit)
        compose.waitUntil(5_000) { viewModel.numberProfileState.value.identity?.displayName == provider.name && !viewModel.numberProfileState.value.isChecking }
        compose.onNode(hasSetTextAction()).assertTextEquals("Имя или название компании", "Draft synthetic name")
        assertNull(runBlocking { database.dao().getNumberEntry(first) })
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextEquals("Имя или название компании", "Draft synthetic name")
        compose.onNodeWithText("Личный").assertIsSelected()
        assertEquals(1, provider.calls.get())
        compose.onNodeWithText("Сохранить", useUnmergedTree = false).performClick()
        compose.waitUntil(5_000) { runBlocking { database.dao().getNumberEntry(first)?.personalName == "Draft synthetic name" } }
        assertEquals(NumberType.PERSONAL, runBlocking { database.dao().getNumberEntry(first)?.numberType })
    }

    @Test
    fun existingPersonalNameAndTypePrefillEditorAndDoNotTriggerAutomaticLookup() {
        runBlocking { database.dao().upsertNumberEntry(NumberEntryEntity(first, personalName = "Saved synthetic name", numberType = NumberType.BUSINESS)) }
        showCard()
        waitForLocal(first)
        compose.onNodeWithText("Изменить имя и тип").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("Имя или название компании", "Saved synthetic name")
        compose.onAllNodesWithText("Бизнес").filter(isSelectable()).onFirst().assertIsSelected()
        assertEquals(0, provider.calls.get())
    }

    @Test
    fun permissionActionIsVisibleAndGrantRefreshesContactWithoutNetworkLookup() {
        contacts[first] = ContactLookupResult.PermissionRequired
        permission.value = PermissionUiStatus.NOT_REQUESTED
        showCard()
        waitForLocal(first)
        compose.onNodeWithText("Разрешить доступ").performScrollTo().performClick()
        assertEquals(1, permissionRequests)
        assertEquals(0, provider.calls.get())
        contacts[first] = ContactLookupResult.Found("Granted synthetic contact", null)
        compose.runOnIdle { permission.value = PermissionUiStatus.GRANTED }
        compose.waitUntil(5_000) { viewModel.numberProfileState.value.isContact }
        compose.onNodeWithText("Granted synthetic contact").assertExists()
        compose.onNodeWithText("Разрешить доступ").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        assertEquals(0, provider.calls.get())
    }

    @Test
    fun savedNameKeepsContactPermissionActionAvailable() {
        contacts[first] = ContactLookupResult.PermissionRequired
        permission.value = PermissionUiStatus.NOT_REQUESTED
        runBlocking { database.dao().upsertNumberEntry(NumberEntryEntity(first, personalName = "Saved synthetic name")) }
        showCard()
        waitForLocal(first)
        compose.onNodeWithText("Разрешить доступ к контактам").performScrollTo().performClick()
        assertEquals(1, permissionRequests)
        assertEquals(0, provider.calls.get())
    }

    @Test
    fun newNumberBackClosesBlankFormAndProtectsDraft() {
        selectedNumber.value = ""
        showCard()
        compose.onNodeWithContentDescription("Назад").performClick()
        assertEquals(1, backCount)
        compose.onNodeWithText("Номер телефона").performTextInput("+12025550199")
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Сохранить изменения?").assertIsDisplayed()
        assertEquals(1, backCount)
        compose.onNodeWithText("Не сохранять").performClick()
        assertEquals(2, backCount)
    }

    @Test
    fun externalWarningAndPersonalRulesRemainDistinct() {
        provider.spam = true
        runBlocking { database.dao().upsertOverride(UserOverrideEntity(first, action = PersonalAction.BLOCK, personalSpam = true)) }
        showCard(roleHeld = false)
        waitForLocal(first)
        compose.waitUntil(5_000) { viewModel.numberProfileState.value.identity?.externalSpam == true }
        compose.onNodeWithText("Блокировка настроена, но не действует").assertExists()
        compose.onNodeWithText("Личная отметка: спам").assertExists()
        compose.onNodeWithText("Возможный спам").assertExists()
        compose.onNodeWithText("Включить определение звонков").assertExists()
    }

    @Test
    fun lookupDetailsAreAvailableBeforeCheckingContact() {
        contacts[first] = ContactLookupResult.Found("Synthetic contact", null)
        showCard()
        waitForLocal(first)
        compose.onNodeWithText("Источники и детали").performScrollTo().performClick()
        compose.onNodeWithText("Результатов проверки пока нет").assertIsDisplayed()
        assertEquals(0, provider.calls.get())
    }

    @Test
    fun largeTextKeepsActionsReachableAndSwitchingNumbersClearsPreviousDraft() {
        showCard(fontScale = 1.6f)
        waitForLocal(first)
        compose.onNodeWithText("Задать имя").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Previous synthetic draft")
        compose.onNodeWithText("Сохранить").assertIsDisplayed()
        contacts[second] = ContactLookupResult.Found("Second synthetic contact", null)
        compose.runOnIdle { selectedNumber.value = second }
        waitForLocal(second)
        compose.onNodeWithText("Second synthetic contact").assertExists()
        compose.onNodeWithText("Открыть контакт").performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { selectedNumber.value = first }
        waitForLocal(first)
        compose.onNodeWithText("Задать имя").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assertTextEquals("Имя или название компании", "")
    }

    private fun waitForLocal(number: String) {
        compose.waitUntil(5_000) { viewModel.numberProfileState.value.identity?.e164 == number && !viewModel.numberProfileState.value.isLoadingLocal }
        compose.waitForIdle()
    }

    private fun showCard(fontScale: Float = 1f, restoration: StateRestorationTester? = null, roleHeld: Boolean = true) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                WhoCallToMeTheme(ThemeMode.SYSTEM) {
                    NumberProfileScreen(
                        number = selectedNumber.value,
                        viewModel = viewModel,
                        roleHeld = roleHeld,
                        onRequestRole = {}, onBack = { backCount++ },
                        onOpenExisting = { selectedNumber.value = it },
                        contactsPermissionStatus = permission.value,
                        onRequestContactsPermission = { permissionRequests++ },
                        onOpenAppSettings = {},
                    )
                }
            }
        }
        if (restoration != null) restoration.setContent(content) else compose.setContent(content)
    }

    private class FakeProvider : NumberLookupProvider {
        override val id = "ipqs"
        val calls = AtomicInteger()
        var gate: CompletableDeferred<Unit>? = null
        var name: String? = null
        var spam: Boolean = false
        override suspend fun lookup(e164: String): LookupResult {
            calls.incrementAndGet()
            gate?.await()
            val now = System.currentTimeMillis()
            return LookupResult(
                e164, id, if (name == null) LookupStatus.NOT_FOUND else LookupStatus.FOUND,
                displayName = name,
                spamScore = if (spam) 90 else null,
                isSpam = spam,
                fetchedAt = now,
                nameExpiresAt = now + 60_000,
                refreshExpiresAt = now + 60_000,
                negativeExpiresAt = now + 60_000,
            )
        }
    }
}
