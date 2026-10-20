package com.vovoscan.core.network

import io.ktor.client.engine.HttpClientEngine

/** OkHttp sur Android, Darwin (URLSession) sur iOS. */
expect fun platformHttpEngine(): HttpClientEngine
