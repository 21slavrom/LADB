package com.draco.ladb.viewmodels

import android.app.Application
import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.AndroidViewModel
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.draco.ladb.R
import com.draco.ladb.recyclers.BookmarksRecyclerAdapter

class BookmarksActivityViewModel(application: Application): AndroidViewModel(application) {
    val recyclerAdapter = BookmarksRecyclerAdapter(application.applicationContext)

    /**
     * Prepare the recycler view
     */
    fun prepareRecycler(context: Context, recycler: RecyclerView) {
        recycler.apply {
            adapter = recyclerAdapter
            layoutManager = LinearLayoutManager(context)
        }
        recyclerAdapter.updateList()
    }

    /**
     * Show a dialog with a single text field
     */
    private fun inputDialog(
        context: Context,
        @StringRes title: Int,
        initialText: String,
        onDone: (String) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_text_input, null)
        val editText = view.findViewById<EditText>(android.R.id.edit)
            .also { it.setText(initialText) }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.done) { _, _ -> onDone(editText.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun add(context: Context, initialText: String) {
        inputDialog(context, R.string.add, initialText) { text ->
            if (text.isNotEmpty())
                recyclerAdapter.add(text)
        }
    }

    fun edit(context: Context, text: String) {
        inputDialog(context, R.string.edit, text) { newText ->
            if (newText.isNotEmpty() && newText != text)
                recyclerAdapter.edit(text, newText)
        }
    }

    fun areYouSure(context: Context, callback: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.delete)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ -> callback() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
