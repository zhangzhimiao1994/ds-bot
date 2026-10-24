package com.dsbot.mobile

import android.app.Application

/**
 * Process-wide container. The real app shell installs the gateway + bot team in
 * MainActivity; the Application only keeps a context handle for debug tooling.
 */
class DsBotApplication : Application()
