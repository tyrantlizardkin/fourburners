package app.fourburners.ui.screens

fun formatOffset(sec: Int): String { val m = sec / 60; val s = sec % 60; return if (s > 0) "$m:$s" else "$m" }
fun formatTime(sec: Int): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return when { h > 0 -> "$h:${m.toString().padStart(2,'0')}:${s.toString().padStart(2,'0')}"; m > 0 || s > 0 -> if (s > 0 && m == 0) "$s" else "$m:$s"; else -> "0" }
}
