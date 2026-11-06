package dev.aravind.assistant

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity(tableName = "audit")
data class AuditRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entry: String,
    val mac: String,
)

@Entity(tableName = "call_messages")
data class CallMessageRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val text: String,
)

@Dao
interface AuditDao {
    @Insert
    fun insert(row: AuditRow)

    @Query("SELECT * FROM audit ORDER BY id")
    fun all(): List<AuditRow>

    @Query("SELECT * FROM audit ORDER BY id DESC LIMIT 1")
    fun last(): AuditRow?
}

@Dao
interface CallMessageDao {
    @Insert
    fun insert(row: CallMessageRow)

    @Query("SELECT * FROM call_messages ORDER BY id DESC LIMIT 30")
    fun latest(): List<CallMessageRow>
}

@Database(entities = [AuditRow::class, CallMessageRow::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun audit(): AuditDao
    abstract fun callMessages(): CallMessageDao
}

/** Room storage, encrypted with SQLCipher. The key is protected by Android Keystore. */
object Db {
    private const val NAME = "assistant.db"

    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
        instance ?: run {
            System.loadLibrary("sqlcipher")
            val app = context.applicationContext
            Room.databaseBuilder(app, AppDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(Keys.derivedSecret(app, "database")))
                .allowMainThreadQueries()
                .build()
                .also { instance = it }
        }
    }

    fun delete(context: Context) {
        synchronized(this) {
            instance?.close()
            instance = null
            context.applicationContext.deleteDatabase(NAME)
        }
    }
}
