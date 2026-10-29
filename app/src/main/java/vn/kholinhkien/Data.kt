package vn.kholinhkien

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "parts", indices = [Index(value = ["code"], unique = true)])
data class Part(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val name: String,
    val quantity: Int = 0,
    val minQuantity: Int = 0,
    val unit: String = "cái",
    val photoPath: String = "",
    val note: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "movements")
data class Movement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val partName: String,
    val type: String,
    val amount: Int,
    val beforeQty: Int,
    val afterQty: Int,
    val reason: String = "",
    val machineOrStore: String = "",
    val time: Long = System.currentTimeMillis()
)

@Dao
interface InventoryDao {
    @Query("SELECT * FROM parts ORDER BY name COLLATE NOCASE")
    fun parts(): Flow<List<Part>>
    @Query("SELECT * FROM parts WHERE code = :code LIMIT 1")
    suspend fun byCode(code: String): Part?
    @Query("SELECT * FROM parts WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): Part?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(part: Part): Long
    @Update
    suspend fun update(part: Part)
    @Query("SELECT * FROM movements ORDER BY time DESC")
    fun movements(): Flow<List<Movement>>
    @Insert
    suspend fun addMovement(movement: Movement)
    @Query("DELETE FROM movements")
    suspend fun clearMovements()
    @Query("DELETE FROM parts")
    suspend fun clearParts()
    @Insert
    suspend fun insertMovement(movement: Movement)
    @Insert
    suspend fun insertPart(part: Part)
    @Transaction
    suspend fun restoreAll(newParts: List<Part>, newMovements: List<Movement>) {
        clearMovements()
        clearParts()
        newParts.forEach { insertPart(it.copy(id = 0)) }
        newMovements.forEach { insertMovement(it.copy(id = 0)) }
    }
    @Transaction
    suspend fun changeStock(code: String, type: String, amount: Int, reason: String, machine: String) {
        require(if (type == "KIỂM KÊ") amount >= 0 else amount > 0) {
            if (type == "KIỂM KÊ") "Số lượng kiểm kê không được âm" else "Số lượng phải lớn hơn 0"
        }
        val part = byCode(code) ?: error("Không tìm thấy mã linh kiện: $code")
        val after = when (type) {
            "NHẬP" -> part.quantity + amount
            "XUẤT" -> part.quantity - amount
            else -> amount
        }
        require(after >= 0) { "Tồn kho không đủ. Hiện còn ${part.quantity} ${part.unit}" }
        update(part.copy(quantity = after, updatedAt = System.currentTimeMillis()))
        addMovement(Movement(code = code, partName = part.name, type = type, amount = amount,
            beforeQty = part.quantity, afterQty = after, reason = reason, machineOrStore = machine))
    }
}

@Database(entities = [Part::class, Movement::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): InventoryDao
    companion object {
        @Volatile private var INSTANCE: AppDb? = null
        fun get(context: Context): AppDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "kho-linh-kien.db")
                .build().also { INSTANCE = it }
        }
    }
}
