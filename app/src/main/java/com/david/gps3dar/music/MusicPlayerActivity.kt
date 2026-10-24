package com.david.gps3dar.music

import android.os.Bundle
import android.graphics.Color
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.david.gps3dar.R
import java.util.concurrent.Executors

/** A real screen in GPS3D. Finishing this activity leaves playback in its MediaSessionService. */
class MusicPlayerActivity : AppCompatActivity() {
    private lateinit var panel: MusicPanelController
    private lateinit var network: MusicNetwork
    private lateinit var catalogue: MusicCatalogue
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private lateinit var query: EditText
    private lateinit var source: Spinner
    private val io=Executors.newSingleThreadExecutor()
    private var mode="search"
    private var generation=0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setContentView(R.layout.activity_music_player)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.musicPlayerRoot)) { view, insets ->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(dp(16)+bars.left,dp(16)+bars.top,dp(16)+bars.right,dp(16)+bars.bottom)
            insets
        }
        getSharedPreferences(MusicPlaybackService.PREFS,MODE_PRIVATE).edit().putString(MusicPanelController.SOURCE,MusicPanelController.LOCAL).apply()
        panel=MusicPanelController(this,true)
        network=MusicNetwork { getSharedPreferences(MusicPlaybackService.PREFS,MODE_PRIVATE).getBoolean(MusicNetwork.FILTER,true) }
        catalogue=MusicCatalogue(network)
        results=findViewById(R.id.musicResults);status=findViewById(R.id.musicCatalogueStatus);query=findViewById(R.id.musicSearch)
        source=findViewById(R.id.musicCatalogueSource)
        source.adapter=object:ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,MusicCatalogue.Source.entries.map { it.label }) {
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View = super.getView(position,convertView,parent).also {
                (it as? TextView)?.setTextColor(Color.rgb(237,242,247))
            }
            override fun getDropDownView(position:Int,convertView:View?,parent:android.view.ViewGroup):View = super.getDropDownView(position,convertView,parent).also {
                (it as? TextView)?.setTextColor(Color.rgb(16,25,35))
            }
        }
        query.setText(savedInstanceState?.getString("query").orEmpty())
        source.setSelection(savedInstanceState?.getInt("source") ?: 0)
        findViewById<View>(R.id.musicMinimize).setOnClickListener { finish() }
        findViewById<View>(R.id.musicSearchGo).setOnClickListener { search() }
        query.setOnEditorActionListener { _,action,_ -> if(action==EditorInfo.IME_ACTION_SEARCH){search();true}else false }
        findViewById<View>(R.id.musicBrowse).setOnClickListener { mode="search";showSearch(true);results.removeAllViews();status.text="Busca canciones del catálogo público de Audius o SoundCloud. Requiere internet." }
        findViewById<View>(R.id.musicLibrary).setOnClickListener { library() }
        findViewById<View>(R.id.musicPhone).setOnClickListener { panel.importFiles() }
        findViewById<View>(R.id.musicRadio).setOnClickListener { mode="radio";showSearch(false);radio() }
        panel.onLibraryChanged={if(mode=="library")library()}
        status.text="Tu reproductor en GPS3D · catálogos públicos de Audius y SoundCloud. Las canciones disponibles dependen de cada catálogo."
    }
    private fun showSearch(visible:Boolean) {
        findViewById<View>(R.id.musicSearchRow).visibility=if(visible)View.VISIBLE else View.GONE
        source.visibility=if(visible)View.VISIBLE else View.GONE
    }
    private fun search() {
        val term=query.text.toString().trim()
        if(term.isBlank()){query.error="Escribe una canción o artista";return}
        mode="search";showSearch(true)
        val id=++generation
        val provider=MusicCatalogue.Source.entries[source.selectedItemPosition]
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(query.windowToken,0)
        results.removeAllViews();status.text="Buscando en ${provider.label}…"
        val button=findViewById<View>(R.id.musicSearchGo);button.isEnabled=false
        io.execute {
            val found=runCatching { catalogue.search(term,provider) }
            runOnUiThread {
                if(isDestroyed || id!=generation)return@runOnUiThread
                button.isEnabled=true
                if(mode!="search")return@runOnUiThread
                found.onSuccess { tracks ->
                    status.text=if(tracks.isEmpty())"No hay pistas públicas reproducibles para esta búsqueda en ${provider.label}." else "${tracks.size} canciones · ${provider.label} · toca una para reproducir"
                    tracks.forEach { track -> row(track.title,track.artist.ifBlank{provider.label}) { panel.playTrack(track) } }
                }.onFailure { status.text="No se pudo consultar ${provider.label}. Revisa la conexión e inténtalo de nuevo." }
            }
        }
    }
    private fun library() {
        mode="library";showSearch(false);results.removeAllViews();status.text="Mi lista"
        panel.library ready@ { tracks ->
            if(isDestroyed || mode!="library")return@ready
            status.text=if(tracks.isEmpty())"Tu lista está vacía. Busca una canción, elige Radio o añade archivos del teléfono." else "${tracks.size} canciones en tu lista"
            results.removeAllViews()
            tracks.forEachIndexed { index,item -> row(item.mediaMetadata.title?.toString() ?: "Canción",item.mediaMetadata.artist?.toString().orEmpty()){panel.playAt(index)} }
        }
    }
    private fun radio() {
        results.removeAllViews();status.text="Radio en vivo · los botones cambian de emisora"
        val names=listOf("Mezcla general","Rock","Relajante")
        val streams=listOf("mp3-128","rock-128","mellow-128")
        val stations=names.indices.map { MusicTrack("https://stream.radioparadise.com/${streams[it]}","Radio Paradise · ${names[it]}",true,true) }
        names.forEachIndexed { i,name -> row("Radio Paradise · $name","Emisora en vivo"){panel.playTracks(stations,i)} }
    }
    private fun row(name:String,description:String,play:()->Unit) {
        val box=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12));minimumHeight=dp(68)
            isClickable=true;isFocusable=true;contentDescription="$name, $description, reproducir"
            setOnClickListener{play()}
        }
        box.addView(TextView(this).apply{text=name;textSize=16f;setTextColor(Color.rgb(237,242,247));maxLines=2})
        box.addView(TextView(this).apply{text=description;textSize=12f;setTextColor(Color.rgb(186,200,214));maxLines=1})
        results.addView(box)
        results.addView(View(this).apply {setBackgroundColor(Color.rgb(45,60,75));layoutParams=LinearLayout.LayoutParams(-1,dp(1))})
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    override fun onSaveInstanceState(outState:Bundle) {outState.putString("query",query.text.toString());outState.putInt("source",source.selectedItemPosition);super.onSaveInstanceState(outState)}
    override fun onDestroy() {
        generation++;panel.close();network.close();io.shutdownNow();super.onDestroy()
    }
}
