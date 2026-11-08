package com.kairos.tv

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** «Chi guarda?» — profili dell'abbonamento gestiti dal pannello Kairos (anche profili bambini) */
class ProfilesActivity : BaseActivity() {
    private lateinit var row: LinearLayout
    private lateinit var manageBtn: Button
    private var manage = false
    private var fromSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)
        row = findViewById(R.id.row)
        manageBtn = findViewById(R.id.manage)
        fromSettings = intent.getBooleanExtra("fromSettings", false)
        manage = false
        manageBtn.setOnClickListener { manage = !manage; build() }
        build()
    }

    override fun onStateChanged() { build() }

    private fun canEdit(): Boolean {
        val s = Panel.state ?: return false
        val a = s.account ?: return false
        return s.perm("profiles") && a.canEdit
    }

    private fun profiles(): List<Profile> = Panel.state?.account?.profiles ?: emptyList()

    private fun build() {
        row.removeAllViews()
        val a = Panel.state?.account
        val list = profiles()
        findViewById<TextView>(R.id.title).text = if (manage) "Gestisci profili" else "Chi guarda?"
        findViewById<TextView>(R.id.hint).text = when {
            manage -> "Scegli un profilo per modificarlo" + (if (a != null) " · massimo ${a.maxProfiles}" else "")
            list.isEmpty() -> "Crea il primo profilo"
            else -> ""
        }
        var first: View? = null
        for (p in list) {
            val v = item(p.name, p.avatar, if (p.kids) "BAMBINI" else "") { if (manage) edit(p) else pick(p) }
            if (first == null || p.id == Store.profileId) first = v
        }
        if (canEdit() && a != null && list.size < a.maxProfiles) {
            val v = item("Aggiungi", "a12", "") { edit(null) }
            v.findViewById<TextView>(R.id.avatar).text = "+"
            if (first == null) first = v
        }
        manageBtn.visibility = if (canEdit() && list.isNotEmpty()) View.VISIBLE else View.GONE
        manageBtn.text = if (manage) "Fatto" else "Gestisci profili"
        first?.requestFocus()
    }

    private fun item(name: String, av: String, note: String, click: () -> Unit): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_profile, row, false)
        Ui.avatar(v.findViewById(R.id.avatar), name, av, 26)
        v.findViewById<TextView>(R.id.name).text = name
        v.findViewById<TextView>(R.id.note).text = note
        v.setOnClickListener { click() }
        Ui.zoom(v, 1.07f)
        row.addView(v)
        return v
    }

    private fun pick(p: Profile) {
        Store.profileId = p.id
        Sync.start()
        if (fromSettings) { finish(); return }
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun edit(p: Profile?) {
        val pad = Ui.dp(this, 20)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, Ui.dp(this, 8), pad, 0)
        val name = EditText(this)
        name.hint = "Nome del profilo"
        name.setSingleLine()
        name.setText(p?.name ?: "")
        name.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text))
        val kids = CheckBox(this)
        kids.text = "Profilo bambini (niente contenuti per adulti)"
        kids.isChecked = p?.kids == true
        kids.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text2))
        var av = p?.avatar ?: "a" + (1 + profiles().size % 12)
        val color = Button(this)
        color.text = "Cambia colore"
        fun paint() { color.setBackgroundColor(Ui.avatarColor(av)); color.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.on_accent)) }
        paint()
        color.setOnClickListener {
            val n = (av.removePrefix("a").toIntOrNull() ?: 1) % 12 + 1
            av = "a$n"
            paint()
        }
        box.addView(name)
        box.addView(kids)
        box.addView(color)
        val b = AlertDialog.Builder(this).setTitle(if (p == null) "Nuovo profilo" else "Modifica profilo").setView(box)
            .setPositiveButton("Salva") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) { Ui.toast("Scrivi un nome"); return@setPositiveButton }
                val list = profiles().map { Profile(it.id, it.name, it.avatar, it.kids, it.pl, it.pinH, it.age) }.toMutableList()
                if (p == null) list.add(Profile("", n, av, kids.isChecked, "all"))
                else list.find { it.id == p.id }?.let { it.name = n; it.avatar = av; it.kids = kids.isChecked }
                save(list)
            }
            .setNegativeButton("Annulla", null)
        if (p != null) b.setNeutralButton("Elimina") { _, _ ->
            Ui.dialog(this, "Eliminare ${p.name}?", "Il profilo viene tolto da tutte le TV collegate.", "Elimina", "Annulla") {
                if (Store.profileId == p.id) Store.profileId = ""
                save(profiles().filter { it.id != p.id })
            }
        }
        try { b.show(); name.requestFocus() } catch (_: Exception) {}
    }

    private fun save(list: List<Profile>) {
        bg {
            val err = Panel.saveProfiles(list)
            if (err == null) try { Panel.register() } catch (_: Exception) {}
            ui { if (err != null) Ui.dialog(this, "Profili", err) else build() }
        }
    }
}
