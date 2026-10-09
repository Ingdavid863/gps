package com.david.gps3dar

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchNavigationTest {
    private fun set(activity: RealisticMapActivity, name: String, value: Any) {
        RealisticMapActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity,value)
    }
    private fun call(activity: RealisticMapActivity, name: String, types: Array<Class<*>> = emptyArray(), vararg values: Any) {
        RealisticMapActivity::class.java.getDeclaredMethod(name,*types).apply { isAccessible = true }.invoke(activity,*values)
    }
    @Test fun recentDestinationIsVisibleAtNightAndDayAndNavigationHidesSearchWithLargeTurnDistance() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("destinations",0)
        prefs.edit().putString("recent","[]").commit()
        RecentDestinations({prefs.getString("recent","[]")!!},{prefs.edit().putString("recent",it).commit()})
            .select(DestinationSearch.Result(19.43,-99.13,"Lago de Guadalupe","Cuautitlán Izcalli"))
        ActivityScenario.launch(RealisticMapActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                set(activity,"darkTheme",true); call(activity,"applyPalette")
                val input=activity.findViewById<EditText>(R.id.searchInput)
                input.requestFocus()
                val list=activity.findViewById<LinearLayout>(R.id.searchSuggestions)
                assertEquals(View.VISIBLE,list.visibility)
                fun row()=(0 until list.childCount).map{list.getChildAt(it)}.filterIsInstance<TextView>().first{it.text.contains("Lago de Guadalupe")}
                assertTrue("dark suggestions readable",ColorUtils.calculateContrast(row().currentTextColor,Color.parseColor("#232B36")) >= 7)
                assertEquals("search input stays transparent",Color.TRANSPARENT,(input.background as android.graphics.drawable.ColorDrawable).color)
                assertEquals(View.GONE,activity.findViewById<View>(R.id.mapControls).visibility)
                set(activity,"darkTheme",false);call(activity,"applyPalette")
                assertTrue("day suggestions readable",ColorUtils.calculateContrast(row().currentTextColor,Color.WHITE) >= 7)
                input.setText("lá")
                assertTrue(row().text.contains("Lago"))
                row().performClick()
                assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.placePreview).visibility)
                assertEquals(View.GONE,list.visibility)
                val here=Location("test").apply{latitude=19.43;longitude=-99.13;accuracy=5f}
                set(activity,"rawLocation",here);set(activity,"displayLocation",here);set(activity,"voiceEnabled",false)
                val points=listOf(RealisticMapActivity.GeoPoint(19.43,-99.13),RealisticMapActivity.GeoPoint(19.43072,-99.13),RealisticMapActivity.GeoPoint(19.43072,-99.131))
                val steps=listOf(RealisticMapActivity.NavStep(19.43072,-99.13,"Gira a la izquierda en la siguiente calle","↰",1,"TURN_LEFT"))
                set(activity,"routeAlternatives",listOf(RealisticMapActivity.RouteOption(points,steps,120.0,185.0)))
                call(activity,"activateRoute",arrayOf(Int::class.javaPrimitiveType!!,Boolean::class.javaPrimitiveType!!),0,false)
                assertEquals(View.GONE,activity.findViewById<View>(R.id.searchPanel).visibility)
                assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.changeDestinationButton).visibility)
                val distance=activity.findViewById<TextView>(R.id.turnDistance)
                assertTrue("next turn in metres",distance.text.contains("m"))
                assertTrue("distance at least 32sp",distance.textSize/activity.resources.displayMetrics.scaledDensity>=31.9f)
                assertTrue(distance.typeface.isBold)
                activity.findViewById<View>(R.id.changeDestinationButton).performClick()
                assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.searchPanel).visibility)
                assertTrue(row().text.contains("Lago"))
                call(activity,"stopNavigation")
                assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.searchPanel).visibility)
                assertEquals(View.GONE,activity.findViewById<View>(R.id.changeDestinationButton).visibility)
            }
        }
        prefs.edit().clear().commit()
    }
}
