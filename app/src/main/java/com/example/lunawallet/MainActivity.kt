package com.example.lunawallet

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import java.util.Locale

class MainActivity : ComponentActivity() {

    // Simple data persistence
    private var lastIncomeAmount: String = ""
    private var lastIncomeSource: String = ""
    private var lastIncomeCategory: String = ""
    
    private var lastExpenseAmount: String = ""
    private var lastExpenseDescription: String = ""
    private var lastExpenseCategory: String = ""

    private var totalIncome: Double = 0.0
    private var totalExpense: Double = 0.0

    // Savings persistence
    private var lastGoalName: String = ""
    private var lastGoalTarget: Double = 0.0
    private var lastGoalCurrent: Double = 0.0

    private var userName: String = "User"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
    }

    fun LoginBtn(view: View) {
        val nameInput = findViewById<EditText>(R.id.etFullName)
        if (nameInput != null && nameInput.text.isNotEmpty()) {
            userName = nameInput.text.toString()
        }
        showDashboard()
    }

    private fun showDashboard() {
        setContentView(R.layout.dashboard)
       // updateDashboardUI()
       // setupBottomNavigation()
    }

    private fun updateDashboardUI() {
        val balance = totalIncome - totalExpense
        findViewById<TextView>(R.id.UserNameTextView)?.text = userName
        findViewById<TextView>(R.id.TBalance)?.text = String.format(Locale.getDefault(), "R%,.2f", balance)
        findViewById<TextView>(R.id.tvTotalIncome)?.text = String.format(Locale.getDefault(), "+R%,.0f", totalIncome)
        findViewById<TextView>(R.id.tvTotalExpense)?.text = String.format(Locale.getDefault(), "-R%,.0f", totalExpense)
        
        // Update recent transaction on dashboard
        val recentAmountTv = findViewById<TextView>(R.id.tvRecentAmount)
        val recentTitleTv = findViewById<TextView>(R.id.tvRecentTitle)
        val recentSubtitleTv = findViewById<TextView>(R.id.tvRecentSubtitle)

        if (totalExpense > 0.0) { // If user added an expense
            recentAmountTv?.text = lastExpenseAmount
            recentTitleTv?.text = lastExpenseDescription
            recentSubtitleTv?.text = lastExpenseCategory
        } else if (totalIncome > 0.0) { // If user added an income
            recentAmountTv?.text = lastIncomeAmount
            recentTitleTv?.text = lastIncomeSource
            recentSubtitleTv?.text = lastIncomeCategory
            recentAmountTv?.setTextColor(resources.getColor(R.color.income_green, null))
        }

        // Update chart
        findViewById<LunaChartView>(R.id.spendingTrendChart)?.setData(totalIncome, totalExpense)
    }

    fun ExpensePage(view: View) {
        setContentView(R.layout.expense)
        updateExpenseUI()
        setupBottomNavigation()
    }

    private fun updateExpenseUI() {
        findViewById<TextView>(R.id.tvExpenseAmount1)?.text = lastExpenseAmount
        findViewById<TextView>(R.id.tvExpenseTitle1)?.text = lastExpenseDescription
        findViewById<TextView>(R.id.tvExpenseSubtitle1)?.text = lastExpenseCategory
    }

    fun AddExpensePage(view: View) {
        setContentView(R.layout.add_expense)
    }

    fun SaveExpense(view: View) {
        val amountStr = findViewById<EditText>(R.id.etAmount)?.text.toString()
        val description = findViewById<EditText>(R.id.etDescription)?.text.toString()
        
        if (amountStr.isNotEmpty()) {
            val amount = amountStr.toDoubleOrNull() ?: 0.0
            totalExpense += amount
            lastExpenseAmount = String.format(Locale.getDefault(), "-R%,.0f", amount)
            lastExpenseDescription = if (description.isNotEmpty()) description else "Expense"
            lastExpenseCategory = "General" 
        }
        
        ExpensePage(view)
    }

    fun IncomePage(view: View) {
        setContentView(R.layout.income)
        updateIncomeUI()
        setupBottomNavigation()
    }

    private fun updateIncomeUI() {
        findViewById<TextView>(R.id.tvIncomeAmount1)?.text = lastIncomeAmount
        findViewById<TextView>(R.id.tvIncomeTitle1)?.text = lastIncomeSource
        findViewById<TextView>(R.id.tvIncomeSubtitle1)?.text = lastIncomeCategory
    }

    fun AddIncomePage(view: View) {
        setContentView(R.layout.add_income_source)
    }

    fun SaveIncome(view: View) {
        val amountStr = findViewById<EditText>(R.id.etAmount)?.text.toString()
        val source = findViewById<EditText>(R.id.etSourceName)?.text.toString()

        if (amountStr.isNotEmpty()) {
            val amount = amountStr.toDoubleOrNull() ?: 0.0
            totalIncome += amount
            lastIncomeAmount = String.format(Locale.getDefault(), "+R%,.0f", amount)
            lastIncomeSource = if (source.isNotEmpty()) source else "Income"
            lastIncomeCategory = "Salary"
        }

        IncomePage(view)
    }

    fun createAcc(view: View) {
        setContentView(R.layout.create_acc)
    }

    fun SavingsPage(view: View) {
        setContentView(R.layout.savings)
        updateSavingsUI()
        setupBottomNavigation()
    }

    private fun updateSavingsUI() {
        val goalNameTv = findViewById<TextView>(R.id.tvGoalName1)
        val goalAmountTv = findViewById<TextView>(R.id.tvGoalAmount1)
        val goalPb = findViewById<android.widget.ProgressBar>(R.id.pbGoal1)

        if (lastGoalName.isNotEmpty()) {
            goalNameTv?.text = lastGoalName
            goalAmountTv?.text = String.format(Locale.getDefault(), "R%,.0f / R%,.0f", lastGoalCurrent, lastGoalTarget)
        } else {
            goalNameTv?.text = getString(R.string.no_savings_goal)
            goalAmountTv?.text = getString(R.string.zero_balance)
        }
        
        val progress = if (lastGoalTarget > 0) ((lastGoalCurrent / lastGoalTarget) * 100).toInt() else 0
        goalPb?.progress = progress
    }

    fun AddSavingsGoalPage(view: View) {
        setContentView(R.layout.add_savings_goal)
    }

    fun SaveGoal(view: View) {
        val name = findViewById<EditText>(R.id.etGoalName)?.text.toString()
        val targetStr = findViewById<EditText>(R.id.etTargetAmount)?.text.toString()
        val initialStr = findViewById<EditText>(R.id.etInitialDeposit)?.text.toString()

        if (name.isNotEmpty() && targetStr.isNotEmpty()) {
            lastGoalName = name
            lastGoalTarget = targetStr.toDoubleOrNull() ?: 0.0
            lastGoalCurrent = initialStr.toDoubleOrNull() ?: 0.0
        }

        SavingsPage(view)
    }

    private fun setupBottomNavigation() {
        val bottomNavigationView = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigationView?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> {
                    showDashboard()
                    true
                }
                R.id.navigation_savings -> {
                    SavingsPage(bottomNavigationView)
                    true
                }
                else -> false
            }
        }
    }
}
