package com.jarvis.copilot.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notification_entries")
data class NotificationEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long
)

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(entry: NotificationEntry)

    @Query("SELECT * FROM notification_entries ORDER BY timestamp DESC")
    fun getAll(): Flow<List<NotificationEntry>>

    @Query("DELETE FROM notification_entries")
    suspend fun clearAll()

    @Delete
    suspend fun delete(entry: NotificationEntry)
}

@Database(entities = [NotificationEntry::class], version = 1, exportSchema = false)
abstract class NotificationHistoryDb : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao

    companion object {
        @Volatile private var INSTANCE: NotificationHistoryDb? = null

        fun getInstance(context: Context): NotificationHistoryDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NotificationHistoryDb::class.java,
                    "notification_history.db" // local only, per device — not synced
                ).build().also { INSTANCE = it }
            }
    }
}
