package com.vovoscan.core.network

import io.ktor.client.engine.HttpClientEngine

/** OkHttp on Android, Darwin (URLSession) on iOS. */
expect fun platformHttpEngine(): HttpClientEngine
