package com.relay.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
