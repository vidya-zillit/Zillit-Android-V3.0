package com.zillit.zillitapp.feature.tools.registry.specs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WbSunny
import com.zillit.zillitapp.R
import com.zillit.zillitapp.feature.tools.registry.ToolSpec

/** The Weather tile. */
val WeatherTool = ToolSpec(
    identifier = "weather_tool",
    icon = Icons.Outlined.WbSunny,
    infoText = R.string.tool_info_weather,
    // No destination yet: the tool's own screen has not been built. The tile says so
    // rather than doing nothing when tapped.
    videoFile = "how%20to%20view%20weather.mp4",
    helpAnchor = "weather",
    destination = null,
)
