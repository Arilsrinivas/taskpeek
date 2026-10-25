package com.taskpeek.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("""
        SELECT * FROM tasks 
        WHERE date <= :todayDate AND isCompleted = 0 
        ORDER BY 
            CASE priority 
                WHEN 'HIGH' THEN 1 
                WHEN 'MEDIUM' THEN 2 
                WHEN 'LOW' THEN 3 
                ELSE 4 
            END, 
            date DESC,
            id DESC 
        LIMIT 3
    """)
    suspend fun getTodayPendingTasks(todayDate: String): List<Task>

    @Query("""
        SELECT * FROM tasks 
        WHERE (date = :todayDate) OR (date < :todayDate AND isCompleted = 0) 
        ORDER BY isCompleted ASC, date DESC, id DESC
    """)
    fun getTodayTasksFlow(todayDate: String): Flow<List<Task>>

    @Query("SELECT * FROM tasks ORDER BY isCompleted ASC, date ASC, id DESC")
    fun getAllTasksFlow(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE date = :date ORDER BY isCompleted ASC, id DESC")
    fun getTasksForDateFlow(date: String): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE date = :date ORDER BY isCompleted ASC, id DESC")
    suspend fun getTasksForDate(date: String): List<Task>

    @Query("SELECT * FROM tasks WHERE isCompleted = 1 ORDER BY completedAt DESC, date DESC, id DESC")
    fun getCompletedTasksFlow(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE category = :category ORDER BY isCompleted ASC, date DESC, id DESC")
    fun getTasksByCategoryFlow(category: String): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTaskById(taskId: Long): Task?

    @Query("SELECT * FROM tasks WHERE id = :taskId LIMIT 1")
    fun getTaskByIdFlow(taskId: Long): Flow<Task?>

    @Query("SELECT COUNT(*) FROM tasks WHERE category = :category")
    fun getCategoryTaskCountFlow(category: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM tasks WHERE category = :category")
    suspend fun getCategoryTaskCount(category: String): Int

    @Query("SELECT * FROM tasks")
    suspend fun getAllTasks(): List<Task>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task): Long

    @Update
    suspend fun updateTask(task: Task)

    @Query("UPDATE tasks SET isCompleted = :completed, completedAt = :completedAt WHERE id = :taskId")
    suspend fun setTaskCompletedWithTime(taskId: Long, completed: Boolean, completedAt: Long)

    @Query("UPDATE tasks SET isCompleted = :completed WHERE id = :taskId")
    suspend fun setTaskCompleted(taskId: Long, completed: Boolean)

    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun deleteTaskById(taskId: Long)

    @Delete
    suspend fun deleteTask(task: Task)
}
