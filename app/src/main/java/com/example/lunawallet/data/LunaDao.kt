package com.example.lunawallet.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LunaDao {
    @Query("SELECT * FROM expenses ORDER BY date DESC")
    fun getAllExpenses(): Flow<List<ExpenseEntity>>

    @Insert
    suspend fun insertExpense(expense: ExpenseEntity)

    @Query("SELECT * FROM incomes ORDER BY date DESC")
    fun getAllIncomes(): Flow<List<IncomeEntity>>

    @Insert
    suspend fun insertIncome(income: IncomeEntity)

    @Query("SELECT * FROM savings_goals")
    fun getAllSavingsGoals(): Flow<List<SavingsGoalEntity>>

    @Query("SELECT * FROM savings_goals")
    suspend fun getSavingsGoalsList(): List<SavingsGoalEntity>

    @Update
    suspend fun updateSavingsGoal(goal: SavingsGoalEntity)

    @Insert
    suspend fun insertSavingsGoal(goal: SavingsGoalEntity)

    @Query("SELECT * FROM spending_limits")
    fun getAllSpendingLimits(): Flow<List<SpendingLimitEntity>>

    @Insert
    suspend fun insertSpendingLimit(limit: SpendingLimitEntity)

    @Delete
    suspend fun deleteSpendingLimit(limit: SpendingLimitEntity)

    @Insert
    suspend fun insertUser(user: UserEntity)

    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    suspend fun getUserByEmail(email: String): UserEntity?
}
