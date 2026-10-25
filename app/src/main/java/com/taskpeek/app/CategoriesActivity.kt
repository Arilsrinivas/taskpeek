package com.taskpeek.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taskpeek.app.data.AppDatabase
import com.taskpeek.app.data.Category
import com.taskpeek.app.databinding.ActivityCategoriesBinding
import com.taskpeek.app.databinding.ItemCategoryBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CategoriesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoriesBinding
    private lateinit var database: AppDatabase
    private val categoriesList = mutableListOf<CategoryItem>()
    private lateinit var adapter: CategoryAdapter

    data class CategoryItem(val category: Category, var count: Int = 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoriesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getInstance(this)

        setupRecyclerView()
        setupListeners()
        observeCategories()
    }

    private fun setupRecyclerView() {
        adapter = CategoryAdapter(categoriesList) { item ->
            // Show category tasks dialog or details
            showCategoryTasks(item.category.name)
        }
        binding.rvCategories.layoutManager = LinearLayoutManager(this)
        binding.rvCategories.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnAddCategory.setOnClickListener {
            showAddCategoryDialog()
        }
    }

    private fun observeCategories() {
        lifecycleScope.launch {
            database.categoryDao().getAllCategoriesFlow().collectLatest { categories ->
                val allTasks = database.taskDao().getAllTasks()
                categoriesList.clear()
                categories.forEach { cat ->
                    val count = allTasks.count { it.category.equals(cat.name, ignoreCase = true) }
                    categoriesList.add(CategoryItem(cat, count))
                }
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun showAddCategoryDialog() {
        val input = EditText(this).apply {
            hint = "Category name"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Add Category")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        database.categoryDao().insertCategory(Category(name = name))
                        Toast.makeText(this@CategoriesActivity, "Category added", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCategoryTasks(categoryName: String) {
        lifecycleScope.launch {
            val tasks = database.taskDao().getAllTasks().filter { it.category.equals(categoryName, ignoreCase = true) }
            val titles = if (tasks.isNotEmpty()) {
                tasks.map { (if (it.isCompleted) "✓ " else "○ ") + it.title }.toTypedArray()
            } else {
                arrayOf("No tasks in this category")
            }

            AlertDialog.Builder(this@CategoriesActivity)
                .setTitle(categoryName)
                .setItems(titles, null)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    inner class CategoryAdapter(
        private val items: List<CategoryItem>,
        private val onClick: (CategoryItem) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

        inner class ViewHolder(val itemBinding: ItemCategoryBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemCategoryBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.itemBinding.tvCategoryName.text = item.category.name
            holder.itemBinding.tvCategoryCount.text = item.count.toString()
            holder.itemView.setOnClickListener {
                onClick(item)
            }
        }

        override fun getItemCount() = items.size
    }
}
