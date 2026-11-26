package com.promptcut.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProjectEntity::class,
        MediaAssetEntity::class,
        ConversationEntity::class,
        RenderJobEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun mediaAssetDao(): MediaAssetDao
    abstract fun conversationDao(): ConversationDao
    abstract fun renderJobDao(): RenderJobDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** Non-destructive v1 -> v2 schema migration preserving all existing projects, assets, and chats. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN thumbnailPath TEXT")
                db.execSQL("ALTER TABLE projects ADD COLUMN missingAssetsCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE projects ADD COLUMN lastBackupAtMs INTEGER")
                db.execSQL("ALTER TABLE projects ADD COLUMN lastExportDiagnosticsJson TEXT")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "promptcut_projects.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
