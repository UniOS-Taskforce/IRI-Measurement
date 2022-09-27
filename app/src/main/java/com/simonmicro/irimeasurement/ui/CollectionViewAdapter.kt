package com.simonmicro.irimeasurement.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import com.simonmicro.irimeasurement.R

class CollectionViewAdapter(context: Context, arrayList: ArrayList<CollectionView>?) : ArrayAdapter<CollectionView?>(context, 0, arrayList!! as List<CollectionView?>) {
    companion object {
        const val inflatorId = R.layout.collection_view
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var currentItemView: View? = convertView

        // if the recyclable view is null then inflate the custom layout for the same
        if (currentItemView == null) {
            currentItemView = LayoutInflater.from(context).inflate(inflatorId, parent, false)
        }

        val currentNumberPosition: CollectionView? = getItem(position)
        currentNumberPosition?.updateView(currentItemView!!, this)

        return currentItemView!!
    }
}
