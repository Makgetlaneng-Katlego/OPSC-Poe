package com.example.lunawallet

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.example.lunawallet.data.ExpenseEntity
import com.example.lunawallet.data.IncomeEntity
import com.example.lunawallet.data.LunaDatabase
import com.example.lunawallet.data.SavingsGoalEntity
import com.example.lunawallet.data.SpendingLimitEntity
import kotlinx.coroutines.launch
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Color
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import com.example.lunawallet.data.UserEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var database: LunaDatabase

    // Data persistence using companion object to survive activity recreation
    companion object {
        data class Expense(val id: Long, val amount: String, val description: String, val category: String, val receiptUri: Uri? = null, val date: String)
        val expenses = mutableListOf<Expense>()

        data class Income(val id: Long, val amount: String, val source: String, val category: String, val date: String)
        val incomes = mutableListOf<Income>()

        data class SpendingLimit(val id: Long, val name: String, val amount: Double, val alertPercent: Int, val frequency: String = "Monthly")
        val spendingLimits = mutableListOf<SpendingLimit>().apply {
            // These will be replaced by DB data soon
        }

        data class SavingsGoal(val id: Long, val name: String, val target: Double, var current: Double)
        val savingsGoals = mutableListOf<SavingsGoal>()

        var totalIncome: Double = 0.0
        var totalExpense: Double = 0.0
        var userName: String = "User"
        var userEmail: String = "alex@example.co.za"
        
        // Persistent settings
        var notificationFrequency: String = "Daily"
        var notificationTime: String = "Morning"
        const val CHANNEL_ID = "BUDGET_ALERTS"
    }

    private var selectedReceiptUri: Uri? = null
    private var activeSavingsGoalIndex: Int = -1

    // Register image picker
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            selectedReceiptUri = uri
            findViewById<ImageView>(R.id.ivReceiptPreview)?.let {
                it.setImageURI(uri)
                it.alpha = 1.0f
            }
            findViewById<TextView>(R.id.tvReceiptStatus)?.text = "Receipt attached!"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Load and apply theme and user profile BEFORE super.onCreate
        val prefs = getSharedPreferences("LunaPrefs", Context.MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("dark_mode", false)
        if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        userName = prefs.getString("user_name", "User") ?: "User"
        userEmail = prefs.getString("user_email", "alex@example.co.za") ?: "alex@example.co.za"
        notificationFrequency = prefs.getString("notification_frequency", "Daily") ?: "Daily"
        notificationTime = prefs.getString("notification_time", "Morning") ?: "Morning"

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        database = LunaDatabase.getDatabase(this)
        observeDatabase()
        createNotificationChannel()
        requestNotificationPermission()

        // 2. Determine which layout to show (persisted across theme changes)
        val isLoggedIn = prefs.getBoolean("is_logged_in", false)
        val defaultLayout = if (isLoggedIn) R.layout.dashboard else R.layout.activity_main
        val currentLayoutId = prefs.getInt("current_layout", defaultLayout)

        setContentView(currentLayoutId)

        // 3. Initialize the UI for the current layout
        initializeUI(currentLayoutId)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Budget Alerts"
            val descriptionText = "Notifications for spending limit alerts"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != 
                PackageManager.PERMISSION_GRANTED) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun sendBudgetNotification(category: String, percent: Int) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_account_balance_wallet_24)
            .setContentTitle("Budget Alert")
            .setContentText("You have used $percent% of your $category limit")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(this)) {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == 
                PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                notify(category.hashCode(), builder.build())
            }
        }
    }

    private fun saveCurrentState(layoutId: Int) {
        val prefs = getSharedPreferences("LunaPrefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("current_layout", layoutId).apply()
    }

    private fun observeDatabase() {
        lifecycleScope.launch {
            database.lunaDao().getAllExpenses().collect { entities ->
                expenses.clear()
                expenses.addAll(entities.map {
                    val uri = it.receiptUri?.let { path ->
                        if (path.startsWith("/")) Uri.fromFile(File(path))
                        else Uri.parse(path)
                    }
                    Expense(it.id, it.amount, it.description, it.category, uri, it.date)
                })
                totalExpense = entities.sumOf { parseAmount(it.amount) }
                updateDashboardUIIfActive(R.layout.dashboard)
                updateDashboardUIIfActive(R.layout.expense)
            }
        }
        lifecycleScope.launch {
            database.lunaDao().getAllIncomes().collect { entities ->
                incomes.clear()
                incomes.addAll(entities.map {
                    Income(it.id, it.amount, it.source, it.category, it.date)
                })
                totalIncome = entities.sumOf { parseAmount(it.amount) }
                updateDashboardUIIfActive(R.layout.dashboard)
                updateDashboardUIIfActive(R.layout.income)
            }
        }
        lifecycleScope.launch {
            database.lunaDao().getAllSpendingLimits().collect { entities ->
                if (entities.isEmpty()) {
                    // Pre-populate with default data
                    database.lunaDao().insertSpendingLimit(SpendingLimitEntity(name = "Overall Monthly Budget", amount = 5000.0, alertPercent = 80, frequency = "Monthly"))
                    database.lunaDao().insertSpendingLimit(SpendingLimitEntity(name = "Groceries", amount = 2000.0, alertPercent = 90, frequency = "Monthly"))
                }
                spendingLimits.clear()
                spendingLimits.addAll(entities.map {
                    SpendingLimit(it.id, it.name, it.amount, it.alertPercent, it.frequency)
                })
                updateDashboardUIIfActive(R.layout.spending_limits)
            }
        }
        lifecycleScope.launch {
            database.lunaDao().getAllSavingsGoals().collect { entities ->
                savingsGoals.clear()
                savingsGoals.addAll(entities.map {
                    SavingsGoal(it.id, it.name, it.target, it.current)
                })
                updateDashboardUIIfActive(R.layout.savings)
            }
        }
    }

    private fun parseAmount(amountStr: String): Double {
        return amountStr.replace("[^0-9.]".toRegex(), "").toDoubleOrNull() ?: 0.0
    }

    private fun updateDashboardUIIfActive(layoutId: Int) {
        val prefs = getSharedPreferences("LunaPrefs", Context.MODE_PRIVATE)
        val currentLayout = prefs.getInt("current_layout", R.layout.dashboard)
        if (currentLayout == layoutId) {
            initializeUI(layoutId)
        }
    }

    private fun initializeUI(layoutId: Int) {
        when (layoutId) {
            R.layout.dashboard -> updateDashboardUI()
            R.layout.expense -> updateExpenseUI()
            R.layout.income -> updateIncomeUI()
            R.layout.savings -> updateSavingsUI()
            R.layout.settings -> updateSettingsUI()
            R.layout.notification_settings -> updateNotificationSettingsUI()
            R.layout.spending_limits -> updateSpendingLimitsUI()
            R.layout.budget_limit -> { /* No specific UI update needed for now */ }
        }
        
        // Add navigation if on a main screen
        if (layoutId != R.layout.activity_main && layoutId != R.layout.create_acc && layoutId != R.layout.notification_settings && layoutId != R.layout.budget_limit) {
            setupBottomNavigation()
        }
    }

    fun LoginBtn(view: View) {
        val emailInput = findViewById<EditText>(R.id.EdiText)
        val passwordInput = findViewById<EditText>(R.id.EditText)
        
        val email = emailInput?.text.toString().trim()
        val password = passwordInput?.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val user = withContext(Dispatchers.IO) {
                database.lunaDao().getUserByEmail(email)
            }

            if (user != null && user.password == password) {
                userName = user.fullName
                userEmail = user.email
                getSharedPreferences("LunaPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("user_name", userName)
                    .putString("user_email", userEmail)
                    .putBoolean("is_logged_in", true)
                    .apply()
                showDashboard()
            } else {
                Toast.makeText(this@MainActivity, "Invalid email or password", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun RegisterBtn(view: View) {
        val nameInput = findViewById<EditText>(R.id.etFullName)
        val emailInput = findViewById<EditText>(R.id.etEmail)
        val pass1Input = findViewById<EditText>(R.id.etPassword1)
        val pass2Input = findViewById<EditText>(R.id.etPassword)

        val name = nameInput?.text.toString().trim()
        val email = emailInput?.text.toString().trim()
        val pass1 = pass1Input?.text.toString().trim()
        val pass2 = pass2Input?.text.toString().trim()

        if (name.isEmpty() || email.isEmpty() || pass1.isEmpty() || pass2.isEmpty()) {
            Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            return
        }

        if (pass1 != pass2) {
            Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val existingUser = withContext(Dispatchers.IO) {
                database.lunaDao().getUserByEmail(email)
            }

            if (existingUser != null) {
                Toast.makeText(this@MainActivity, "Email already registered", Toast.LENGTH_SHORT).show()
            } else {
                val newUser = UserEntity(fullName = name, email = email, password = pass1)
                withContext(Dispatchers.IO) {
                    database.lunaDao().insertUser(newUser)
                }
                
                userName = name
                userEmail = email
                getSharedPreferences("LunaPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("user_name", userName)
                    .putString("user_email", userEmail)
                    .putBoolean("is_logged_in", true)
                    .apply()
                showDashboard()
            }
        }
    }

    fun LogoutBtn(view: View) {
        getSharedPreferences("LunaPrefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_logged_in", false)
            .apply()
        val layoutId = R.layout.activity_main
        saveCurrentState(layoutId)
        setContentView(layoutId)
    }

    fun showDashboard(view: View? = null) {
        val layoutId = R.layout.dashboard
        saveCurrentState(layoutId)
        setContentView(layoutId)
        updateDashboardUI()
        setupBottomNavigation()
        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)?.selectedItemId = R.id.navigation_home
    }

    private fun updateDashboardUI() {
        val balance = totalIncome - totalExpense
        findViewById<TextView>(R.id.UserNameTextView)?.text = userName
        findViewById<TextView>(R.id.TBalance)?.text = String.format(Locale.getDefault(), "R%,.2f", balance)
        findViewById<TextView>(R.id.tvTotalIncome)?.text = String.format(Locale.getDefault(), "+R%,.0f", totalIncome)
        findViewById<TextView>(R.id.tvTotalExpense)?.text = String.format(Locale.getDefault(), "-R%,.0f", totalExpense)
        
        val container = findViewById<LinearLayout>(R.id.transactionContainer)
        val emptyText = findViewById<TextView>(R.id.tvEmptyTransactions)

        if (container == null) return

        if (expenses.isEmpty() && incomes.isEmpty()) {
            emptyText?.visibility = View.VISIBLE
        } else {
            emptyText?.visibility = View.GONE
            container.removeAllViews()
            container.addView(emptyText)
            emptyText.visibility = View.GONE

            val inflater = LayoutInflater.from(this)
            for (expense in expenses.reversed().take(3)) {
                addTransactionToDashboard(inflater, container, expense)
            }
            for (income in incomes.reversed().take(3)) {
                addTransactionToDashboard(inflater, container, income)
            }
        }
        findViewById<LunaChartView>(R.id.spendingTrendChart)?.let { chart ->
            updateChartWithRealData(chart)
        }
        updateCategoryCharts()
    }

    private fun updateChartWithRealData(chart: LunaChartView) {
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
        val last7Days = mutableListOf<String>()
        
        for (i in 0 until 7) {
            last7Days.add(dateFormat.format(calendar.time))
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        last7Days.reverse()

        val dailyNet = mutableMapOf<String, Double>()
        for (day in last7Days) dailyNet[day] = 0.0
        
        for (expense in expenses) {
            val cleanAmount = expense.amount.replace("[^0-9]".toRegex(), "")
            val amount = cleanAmount.toDoubleOrNull() ?: 0.0
            if (dailyNet.containsKey(expense.date)) {
                dailyNet[expense.date] = dailyNet[expense.date]!! - amount
            }
        }

        for (income in incomes) {
            val cleanAmount = income.amount.replace("[^0-9]".toRegex(), "")
            val amount = cleanAmount.toDoubleOrNull() ?: 0.0
            if (dailyNet.containsKey(income.date)) {
                dailyNet[income.date] = dailyNet[income.date]!! + amount
            }
        }

        val nets = last7Days.map { dailyNet[it] ?: 0.0 }
        val maxNet = nets.maxOrNull() ?: 1.0
        val minNet = nets.minOrNull() ?: -1.0
        
        val range = maxNet - minNet
        val trendPoints = if (range != 0.0) {
            nets.map { net -> 
                ((net - minNet) / range).toFloat().coerceIn(0.1f, 0.9f)
            }
        } else {
            nets.map { 0.5f }
        }
        
        chart.setTrendData(trendPoints)
    }

    private fun updateCategoryCharts() {
        val container = findViewById<LinearLayout>(R.id.categoryChartsContainer) ?: return
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        // Expense Categories
        val expenseCats = listOf("Food & Dining", "Transport", "Home", "Shopping", "General")
        for (cat in expenseCats) {
            val filteredExpenses = expenses.filter { it.category == cat }
            if (filteredExpenses.isNotEmpty()) {
                addCategoryChart(container, cat, R.color.expense_red, isExpense = true)
            }
        }

        // Income Categories - Based on chips in add_income_source.xml
        val incomeCats = listOf("Salary", "Freelance", "Gift")
        for (cat in incomeCats) {
            val filteredIncomes = incomes.filter { it.category == cat }
            if (filteredIncomes.isNotEmpty()) {
                addCategoryChart(container, cat, R.color.income_green, isExpense = false)
            }
        }
    }

    private fun addCategoryChart(container: LinearLayout, category: String, colorRes: Int, isExpense: Boolean) {
        val context = container.context
        
        val cardView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.card_background, null)
            elevation = 4f
            setPadding(32, 32, 32, 32)
            val params = LinearLayout.LayoutParams(500, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 32, 16)
            layoutParams = params
        }

        val titleTv = TextView(context).apply {
            text = category
            setTextColor(resources.getColor(R.color.primaryTextColor, null))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }
        cardView.addView(titleTv)

        val chartView = LunaChartView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 150)
            setChartColor(resources.getColor(colorRes, null))
        }
        
        val points = getTrendDataForCategory(category, isExpense)
        chartView.setTrendData(points)
        cardView.addView(chartView)
        
        container.addView(cardView)
    }

    private fun getTrendDataForCategory(category: String, isExpense: Boolean): List<Float> {
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
        val last7Days = mutableListOf<String>()
        for (i in 0 until 7) {
            last7Days.add(dateFormat.format(calendar.time))
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        last7Days.reverse()

        val dailyData = last7Days.associateWith { 0.0 }.toMutableMap()
        
        if (isExpense) {
            expenses.filter { it.category == category }.forEach {
                val cleanAmount = it.amount.replace("[^0-9]".toRegex(), "")
                val amount = cleanAmount.toDoubleOrNull() ?: 0.0
                if (dailyData.containsKey(it.date)) {
                    dailyData[it.date] = (dailyData[it.date] ?: 0.0) + amount
                }
            }
        } else {
            incomes.filter { it.category == category }.forEach {
                val cleanAmount = it.amount.replace("[^0-9]".toRegex(), "")
                val amount = cleanAmount.toDoubleOrNull() ?: 0.0
                if (dailyData.containsKey(it.date)) {
                    dailyData[it.date] = (dailyData[it.date] ?: 0.0) + amount
                }
            }
        }

        val values = last7Days.map { dailyData[it] ?: 0.0 }
        val maxVal = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        return values.map { (it / maxVal).toFloat().coerceIn(0.1f, 0.9f) }
    }

    private fun addTransactionToDashboard(inflater: LayoutInflater, container: LinearLayout, transaction: Any) {
        val itemView = inflater.inflate(R.layout.item_expense, container, false)
        val amountTv = itemView.findViewById<TextView>(R.id.tvExpenseAmount)
        val titleTv = itemView.findViewById<TextView>(R.id.tvExpenseTitle)
        val subtitleTv = itemView.findViewById<TextView>(R.id.tvExpenseSubtitle)
        val iconIv = itemView.findViewById<ImageView>(R.id.ivExpenseIcon)

        if (transaction is Expense) {
            titleTv.text = transaction.description
            subtitleTv.text = transaction.category
            amountTv.text = transaction.amount
            amountTv.setTextColor(resources.getColor(R.color.expense_red, null))
            when (transaction.category) {
                "Food & Dining" -> iconIv.setImageResource(android.R.drawable.ic_menu_today)
                "Transport" -> iconIv.setImageResource(android.R.drawable.ic_menu_directions)
                "Home" -> iconIv.setImageResource(R.drawable.baseline_home_24)
                "Shopping" -> iconIv.setImageResource(R.drawable.baseline_account_balance_wallet_24)
                else -> iconIv.setImageResource(android.R.drawable.ic_menu_info_details)
            }
            itemView.setOnClickListener { showTransactionDetails(transaction) }
        } else if (transaction is Income) {
            titleTv.text = transaction.source
            subtitleTv.text = transaction.category
            amountTv.text = transaction.amount
            amountTv.setTextColor(resources.getColor(R.color.income_green, null))
            iconIv.setImageResource(android.R.drawable.ic_input_add)
            itemView.setOnClickListener { showTransactionDetails(transaction) }
        }
        container.addView(itemView)
    }

    fun ExpensePage(view: View) {
        val layoutId = R.layout.expense
        saveCurrentState(layoutId)
        setContentView(layoutId)
        updateExpenseUI()
        setupBottomNavigation()
    }

    private fun updateExpenseUI() {
        val container = findViewById<LinearLayout>(R.id.expensesContainer)
        val emptyText = findViewById<TextView>(R.id.tvEmptyExpenses)
        if (container == null) return

        if (expenses.isEmpty()) {
            emptyText?.visibility = View.VISIBLE
        } else {
            emptyText?.visibility = View.GONE
            container.removeAllViews()
            container.addView(emptyText)
            emptyText.visibility = View.GONE

            val inflater = LayoutInflater.from(this)
            for (expense in expenses.reversed()) {
                val itemView = inflater.inflate(R.layout.item_expense, container, false)
                itemView.findViewById<TextView>(R.id.tvExpenseAmount).text = expense.amount
                itemView.findViewById<TextView>(R.id.tvExpenseTitle).text = expense.description
                val subtitle = itemView.findViewById<TextView>(R.id.tvExpenseSubtitle)
                subtitle.text = expense.category
                
                itemView.setOnClickListener { showTransactionDetails(expense) }

                if (expense.receiptUri != null) {
                    itemView.findViewById<ImageView>(R.id.ivReceiptIndicator).visibility = View.VISIBLE
                }

                val iconIv = itemView.findViewById<ImageView>(R.id.ivExpenseIcon)
                when (expense.category) {
                    "Food & Dining" -> iconIv.setImageResource(android.R.drawable.ic_menu_today)
                    "Transport" -> iconIv.setImageResource(android.R.drawable.ic_menu_directions)
                    "Home" -> iconIv.setImageResource(R.drawable.baseline_home_24)
                    "Shopping" -> iconIv.setImageResource(R.drawable.baseline_account_balance_wallet_24)
                    else -> iconIv.setImageResource(android.R.drawable.ic_menu_info_details)
                }
                container.addView(itemView)
            }
        }
    }

    fun AddExpensePage(view: View) {
        val layoutId = R.layout.add_expense
        saveCurrentState(layoutId)
        setContentView(layoutId)
        selectedReceiptUri = null
        findViewById<View>(R.id.llAttachReceipt)?.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private fun saveImageToInternalStorage(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val directory = File(filesDir, "receipts")
            if (!directory.exists()) directory.mkdirs()
            
            val fileName = "receipt_${System.currentTimeMillis()}.jpg"
            val file = File(directory, fileName)
            val outputStream = FileOutputStream(file)
            
            inputStream.copyTo(outputStream)
            
            inputStream.close()
            outputStream.close()
            
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun checkBudgetAlerts(category: String, newAmount: Double) {
        val currentMonth = SimpleDateFormat("yyyy/MM", Locale.getDefault()).format(Date())
        
        // Check category-specific limit
        val categoryLimit = spendingLimits.find { it.name == category }
        if (categoryLimit != null) {
            val categorySpending = expenses.filter { it.category == category && it.date.startsWith(currentMonth) }
                .sumOf { parseAmount(it.amount) }
            val totalAfter = categorySpending + newAmount
            val usagePercent = ((totalAfter / categoryLimit.amount) * 100).toInt()
            
            if (usagePercent >= categoryLimit.alertPercent) {
                sendBudgetNotification(category, usagePercent)
            }
        }
        
        // Check overall limit
        val overallLimit = spendingLimits.find { it.name == "Overall Monthly Budget" }
        if (overallLimit != null) {
            val totalSpending = expenses.filter { it.date.startsWith(currentMonth) }
                .sumOf { parseAmount(it.amount) }
            val totalAfter = totalSpending + newAmount
            val usagePercent = ((totalAfter / overallLimit.amount) * 100).toInt()
            
            if (usagePercent >= overallLimit.alertPercent) {
                sendBudgetNotification("Overall", usagePercent)
            }
        }
    }

    fun SaveExpense(view: View) {
        val amountStr = findViewById<EditText>(R.id.etAmount)?.text.toString()
        val description = findViewById<EditText>(R.id.etDescription)?.text.toString()
        val category = findViewById<android.widget.Spinner>(R.id.spCategory)?.selectedItem?.toString() ?: "General"
        
        if (amountStr.isNotEmpty()) {
            val amount = amountStr.toDoubleOrNull() ?: 0.0
            val formattedAmount = String.format(Locale.getDefault(), "-R%,.0f", amount)
            val desc = if (description.isNotEmpty()) description else "Expense"
            val date = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())
            
            lifecycleScope.launch {
                val localPath = withContext(Dispatchers.IO) {
                    selectedReceiptUri?.let { saveImageToInternalStorage(it) }
                }
                
                database.lunaDao().insertExpense(ExpenseEntity(
                    amount = formattedAmount,
                    description = desc,
                    category = category,
                    receiptUri = localPath,
                    date = date
                ))
                
                // Check alerts after saving
                checkBudgetAlerts(category, amount)
            }
        }
        ExpensePage(view)
    }

    fun IncomePage(view: View) {
        val layoutId = R.layout.income
        saveCurrentState(layoutId)
        setContentView(layoutId)
        updateIncomeUI()
        setupBottomNavigation()
    }

    private fun updateIncomeUI() {
        val container = findViewById<LinearLayout>(R.id.incomeContainer)
        val emptyText = findViewById<TextView>(R.id.tvEmptyIncome)
        if (container == null) return

        if (incomes.isEmpty()) {
            emptyText?.visibility = View.VISIBLE
        } else {
            emptyText?.visibility = View.GONE
            container.removeAllViews()
            container.addView(emptyText)
            emptyText.visibility = View.GONE

            val inflater = LayoutInflater.from(this)
            for (income in incomes.reversed()) {
                val itemView = inflater.inflate(R.layout.item_income, container, false)
                itemView.findViewById<TextView>(R.id.tvIncomeAmount).text = income.amount
                itemView.findViewById<TextView>(R.id.tvIncomeTitle).text = income.source
                itemView.findViewById<TextView>(R.id.tvIncomeSubtitle).text = income.category
                
                itemView.setOnClickListener { showTransactionDetails(income) }
                
                container.addView(itemView)
            }
        }
    }

    fun AddIncomePage(view: View) {
        val layoutId = R.layout.add_income_source
        saveCurrentState(layoutId)
        setContentView(layoutId)
    }

    fun SpendingLimitsPage(view: View) {
        val layoutId = R.layout.spending_limits
        saveCurrentState(layoutId)
        setContentView(layoutId)
        initializeUI(layoutId)
    }

    private fun updateSpendingLimitsUI() {
        val container = findViewById<LinearLayout>(R.id.limitsContainer)
        val emptyText = findViewById<TextView>(R.id.tvEmptyLimits)
        if (container == null) return

        if (spendingLimits.isEmpty()) {
            emptyText?.visibility = View.VISIBLE
        } else {
            emptyText?.visibility = View.GONE
            container.removeAllViews()
            
            val inflater = LayoutInflater.from(this)
            for ((index, limit) in spendingLimits.withIndex()) {
                val itemView = inflater.inflate(R.layout.item_spending_limit, container, false)
                itemView.findViewById<TextView>(R.id.tvLimitName).text = limit.name
                itemView.findViewById<TextView>(R.id.tvLimitDetails).text = "${limit.frequency} • alert at ${limit.alertPercent}%"
                itemView.findViewById<TextView>(R.id.tvLimitAmount).text = String.format(Locale.getDefault(), "R%,.0f", limit.amount)
                
                itemView.findViewById<ImageView>(R.id.btnDeleteLimit).setOnClickListener {
                    lifecycleScope.launch {
                        database.lunaDao().deleteSpendingLimit(SpendingLimitEntity(
                            id = limit.id,
                            name = limit.name,
                            amount = limit.amount,
                            alertPercent = limit.alertPercent,
                            frequency = limit.frequency
                        ))
                    }
                }

                if (index == spendingLimits.size - 1) {
                    itemView.findViewById<View>(R.id.limitDivider).visibility = View.GONE
                }
                
                container.addView(itemView)
            }
        }
    }

    fun AddSpendingLimitPage(view: View) {
        val layoutId = R.layout.budget_limit
        saveCurrentState(layoutId)
        setContentView(layoutId)
        initializeUI(layoutId)
    }

    fun SaveSpendingLimit(view: View) {
        val name = findViewById<android.widget.Spinner>(R.id.spBudgetCategory)?.selectedItem?.toString() ?: "Overall Monthly Budget"
        val amountStr = findViewById<EditText>(R.id.etBudgetAmount)?.text.toString()
        val period = if (findViewById<RadioButton>(R.id.rbWeekly)?.isChecked == true) "Weekly" else "Monthly"
        val alertStr = findViewById<android.widget.Spinner>(R.id.spBudgetAlert)?.selectedItem?.toString() ?: "80% of limit"
        
        // Extract percentage from string like "80% of limit"
        val alert = alertStr.filter { it.isDigit() }.toIntOrNull() ?: 80

        if (amountStr.isNotEmpty()) {
            val amount = amountStr.toDoubleOrNull() ?: 0.0
            lifecycleScope.launch {
                database.lunaDao().insertSpendingLimit(SpendingLimitEntity(
                    name = name,
                    amount = amount,
                    alertPercent = alert,
                    frequency = period
                ))
            }
        }
        SpendingLimitsPage(view)
    }

    fun SaveIncome(view: View) {
        val amountStr = findViewById<EditText>(R.id.etAmount)?.text.toString()
        val source = findViewById<EditText>(R.id.etSourceName)?.text.toString()

        if (amountStr.isNotEmpty()) {
            val amount = amountStr.toDoubleOrNull() ?: 0.0
            val formattedAmount = String.format(Locale.getDefault(), "+R%,.0f", amount)
            val src = if (source.isNotEmpty()) source else "Income"
            val date = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())
            lifecycleScope.launch {
                database.lunaDao().insertIncome(IncomeEntity(
                    amount = formattedAmount,
                    source = src,
                    category = "Salary",
                    date = date
                ))
            }
        }
        IncomePage(view)
    }

    fun createAcc(view: View) {
        val layoutId = R.layout.create_acc
        saveCurrentState(layoutId)
        setContentView(layoutId)
    }

    fun SavingsPage(view: View) {
        val layoutId = R.layout.savings
        saveCurrentState(layoutId)
        setContentView(layoutId)
        updateSavingsUI()
        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)?.selectedItemId = R.id.navigation_savings
        setupBottomNavigation()
    }

    private fun updateSavingsUI() {
        val container = findViewById<LinearLayout>(R.id.savingsContainer)
        val emptyText = findViewById<TextView>(R.id.tvEmptySavings)
        if (container == null) return

        if (savingsGoals.isEmpty()) {
            emptyText?.visibility = View.VISIBLE
        } else {
            emptyText?.visibility = View.GONE
            container.removeAllViews()
            container.addView(emptyText)
            emptyText.visibility = View.GONE

            val inflater = LayoutInflater.from(this)
            for ((index, goal) in savingsGoals.withIndex()) {
                val itemView = inflater.inflate(R.layout.item_savings_goal, container, false)
                itemView.findViewById<TextView>(R.id.tvGoalName).text = goal.name
                itemView.findViewById<TextView>(R.id.tvGoalAmount).text = 
                    String.format(Locale.getDefault(), "R%,.0f / R%,.0f", goal.current, goal.target)
                val progress = if (goal.target > 0) ((goal.current / goal.target) * 100).toInt() else 0
                itemView.findViewById<ProgressBar>(R.id.pbGoal).progress = progress
                
                itemView.setOnClickListener {
                    showAddToSavingsPage(index)
                }
                
                container.addView(itemView)
            }
        }
    }

    fun AddSavingsGoalPage(view: View) {
        val layoutId = R.layout.add_savings_goal
        saveCurrentState(layoutId)
        setContentView(layoutId)
    }

    fun SaveGoal(view: View) {
        val name = findViewById<EditText>(R.id.etGoalName)?.text.toString()
        val targetStr = findViewById<EditText>(R.id.etTargetAmount)?.text.toString()
        val initialStr = findViewById<EditText>(R.id.etInitialDeposit)?.text.toString()

        if (name.isNotEmpty() && targetStr.isNotEmpty()) {
            val target = targetStr.toDoubleOrNull() ?: 0.0
            val current = initialStr.toDoubleOrNull() ?: 0.0
            lifecycleScope.launch {
                database.lunaDao().insertSavingsGoal(SavingsGoalEntity(
                    name = name,
                    target = target,
                    current = current
                ))
            }
        }
        SavingsPage(view)
    }

    private fun showAddToSavingsPage(index: Int) {
        activeSavingsGoalIndex = index
        val goal = savingsGoals[index]
        val layoutId = R.layout.add_to_savings
        saveCurrentState(layoutId)
        setContentView(layoutId)
        findViewById<TextView>(R.id.tvCurrentGoalName)?.text = goal.name
        findViewById<TextView>(R.id.tvCurrentGoalProgress)?.text = 
            String.format(Locale.getDefault(), "R%,.0f / R%,.0f", goal.current, goal.target)
    }

    fun ConfirmAddSavings(view: View) {
        val amountStr = findViewById<EditText>(R.id.etAddAmount)?.text.toString()
        if (amountStr.isNotEmpty() && activeSavingsGoalIndex != -1) {
            val amount = amountStr.toDoubleOrNull() ?: 0.0
            val goal = savingsGoals[activeSavingsGoalIndex]
            
            lifecycleScope.launch {
                database.lunaDao().updateSavingsGoal(SavingsGoalEntity(
                    id = goal.id,
                    name = goal.name,
                    target = goal.target,
                    current = goal.current + amount
                ))
            }
            
            if (goal.current + amount >= goal.target) {
                showGoalAchievedPage(goal.name)
            } else {
                SavingsPage(view)
            }
        }
    }

    private fun showGoalAchievedPage(goalName: String) {
        val layoutId = R.layout.goal_achieved
        saveCurrentState(layoutId)
        setContentView(layoutId)
        findViewById<TextView>(R.id.tvAchievedGoalName)?.text = goalName
        
        val root = findViewById<ViewGroup>(R.id.rlGoalAchievedRoot)
        if (root != null) {
            startGoalCelebrationAnimation(root)
        }
    }

    private fun startGoalCelebrationAnimation(root: ViewGroup) {
        val emoji = findViewById<TextView>(R.id.tvCelebrationEmoji)
        val title = findViewById<TextView>(R.id.tvAchievedTitle)

        // Pop animation for emoji and title
        if (emoji != null && title != null) {
            emoji.scaleX = 0f
            emoji.scaleY = 0f
            title.alpha = 0f

            val emojiScaleX = ObjectAnimator.ofFloat(emoji, View.SCALE_X, 0f, 1.2f, 1f)
            val emojiScaleY = ObjectAnimator.ofFloat(emoji, View.SCALE_Y, 0f, 1.2f, 1f)
            val titleFade = ObjectAnimator.ofFloat(title, View.ALPHA, 0f, 1f)

            AnimatorSet().apply {
                playTogether(emojiScaleX, emojiScaleY, titleFade)
                duration = 1000
                interpolator = OvershootInterpolator()
                start()
            }
        }

        // Programmatic Confetti
        val colors = intArrayOf(
            Color.parseColor("#10B981"), // income_green
            Color.parseColor("#EF4444"), // expense_red
            Color.parseColor("#3B82F6"), // blue
            Color.parseColor("#F59E0B"), // amber
            Color.parseColor("#8B5CF6")  // purple
        )

        for (i in 0 until 50) {
            val confetti = View(this)
            val size = (10..30).random()
            confetti.layoutParams = ViewGroup.LayoutParams(size, size)
            confetti.setBackgroundColor(colors.random())
            
            // Random start position at the top
            confetti.x = (0..resources.displayMetrics.widthPixels).random().toFloat()
            confetti.y = -size.toFloat()
            confetti.rotation = (0..360).random().toFloat()
            
            root.addView(confetti)

            val fallAnim = ObjectAnimator.ofFloat(confetti, View.TRANSLATION_Y, -size.toFloat(), resources.displayMetrics.heightPixels.toFloat() + 100)
            val rotAnim = ObjectAnimator.ofFloat(confetti, View.ROTATION, confetti.rotation, confetti.rotation + (360..720).random())
            val xAnim = ObjectAnimator.ofFloat(confetti, View.TRANSLATION_X, confetti.x, confetti.x + (-100..100).random())

            AnimatorSet().apply {
                playTogether(fallAnim, rotAnim, xAnim)
                duration = (1500..3000).random().toLong()
                startDelay = (0..1000).random().toLong()
                interpolator = AccelerateInterpolator()
                start()
            }
        }
    }

    fun SettingsPage(view: View) {
        val layoutId = R.layout.settings
        saveCurrentState(layoutId)
        setContentView(layoutId)
        updateSettingsUI()
        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)?.selectedItemId = R.id.navigation_account
        setupBottomNavigation()
    }

    private fun updateSettingsUI() {
        findViewById<TextView>(R.id.tvSettingsUserName)?.text = userName
        findViewById<TextView>(R.id.tvSettingsUserEmail)?.text = userEmail
        
        val prefs = getSharedPreferences("LunaPrefs", Context.MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("dark_mode", false)
        
        val darkModeSwitch = findViewById<SwitchCompat>(R.id.swDarkMode)
        darkModeSwitch?.isChecked = isDarkMode
        darkModeSwitch?.setOnCheckedChangeListener { _, isChecked ->
            // Save preference
            prefs.edit().putBoolean("dark_mode", isChecked).apply()
            
            // Apply theme change
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
    }

    fun NotificationSettingsPage(view: View) {
        val layoutId = R.layout.notification_settings
        saveCurrentState(layoutId)
        setContentView(layoutId)
        updateNotificationSettingsUI()
    }

    private fun updateNotificationSettingsUI() {
        if (notificationFrequency == "Daily") {
            findViewById<RadioButton>(R.id.rbDaily)?.isChecked = true
        } else {
            findViewById<RadioButton>(R.id.rbWeekly)?.isChecked = true
        }

        if (notificationTime == "Morning") {
            findViewById<RadioButton>(R.id.rbMorning)?.isChecked = true
        } else {
            findViewById<RadioButton>(R.id.rbNight)?.isChecked = true
        }
    }

    private fun scheduleReminder(frequency: String, time: String) {
        val workManager = WorkManager.getInstance(this)
        
        // Calculate delay to the next occurrence
        val calendar = Calendar.getInstance()
        val now = calendar.timeInMillis
        
        val targetHour = if (time == "Morning") 8 else 20
        calendar.set(Calendar.HOUR_OF_DAY, targetHour)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        
        if (calendar.timeInMillis <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        
        val initialDelay = calendar.timeInMillis - now
        val interval = if (frequency == "Daily") 1L else 7L
        
        val reminderRequest = PeriodicWorkRequestBuilder<ReminderWorker>(interval, TimeUnit.DAYS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()
            
        workManager.enqueueUniquePeriodicWork(
            "FinancialReminder",
            ExistingPeriodicWorkPolicy.UPDATE,
            reminderRequest
        )
    }

    fun SaveNotificationSettings(view: View) {
        val rgFreq = findViewById<RadioGroup>(R.id.rgFrequency)
        val rgTime = findViewById<RadioGroup>(R.id.rgTime)

        if (rgFreq != null) {
            notificationFrequency = if (rgFreq.checkedRadioButtonId == R.id.rbDaily) "Daily" else "Weekly"
        }
        
        if (rgTime != null) {
            notificationTime = if (rgTime.checkedRadioButtonId == R.id.rbMorning) "Morning" else "Night"
        }

        getSharedPreferences("LunaPrefs", Context.MODE_PRIVATE).edit().apply {
            putString("notification_frequency", notificationFrequency)
            putString("notification_time", notificationTime)
            apply()
        }
        
        scheduleReminder(notificationFrequency, notificationTime)
        Toast.makeText(this, "Reminder scheduled: $notificationFrequency at $notificationTime", Toast.LENGTH_SHORT).show()

        SettingsPage(view)
    }

    private fun showTransactionDetails(transaction: Any) {
        val previousLayoutId = getSharedPreferences("LunaPrefs", Context.MODE_PRIVATE).getInt("current_layout", R.layout.dashboard)
        val layoutId = R.layout.transaction_details
        saveCurrentState(layoutId)
        setContentView(layoutId)
        
        val iconIv = findViewById<ImageView>(R.id.ivIconDetails)
        val amountTv = findViewById<TextView>(R.id.tvAmountDetails)
        val titleTv = findViewById<TextView>(R.id.tvTitleDetails)
        val categoryTv = findViewById<TextView>(R.id.tvCategoryDetails)
        val dateTv = findViewById<TextView>(R.id.tvDateDetails)
        val receiptLayout = findViewById<LinearLayout>(R.id.llReceiptDetails)
        val receiptIv = findViewById<ImageView>(R.id.ivReceiptDetails)
        
        if (transaction is Expense) {
            titleTv.text = transaction.description
            categoryTv.text = transaction.category
            amountTv.text = transaction.amount
            amountTv.setTextColor(resources.getColor(R.color.expense_red, null))
            dateTv.text = transaction.date
            
            when (transaction.category) {
                "Food & Dining" -> iconIv.setImageResource(android.R.drawable.ic_menu_today)
                "Transport" -> iconIv.setImageResource(android.R.drawable.ic_menu_directions)
                "Home" -> iconIv.setImageResource(R.drawable.baseline_home_24)
                "Shopping" -> iconIv.setImageResource(R.drawable.baseline_account_balance_wallet_24)
                else -> iconIv.setImageResource(android.R.drawable.ic_menu_info_details)
            }
            
            if (transaction.receiptUri != null) {
                receiptLayout.visibility = View.VISIBLE
                receiptIv.setImageURI(transaction.receiptUri)
            }
        } else if (transaction is Income) {
            titleTv.text = transaction.source
            categoryTv.text = transaction.category
            amountTv.text = transaction.amount
            amountTv.setTextColor(resources.getColor(R.color.income_green, null))
            dateTv.text = transaction.date
            iconIv.setImageResource(android.R.drawable.ic_input_add)
            receiptLayout.visibility = View.GONE
        }
        
        findViewById<ImageView>(R.id.btnBackDetails)?.setOnClickListener {
            saveCurrentState(previousLayoutId)
            setContentView(previousLayoutId)
            initializeUI(previousLayoutId)
        }
    }

    private fun setupBottomNavigation() {
        val bottomNavigationView = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigationView?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> {
                    if (bottomNavigationView.selectedItemId != R.id.navigation_home) {
                        showDashboard()
                    }
                    true
                }
                R.id.navigation_savings -> {
                    if (bottomNavigationView.selectedItemId != R.id.navigation_savings) {
                        SavingsPage(bottomNavigationView)
                    }
                    true
                }
                R.id.navigation_account -> {
                    if (bottomNavigationView.selectedItemId != R.id.navigation_account) {
                        SettingsPage(bottomNavigationView)
                    }
                    true
                }
                else -> false
            }
        }
    }
}
