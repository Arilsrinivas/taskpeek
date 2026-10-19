package com.taskpeek.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("""
        SELECT * FROM tasks 
        WHERE date = :todayDate AND isCompleted = 0 
        ORDER BY 
            CASE priority 
                WHEN 'HIGH' THEN 1 
                WHEN 'MEDIUM' THEN 2 
                WHEN 'LOW' THEN 3 
                ELSE 4 
            END, 
            id ASC 
        LIMIT 3
    """)
    suspend fun getTodayPendingTasks(todayDate: String): List<Task>

    @Query("SELECT * FROM tasks WHERE date = :todayDate ORDER BY isCompleted ASC, id DESC")
    fun getTodayTasksFlow(todayDate: String): Flow<List<Task>>

    @Query("SELECT * FROM tasks ORDER BY isCompleted ASC, date ASC, id DESC")
    fun getAllTasksFlow(): Flow<List<Task>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task): Long

    @Update
    suspend fun updateTask(task: Task)

    @Query("UPDATE tasks SET isCompleted = :completed WHERE id = :taskId")
    suspend fun setTaskCompleted(taskId: Long, completed: Boolean)

    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun deleteTaskById(taskId: Long)

    @Delete
    suspend fun deleteTask(task: Task)
}
