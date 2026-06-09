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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.bolimot.mindtheclub.functions.debugLine

fun letterAvatar(name: String): Bitmap {
    val size = 120
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)

    val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"

    val palette = intArrayOf(
        0xFFE57373.toInt(), 0xFFBA68C8.toInt(), 0xFF64B5F6.toInt(),
        0xFF4DB6AC.toInt(), 0xFF81C784.toInt(), 0xFFFFB74D.toInt(),
        0xFFA1887F.toInt(), 0xFF90A4AE.toInt(), 0xFF7986CB.toInt(),
        0xFF4FC3F7.toInt()
    )
    val color = palette[(name.hashCode() and 0x7FFFFFFF) % palette.size]

    val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, circlePaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        textSize = size * 0.5f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    val yPos = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(letter, size / 2f, yPos, textPaint)

    return bitmap
}

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

data class ContactNumber(val number: String, val label: String)

suspend fun loadPhoneNumbers(context: Context, contactId: Long): List<ContactNumber> = withContext(Dispatchers.IO) {
    val list = mutableListOf<ContactNumber>()
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.NUMBER,
        ContactsContract.CommonDataKinds.Phone.TYPE,
        ContactsContract.CommonDataKinds.Phone.LABEL
    )
    val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"

    context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        projection, selection, arrayOf(contactId.toString()), null
    )?.use { c ->
        val numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val typeIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
        val labelIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
        val seen = mutableSetOf<String>()
        while (c.moveToNext()) {
            val number = c.getString(numIdx) ?: continue
            if (!seen.add(number.replace("\\s".toRegex(), ""))) continue
            val type = c.getInt(typeIdx)
            val customLabel = c.getString(labelIdx)
            val label = ContactsContract.CommonDataKinds.Phone
                .getTypeLabel(context.resources, type, customLabel).toString()
            list.add(ContactNumber(number, label))
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

        val ctx = holder.itemView.context
        var photoSet = false
        if (item.photoUri != null) {
            try {
                ctx.contentResolver.openInputStream(item.photoUri.toUri()).use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        val rounded = RoundedBitmapDrawableFactory.create(ctx.resources, bmp)
                        rounded.isCircular = true
                        holder.photo.setImageDrawable(rounded)
                        photoSet = true
                    }
                }
            } catch (e: Exception) {
                debugLine("ContactsAdapter", "OnBind $e")
            }
        }
        if (!photoSet) {
            holder.photo.setImageBitmap(letterAvatar(item.name))
        }

        holder.itemView.setOnClickListener { onClick(item) }
    }
}
