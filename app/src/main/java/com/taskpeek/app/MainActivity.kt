package com.taskpeek.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.taskpeek.app.data.AppDatabase
import com.taskpeek.app.data.Task
import com.taskpeek.app.databinding.ActivityMainBinding
import com.taskpeek.app.overlay.TaskOverlayManager
import com.taskpeek.app.service.UnlockMonitorService
import com.taskpeek.app.ui.CalendarAdapter
import com.taskpeek.app.ui.TaskAdapter
import com.taskpeek.app.util.DateUtils
import com.taskpeek.app.util.PreferenceHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: AppDatabase

    private lateinit var todayTaskAdapter: TaskAdapter
    private lateinit var calendarTaskAdapter: TaskAdapter
    private lateinit var calendarAdapter: CalendarAdapter

    private var currentTab = TAB_TODAY
    private var observeTasksJob: Job? = null
    private var observeCalendarJob: Job? = null
    private var observeStatsJob: Job? = null

    // Calendar state
    private var currentYearMonth: YearMonth = YearMonth.now()
    private var selectedCalendarDate: LocalDate = LocalDate.now()

    // Search state
    private var searchQuery: String = ""
    private var fullTodayTaskList: List<Task> = emptyList()

    // Stats period
    private var statsPeriod = "This Week"

    private val dbDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val displayDateFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.US)
    private val monthYearFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

    // Permission launcher for overlay permission (Android 6.0+)
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasOverlayPermission() && PreferenceHelper.isMonitoringEnabled(this)) {
            UnlockMonitorService.start(this)
        }
    }

    // Permission launcher for notifications (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && PreferenceHelper.isMonitoringEnabled(this) && hasOverlayPermission()) {
            UnlockMonitorService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getInstance(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContentContainer) { _, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.topBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarInsets.top
            }
            binding.bottomNav.updatePadding(bottom = navBarInsets.bottom)
            binding.navDrawerContent.updatePadding(top = statusBarInsets.top, bottom = navBarInsets.bottom)
            insets
        }

        setupTodayTab()
        setupCalendarTab()
        setupStatsTab()
        setupNavigationAndDrawer()
        setupSearch()
        checkPermissionsAndStartService()

        switchToTab(TAB_TODAY)
    }

    override fun onResume() {
        super.onResume()
        observeTodayTasks()
        refreshCalendarDays()
        refreshStats()
        TaskOverlayManager.warmCache(this)
    }

    private fun checkPermissionsAndStartService() {
        if (PreferenceHelper.isMonitoringEnabled(this)) {
            if (hasOverlayPermission() && hasNotificationPermission()) {
                UnlockMonitorService.start(this)
            }
        }
    }

    // ==========================================
    // 1. TODAY TAB
    // ==========================================

    private fun setupTodayTab() {
        binding.viewTodayTab.tvTodayDateSubtitle.text = LocalDate.now().format(displayDateFormatter)

        todayTaskAdapter = TaskAdapter(
            onToggleComplete = { task, isCompleted ->
                lifecycleScope.launch {
                    val completedTime = if (isCompleted) System.currentTimeMillis() else 0L
                    database.taskDao().setTaskCompletedWithTime(task.id, isCompleted, completedTime)
                    TaskOverlayManager.invalidateCache()
                    TaskOverlayManager.warmCache(this@MainActivity)
                }
            },
            onTaskClick = { task ->
                val intent = Intent(this, TaskDetailActivity::class.java).apply {
                    putExtra(TaskDetailActivity.EXTRA_TASK_ID, task.id)
                }
                startActivity(intent)
            }
        )

        binding.viewTodayTab.rvTodayTasks.layoutManager = LinearLayoutManager(this)
        binding.viewTodayTab.rvTodayTasks.adapter = todayTaskAdapter

        observeTodayTasks()
    }

    private fun observeTodayTasks() {
        observeTasksJob?.cancel()
        observeTasksJob = lifecycleScope.launch {
            val todayStr = DateUtils.getTodayDateString()
            database.taskDao().getTodayTasksFlow(todayStr).collectLatest { tasks ->
                fullTodayTaskList = tasks
                applySearchFilter()
            }
        }
    }

    private fun applySearchFilter() {
        val filtered = if (searchQuery.isBlank()) {
            fullTodayTaskList
        } else {
            fullTodayTaskList.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.category.contains(searchQuery, ignoreCase = true) ||
                it.description.contains(searchQuery, ignoreCase = true)
            }
        }

        todayTaskAdapter.submitList(filtered)
        val taskCount = filtered.size
        binding.viewTodayTab.tvTodayTaskCount.text = "$taskCount ${if (taskCount == 1) "task" else "tasks"}"
        binding.viewTodayTab.tvEmptyToday.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    // ==========================================
    // 2. CALENDAR TAB
    // ==========================================

    private fun setupCalendarTab() {
        calendarAdapter = CalendarAdapter { selectedDate ->
            selectedCalendarDate = selectedDate
            refreshCalendarDays()
            observeTasksForCalendarDate()
        }

        binding.viewCalendarTab.rvCalendarGrid.layoutManager = GridLayoutManager(this, 7)
        binding.viewCalendarTab.rvCalendarGrid.adapter = calendarAdapter

        calendarTaskAdapter = TaskAdapter(
            onToggleComplete = { task, isCompleted ->
                lifecycleScope.launch {
                    val completedTime = if (isCompleted) System.currentTimeMillis() else 0L
                    database.taskDao().setTaskCompletedWithTime(task.id, isCompleted, completedTime)
                    TaskOverlayManager.invalidateCache()
                    TaskOverlayManager.warmCache(this@MainActivity)
                }
            },
            onTaskClick = { task ->
                val intent = Intent(this, TaskDetailActivity::class.java).apply {
                    putExtra(TaskDetailActivity.EXTRA_TASK_ID, task.id)
                }
                startActivity(intent)
            }
        )

        binding.viewCalendarTab.rvCalendarTasks.layoutManager = LinearLayoutManager(this)
        binding.viewCalendarTab.rvCalendarTasks.adapter = calendarTaskAdapter

        binding.viewCalendarTab.btnPrevMonth.setOnClickListener {
            currentYearMonth = currentYearMonth.minusMonths(1)
            refreshCalendarDays()
        }

        binding.viewCalendarTab.btnNextMonth.setOnClickListener {
            currentYearMonth = currentYearMonth.plusMonths(1)
            refreshCalendarDays()
        }

        refreshCalendarDays()
        observeTasksForCalendarDate()
    }

    private fun refreshCalendarDays() {
        binding.viewCalendarTab.tvCurrentMonthYear.text = currentYearMonth.format(monthYearFormatter)

        lifecycleScope.launch {
            val allTasks = database.taskDao().getAllTasks()
            val taskDates = allTasks.map { it.date }.toSet()

            val daysList = mutableListOf<CalendarAdapter.DayModel>()
            val firstDayOfMonth = currentYearMonth.atDay(1)
            val daysInMonth = currentYearMonth.lengthOfMonth()

            // Sunday = 7 in DayOfWeek % 7 = 0
            val startDayOfWeek = firstDayOfMonth.dayOfWeek.value % 7

            // Previous month padding
            val prevYearMonth = currentYearMonth.minusMonths(1)
            val prevMonthDays = prevYearMonth.lengthOfMonth()
            for (i in startDayOfWeek - 1 downTo 0) {
                val date = prevYearMonth.atDay(prevMonthDays - i)
                val hasTasks = taskDates.contains(date.format(dbDateFormatter))
                daysList.add(CalendarAdapter.DayModel(date, isCurrentMonth = false, isSelected = (date == selectedCalendarDate), hasTasks = hasTasks))
            }

            // Current month days
            for (day in 1..daysInMonth) {
                val date = currentYearMonth.atDay(day)
                val hasTasks = taskDates.contains(date.format(dbDateFormatter))
                daysList.add(CalendarAdapter.DayModel(date, isCurrentMonth = true, isSelected = (date == selectedCalendarDate), hasTasks = hasTasks))
            }

            // Next month trailing days to complete full grid
            val remaining = (7 - (daysList.size % 7)) % 7
            val nextYearMonth = currentYearMonth.plusMonths(1)
            for (day in 1..remaining) {
                val date = nextYearMonth.atDay(day)
                val hasTasks = taskDates.contains(date.format(dbDateFormatter))
                daysList.add(CalendarAdapter.DayModel(date, isCurrentMonth = false, isSelected = (date == selectedCalendarDate), hasTasks = hasTasks))
            }

            calendarAdapter.submitDays(daysList)
        }
    }

    private fun observeTasksForCalendarDate() {
        observeCalendarJob?.cancel()
        val dateStr = selectedCalendarDate.format(dbDateFormatter)
        binding.viewCalendarTab.tvCalendarSelectedDate.text = selectedCalendarDate.format(displayDateFormatter)

        observeCalendarJob = lifecycleScope.launch {
            database.taskDao().getTasksForDateFlow(dateStr).collectLatest { tasks ->
                calendarTaskAdapter.submitList(tasks)
                val count = tasks.size
                binding.viewCalendarTab.tvCalendarTaskCount.text = "$count ${if (count == 1) "task" else "tasks"}"
                binding.viewCalendarTab.tvEmptyCalendarTasks.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // ==========================================
    // 3. STATS TAB
    // ==========================================

    private fun setupStatsTab() {
        binding.viewStatsTab.btnPeriodSelector.setOnClickListener {
            val periods = arrayOf("This Week", "This Month", "All Time")
            AlertDialog.Builder(this)
                .setTitle("Select Period")
                .setItems(periods) { _, which ->
                    statsPeriod = periods[which]
                    binding.viewStatsTab.tvSelectedPeriod.text = statsPeriod
                    refreshStats()
                }
                .show()
        }

        refreshStats()
    }

    private fun refreshStats() {
        observeStatsJob?.cancel()
        observeStatsJob = lifecycleScope.launch {
            val allTasks = database.taskDao().getAllTasks()
            val today = LocalDate.now()

            val filteredTasks = when (statsPeriod) {
                "This Month" -> {
                    allTasks.filter {
                        try {
                            val parsed = LocalDate.parse(it.date, dbDateFormatter)
                            parsed.year == today.year && parsed.monthValue == today.monthValue
                        } catch (e: Exception) {
                            false
                        }
                    }
                }
                "All Time" -> allTasks
                else -> { // "This Week"
                    val startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    val endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                    allTasks.filter {
                        try {
                            val parsed = LocalDate.parse(it.date, dbDateFormatter)
                            !parsed.isBefore(startOfWeek) && !parsed.isAfter(endOfWeek)
                        } catch (e: Exception) {
                            false
                        }
                    }
                }
            }

            val doneCount = filteredTasks.count { it.isCompleted }
            val pendingCount = filteredTasks.count { !it.isCompleted }
            val total = filteredTasks.size
            val completionRate = if (total > 0) ((doneCount * 100) / total) else 0

            binding.viewStatsTab.tvStatTasksDone.text = doneCount.toString()
            binding.viewStatsTab.tvStatTasksPending.text = pendingCount.toString()
            binding.viewStatsTab.tvStatCompletionRate.text = "$completionRate%"

            // Weekly Bars (Mon - Sun)
            val startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val daysOfWeek = (0..6).map { startOfWeek.plusDays(it.toLong()) }
            val maxBarHeightPx = (130 * resources.displayMetrics.density).toInt()

            val bars = listOf(
                binding.viewStatsTab.barMon,
                binding.viewStatsTab.barTue,
                binding.viewStatsTab.barWed,
                binding.viewStatsTab.barThu,
                binding.viewStatsTab.barFri,
                binding.viewStatsTab.barSat,
                binding.viewStatsTab.barSun
            )

            daysOfWeek.forEachIndexed { index, day ->
                val dayStr = day.format(dbDateFormatter)
                val dayTasks = allTasks.filter { it.date == dayStr }
                val dayDone = dayTasks.count { it.isCompleted }
                val ratio = if (dayTasks.isNotEmpty()) dayDone.toFloat() / dayTasks.size.toFloat() else 0f
                val barHeight = (ratio * maxBarHeightPx).toInt().coerceAtLeast(if (dayTasks.isNotEmpty()) 8 else 0)

                val params = bars[index].layoutParams
                params.height = barHeight
                bars[index].layoutParams = params
            }
        }
    }

    // ==========================================
    // 4. NAVIGATION & DRAWER
    // ==========================================

    private fun setupNavigationAndDrawer() {
        // Drawer toggle button
        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        // Drawer Items
        binding.drawerItemCategories.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, CategoriesActivity::class.java))
        }

        binding.drawerItemCompleted.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, CompletedActivity::class.java))
        }

        binding.drawerItemSettings.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.drawerItemTestOverlay.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            if (!hasOverlayPermission()) {
                requestOverlayPermission()
            } else {
                Toast.makeText(this, "Displaying preview overlay for 3s...", Toast.LENGTH_SHORT).show()
                TaskOverlayManager.triggerUnlock(this)
            }
        }

        // Bottom Navigation Tabs
        binding.tabBtnToday.setOnClickListener {
            switchToTab(TAB_TODAY)
        }

        binding.tabBtnCalendar.setOnClickListener {
            switchToTab(TAB_CALENDAR)
        }

        binding.tabBtnStats.setOnClickListener {
            switchToTab(TAB_STATS)
        }

        // FAB Add Task
        binding.fabAddTask.setOnClickListener {
            startActivity(Intent(this, AddTaskActivity::class.java))
        }
    }

    private fun switchToTab(tab: Int) {
        currentTab = tab

        binding.viewTodayTab.root.visibility = if (tab == TAB_TODAY) View.VISIBLE else View.GONE
        binding.viewCalendarTab.root.visibility = if (tab == TAB_CALENDAR) View.VISIBLE else View.GONE
        binding.viewStatsTab.root.visibility = if (tab == TAB_STATS) View.VISIBLE else View.GONE

        // Hide FAB on Stats tab
        binding.fabAddTask.visibility = if (tab == TAB_STATS) View.GONE else View.VISIBLE

        // Tab selection styling
        val activeColor = ContextCompat.getColor(this, R.color.text_primary)
        val mutedColor = ContextCompat.getColor(this, R.color.text_muted)

        binding.tvTabToday.setTextColor(if (tab == TAB_TODAY) activeColor else mutedColor)
        binding.ivTabToday.setColorFilter(if (tab == TAB_TODAY) activeColor else mutedColor)

        binding.tvTabCalendar.setTextColor(if (tab == TAB_CALENDAR) activeColor else mutedColor)
        binding.ivTabCalendar.setColorFilter(if (tab == TAB_CALENDAR) activeColor else mutedColor)

        binding.tvTabStats.setTextColor(if (tab == TAB_STATS) activeColor else mutedColor)
        binding.ivTabStats.setColorFilter(if (tab == TAB_STATS) activeColor else mutedColor)

        if (tab == TAB_CALENDAR) {
            refreshCalendarDays()
            observeTasksForCalendarDate()
        } else if (tab == TAB_STATS) {
            refreshStats()
        }
    }

    // ==========================================
    // 5. SEARCH BAR
    // ==========================================

    private fun setupSearch() {
        binding.btnSearch.setOnClickListener {
            val isVisible = binding.layoutSearchBar.visibility == View.VISIBLE
            binding.layoutSearchBar.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) {
                binding.etSearchQuery.requestFocus()
            } else {
                binding.etSearchQuery.text?.clear()
                searchQuery = ""
                applySearchFilter()
            }
        }

        binding.btnCloseSearch.setOnClickListener {
            binding.layoutSearchBar.visibility = View.GONE
            binding.etSearchQuery.text?.clear()
            searchQuery = ""
            applySearchFilter()
        }

        binding.etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                applySearchFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // ==========================================
    // 6. PERMISSIONS
    // ==========================================

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        const val TAB_TODAY = 0
        const val TAB_CALENDAR = 1
        const val TAB_STATS = 2
    }
}
