package com.example.lunawallet.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ExpenseEntity::class, IncomeEntity::class, SavingsGoalEntity::class, SpendingLimitEntity::class, UserEntity::class], version = 2)
abstract class LunaDatabase : RoomDatabase() {
    abstract fun lunaDao(): LunaDao

    companion object {
        @Volatile
        private var INSTANCE: LunaDatabase? = null

        fun getDatabase(context: Context): LunaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LunaDatabase::class.java,
                    "luna_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
