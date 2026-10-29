package com.fenakhay.kwikibot.net

import platform.Foundation.NSProcessInfo

internal actual fun platformDescription(): String =
    "macOS ${NSProcessInfo.processInfo.operatingSystemVersionString}"
