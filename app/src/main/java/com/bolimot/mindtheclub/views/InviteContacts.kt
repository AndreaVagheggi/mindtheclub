package com.bolimot.mindtheclub.views

import android.content.Context
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bolimot.mindtheclub.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.net.toUri

data class PhoneContact(val id: Long, val name: String, val photoUri: String?)

suspend fun loadPhoneContacts(context: Context): List<PhoneContact> = withContext(Dispatchers.IO) {
    val list = mutableListOf<PhoneContact>()
    val projection = arrayOf(
        ContactsContract.Contacts._ID,
        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        ContactsContract.Contacts.PHOTO_THUMBNAIL_URI
    )
    val selection = "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1"
    val sortOrder = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC"

    context.contentResolver.query(
        ContactsContract.Contacts.CONTENT_URI, projection, selection, null, sortOrder
    )?.use { c ->
        val idIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
        val nameIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
        val photoIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
        while (c.moveToNext()) {
            val name = c.getString(nameIdx) ?: continue
            list.add(PhoneContact(c.getLong(idIdx), name, c.getString(photoIdx)))
        }
    }
    list
}

class ContactsAdapter(private val onClick: (PhoneContact) -> Unit) :
    ListAdapter<PhoneContact, ContactsAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PhoneContact>() {
            override fun areItemsTheSame(a: PhoneContact, b: PhoneContact) = a.id == b.id
            override fun areContentsTheSame(a: PhoneContact, b: PhoneContact) = a == b
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val photo: ImageView = view.findViewById(R.id.contactPhoto)
        val name: TextView = view.findViewById(R.id.contactName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.name.text = item.name
        if (item.photoUri != null) {
            holder.photo.setImageURI(item.photoUri.toUri())
            if (holder.photo.drawable == null) {
                holder.photo.setImageResource(R.drawable.ic_contact_placeholder)
            }
        } else {
            holder.photo.setImageResource(R.drawable.ic_contact_placeholder)
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }
}
