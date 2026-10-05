package com.nshd.geminifreellm.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class, AttachmentEntity::class, DocumentChunkEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN parentMessageId INTEGER")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN systemPrompt TEXT")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN temporary INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS document_chunks (
                        id TEXT NOT NULL,
                        sourcePath TEXT NOT NULL,
                        sourceChecksum TEXT NOT NULL,
                        chunkIndex INTEGER NOT NULL,
                        content TEXT NOT NULL,
                        embedding TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_chunks_sourcePath ON document_chunks(sourcePath)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_chunks_sourceChecksum ON document_chunks(sourceChecksum)")
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "freellm.db"
                )
                     .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
