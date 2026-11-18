package com.seyal.app.util

import org.json.JSONObject

/**
 * Where the common cities are, so the weather for them needs one request instead of two (the first, "where is
 * Coimbatore?", was the one that timed out in a test run and made the answer take 40 seconds). Any other place is
 * still looked up on the web.
 */
object Places {
    private class P(val name: String, val state: String, val lat: Double, val lon: Double)

    private val cities = listOf(
        P("Coimbatore", "Tamil Nadu", 11.0168, 76.9558), P("Chennai", "Tamil Nadu", 13.0827, 80.2707), P("Madurai", "Tamil Nadu", 9.9252, 78.1198),
        P("Tiruchirappalli", "Tamil Nadu", 10.7905, 78.7047), P("Salem", "Tamil Nadu", 11.6643, 78.1460), P("Tiruppur", "Tamil Nadu", 11.1085, 77.3411),
        P("Erode", "Tamil Nadu", 11.3410, 77.7172), P("Vellore", "Tamil Nadu", 12.9165, 79.1325), P("Tirunelveli", "Tamil Nadu", 8.7139, 77.7567),
        P("Thanjavur", "Tamil Nadu", 10.7870, 79.1378), P("Ooty", "Tamil Nadu", 11.4102, 76.6950), P("Kodaikanal", "Tamil Nadu", 10.2381, 77.4892),
        P("Pollachi", "Tamil Nadu", 10.6609, 77.0048), P("Karur", "Tamil Nadu", 10.9601, 78.0766), P("Namakkal", "Tamil Nadu", 11.2189, 78.1674),
        P("Dindigul", "Tamil Nadu", 10.3673, 77.9803), P("Thoothukudi", "Tamil Nadu", 8.7642, 78.1348), P("Nagercoil", "Tamil Nadu", 8.1833, 77.4119),
        P("Kanyakumari", "Tamil Nadu", 8.0883, 77.5385), P("Hosur", "Tamil Nadu", 12.7409, 77.8253), P("Kanchipuram", "Tamil Nadu", 12.8342, 79.7036),
        P("Puducherry", "Puducherry", 11.9416, 79.8083), P("Bengaluru", "Karnataka", 12.9716, 77.5946), P("Mysuru", "Karnataka", 12.2958, 76.6394),
        P("Mangaluru", "Karnataka", 12.9141, 74.8560), P("Udupi", "Karnataka", 13.3409, 74.7421), P("Hyderabad", "Telangana", 17.3850, 78.4867),
        P("Visakhapatnam", "Andhra Pradesh", 17.6868, 83.2185), P("Tirupati", "Andhra Pradesh", 13.6288, 79.4192), P("Kochi", "Kerala", 9.9312, 76.2673),
        P("Thiruvananthapuram", "Kerala", 8.5241, 76.9366), P("Kozhikode", "Kerala", 11.2588, 75.7804), P("Palakkad", "Kerala", 10.7867, 76.6548),
        P("Mumbai", "Maharashtra", 19.0760, 72.8777), P("Pune", "Maharashtra", 18.5204, 73.8567), P("Delhi", "Delhi", 28.6139, 77.2090),
        P("Kolkata", "West Bengal", 22.5726, 88.3639), P("Ahmedabad", "Gujarat", 23.0225, 72.5714), P("Jaipur", "Rajasthan", 26.9124, 75.7873),
        P("Lucknow", "Uttar Pradesh", 26.8467, 80.9462), P("Chandigarh", "Chandigarh", 30.7333, 76.7794), P("Panaji", "Goa", 15.4909, 73.8278),
        P("Gurugram", "Haryana", 28.4595, 77.0266), P("Noida", "Uttar Pradesh", 28.5355, 77.3910),
    )
    private val other = mapOf(
        "kovai" to "coimbatore", "cbe" to "coimbatore", "madras" to "chennai", "trichy" to "tiruchirappalli", "tiruchi" to "tiruchirappalli", "tirupur" to "tiruppur",
        "tanjore" to "thanjavur", "udhagamandalam" to "ooty", "tuticorin" to "thoothukudi", "pondicherry" to "puducherry", "pondy" to "puducherry", "bangalore" to "bengaluru",
        "mysore" to "mysuru", "mangalore" to "mangaluru", "vizag" to "visakhapatnam", "cochin" to "kochi", "ernakulam" to "kochi", "trivandrum" to "thiruvananthapuram",
        "calicut" to "kozhikode", "palghat" to "palakkad", "bombay" to "mumbai", "new delhi" to "delhi", "calcutta" to "kolkata", "goa" to "panaji", "gurgaon" to "gurugram",
    )
    private val byName = cities.associateBy { it.name.lowercase() }

    /** The city [place] names ("Coimbatore", "kovai", "Coimbatore, Tamil Nadu"), or null when it is not one of the known ones. */
    fun find(place: String): JSONObject? {
        val low = place.trim().lowercase()
        val key = low.substringBefore(',').replace(Regex("\\s+(city|town|district)$"), "").trim()
        val p = byName[other[key] ?: key] ?: return null
        // "Salem, Oregon" is not Tamil Nadu's Salem: whatever follows a comma has to be this city's own state or country.
        val rest = low.substringAfter(',', "").trim()
        if (rest.isNotEmpty() && rest.split(',').map { it.trim() }.any { it.isNotEmpty() && it != "india" && it != "in" && it != "tn" && it != p.state.lowercase() }) return null
        return JSONObject().put("latitude", p.lat).put("longitude", p.lon).put("name", p.name).put("admin1", p.state).put("country", "India")
    }
}
