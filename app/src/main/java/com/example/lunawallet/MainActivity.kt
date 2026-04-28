package com.example.lunawallet

import android.content.Context
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Data persistence using companion object to survive activity recreation
    companion object {
        data class Expense(val amount: String, val description: String, val category: String, val receiptUri: Uri? = null, val date: String)
        val expenses = mutableListOf<Expense>()

        data class Income(val amount: String, val source: String, val category: String, val date: String)
        val incomes = mutableListOf<Income>()

        data class SavingsGoal(val name: String, val target: Double, var current: Double)
        val savingsGoals = mutableListOf<SavingsGoal>()

        var totalIncome: Double = 0.0
        var totalExpense: Double = 0.0
        var userName: String = "User"
        
        // Persistent settings
        var notificationFrequency: String = "Daily"
        var notificationTime: String = "Morning"
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
        // 1. Load and apply theme BEFORE super.onCreate
        val prefs = getSharedPreferences("LunaPrefs", Context.MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("dark_mode", false)
        if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 2. Determine which layout to show (persisted across theme changes)
        val currentLayoutId = prefs.getInt("current_layout", R.layout.activity_main)
        setContentView(currentLayoutId)

        // 3. Initialize the UI for the current layout
        initializeUI(currentLayoutId)
    }

    private fun saveCurrentState(layoutId: Int) {
        val prefs = getSharedPreferences("LunaPrefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("current_layout", layoutId).apply()
    }

    private fun initializeUI(layoutId: Int) {
        when (layoutId) {
            R.layout.dashboard -> updateDashboardUI()
            R.layout.expense -> updateExpenseUI()
            R.layout.income -> updateIncomeUI()
            R.layout.savings -> updateSavingsUI()
            R.layout.settings -> updateSettingsUI()
            R.layout.notification_settings -> updateNotificationSettingsUI()
        }
        
        // Add navigation if on a main screen
        if (layoutId != R.layout.activity_main && layoutId != R.layout.create_acc && layoutId != R.layout.notification_settings) {
            setupBottomNavigation()
        }
    }

    fun LoginBtn(view: View) {
        val nameInput = findViewById<EditText>(R.id.etFullName)
        if (nameInput != null && nameInput.text.isNotEmpty()) {
            userName = nameInput.text.toString()
        }
        showDashboard()
    }

    fun LogoutBtn(view: View) {
        val layoutId = R.layout.activity_main
        saveCurrentState(layoutId)
        setContentView(layoutId)
    }

    private fun showDashboard() {
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

    fun SaveExpense(view: View) {
        val amountStr = findViewById<EditText>(R.id.etAmount)?.text.toString()
        val description = findViewById<EditText>(R.id.etDescription)?.text.toString()
        val category = findViewById<android.widget.Spinner>(R.id.spCategory)?.selectedItem?.toString() ?: "General"
        
        if (amountStr.isNotEmpty()) {
            val amount = amountStr.toDoubleOrNull() ?: 0.0
            totalExpense += amount
            val formattedAmount = String.format(Locale.getDefault(), "-R%,.0f", amount)
            val desc = if (description.isNotEmpty()) description else "Expense"
            val date = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())
            expenses.add(Expense(formattedAmount, desc, category, selectedReceiptUri, date))
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

    fun SaveIncome(view: View) {
        val amountStr = findViewById<EditText>(R.id.etAmount)?.text.toString()
        val source = findViewById<EditText>(R.id.etSourceName)?.text.toString()

        if (amountStr.isNotEmpty()) {
            val amount = amountStr.toDoubleOrNull() ?: 0.0
            totalIncome += amount
            val formattedAmount = String.format(Locale.getDefault(), "+R%,.0f", amount)
            val src = if (source.isNotEmpty()) source else "Income"
            val date = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())
            incomes.add(Income(formattedAmount, src, "Salary", date))
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
            savingsGoals.add(SavingsGoal(name, target, current))
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
            goal.current += amount
            
            if (goal.current >= goal.target) {
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

    fun SaveNotificationSettings(view: View) {
        val rgFreq = findViewById<RadioGroup>(R.id.rgFrequency)
        val rgTime = findViewById<RadioGroup>(R.id.rgTime)

        if (rgFreq != null) {
            notificationFrequency = if (rgFreq.checkedRadioButtonId == R.id.rbDaily) "Daily" else "Weekly"
        }
        
        if (rgTime != null) {
            notificationTime = if (rgTime.checkedRadioButtonId == R.id.rbMorning) "Morning" else "Night"
        }

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
