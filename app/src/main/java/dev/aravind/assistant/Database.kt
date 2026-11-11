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
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/** Something you asked the assistant to remember. Never passwords or codes. */
@Entity(tableName = "memories")
data class MemoryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val created: Long,
)

/** How often you use an app, contact or music, if "Learn from my usage" is on. */
@Entity(tableName = "usage_counts")
data class UsageRow(
    @PrimaryKey val item: String,
    val kind: String,
    val name: String,
    val uses: Int,
    val lastUsed: Long,
)

@Dao
interface MemoryDao {
    @Insert
    fun insert(row: MemoryRow): Long

    @Update
    fun update(row: MemoryRow)

    @Query("SELECT * FROM memories ORDER BY id DESC")
    fun all(): List<MemoryRow>

    @Query("DELETE FROM memories WHERE id = :id")
    fun delete(id: Long)

    @Query("DELETE FROM memories")
    fun clear()
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM usage_counts WHERE item = :item")
    fun find(item: String): UsageRow?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    fun put(row: UsageRow)

    @Query("SELECT * FROM usage_counts WHERE kind = :kind ORDER BY uses DESC, lastUsed DESC LIMIT :limit")
    fun top(kind: String, limit: Int): List<UsageRow>

    @Query("SELECT * FROM usage_counts ORDER BY kind, uses DESC")
    fun all(): List<UsageRow>

    @Query("DELETE FROM usage_counts WHERE item = :item")
    fun delete(item: String)

    @Query("DELETE FROM usage_counts")
    fun clear()
}

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

@Database(entities = [AuditRow::class, CallMessageRow::class, MemoryRow::class, UsageRow::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun audit(): AuditDao
    abstract fun callMessages(): CallMessageDao
    abstract fun memories(): MemoryDao
    abstract fun usage(): UsageDao
}

/** Adds the memory tables and keeps the existing audit log and call messages. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `created` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `usage_counts` (`item` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`uses` INTEGER NOT NULL, `lastUsed` INTEGER NOT NULL, PRIMARY KEY(`item`))",
        )
    }
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
                .addMigrations(MIGRATION_1_2)
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
