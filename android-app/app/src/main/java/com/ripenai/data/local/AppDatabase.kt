package com.ripenai.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ScanHistory::class, FarmerContainerEntity::class, FarmerSensorReadingEntity::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanHistoryDao(): ScanHistoryDao
    abstract fun farmerDao(): FarmerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ripenai_database"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS farmer_containers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        fruitType TEXT NOT NULL,
                        ipAddress TEXT NOT NULL,
                        ssid TEXT NOT NULL,
                        lastSyncMillis INTEGER,
                        lastReadingTimestamp INTEGER,
                        latestTemperature REAL,
                        latestHumidity REAL,
                        latestGas REAL,
                        latestRiskScore REAL,
                        latestStatus TEXT NOT NULL,
                        latestRecommendation TEXT NOT NULL,
                        lastError TEXT
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS farmer_sensor_readings (
                        containerId INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        temperature REAL NOT NULL,
                        humidity REAL NOT NULL,
                        gas REAL NOT NULL,
                        riskScore REAL,
                        PRIMARY KEY(containerId, timestamp)
                    )"""
                )
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE farmer_containers ADD COLUMN latestModelScore REAL")
                db.execSQL("ALTER TABLE farmer_containers ADD COLUMN latestModelConfidence REAL")
                db.execSQL("ALTER TABLE farmer_containers ADD COLUMN latestAnalysisSource TEXT NOT NULL DEFAULT 'Rule-based v1'")
            }
        }

        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE farmer_containers ADD COLUMN latestHoursToAction REAL")
            }
        }

        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE farmer_containers ADD COLUMN latestCalibrationSamples INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
