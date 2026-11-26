package com.retroarcade.handheld

import android.app.Activity
import android.os.Environment
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.util.Locale

/** A plain folder browser. The cores need a real file path, so the system picker will not do. */
object FolderPicker {

    fun show(activity: Activity, start: File, onPicked: (File) -> Unit) {
        var current: File = if (start.isDirectory) start else Environment.getExternalStorageDirectory()

        // Internal storage plus any SD card, worked out from the app's own folders on each.
        val roots = ArrayList<File>()
        for (dir in activity.getExternalFilesDirs(null)) {
            if (dir == null) continue
            val path = dir.absolutePath
            val cut = path.indexOf("/Android/")
            val root = File(if (cut > 0) path.substring(0, cut) else path)
            if (!roots.contains(root)) roots.add(root)
        }

        val targets = ArrayList<File>()
        val labels = ArrayList<String>()
        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, labels)
        val list = ListView(activity)
        list.adapter = adapter

        val dialog = AlertDialog.Builder(activity)
            .setTitle(current.absolutePath)
            .setView(list)
            .setPositiveButton("이 폴더 사용") { _, _ -> onPicked(current) }
            .setNegativeButton("취소", null)
            .create()

        fun refresh() {
            targets.clear()
            labels.clear()
            val parent = current.parentFile
            if (parent != null && parent.canRead() && !roots.contains(current)) {
                targets.add(parent)
                labels.add("⬆  상위 폴더")
            } else {
                for (root in roots) {
                    if (root != current) {
                        targets.add(root)
                        labels.add("💾  " + root.absolutePath)
                    }
                }
            }
            val children = current.listFiles()
            if (children != null) {
                val folders = children
                    .filter { it.isDirectory && !it.name.startsWith(".") }
                    .sortedBy { it.name.lowercase(Locale.ROOT) }
                for (folder in folders) {
                    targets.add(folder)
                    labels.add("📁  " + folder.name)
                }
            }
            adapter.notifyDataSetChanged()
            dialog.setTitle(current.absolutePath)
        }

        list.setOnItemClickListener { _, _, position, _ ->
            if (position >= 0 && position < targets.size) {
                current = targets[position]
                refresh()
            }
        }

        refresh()
        dialog.show()
    }
}
