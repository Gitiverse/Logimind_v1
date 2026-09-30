package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.*

@Database(
    entities = [
        FormTemplate::class,
        FormSection::class,
        FormField::class,
        FormFieldOption::class,
        Patient::class,
        FormEntry::class,
        EntryAnswer::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun medicalFormDao(): MedicalFormDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "medical_form_database"
                )
                // No destructive fallback: version 3 holds users' clinical data. Any future
                // schema change must ship a real Migration.
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
